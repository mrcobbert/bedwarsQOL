/**
 * BedwarsQol stats backend — scrapes hypixel.net/player/<name> from Cloudflare edge.
 *
 * GET /bedwars/<name>          -> Bedwars JSON for one player (USER/immediate path)
 * GET /bedwars/batch?names=a,b -> Bedwars JSON for many players in one call (background lobby path)
 * GET /test/<name>             -> diagnostic egress probe
 * GET /health                  -> health check
 *
 * Caching is two-tier (per-colo caches.default + optional global Workers KV); cache-miss scrapes are
 * paced server-side with 429 backoff so the mod never has to rate-limit itself.
 */

import { getBedwars, streamBedwarsBatch } from "./scrape.js";

const BROWSER_UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

const CHALLENGE_RE =
  /just a moment|cf-challenge|turnstile|challenge-platform|attention required/i;

const NAME_RE = /^[A-Za-z0-9_]{1,16}$/;

export default {
  async scheduled(event, env, ctx) {
    const result = await probeHypixelPlayer("beepor");
    console.log("SCHEDULED_PROBE", JSON.stringify(result));
  },

  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "") || "/";

    if (path === "/" || path === "/health") {
      if (path === "/health") return jsonResponse(await probeHypixelPlayer("beepor"));
      return new Response(usageText(), { headers: { "content-type": "text/plain; charset=utf-8" } });
    }

    // Batch must be matched before the single /bedwars/<name> route ("batch" is a valid name pattern).
    if (path === "/bedwars/batch") {
      const denied = checkAuth(request, env);
      if (denied) return denied;
      const namesParam = url.searchParams.get("names") || "";
      const names = namesParam.split(",").map((s) => decodeURIComponent(s.trim())).filter(Boolean);
      if (names.length === 0) {
        return jsonResponse({ success: false, error: "no_names" }, 400);
      }
      // Streams NDJSON (one line per player as it resolves) — not a buffered JSON response.
      return streamBedwarsBatch(names, env, ctx);
    }

    const bedwars = path.match(/^\/bedwars\/([^/]+)$/);
    if (bedwars) {
      const denied = checkAuth(request, env);
      if (denied) return denied;
      const player = decodeURIComponent(bedwars[1]);
      if (!NAME_RE.test(player)) {
        return jsonResponse({ success: false, error: "invalid_player_name", player }, 400);
      }
      const fresh = url.searchParams.get("fresh") === "1";
      return jsonResponse(await getBedwars(player, env, ctx, fresh));
    }

    const test = path.match(/^\/test\/([^/]+)$/);
    if (test) {
      const denied = checkAuth(request, env);
      if (denied) return denied;
      const player = decodeURIComponent(test[1]);
      if (!NAME_RE.test(player)) {
        return jsonResponse({ error: "invalid_player_name", player }, 400);
      }
      return jsonResponse(await probeHypixelPlayer(player));
    }

    return new Response("Not found. Try GET /bedwars/PlayerName", { status: 404 });
  },
};

/**
 * Gate the data endpoints behind a shared secret. If the STATS_TOKEN secret is unset the
 * Worker stays open (back-compat, and lets a friend run their own no-auth deployment).
 * Set it with: wrangler secret put STATS_TOKEN
 */
function checkAuth(request, env) {
  const expected = env && env.STATS_TOKEN;
  if (!expected) return null; // no secret configured → open
  const got = request.headers.get("X-BedwarsQol-Token");
  if (got !== expected) {
    return jsonResponse({ success: false, state: "ERROR", error: "unauthorized" }, 401);
  }
  return null;
}

/** Diagnostic egress probe (full read, no cache) — used by /test, /health and the cron. */
async function probeHypixelPlayer(player) {
  const target = `https://hypixel.net/player/${encodeURIComponent(player)}`;
  const started = Date.now();

  let response;
  let fetchError = null;
  try {
    response = await fetch(target, {
      method: "GET",
      headers: {
        "User-Agent": BROWSER_UA,
        Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
      },
      redirect: "follow",
    });
  } catch (e) {
    fetchError = String(e && e.message ? e.message : e);
  }

  const elapsedMs = Date.now() - started;

  if (fetchError) {
    return {
      ok: false,
      verdict: "FETCH_FAILED",
      player,
      target,
      fetchError,
      elapsedMs,
      testedAt: new Date().toISOString(),
      from: "cloudflare-worker",
    };
  }

  const body = await response.text();
  const titleMatch = body.match(/<title[^>]*>([^<]+)<\/title>/i);
  const title = titleMatch ? titleMatch[1].trim() : null;

  const hasBedwars = body.includes("stats-content-bedwars");
  const looksLikeChallenge = body.length < 50_000 && CHALLENGE_RE.test(body);
  const cfMitigated = response.headers.get("cf-mitigated");
  const cfRay = response.headers.get("cf-ray");
  const server = response.headers.get("server");

  let verdict;
  if (response.status === 200 && hasBedwars) verdict = "PASS";
  else if (response.status === 403 || looksLikeChallenge) verdict = "BLOCKED";
  else if (response.status === 200 && !hasBedwars) verdict = "UNEXPECTED_HTML";
  else verdict = "FAIL";

  return {
    ok: verdict === "PASS",
    verdict,
    player,
    target,
    httpStatus: response.status,
    bodyBytes: body.length,
    title,
    hasBedwarsSection: hasBedwars,
    looksLikeCloudflareBlock: looksLikeChallenge,
    cfMitigated,
    cfRay,
    server,
    elapsedMs,
    testedAt: new Date().toISOString(),
    from: "cloudflare-worker",
    hint:
      verdict === "PASS"
        ? "Worker egress can scrape this page today."
        : verdict === "BLOCKED"
          ? "Worker egress is blocked."
          : "Unexpected response; inspect title/bodyBytes.",
  };
}

function jsonResponse(obj, status = 200) {
  return new Response(JSON.stringify(obj, null, 2), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

function usageText() {
  return [
    "BedwarsQol Hypixel stats Worker",
    "",
    "  GET /bedwars/<player>        -> stats JSON for one player",
    "  GET /bedwars/batch?names=a,b -> stats JSON for many players in one call",
    "  GET /test/<player>           -> diagnostic egress probe",
    "  GET /health                  -> health check",
  ].join("\n");
}

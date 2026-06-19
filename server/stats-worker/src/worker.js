/**
 * BedwarsQol stats backend — scrapes hypixel.net/player/<name> from Cloudflare edge.
 *
 * GET /bedwars/<name>  -> Bedwars JSON for the mod
 * GET /test/<name>     -> diagnostic egress probe
 * GET /health          -> health check
 */

import { parseBedwarsFromHtml } from "./bedwars-parse.js";
import { parseProfile } from "./profile-parse.js";

const BROWSER_UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

const CHALLENGE_RE =
  /just a moment|cf-challenge|turnstile|challenge-platform|attention required/i;

const CACHE_TTL_SEC = 900; // 15m, matches mod disk cache

export default {
  async scheduled(event, env, ctx) {
    const result = await probeHypixelPlayer("beepor");
    console.log("SCHEDULED_PROBE", JSON.stringify(result));
  },

  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "") || "/";

    if (path === "/" || path === "/health") {
      if (path === "/health") {
        return jsonResponse(await probeHypixelPlayer("beepor"));
      }
      return new Response(usageText(), {
        headers: { "content-type": "text/plain; charset=utf-8" },
      });
    }

    const bedwars = path.match(/^\/bedwars\/([^/]+)$/);
    if (bedwars) {
      const denied = checkAuth(request, env);
      if (denied) return denied;
      const player = decodeURIComponent(bedwars[1]);
      if (!/^[A-Za-z0-9_]{1,16}$/.test(player)) {
        return jsonResponse({ success: false, error: "invalid_player_name", player }, 400);
      }
      const fresh = url.searchParams.get("fresh") === "1";
      return jsonResponse(await fetchBedwars(player, ctx, fresh));
    }

    const test = path.match(/^\/test\/([^/]+)$/);
    if (test) {
      const denied = checkAuth(request, env);
      if (denied) return denied;
      const player = decodeURIComponent(test[1]);
      if (!/^[A-Za-z0-9_]{1,16}$/.test(player)) {
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

async function fetchBedwars(player, ctx, bypassCache = false) {
  const cache = caches.default;
  const cacheKey = new Request(
    `https://bedwarsqol.internal/cache/bedwars/v2/${player.toLowerCase()}`
  );

  // bypassCache (from /bedwars/<name>?fresh=1, i.e. a manual /bw) skips the read but still
  // writes below, so a manual refresh updates the shared edge entry for everyone.
  if (!bypassCache) {
    const cached = await cache.match(cacheKey);
    if (cached) {
      const body = await cached.json();
      return { ...body, cached: true };
    }
  }

  const scraped = await scrapePlayerHtml(player);
  if (!scraped.ok) {
    return scraped.body;
  }

  const parsed = parseBedwarsFromHtml(scraped.html, player);
  // Only cache real results. Don't pin a parse failure (or transient NICKED) for the
  // full TTL — those should be retried on the next lookup.
  if (parsed.success === true) {
    // Merge account-header fields (network level, rank, canonical-cased name).
    const profile = parseProfile(scraped.html);
    if (profile.displayName) parsed.displayName = profile.displayName;
    parsed.networkLevel = profile.networkLevel ?? 0;
    parsed.rank = profile.rank;

    const response = new Response(JSON.stringify(parsed), {
      headers: {
        "content-type": "application/json; charset=utf-8",
        "cache-control": `public, max-age=${CACHE_TTL_SEC}`,
      },
    });
    ctx.waitUntil(cache.put(cacheKey, response.clone()));
  }
  return parsed;
}

async function scrapePlayerHtml(player) {
  const target = `https://hypixel.net/player/${encodeURIComponent(player)}`;
  let response;
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
    return {
      ok: false,
      body: {
        success: false,
        state: "ERROR",
        displayName: player,
        error: String(e && e.message ? e.message : e),
      },
    };
  }

  const html = await response.text();
  if (response.status === 403 || (html.length < 50_000 && CHALLENGE_RE.test(html))) {
    return {
      ok: false,
      body: {
        success: false,
        state: "ERROR",
        displayName: player,
        error: "blocked_by_cloudflare",
        httpStatus: response.status,
      },
    };
  }

  if (response.status < 200 || response.status >= 300) {
    return {
      ok: false,
      body: {
        success: false,
        state: "ERROR",
        displayName: player,
        error: "http_" + response.status,
        httpStatus: response.status,
      },
    };
  }

  return { ok: true, html };
}

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
  if (response.status === 200 && hasBedwars) {
    verdict = "PASS";
  } else if (response.status === 403 || looksLikeChallenge) {
    verdict = "BLOCKED";
  } else if (response.status === 200 && !hasBedwars) {
    verdict = "UNEXPECTED_HTML";
  } else {
    verdict = "FAIL";
  }

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
    "  GET /bedwars/<player>  -> stats JSON for BedwarsQOL",
    "  GET /test/<player>      -> diagnostic egress probe",
    "  GET /health             -> health check",
  ].join("\n");
}

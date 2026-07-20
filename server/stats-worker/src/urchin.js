/**
 * Urchin blacklist integration (legacy v2 API, https://urchin.ws).
 *
 * v2 vs Coral v3: the owner's key (issued by the Urchin bot's /dashboard) is only valid on
 * the legacy v2 API, and v3 serves empty tag lists to Default-rank accounts anyway (verified
 * live 2026-07-16, see .ai/research/urchin/PROBES.md). Urchin's own Starfish-Proxy plugin
 * also targets v2. v2 quirks handled here: the key travels as a `key` query parameter (v2
 * accepts nothing else), every data response is HTTP 200 (bad key = {"detail":"Invalid Key"}
 * body; unknown player = input echoed back in `uuid`), tag fields are `type`/ISO `added_on`,
 * and the batch endpoint takes UUIDs inside a `usernames` array, echoing them back as keys.
 *
 * Read-only, owner-only, fail-closed. The API key lives ONLY in this Worker (URCHIN_KEY
 * secret, else KV "urchin:cfg:key"); redirects are disabled so the keyed URL is never
 * replayed off-origin, and upstream URLs are never logged.
 *
 * Gating (all must hold before any Coral traffic):
 *   - STATS_TOKEN configured AND matched (owner-only; a personal key must not serve strangers)
 *   - X-BWQOL-Urchin: 1 opt-in header (client sends it only for identity-confirmed tasks)
 *   - STATS_KV bound (the budget/backoff/disabled state lives there; no KV -> inert)
 *
 * Cache: 24 h retention / 6 h freshness per player UUID; stale entries are served only when
 * a refetch cannot run (backoff/disabled/failure). Negative results cache the same way.
 * Only {type, reason, addedOn, expiresAt} are retained - reporter identity fields and the
 * never-displayed info/account types are dropped before storage (data minimization).
 *
 * Rate ceiling: fixed 5-minute KV window of 25 requests (adjacent-window worst case 50,
 * under the legacy API's documented 60/5 min batch limit). Every upstream call counts.
 * The window deliberately SURVIVES key set/clear so reconfiguration can't reset the budget.
 *
 * PRIVACY NOTE: only identity-confirmed, active-game UUIDs may ever reach the batch
 * endpoint; the route tests assert the exact upstream UUID array.
 */

const CORAL_BASE_DEFAULT = "https://urchin.ws";
const V2_SOURCES = "MANUAL"; // community blacklist tags only (matches Urchin's own plugin)

const FRESH_MS = 6 * 60 * 60 * 1000; // serve without refetch
const RETAIN_SEC = 24 * 60 * 60; // KV expirationTtl (stale window for outages)
const WINDOW_MS = 5 * 60 * 1000;
const WINDOW_LIMIT = 25;
const BACKOFF_MS = 5 * 60 * 1000; // after a Coral 429
const DISABLED_RETRY_MS = 60 * 60 * 1000; // after a Coral 401/403 (bad/locked key)

const UUID_RE = /^[0-9a-f]{32}$/;

// Per-isolate in-flight dedupe: uuid -> Promise<result>.
const inflight = new Map();
let isolateKeyRejectedAt = 0; // time-bounded: re-consult KV after DISABLED_RETRY_MS

function coralBase(env) {
  return (env && env.URCHIN_BASE) || CORAL_BASE_DEFAULT;
}

/** Lowercase, undashed canonical UUID or null. */
export function normalizeUuid(raw) {
  if (typeof raw !== "string") return null;
  const s = raw.trim().toLowerCase().replace(/-/g, "");
  return UUID_RE.test(s) ? s : null;
}

/** Strip color/control chars, collapse whitespace, cap length. */
export function sanitizeText(raw, max) {
  if (typeof raw !== "string") return "";
  return raw
    .replace(/\u00a7./g, "")
    // ALL Unicode format chars (Cf: bidi controls, zero-width, BOM, ALM, ...) plus
    // C0 + DEL + C1 controls
    .replace(/[\p{Cf}\u0000-\u001f\u007f-\u009f]/gu, " ")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, max || 120);
}

/**
 * Map an upstream tag list to our stored shape, dropping reporter fields + info/account.
 * Accepts both the v2 shape ({type, added_on: ISO string}) and the v3 shape
 * ({tag_type, added_on: unix ms}) so the parsing contract survives an API migration.
 */
export function mapTags(coralTags, nowMs) {
  if (!Array.isArray(coralTags)) return [];
  const out = [];
  for (const t of coralTags) {
    if (!t || typeof t !== "object") continue;
    const type = sanitizeText(String(t.type || t.tag_type || ""), 40).toLowerCase();
    if (!type || type === "info" || type === "account") continue;
    // expires_at: absent/null = never expires; present but unparseable or non-positive =
    // malformed upstream data -> drop the tag defensively rather than pin it forever.
    let expiresAt = null;
    if (t.expires_at !== undefined && t.expires_at !== null) {
      const e = toMs(t.expires_at);
      if (e == null || e <= 0) continue;
      expiresAt = e;
    }
    if (expiresAt != null && expiresAt <= nowMs) continue;
    out.push({
      type,
      reason: sanitizeText(String(t.reason || ""), 120),
      addedOn: toMs(t.added_on) || 0,
      expiresAt,
    });
  }
  return out;
}

/** Unix ms from either a finite number (v3) or an ISO-8601 string (v2); null otherwise. */
function toMs(v) {
  if (Number.isFinite(v)) return v;
  if (typeof v === "string" && v) {
    const p = Date.parse(v.endsWith("Z") || /[+-]\d\d:\d\d$/.test(v) ? v : v + "Z");
    if (Number.isFinite(p)) return p;
  }
  return null;
}

/** Drop tags whose expiry has passed since storage. */
export function filterActive(tags, nowMs) {
  if (!Array.isArray(tags)) return [];
  return tags.filter((t) => t && (t.expiresAt == null || t.expiresAt > nowMs));
}

/**
 * An entry is fresh while (a) it is younger than FRESH_MS AND (b) none of its stored tags
 * has expired since it was written - the earliest expiresAt ends effective freshness so a
 * replacement/new tag is refetched at that instant instead of hiding behind checked-empty.
 */
export function entryFresh(entry, nowMs) {
  if (!entry || !Number.isFinite(entry.fetchedAt)) return false;
  if (nowMs - entry.fetchedAt >= FRESH_MS) return false;
  for (const t of entry.tags || []) {
    if (t && t.expiresAt != null && t.expiresAt <= nowMs) return false;
  }
  return true;
}

// ---- gating ---------------------------------------------------------------

/** Owner token valid AND configured (never open). */
function hasValidToken(request, env) {
  const expected = env && env.STATS_TOKEN;
  if (!expected) return false;
  return request.headers.get("X-BedwarsQol-Token") === expected;
}

/** Owner authentication + explicit opt-in for Urchin DATA routes (capability is separate). */
export function urchinAllowed(request, env) {
  return Boolean(hasValidToken(request, env) && request.headers.get("X-BWQOL-Urchin") === "1");
}

/**
 * Capability: without STATS_KV the budget/backoff/disabled state cannot exist, so data
 * routes must make zero Coral calls - but an authenticated opted-in owner gets resolved
 * "unavailable" metadata rather than a misleading authentication error.
 */
export function urchinCapable(env) {
  return Boolean(env && env.STATS_KV);
}

/** Gate for the set-key route: token + KV only (no opt-in, no master dependency). */
export function keyRouteAllowed(request, env) {
  return Boolean(hasValidToken(request, env) && env && env.STATS_KV);
}

async function activeKey(env) {
  if (env && env.URCHIN_KEY) return { key: env.URCHIN_KEY, fromSecret: true };
  if (env && env.STATS_KV) {
    try {
      const k = await env.STATS_KV.get("urchin:cfg:key");
      if (k) return { key: k, fromSecret: false };
    } catch (_) { /* ignore */ }
  }
  return { key: null, fromSecret: false };
}

// ---- cache ----------------------------------------------------------------

function tagsL1Key(uuid) {
  return new Request(`https://bedwarsqol.internal/cache/urchin/v1/${uuid}`);
}
function tagsL2Key(uuid) {
  return `urchin:v1:${uuid}`;
}

async function readEntry(uuid, env, ctx) {
  const cache = caches.default;
  try {
    const l1 = await cache.match(tagsL1Key(uuid));
    if (l1) return await l1.json();
  } catch (_) { /* ignore */ }
  try {
    const kv = await env.STATS_KV.get(tagsL2Key(uuid), "json");
    if (kv) {
      if (ctx) {
        ctx.waitUntil(
          cache
            .put(
              tagsL1Key(uuid),
              new Response(JSON.stringify(kv), {
                headers: {
                  "content-type": "application/json",
                  "cache-control": `public, max-age=${Math.floor(FRESH_MS / 1000)}`,
                },
              })
            )
            .catch(() => {})
        );
      }
      return kv;
    }
  } catch (_) { /* ignore */ }
  return null;
}

function writeEntry(uuid, entry, env, ctx) {
  const body = JSON.stringify(entry);
  const put = caches.default.put(
    tagsL1Key(uuid),
    new Response(body, {
      headers: {
        "content-type": "application/json",
        "cache-control": `public, max-age=${Math.floor(FRESH_MS / 1000)}`,
      },
    })
  );
  const kvPut = env.STATS_KV.put(tagsL2Key(uuid), body, { expirationTtl: RETAIN_SEC }).catch(() => {});
  if (ctx) {
    ctx.waitUntil(put.catch(() => {}));
    ctx.waitUntil(kvPut);
  }
}

// ---- budget / backoff / disabled state -------------------------------------

/**
 * Reserve n requests from the fixed 5-minute window. Best-effort (KV is not atomic - the
 * 250 threshold leaves a 350-request margin under the 600 limit even across a boundary,
 * recorded as an owner-accepted relaxation). Returns true if the calls may proceed.
 */
async function reserveBudget(env, ctx, n, nowMs) {
  try {
    const cur = (await env.STATS_KV.get("urchin:cfg:window", "json")) || { start: nowMs, count: 0 };
    const w = nowMs - cur.start >= WINDOW_MS ? { start: nowMs, count: 0 } : cur;
    if (w.count + n > WINDOW_LIMIT) return false;
    w.count += n;
    // The reservation must be PERSISTED before the upstream call is authorized - a known
    // failed write is not covered by the owner-approved non-atomicity relaxation.
    await env.STATS_KV.put("urchin:cfg:window", JSON.stringify(w), {
      expirationTtl: Math.ceil((WINDOW_MS * 2) / 1000),
    });
    return true;
  } catch (_) {
    return false; // can't account -> don't spend
  }
}

let isolateBackoffUntil = 0; // immediate local record; KV write is best-effort cross-isolate

async function isBlocked(env, nowMs) {
  if (isolateKeyRejectedAt && nowMs - isolateKeyRejectedAt < DISABLED_RETRY_MS) return true;
  if (nowMs < isolateBackoffUntil) return true;
  try {
    const [backoff, disabled] = await Promise.all([
      env.STATS_KV.get("urchin:cfg:backoff"),
      env.STATS_KV.get("urchin:cfg:disabled"),
    ]);
    if (backoff && nowMs - Number(backoff) < BACKOFF_MS) return true;
    if (disabled && nowMs - Number(disabled) < DISABLED_RETRY_MS) return true;
  } catch (_) {
    // Cannot read the shared block state -> fail closed for one backoff interval.
    isolateBackoffUntil = Math.max(isolateBackoffUntil, nowMs + BACKOFF_MS);
    return true;
  }
  return false;
}

function noteBackoff(env, ctx, nowMs) {
  isolateBackoffUntil = Math.max(isolateBackoffUntil, nowMs + BACKOFF_MS);
  const put = env.STATS_KV.put("urchin:cfg:backoff", String(nowMs), { expirationTtl: 600 });
  if (ctx) ctx.waitUntil(put.catch(() => {}));
}

function noteKeyRejected(env, ctx, nowMs) {
  if (!isolateKeyRejectedAt) console.log("URCHIN_KEY_REJECTED");
  isolateKeyRejectedAt = nowMs;
  const put = env.STATS_KV.put("urchin:cfg:disabled", String(nowMs), { expirationTtl: 7200 });
  if (ctx) ctx.waitUntil(put.catch(() => {}));
}

// ---- Coral calls ----------------------------------------------------------

async function coralFetch(env, path, init) {
  const { key } = await activeKey(env);
  if (!key) return { status: "nokey" };
  let res;
  try {
    // v2 accepts the key ONLY as a query parameter. workerd only supports "follow"/"manual";
    // "manual" + explicit 3xx failure means the keyed URL is never replayed off-origin.
    const sep = path.includes("?") ? "&" : "?";
    res = await fetch(
      `${coralBase(env)}${path}${sep}key=${encodeURIComponent(key)}&sources=${V2_SOURCES}`,
      { ...init, redirect: "manual" }
    );
  } catch (e) {
    return { status: "error" };
  }
  if (res.status >= 300 && res.status < 400) {
    try { await res.body?.cancel(); } catch (_) {}
    return { status: "error" };
  }
  if (res.status === 429) return { status: "ratelimited" };
  if (res.status === 401 || res.status === 403) return { status: "rejected" };
  if (res.status === 404) return { status: "notfound" };
  if (!res.ok) {
    try { await res.body?.cancel(); } catch (_) {}
    return { status: "error" };
  }
  let json;
  try { json = await res.json(); } catch (_) { return { status: "error" }; }
  // v2 signals a bad/unknown key with HTTP 200 and a "detail"/plain-string body.
  const detail = typeof json === "string" ? json : json && json.detail;
  if (typeof detail === "string" && /invalid key/i.test(detail)) return { status: "rejected" };
  return { status: "ok", json };
}

/**
 * Resolve tags for canonical UUIDs. Returns Map<uuid, result> where result is
 * { state: "ok"|"unavailable", tags: [...] } - "ok" means concluded (urchinChecked),
 * "unavailable" means no data could be obtained now (urchinUnavailable, maybe stale tags).
 * Missing map entries mean transient failure (client may retry later).
 */
export async function tagsForUuids(rawUuids, env, ctx) {
  const nowMs = Date.now();
  const out = new Map();
  const uuids = [];
  for (const raw of rawUuids) {
    const u = normalizeUuid(raw);
    if (u && !uuids.includes(u)) uuids.push(u);
  }
  if (uuids.length === 0) return out;

  const { key } = await activeKey(env);
  const blocked = key ? await isBlocked(env, nowMs) : true;

  const misses = [];
  for (const u of uuids) {
    const entry = await readEntry(u, env, ctx);
    if (!key) {
      // No key -> Urchin is off entirely: no cached tags either (a cleared key must not
      // keep old accusations displayable anywhere).
      out.set(u, { state: "unavailable", tags: [] });
    } else if (entryFresh(entry, nowMs)) {
      out.set(u, { state: "ok", tags: filterActive(entry.tags, nowMs) });
    } else if (blocked) {
      // Cannot refetch: serve stale if we have it, else unavailable.
      out.set(u, { state: "unavailable", tags: entry ? filterActive(entry.tags, nowMs) : [] });
    } else {
      misses.push({ uuid: u, stale: entry });
    }
  }
  if (misses.length === 0) return out;

  // In-flight dedupe within the isolate.
  const toFetch = misses.filter((m) => !inflight.has(m.uuid));
  let batchPromise = null;
  if (toFetch.length > 0) {
    if (!(await reserveBudget(env, ctx, 1, nowMs))) {
      for (const m of misses) {
        out.set(m.uuid, { state: "unavailable", tags: m.stale ? filterActive(m.stale.tags, nowMs) : [] });
      }
      return out;
    }
    // v2 batch: UUIDs travel inside the `usernames` array and are echoed back as the
    // response keys (verified live 2026-07-16).
    batchPromise = coralFetch(env, "/player", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ usernames: toFetch.map((m) => m.uuid).slice(0, 100) }),
    });
    for (const m of toFetch) inflight.set(m.uuid, batchPromise);
  }

  const settle = async (m) => {
    const p = inflight.get(m.uuid) || batchPromise;
    const r = p ? await p : { status: "error" };
    if (r.status === "ok") {
      const players = (r.json && r.json.players) || {};
      // Response keys re-normalized before matching (casing/dashes undocumented).
      const byUuid = new Map();
      for (const k of Object.keys(players)) {
        const nk = normalizeUuid(k);
        if (nk) byUuid.set(nk, players[k]);
      }
      const tags = mapTags(byUuid.get(m.uuid) || [], nowMs);
      writeEntry(m.uuid, { tags, fetchedAt: nowMs }, env, ctx);
      return { state: "ok", tags: filterActive(tags, nowMs) };
    }
    if (r.status === "ratelimited") noteBackoff(env, ctx, nowMs);
    if (r.status === "rejected") noteKeyRejected(env, ctx, nowMs);
    if (r.status === "ratelimited" || r.status === "rejected" || r.status === "nokey") {
      return { state: "unavailable", tags: m.stale ? filterActive(m.stale.tags, nowMs) : [] };
    }
    // Transient error: stale (if any) -> unavailable-with-stale; else omit (client retries).
    if (m.stale) return { state: "unavailable", tags: filterActive(m.stale.tags, nowMs) };
    return null;
  };

  await Promise.all(
    misses.map(async (m) => {
      try {
        const r = await settle(m);
        if (r) out.set(m.uuid, r);
      } finally {
        inflight.delete(m.uuid);
      }
    })
  );
  return out;
}

/** Stale fallback for the manual route: name alias -> cached entry (may be empty). */
async function staleForName(name, env, ctx) {
  try {
    const aliased = await env.STATS_KV.get(`urchin:name:v1:${name.toLowerCase()}`);
    const u = normalizeUuid(aliased);
    if (u) {
      const entry = await readEntry(u, env, ctx);
      if (entry) return { uuid: u, entry };
    }
  } catch (_) { /* ignore */ }
  return null;
}

/** Manual name lookup (GET /player/<name>) - the ONLY name-resolving upstream path. */
export async function tagsForName(name, env, ctx) {
  const nowMs = Date.now();
  const { key } = await activeKey(env);
  const cached = await staleForName(name, env, ctx);

  if (!key) {
    // Keyless: Urchin is off entirely - no cached tags either.
    return { state: "unavailable", tags: [] };
  }
  // Fresh cache reuse: repeated manual lookups inside the 6 h window spend no Coral request.
  if (cached && entryFresh(cached.entry, nowMs)) {
    return { state: "ok", tags: filterActive(cached.entry.tags, nowMs), uuid: cached.uuid };
  }
  if (await isBlocked(env, nowMs)) {
    return { state: "unavailable", tags: cached ? filterActive(cached.entry.tags, nowMs) : [], stale: !!cached };
  }
  if (!(await reserveBudget(env, ctx, 1, nowMs))) {
    return { state: "unavailable", tags: cached ? filterActive(cached.entry.tags, nowMs) : [], stale: !!cached };
  }

  const r = await coralFetch(env, `/player/${encodeURIComponent(name)}`, { method: "GET" });
  if (r.status === "ok") {
    const uuid = normalizeUuid(r.json && r.json.uuid);
    // v2 has no 404 for unknown names: it echoes the input back in `uuid`. A response
    // whose uuid does not normalize is therefore "player not found", not a tag result.
    if (!uuid) return { state: "notfound", tags: [] };
    const tags = mapTags((r.json && r.json.tags) || [], nowMs);
    if (uuid) {
      writeEntry(uuid, { tags, fetchedAt: nowMs }, env, ctx);
      const alias = env.STATS_KV.put(`urchin:name:v1:${name.toLowerCase()}`, uuid, { expirationTtl: RETAIN_SEC });
      if (ctx) ctx.waitUntil(alias.catch(() => {}));
    }
    return { state: "ok", tags: filterActive(tags, nowMs), uuid };
  }
  if (r.status === "notfound") return { state: "notfound", tags: [] };
  if (r.status === "ratelimited") noteBackoff(env, ctx, nowMs);
  if (r.status === "rejected") noteKeyRejected(env, ctx, nowMs);

  // Failure (timeout/5xx/429/rejected): serve stale via the name alias when possible.
  if (cached) return { state: "unavailable", tags: filterActive(cached.entry.tags, nowMs), stale: true };
  return { state: "unavailable", tags: [] };
}

/** Shape a per-player result into response fields (urchin/urchinChecked/urchinUnavailable). */
export function resultFields(result, uuid) {
  if (!result) return {};
  const fields = {};
  // Unambiguous provenance for the client merge: the canonical UUID this result belongs
  // to. Names are NOT a safe join key (case-varied duplicates collide).
  if (uuid) fields.urchinUuid = uuid;
  if (result.state === "ok" || result.state === "notfound") fields.urchinChecked = true;
  if (result.state === "notfound") fields.urchinNotFound = true;
  if (result.state === "unavailable") fields.urchinUnavailable = true;
  if (Array.isArray(result.tags) && result.tags.length > 0) {
    fields.urchin = { tags: result.tags.map(({ type, reason, addedOn, expiresAt }) => ({
      type, reason, addedOn, ...(expiresAt != null ? { expiresAtMs: expiresAt } : {}),
    })) };
  }
  return fields;
}

/** POST /urchin/key: {key: "..."} sets, {key: null} clears. Never echoes the key. */
export async function handleKeySet(request, env, ctx) {
  if (!env || !env.STATS_KV) {
    return json({ success: false, error: "kv_required" }, 503);
  }
  if (!keyRouteAllowed(request, env)) {
    return json({ success: false, error: "unauthorized" }, 403);
  }
  if (env.URCHIN_KEY) {
    return json({ success: false, error: "key_managed_by_secret" }, 409);
  }
  let body;
  try { body = await request.json(); } catch (_) {
    return json({ success: false, error: "bad_request" }, 400);
  }
  const key = body && Object.prototype.hasOwnProperty.call(body, "key") ? body.key : undefined;
  if (key === undefined || (key !== null && (typeof key !== "string" || key.length < 8 || key.length > 200))) {
    return json({ success: false, error: "bad_request" }, 400);
  }
  // Partial-mutation contract: auxiliary state (backoff/disabled) is cleaned BEFORE the key
  // mutation, so a failure response always means the key itself is unchanged - the client
  // can trust "failure = nothing happened" and "success = key state + cleanup committed".
  // (The WINDOW counter survives: rate accounting is independent of reconfiguration.)
  try {
    await env.STATS_KV.delete("urchin:cfg:backoff");
    await env.STATS_KV.delete("urchin:cfg:disabled");
    if (key === null) await env.STATS_KV.delete("urchin:cfg:key");
    else await env.STATS_KV.put("urchin:cfg:key", key);
  } catch (_) {
    return json({ success: false, error: "kv_write_failed" }, 503);
  }
  isolateKeyRejectedAt = 0;
  isolateBackoffUntil = 0;
  return json({ success: true });
}

function json(obj, status) {
  return new Response(JSON.stringify(obj), {
    status: status || 200,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

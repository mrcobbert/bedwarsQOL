/**
 * Flow tests for urchin.js with stubbed KV/edge-cache/fetch: keyless behavior, fresh-cache
 * reuse, stale fallbacks, budget accounting, and malformed-expiry rejection - the failure
 * matrix the route tests can't exercise deterministically.
 */
import test from "node:test";
import assert from "node:assert/strict";
import { tagsForUuids, tagsForName, mapTags, handleKeySet, urchinAllowed, urchinCapable } from "../src/urchin.js";

const UUID = "069a79f444e94726a5befca90e38aaf5";

function makeKv(seed = {}) {
  const store = new Map(Object.entries(seed));
  return {
    store,
    async get(k, type) {
      const v = store.has(k) ? store.get(k) : null;
      if (v == null) return null;
      return type === "json" ? JSON.parse(v) : v;
    },
    async put(k, v) { store.set(k, v); },
    async delete(k) { store.delete(k); },
  };
}

function stubCaches() {
  globalThis.caches = { default: { async match() { return undefined; }, async put() {} } };
}

function env(kv, extra = {}) {
  return { STATS_KV: kv, ...extra };
}
const ctx = { waitUntil() {} };

function freshEntry(tags, ageMs = 0) {
  return JSON.stringify({ tags, fetchedAt: Date.now() - ageMs });
}

test.beforeEach(() => {
  stubCaches();
  globalThis.fetch = async () => { throw new Error("unexpected fetch"); };
});

test("keyless: cached tags are NEVER shown (clear must fully disable display)", async () => {
  const kv = makeKv({ [`urchin:v1:${UUID}`]: freshEntry([{ type: "sniper", reason: "", addedOn: 1, expiresAt: null }]) });
  const res = await tagsForUuids([UUID], env(kv), ctx);
  const r = res.get(UUID);
  assert.equal(r.state, "unavailable");
  assert.equal(r.tags.length, 0);
});

test("keyless: no cache -> unavailable-empty, zero upstream fetches", async () => {
  let fetches = 0;
  globalThis.fetch = async () => { fetches++; throw new Error("no"); };
  const res = await tagsForUuids([UUID], env(makeKv()), ctx);
  assert.equal(res.get(UUID).state, "unavailable");
  assert.equal(fetches, 0);
});

test("expiry-after-cache: an expired stored tag ENDS freshness and forces a refetch", async () => {
  let fetches = 0;
  globalThis.fetch = async () =>
    new Response(JSON.stringify({ players: { [UUID]: [{ tag_type: "caution", reason: "new", added_on: 2 }] } }), {
      headers: { "content-type": "application/json" },
    });
  const origFetch = globalThis.fetch;
  globalThis.fetch = async (...a) => { fetches++; return origFetch(...a); };
  const kv = makeKv({
    "urchin:cfg:key": "k".repeat(16),
    [`urchin:v1:${UUID}`]: freshEntry([{ type: "sniper", reason: "", addedOn: 1, expiresAt: Date.now() - 5 }]),
  });
  const res = await tagsForUuids([UUID], env(kv), ctx);
  const r = res.get(UUID);
  assert.equal(fetches, 1); // entry is young but its tag expired -> no longer fresh
  assert.equal(r.state, "ok");
  assert.deepEqual(r.tags.map((x) => x.type), ["caution"]);
});

test("stale + transient 5xx -> unavailable with stale tags; 429 sets backoff flag", async () => {
  const kv = makeKv({
    "urchin:cfg:key": "k".repeat(16),
    [`urchin:v1:${UUID}`]: freshEntry([{ type: "caution", reason: "", addedOn: 1, expiresAt: null }], 7 * 3600 * 1000),
  });
  globalThis.fetch = async () => new Response("{}", { status: 502 });
  const res = await tagsForUuids([UUID], env(kv), ctx);
  const r = res.get(UUID);
  assert.equal(r.state, "unavailable");
  assert.equal(r.tags[0].type, "caution");

  globalThis.fetch = async () => new Response("{}", { status: 429 });
  const kv2 = makeKv({ "urchin:cfg:key": "k".repeat(16) });
  await tagsForUuids([UUID], env(kv2), ctx);
  assert.ok(kv2.store.has("urchin:cfg:backoff"));
});

test("budget exhausted -> no upstream call, unavailable (stale served)", async () => {
  let fetches = 0;
  globalThis.fetch = async () => { fetches++; return new Response("{}"); };
  const kv = makeKv({
    "urchin:cfg:key": "k".repeat(16),
    "urchin:cfg:window": JSON.stringify({ start: Date.now(), count: 25 }),
  });
  const res = await tagsForUuids([UUID], env(kv), ctx);
  assert.equal(res.get(UUID).state, "unavailable");
  assert.equal(fetches, 0);
});

test("manual: fresh alias cache reuse spends no Coral request", async () => {
  let fetches = 0;
  globalThis.fetch = async () => { fetches++; throw new Error("no"); };
  const kv = makeKv({
    "urchin:cfg:key": "k".repeat(16),
    "urchin:name:v1:someguy": UUID,
    [`urchin:v1:${UUID}`]: freshEntry([{ type: "sniper", reason: "q", addedOn: 1, expiresAt: null }]),
  });
  const r = await tagsForName("SomeGuy", env(kv), ctx);
  assert.equal(r.state, "ok");
  assert.equal(r.tags[0].type, "sniper");
  assert.equal(fetches, 0);
});

test("manual: keyless is fully off; backoff falls back to stale alias data", async () => {
  const seed = {
    "urchin:name:v1:someguy": UUID,
    [`urchin:v1:${UUID}`]: freshEntry([{ type: "caution", reason: "", addedOn: 1, expiresAt: null }], 7 * 3600 * 1000),
  };
  // Keyless: fully off - not even stale tags (clear contract).
  const keyless = await tagsForName("SomeGuy", env(makeKv(seed)), ctx);
  assert.equal(keyless.state, "unavailable");
  assert.equal(keyless.tags.length, 0);

  const backed = await tagsForName(
    "SomeGuy",
    env(makeKv({ ...seed, "urchin:cfg:key": "k".repeat(16), "urchin:cfg:backoff": String(Date.now()) })),
    ctx
  );
  assert.equal(backed.state, "unavailable");
  assert.equal(backed.tags.length, 1);
});

test("manual: timeout with stale -> unavailable+stale; without stale -> unavailable-empty", async () => {
  globalThis.fetch = async () => { throw new Error("timeout"); };
  const withStale = await tagsForName(
    "SomeGuy",
    env(makeKv({
      "urchin:cfg:key": "k".repeat(16),
      "urchin:name:v1:someguy": UUID,
      [`urchin:v1:${UUID}`]: freshEntry([{ type: "sniper", reason: "", addedOn: 1, expiresAt: null }], 7 * 3600 * 1000),
    })),
    ctx
  );
  assert.equal(withStale.state, "unavailable");
  assert.equal(withStale.stale, true);
  assert.equal(withStale.tags.length, 1);

  const without = await tagsForName("Fresh", env(makeKv({ "urchin:cfg:key": "k".repeat(16) })), ctx);
  assert.equal(without.state, "unavailable");
  assert.equal(without.tags.length, 0);
});

test("mapTags: present-but-invalid or negative expires_at drops the tag", () => {
  const now = Date.now();
  const tags = mapTags(
    [
      { tag_type: "sniper", reason: "a", added_on: 1, expires_at: "soon" },
      { tag_type: "caution", reason: "b", added_on: 1, expires_at: -5 },
      { tag_type: "closet_cheater", reason: "c", added_on: 1, expires_at: null },
      { tag_type: "blatant_cheater", reason: "d", added_on: 1 },
    ],
    now
  );
  assert.deepEqual(tags.map((t) => t.type), ["closet_cheater", "blatant_cheater"]);
});

test("failed rate-counter write -> no upstream call (fail closed)", async () => {
  let fetches = 0;
  globalThis.fetch = async () => { fetches++; return new Response("{}"); };
  const kv = makeKv({ "urchin:cfg:key": "k".repeat(16) });
  const origPut = kv.put.bind(kv);
  kv.put = async (k, v) => { if (k === "urchin:cfg:window") throw new Error("kv down"); return origPut(k, v); };
  const res = await tagsForUuids([UUID], env(kv), ctx);
  assert.equal(fetches, 0);
  assert.equal(res.get(UUID).state, "unavailable");
});

test("sanitize (via mapTags): ALM and deprecated bidi controls are stripped", () => {
  const alm = String.fromCharCode(0x061c);
  const dep = String.fromCharCode(0x206a);
  const tags = mapTags([{ tag_type: "sniper", reason: "a" + alm + "b" + dep + "c", added_on: 1 }], Date.now());
  assert.equal(tags[0].reason, "a b c");
});

test("no-KV capability: allowed but not capable; key route 503s", async () => {
  const req = new Request("https://x/urchin/key", {
    method: "POST",
    headers: { "X-BedwarsQol-Token": "t", "X-BWQOL-Urchin": "1" },
    body: JSON.stringify({ key: "k".repeat(16) }),
  });
  const envNoKv = { STATS_TOKEN: "t" };
  assert.equal(urchinAllowed(req, envNoKv), true); // auth is separate from capability
  assert.equal(urchinCapable(envNoKv), false);
  const res = await handleKeySet(req, envNoKv, ctx);
  assert.equal(res.status, 503);
});

test("backoff is fail-closed when KV state reads/writes reject", async () => {
  let fetches = 0;
  globalThis.fetch = async () => { fetches++; return new Response("{}", { status: 429 }); };
  const kv = makeKv({ "urchin:cfg:key": "k".repeat(16) });
  const origGet = kv.get.bind(kv);
  kv.get = async (k, type) => {
    if (k === "urchin:cfg:backoff" || k === "urchin:cfg:disabled") throw new Error("kv down");
    return origGet(k, type);
  };
  const first = await tagsForUuids([UUID], env(kv), ctx);
  // KV state unreadable -> fail closed BEFORE any upstream call.
  assert.equal(fetches, 0);
  assert.equal(first.get(UUID).state, "unavailable");
});

test("429 triggers isolate-local backoff even if the KV write is lost", async () => {
  // Fresh module state per test file is not available (module-level isolate flags), so this
  // asserts the observable contract: after a 429, an immediate second lookup makes no call.
  let fetches = 0;
  globalThis.fetch = async () => { fetches++; return new Response("{}", { status: 429 }); };
  const kv = makeKv({ "urchin:cfg:key": "k".repeat(16) });
  const origPut = kv.put.bind(kv);
  kv.put = async (k, v) => { if (k === "urchin:cfg:backoff") throw new Error("lost"); return origPut(k, v); };
  await tagsForUuids(["11111111222233334444555566667778"], env(kv), ctx);
  const before = fetches;
  await tagsForUuids(["11111111222233334444555566667779"], env(kv), ctx);
  assert.equal(fetches, before); // isolate-local backoff blocks the second call
});

test("key mutation contract: cleanup failure -> 503 AND key unchanged", async () => {
  const kv = makeKv({ "urchin:cfg:key": "oldkey-0123456789", "urchin:cfg:disabled": String(Date.now()) });
  const origDelete = kv.delete.bind(kv);
  kv.delete = async (k) => { if (k === "urchin:cfg:disabled") throw new Error("kv down"); return origDelete(k); };
  const req = new Request("https://x/urchin/key", {
    method: "POST",
    headers: { "X-BedwarsQol-Token": "t" },
    body: JSON.stringify({ key: null }),
  });
  const res = await handleKeySet(req, { STATS_TOKEN: "t", STATS_KV: kv }, ctx);
  assert.equal(res.status, 503);
  // Failure means NOTHING happened to the key: it is still set.
  assert.equal(kv.store.get("urchin:cfg:key"), "oldkey-0123456789");
});

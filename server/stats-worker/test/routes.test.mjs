/**
 * Route-level tests against the real Worker under miniflare-in-wrangler (unstable_dev),
 * with a local fixture standing in for Coral (URCHIN_BASE override). Asserts the gating
 * matrix (token x opt-in x KV x key), wire shapes, NDJSON follow-up ordering, and -
 * critically - the EXACT uuid array that reaches the Coral fixture (an unintended UUID is
 * an external privacy-affecting action).
 */
import test from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import os from "node:os";
import { once } from "node:events";

// workerd (wrangler local dev) cannot connect back to loopback on this platform, so the
// Coral fixture binds 0.0.0.0 and the Worker reaches it via the machine's LAN address.
function lanAddress() {
  for (const ifaces of Object.values(os.networkInterfaces())) {
    for (const i of ifaces || []) {
      if (i.family === "IPv4" && !i.internal) return i.address;
    }
  }
  return null;
}

const TOKEN = "test-token";
const TAGGED = "069a79f444e94726a5befca90e38aaf5";
const UNTAGGED = "11111111222233334444555566667777";

// ---- Coral fixture ---------------------------------------------------------
const coralCalls = [];
const fixture = http.createServer((req, res) => {
  let body = "";
  req.on("data", (c) => (body += c));
  req.on("end", () => {
    const parsed = new URL(req.url, "http://x");
    coralCalls.push({
      method: req.method,
      url: parsed.pathname,
      body,
      key: parsed.searchParams.get("key"),
      sources: parsed.searchParams.get("sources"),
    });
    res.setHeader("content-type", "application/json");
    // Legacy v2 shapes: key in query, batch takes uuids inside `usernames` and echoes them
    // back as keys, single GET echoes unknown names in `uuid` instead of a 404.
    if (req.method === "POST" && parsed.pathname === "/player") {
      const uuids = JSON.parse(body).usernames || [];
      const players = {};
      for (const u of uuids) players[u] = u === TAGGED
        ? [{ type: "blatant_cheater", reason: "kb", added_on: "1970-01-01T00:00:00.005", added_by_id: 1, hide_username: false }]
        : [];
      res.end(JSON.stringify({ players }));
      return;
    }
    const single = req.method === "GET" && parsed.pathname.match(/^\/player\/([^/]+)$/);
    if (single) {
      const name = decodeURIComponent(single[1]);
      if (name === "NoSuchPlayer") { res.end(JSON.stringify({ uuid: name, tags: [] })); return; }
      res.end(JSON.stringify({
        uuid: TAGGED,
        tags: name === "TaggedGuy" ? [{ type: "sniper", reason: "q", added_on: "1970-01-01T00:00:00.007", added_by_id: 1, hide_username: false }] : [],
      }));
      return;
    }
    res.statusCode = 500;
    res.end("{}");
  });
});

let worker;
let base;

test.before(async () => {
  // Hermetic runs: wrangler dev persists KV/cache under .wrangler/state, which would let
  // a previous run's 6h-fresh urchin entries satisfy this run's lookups.
  const { rmSync } = await import("node:fs");
  rmSync(new URL("../.wrangler/state", import.meta.url), { recursive: true, force: true });
  fixture.listen(0, "0.0.0.0");
  await once(fixture, "listening");
  const coralPort = fixture.address().port;
  const lan = lanAddress();
  if (!lan) throw new Error("No LAN IPv4 address available for the Coral fixture");
  const { unstable_dev } = await import("wrangler");
  worker = await unstable_dev("src/worker.js", {
    experimental: { disableExperimentalWarning: true },
    vars: {
      STATS_TOKEN: TOKEN,
      URCHIN_KEY: "unit-test-key-abcdef",
      URCHIN_BASE: `http://${lan}:${coralPort}`,
    },
    kv: [{ binding: "STATS_KV" }],
    local: true,
  });
  base = `http://${worker.address}:${worker.port}`;
});

test.after(async () => {
  if (worker) await worker.stop();
  fixture.close();
});

function req(path, opts = {}) {
  const headers = { ...(opts.headers || {}) };
  if (opts.token !== false) headers["X-BedwarsQol-Token"] = TOKEN;
  if (opts.optIn) headers["X-BWQOL-Urchin"] = "1";
  return fetch(base + path, { method: opts.method || "GET", headers, body: opts.body });
}

// NOTE: /bedwars/<name> hits hypixel.net on cache miss; these tests use paths that don't
// need a successful scrape to prove the Urchin behavior (metadata absence/presence and
// upstream call counts are asserted regardless of stats success).

test("no opt-in header -> zero Coral calls even with key+token (single route)", async () => {
  const before = coralCalls.length;
  const r = await req(`/bedwars/SomePlayer?uuid=${TAGGED}`);
  const body = await r.json();
  assert.equal(coralCalls.length, before);
  assert.ok(!("urchin" in body) && !("urchinChecked" in body) && !("urchinUnavailable" in body));
});

test("wrong token -> urchin inert on manual route", async () => {
  const r = await fetch(base + "/urchin/TaggedGuy", {
    headers: { "X-BedwarsQol-Token": "wrong", "X-BWQOL-Urchin": "1" },
  });
  assert.equal(r.status, 403);
});

test("opt-in single with uuid -> Coral called with exactly that uuid; tags attach", async () => {
  const before = coralCalls.length;
  const r = await req(`/bedwars/TaggedStats?uuid=${TAGGED.toUpperCase()}`, { optIn: true });
  const body = await r.json();
  const calls = coralCalls.slice(before).filter((c) => c.url === "/player" && c.method === "POST");
  assert.equal(calls.length, 1);
  assert.deepEqual(JSON.parse(calls[0].body).usernames, [TAGGED]); // canonicalized, exact set
  assert.equal(body.urchinChecked, true);
  assert.equal(body.urchin.tags[0].type, "blatant_cheater");
  assert.equal(body.urchin.tags[0].reason, "kb");
  assert.ok(!("added_by" in body.urchin.tags[0]));
});

test("opt-in single without uuid -> no Coral call, no metadata", async () => {
  const before = coralCalls.length;
  const r = await req(`/bedwars/NamedOnly`, { optIn: true });
  const body = await r.json();
  assert.equal(coralCalls.slice(before).filter((c) => c.url === "/player" && c.method === "POST").length, 0);
  assert.ok(!("urchinChecked" in body) && !("urchinUnavailable" in body));
});

test("opt-in single with malformed uuid -> no Coral call, no metadata", async () => {
  const before = coralCalls.length;
  const r = await req(`/bedwars/BadUuid?uuid=zzzz`, { optIn: true });
  const body = await r.json();
  assert.equal(coralCalls.slice(before).filter((c) => c.url === "/player" && c.method === "POST").length, 0);
  assert.ok(!("urchinChecked" in body));
});

test("known-empty caches: second lookup for untagged uuid makes no new Coral call", async () => {
  const before = coralCalls.length;
  await req(`/bedwars/UntaggedA?uuid=${UNTAGGED}`, { optIn: true });
  const mid = coralCalls.filter((c) => c.url === "/player" && c.method === "POST").length;
  const r2 = await req(`/bedwars/UntaggedB?uuid=${UNTAGGED}`, { optIn: true });
  const body2 = await r2.json();
  const after = coralCalls.filter((c) => c.url === "/player" && c.method === "POST").length;
  assert.equal(after, mid); // cache hit, no second upstream call
  assert.equal(body2.urchinChecked, true);
  assert.ok(!("urchin" in body2)); // known-empty -> no urchin field
});

test("manual route: tagged / empty / 404 distinctions", async () => {
  const tagged = await (await req("/urchin/TaggedGuy", { optIn: true })).json();
  assert.equal(tagged.success, true);
  assert.equal(tagged.notFound, false);
  assert.equal(tagged.tags[0].type, "sniper");

  const empty = await (await req("/urchin/CleanGuy", { optIn: true })).json();
  assert.equal(empty.tags.length, 0);
  assert.equal(empty.notFound, false);
  assert.equal(empty.unavailable, false);

  const nf = await (await req("/urchin/NoSuchPlayer", { optIn: true })).json();
  assert.equal(nf.notFound, true);
  assert.equal(nf.tags.length, 0);
});

test("batch: uuids aligned with '-' placeholders; only eligible uuids reach Coral; urchinUpdate after base lines", async () => {
  const before = coralCalls.length;
  // Names resolve via cache from earlier tests or fail to scrape; either way the NDJSON
  // stream completes and the Coral body must contain ONLY the aligned non-placeholder uuid.
  const r = await req(
    `/bedwars/batch?names=AlphaOne,BetaTwo&uuids=${TAGGED},-`,
    { optIn: true }
  );
  const text = await r.text();
  const lines = text.trim().split("\n").map((l) => JSON.parse(l));
  const calls = coralCalls.slice(before).filter((c) => c.url === "/player" && c.method === "POST");
  assert.ok(calls.length <= 1);
  if (calls.length === 1) {
    assert.deepEqual(JSON.parse(calls[0].body).usernames, [TAGGED]);
  }
  // Ordering: any urchinUpdate line must come after its base line.
  const baseIdx = new Map();
  lines.forEach((l, i) => { if (!l.urchinUpdate && !l.starUpdate) baseIdx.set(l.name, i); });
  lines.forEach((l, i) => {
    if (l.urchinUpdate) assert.ok(baseIdx.has(l.name) && baseIdx.get(l.name) < i);
  });
  // The placeholder member must never carry urchin metadata.
  for (const l of lines) {
    if (l.name === "BetaTwo") assert.ok(!("urchin" in l) && !("urchinChecked" in l));
  }
});

test("key route: 409 when URCHIN_KEY secret is set; unauthorized without token; key never echoed", async () => {
  const conflict = await req("/urchin/key", {
    method: "POST",
    body: JSON.stringify({ key: "abcdefgh12345678" }),
  });
  assert.equal(conflict.status, 409);
  const cBody = await conflict.text();
  assert.ok(!cBody.includes("abcdefgh12345678"));

  const noAuth = await fetch(base + "/urchin/key", {
    method: "POST",
    body: JSON.stringify({ key: "abcdefgh12345678" }),
  });
  assert.equal(noAuth.status, 403);
  assert.ok(!(await noAuth.text()).includes("abcdefgh12345678"));
});

test("case-varied duplicates with DIFFERENT uuids fail closed: neither uuid reaches Coral", async () => {
  const before = coralCalls.length;
  const r = await req(
    `/bedwars/batch?names=CaseGuy,caseguy&uuids=${TAGGED},${UNTAGGED}`,
    { optIn: true }
  );
  const lines = (await r.text()).trim().split("\n").map((l) => JSON.parse(l));
  // Conflicting alignment cannot be attributed through the case-insensitive stream dedupe,
  // so the batch must not resolve EITHER uuid (no omission ambiguity, no cross-attachment);
  // the client's bounded single path handles each unambiguously later.
  const sent = coralCalls.slice(before).filter((c) => c.url === "/player" && c.method === "POST")
    .flatMap((c) => JSON.parse(c.body).usernames);
  assert.ok(!sent.includes(TAGGED) && !sent.includes(UNTAGGED));
  for (const l of lines) {
    assert.ok(!("urchin" in l) && !("urchinChecked" in l));
    if (l.urchinUnavailable) assert.ok(typeof l.urchinUuid === "string");
  }
});

test("same-uuid case-varied duplicates still resolve normally with urchinUuid provenance", async () => {
  const r = await req(
    `/bedwars/batch?names=SameGuy,sameguy&uuids=${TAGGED},${TAGGED}`,
    { optIn: true }
  );
  const lines = (await r.text()).trim().split("\n").map((l) => JSON.parse(l));
  const withMeta = lines.filter((l) => l.urchin || l.urchinChecked);
  assert.ok(withMeta.length >= 1); // metadata REQUIRED - the regression must not pass vacuously
  for (const l of withMeta) assert.equal(l.urchinUuid, TAGGED);
});

test("no-KV deployment: data routes are resolved-unavailable, not unauthorized", async () => {
  const { unstable_dev } = await import("wrangler");
  const lan = lanAddress();
  const w2 = await unstable_dev("src/worker.js", {
    experimental: { disableExperimentalWarning: true },
    vars: {
      STATS_TOKEN: TOKEN,
      URCHIN_KEY: "unit-test-key-abcdef",
      // Point at the SAME fixture so "zero upstream calls" is observable, not structural.
      URCHIN_BASE: `http://${lan}:${fixture.address().port}`,
    },
    local: true,
  });
  const coralBefore = coralCalls.length;
  try {
    const b2 = `http://${w2.address}:${w2.port}`;
    const h = { "X-BedwarsQol-Token": TOKEN, "X-BWQOL-Urchin": "1" };
    const manual = await (await fetch(`${b2}/urchin/SomeGuy`, { headers: h })).json();
    assert.equal(manual.success, true);
    assert.equal(manual.unavailable, true);
    const single = await (await fetch(`${b2}/bedwars/NoKvGuy?uuid=${TAGGED}`, { headers: h })).json();
    assert.equal(single.urchinUnavailable, true);
    assert.ok(!("urchin" in single));
    const batch = await (await fetch(`${b2}/bedwars/batch?names=NoKvGuy&uuids=${TAGGED}`, { headers: h })).text();
    const line = JSON.parse(batch.trim().split("\n")[0]);
    assert.equal(line.urchinUnavailable, true);
    assert.equal(coralCalls.length, coralBefore); // observable: zero Coral traffic without KV
  } finally {
    await w2.stop();
  }
});

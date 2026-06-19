#!/usr/bin/env node
/**
 * Baseline probes from THIS machine (not Worker egress).
 * Compare results to Worker probe — same PASS/BLOCKED criteria.
 */

import { execSync } from "node:child_process";

const PLAYER = process.env.PLAYER || "beepor";
const URL = `https://hypixel.net/player/${encodeURIComponent(PLAYER)}`;

const PROBES = [
  { label: "browser", args: ["-A", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/124.0.0.0 Safari/537.36"] },
  { label: "curl-default", args: [] },
  { label: "undici", args: ["-A", "undici"] },
  { label: "python-requests", args: ["-A", "python-requests/2.31.0"] },
];

function probe(label, extraArgs) {
  const hdr = execSync(
    `curl -sS ${extraArgs.map((a) => JSON.stringify(a)).join(" ")} -D - -o /tmp/bedwarsqol_probe_body.html -w "%{http_code}" ${JSON.stringify(URL)}`,
    { encoding: "utf8", maxBuffer: 10 * 1024 * 1024 }
  );
  const lines = hdr.trimEnd().split("\n");
  const code = lines[lines.length - 1];
  const headers = lines.slice(0, -1).join("\n");
  const body = execSync("wc -c < /tmp/bedwarsqol_probe_body.html", { encoding: "utf8" }).trim();
  const bytes = Number(body);
  const mitigated = (headers.match(/^cf-mitigated:\s*(.+)$/im) || [])[1]?.trim() || null;
  const title = execSync(
    `grep -o '<title>[^<]*' /tmp/bedwarsqol_probe_body.html | head -1 | sed 's/<title>//'`,
    { encoding: "utf8" }
  ).trim();
  const hasBedwars = execSync(`grep -c stats-content-bedwars /tmp/bedwarsqol_probe_body.html || true`, {
    encoding: "utf8",
  }).trim();
  const blocked =
    code === "403" ||
    (bytes < 50_000 && /attention required|just a moment|challenge-platform/i.test(title));

  let verdict = "FAIL";
  if (code === "200" && Number(hasBedwars) > 0) verdict = "PASS";
  else if (blocked) verdict = "BLOCKED";

  return { label, httpStatus: Number(code), bodyBytes: bytes, title, hasBedwars: Number(hasBedwars) > 0, verdict };
}

console.log(`Local baseline probes -> ${URL}\n`);
console.log("label              status  bytes     verdict   title");
console.log("-".repeat(72));
for (const p of PROBES) {
  const r = probe(p.label, p.args);
  console.log(
    `${r.label.padEnd(18)} ${String(r.httpStatus).padEnd(6)} ${String(r.bodyBytes).padEnd(9)} ${r.verdict.padEnd(9)} ${(r.title || "").slice(0, 40)}`
  );
}
console.log("\nRun Worker test after deploy: WORKER_URL=https://<subdomain>.workers.dev npm run test:worker");

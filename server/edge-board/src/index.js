// Shares the edges Freeport users measured, per operator.
//
// The operator is the caller's ASN, which Cloudflare reports for every
// request; the app reaches this worker directly (it stays outside its own
// VPN), so the ASN is the real ISP, Wi-Fi included. The app's own hint (the
// MCC+MNC on mobile data) is only used when no ASN is known.

const PROTOCOLS = new Set(["masque-h2", "masque-h3", "wireguard"]);
const FAMILIES = new Set(["4", "6", "dual"]);
const CLOUDFLARE_ASNS = new Set([13335, 209242]);
const MAX_AGE_MS = 3 * 24 * 60 * 60 * 1000;
// Enough for a big pool: the app tries the best few and its scout measures the rest.
const MAX_EDGES = 40;
// Ranked by roughly how long a reel takes to start: the time a new connection
// took to its first byte (rtt), one megabyte down and a quarter megabyte up at
// the measured rates (an unmeasured upload counts as half a second).
// How much a new measurement moves an edge's score.
const WEIGHT = 0.4;

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    try {
      if (request.method === "GET" && url.pathname === "/v1/edges") return await list(request, env, url);
      if (request.method === "POST" && url.pathname === "/v1/report") return await report(request, env);
      if (request.method === "GET" && url.pathname === "/v1/release") return await release(ctx);
      if (request.method === "GET" && url.pathname === "/v1/configs") return await listConfigs(env, ctx);
      if (request.method === "POST" && url.pathname === "/v1/configs") return await reportConfigs(request, env, ctx);
      return json({ error: "not found" }, 404);
    } catch (error) {
      return json({ error: "server error" }, 500);
    }
  },
};

async function list(request, env, url) {
  const proto = url.searchParams.get("p") || "";
  const fam = url.searchParams.get("ip") || "";
  if (!PROTOCOLS.has(proto) || !FAMILIES.has(fam)) return json({ error: "bad query" }, 400);
  const net = networkOf(request, url.searchParams.get("net"));
  // One pool for every operator: an edge measured fast anywhere is offered
  // to everyone, at its best measurement. Reports still keep the operator,
  // so the pool can be split again later without losing data.
  const { results } = await env.DB.prepare(
    `SELECT addr, port, rtt, kbps, up, n, MIN(score) AS score FROM (
       SELECT addr, port, rtt, kbps, up, n,
              rtt + 976562.0 / MAX(kbps, 1) + (CASE WHEN up > 0 THEN 244140.0 / up ELSE 500 END) AS score
       FROM edges WHERE proto = ? AND fam = ? AND updated > ?)
     GROUP BY addr, port
     ORDER BY score ASC
     LIMIT ?`,
  ).bind(proto, fam, Date.now() - MAX_AGE_MS, MAX_EDGES).all();
  return json({ net, edges: results }, 200, { "Cache-Control": "public, max-age=60" });
}

async function report(request, env) {
  const body = await request.json().catch(() => null);
  if (!body || typeof body !== "object") return json({ error: "bad body" }, 400);
  const proto = String(body.p || "");
  const fam = String(body.ip || "");
  const addr = String(body.addr || "");
  const port = Number(body.port);
  const rtt = clamp(Number(body.rtt), 0, 10000);
  const kbps = clamp(Number(body.kbps), 0, 200000);
  const up = clamp(Number(body.up || 0), 0, 200000);
  if (!PROTOCOLS.has(proto) || !FAMILIES.has(fam)) return json({ error: "bad protocol" }, 400);
  if (!Number.isInteger(port) || port < 1 || port > 65535) return json({ error: "bad port" }, 400);
  if (!isCloudflareEdge(addr)) return json({ error: "not a cloudflare edge" }, 400);
  if (Number.isNaN(rtt) || Number.isNaN(kbps) || Number.isNaN(up)) return json({ error: "bad numbers" }, 400);

  const net = networkOf(request, body.net);
  const now = Date.now();
  await env.DB.prepare(
    `INSERT INTO edges (net, proto, fam, addr, port, rtt, kbps, up, n, updated)
     VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?10, 1, ?8)
     ON CONFLICT (net, proto, fam, addr, port) DO UPDATE SET
       kbps = CAST(kbps * (1 - ?9) + excluded.kbps * ?9 AS INTEGER),
       rtt = CAST(rtt * (1 - ?9) + excluded.rtt * ?9 AS INTEGER),
       up = CASE WHEN excluded.up = 0 THEN up WHEN up = 0 THEN excluded.up
                 ELSE CAST(up * (1 - ?9) + excluded.up * ?9 AS INTEGER) END,
       n = n + 1,
       updated = excluded.updated`,
  ).bind(net, proto, fam, addr, port, Math.round(rtt), Math.round(kbps), now, WEIGHT, Math.round(up)).run();
  // Keep the table small: forget what nobody has confirmed lately.
  if (Math.random() < 0.02) {
    await env.DB.prepare("DELETE FROM edges WHERE updated < ?").bind(now - MAX_AGE_MS).run();
  }
  return new Response(null, { status: 204 });
}

// Mobile data is named by the app (MCC+MNC), which is exact and the same
// whichever way the request arrives. Anything else is named by the caller's
// ASN — unless the request came through the tunnel, where the ASN is
// Cloudflare's own and says nothing about the operator.
// The latest release, as GitHub's API returns it. The app asks through the
// tunnel, where it shares a Cloudflare address with countless others and
// GitHub's per-address limit answers 403; from here it is one cached call.
const RELEASE_URL = "https://api.github.com/repos/mhammaddawudi/freeport/releases/latest";
const RELEASE_TTL_S = 600;

async function release(ctx) {
  const cache = caches.default;
  const key = new Request(RELEASE_URL);
  const cached = await cache.match(key);
  if (cached) return cached;
  const upstream = await fetch(RELEASE_URL, {
    headers: { "User-Agent": "freeport-edge-board", Accept: "application/vnd.github+json" },
  });
  if (!upstream.ok) return json({ error: `github answered ${upstream.status}` }, 502);
  const body = await upstream.text();
  const response = new Response(body, {
    headers: { "Content-Type": "application/json", "Cache-Control": `public, max-age=${RELEASE_TTL_S}` },
  });
  ctx.waitUntil(cache.put(key, response.clone()));
  return response;
}

function networkOf(request, hint) {
  const clean = String(hint || "").replace(/[^a-z0-9]/gi, "").slice(0, 8);
  if (/^m\d{5,6}$/.test(clean)) return clean;
  const asn = request.cf && request.cf.asn;
  if (asn && !CLOUDFLARE_ASNS.has(asn)) return `as${asn}`;
  return clean ? `h${clean}` : "unknown";
}

// 162.159.0.0/16, 188.114.96.0/22 and 2606:4700::/32 — where WARP and MASQUE live.
function isCloudflareEdge(addr) {
  const v4 = addr.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  if (v4) {
    const b = v4.slice(1).map(Number);
    if (b.some((x) => x > 255)) return false;
    return (b[0] === 162 && b[1] === 159) || (b[0] === 188 && b[1] === 114 && b[2] >= 96 && b[2] <= 99);
  }
  return /^2606:4700:[0-9a-f:]*$/i.test(addr) && addr.length <= 39;
}

function clamp(value, low, high) {
  return Math.min(high, Math.max(low, value));
}

function json(data, status, headers = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json", ...headers },
  });
}

// ---- the shared pool of public V2Ray configs -------------------------------
//
// Every phone tests configs on its own line and reports what answered and
// what did not; every phone starts from what everyone found. Only configs
// from the public collections are taken (their hashes, published daily by
// the census workflow), so nobody can slip a server of their own in.

const CENSUS_BASE = "https://raw.githubusercontent.com/mhammaddawudi/freeport/configs";
const CENSUS_TTL_S = 1800;
const CONFIG_MAX_AGE_MS = 2 * 24 * 60 * 60 * 1000;
const MAX_POOLED = 400;
const MAX_REPORTED = 80;
const SCHEMES = /^(vless|vmess|trojan|ss):\/\//;

async function listConfigs(env, ctx) {
  const { results } = await env.DB.prepare(
    `SELECT link FROM configs
     WHERE updated > ? AND ok > fail
     ORDER BY kbps DESC, delay ASC
     LIMIT ?`,
  ).bind(Date.now() - CONFIG_MAX_AGE_MS, MAX_POOLED).all();
  const pooled = results.map((row) => row.link);
  // The census's own list follows what people found, for networks nobody reported from yet.
  const census = (await cachedText(`${CENSUS_BASE}/alive.txt`, ctx)) || "";
  const seen = new Set(pooled);
  const lines = pooled.concat(census.split("\n").map((l) => l.trim()).filter((l) => l && !seen.has(l)));
  return new Response(lines.join("\n") + "\n", {
    headers: { "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "public, max-age=300" },
  });
}

async function reportConfigs(request, env, ctx) {
  const body = await request.json().catch(() => null);
  const results = body && Array.isArray(body.results) ? body.results.slice(0, MAX_REPORTED) : null;
  if (!results) return json({ error: "bad body" }, 400);
  const known = await knownHashes(ctx);
  if (!known) return json({ error: "census unavailable" }, 503);
  const now = Date.now();
  const statements = [];
  for (const item of results) {
    const link = String(item && item.link || "").trim();
    if (link.length > 4096 || !SCHEMES.test(link)) continue;
    const key = await hash(link);
    if (!known.has(key)) continue;
    const ok = item.ok === true;
    const delay = clamp(Number(item.delay) || 0, 0, 20000);
    const kbps = clamp(Number(item.kbps) || 0, 0, 200000);
    statements.push(env.DB.prepare(
      `INSERT INTO configs (key, link, delay, kbps, ok, fail, updated)
       VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
       ON CONFLICT (key) DO UPDATE SET
         delay = CASE WHEN ?5 = 1 THEN CAST(delay * 0.6 + ?3 * 0.4 AS INTEGER) ELSE delay END,
         kbps = CASE WHEN ?5 = 1 AND ?4 > 0 THEN CAST(kbps * 0.6 + ?4 * 0.4 AS INTEGER) ELSE kbps END,
         ok = ok + ?5, fail = fail + ?6, updated = ?7`,
    ).bind(key, link, Math.round(delay), Math.round(kbps), ok ? 1 : 0, ok ? 0 : 1, now));
  }
  if (statements.length) await env.DB.batch(statements);
  if (Math.random() < 0.02) {
    await env.DB.prepare("DELETE FROM configs WHERE updated < ?").bind(now - CONFIG_MAX_AGE_MS).run();
  }
  return new Response(null, { status: 204 });
}

async function knownHashes(ctx) {
  const text = await cachedText(`${CENSUS_BASE}/known.txt`, ctx);
  return text ? new Set(text.split("\n").map((l) => l.trim()).filter(Boolean)) : null;
}

async function cachedText(url, ctx) {
  const cache = caches.default;
  const key = new Request(url);
  const cached = await cache.match(key);
  if (cached) return cached.text();
  const upstream = await fetch(url, { headers: { "User-Agent": "freeport-edge-board" } });
  if (!upstream.ok) return null;
  const text = await upstream.text();
  ctx.waitUntil(cache.put(key, new Response(text, { headers: { "Cache-Control": `public, max-age=${CENSUS_TTL_S}` } })));
  return text;
}

// The first 16 hex digits of the link's SHA-256, as the census writes them.
async function hash(text) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest).slice(0, 8)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

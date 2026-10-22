"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const vm = require("node:vm");
const fs = require("node:fs");
const source = fs.readFileSync(require.resolve("./server.js"), "utf8");
// Exercise the authenticated DNS proxy without starting the NAS or opening its store.
const start = source.indexOf('    if ((url.pathname === "/api/tv/dns"');
const end = source.indexOf('    if (request.method === "GET" && url.pathname === "/api/tv/providers")', start);
assert.ok(start >= 0 && end > start);
const route = source.slice(start, end);
const payloadStart = source.indexOf("function providerPayload(input)");
const payloadEnd = source.indexOf("function playerProviderRecords()", payloadStart);
const payloadContext = vm.createContext({ isIP: require("node:net").isIP });
vm.runInContext(source.slice(payloadStart, payloadEnd), payloadContext);
const payload = body => JSON.parse(JSON.stringify(payloadContext.providerPayload(body)));
const m3u = { type: "m3u", name: "Fixture", m3uUrl: "http://example.test/list.m3u" };

test("Each playlist import retains its own DNS override without changing the TV default", () => {
  const cloudflare = payload({ ...m3u, dns: { mode: "Cloudflare", primary: "1.1.1.1", secondary: "1.0.0.1" } });
  const google = payload({ ...m3u, dns: { mode: "google", primary: "8.8.8.8", secondary: "8.8.4.4" } });
  assert.equal(cloudflare.dns.mode, "cloudflare");
  assert.equal(google.dns.mode, "google");
  assert.notDeepEqual(cloudflare.dns, google.dns);
});

test("No playlist override follows the TV while explicit automatic remains distinct", () => {
  assert.equal(payload({ ...m3u, dns: null, clearDns: true }).clearDns, true);
  assert.equal(payload(m3u).dns, undefined);
  assert.equal(payload({ ...m3u, dns: { mode: "automatic" } }).dns.mode, "automatic");
});

test("Custom playlist DNS accepts IPv4 and IPv6 and rejects hostnames or malformed payloads", () => {
  const dns = { mode: "custom", primary: " 192.0.2.53 ", secondary: "2001:db8::53" };
  assert.deepEqual(payload({ ...m3u, dns }).dns, { ...dns, primary: "192.0.2.53" });
  for (const value of ["bad", [], {}, { mode: "custom" }, { mode: "custom", primary: "dns.example.test" },
    { mode: "custom", primary: "192.0.2.53", secondary: "999.1.1.1" }]) {
    assert.throws(() => payload({ ...m3u, dns: value }), /DNS/);
  }
});

test("Playlist DNS is forwarded for M3U, Xtream and Stalker without removing provider fields", () => {
  for (const body of [m3u,
    { type: "xtream", name: "Fixture", serverUrl: "http://example.test", username: "fixture-user" },
    { type: "stalker", name: "Fixture", portalUrl: "http://example.test/c/", macAddress: "00:1A:79:00:00:01" }]) {
    const result = payload({ ...body, dns: { mode: "quad9" } });
    assert.equal(result.dns.mode, "quad9");
    for (const [key, value] of Object.entries(body)) assert.equal(result[key], value);
  }
});

function harness(options = {}) {
  const calls = { auth: 0, reads: 0, tv: [] };
  const target = { tv: "192.0.2.163", port: 8093 };
  async function request(path, method = "GET", payload) {
    const context = vm.createContext({
      url: new URL(path, "http://fixture"), request: { method }, target, response: {},
      syncAccountFromRequest: () => { calls.auth++; if (options.unauthorized) throw new Error("Authentification requise."); },
      readJson: async () => { calls.reads++; return payload; },
      requestTv: async (...args) => {
        calls.tv.push(args);
        if (options.offline) throw new Error("Jojo Home ne répond pas.");
        return options.reply || { statusCode: 200, body: { config: { mode: "automatic" }, presets: [] } };
      },
      json: (_, status, body) => ({ status, body })
    });
    const result = await vm.runInContext("(async () => {" + route + "})()", context);
    return result && JSON.parse(JSON.stringify(result));
  }
  return { calls, target, request };
}

test("DNS reads go to the selected TV and require the Companion account", async () => {
  const h = harness();
  const result = await h.request("/api/tv/dns?tv=192.0.2.163&port=8093");
  assert.equal(result.status, 200);
  assert.equal(h.calls.auth, 1);
  assert.equal(h.calls.reads, 0);
  assert.deepEqual(h.calls.tv, [[h.target, "GET", "/api/dns", undefined]]);
});

test("Custom DNS and automatic reset preserve their payload and target", async () => {
  const h = harness();
  const custom = { mode: "custom", primary: "192.0.2.53", secondary: "2001:db8::53" };
  await h.request("/api/tv/dns", "POST", custom);
  await h.request("/api/tv/dns", "POST", { mode: "automatic" });
  assert.deepEqual(h.calls.tv, [[h.target, "POST", "/api/dns", custom], [h.target, "POST", "/api/dns", { mode: "automatic" }]]);
  assert.equal(h.calls.auth, 2);
});

test("DNS tests use the diagnostic endpoint and never the save endpoint", async () => {
  const reply = { statusCode: 200, body: { host: "example.test", addresses: ["192.0.2.42"], elapsedMs: 8 } };
  const h = harness({ reply });
  const body = { config: { mode: "cloudflare" }, host: "example.test" };
  assert.deepEqual(await h.request("/api/tv/dns/test", "POST", body), { status: 200, body: reply.body });
  assert.deepEqual(h.calls.tv, [[h.target, "POST", "/api/dns/test", body]]);
});

test("Unauthenticated reads, saves and tests cannot reach the TV", async () => {
  const h = harness({ unauthorized: true });
  for (const [path, method] of [["/api/tv/dns", "GET"], ["/api/tv/dns", "POST"], ["/api/tv/dns/test", "POST"]]) {
    await assert.rejects(h.request(path, method, { mode: "google" }), /Authentification requise/);
  }
  assert.equal(h.calls.tv.length, 0);
  assert.equal(h.calls.reads, 0);
});

test("Older TV versions give an actionable message without echoing their response", async () => {
  const h = harness({ reply: { statusCode: 404, body: { message: "private-host" } } });
  const result = await h.request("/api/tv/dns");
  assert.equal(result.status, 404);
  assert.match(result.body.error, /version de Jojo Home/);
  assert.doesNotMatch(JSON.stringify(result), /private-host/);
});

test("DNS validation errors retain the TV's HTTP status", async () => {
  const reply = { statusCode: 400, body: { error: "Saisis une adresse IP DNS valide." } };
  const h = harness({ reply });
  assert.deepEqual(await h.request("/api/tv/dns", "POST", { mode: "custom" }), { status: 400, body: reply.body });
});

test("Offline TV failures propagate to the existing Companion error handler", async () => {
  const h = harness({ offline: true });
  await assert.rejects(h.request("/api/tv/dns"), /Jojo Home ne répond pas/);
});

test("Unsupported DNS methods are not forwarded", async () => {
  const h = harness();
  for (const [path, method] of [["/api/tv/dns", "DELETE"], ["/api/tv/dns/test", "GET"], ["/api/tv/dns/test", "DELETE"]]) {
    assert.equal(await h.request(path, method), undefined);
  }
  assert.equal(h.calls.auth, 0);
  assert.equal(h.calls.tv.length, 0);
});

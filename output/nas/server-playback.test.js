"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const vm = require("node:vm");
const fs = require("node:fs");
const crypto = require("node:crypto");
const { EventEmitter } = require("node:events");
const { Readable } = require("node:stream");
const source = fs.readFileSync(require.resolve("./server.js"), "utf8");
// Exercise the real playback functions without starting the NAS or opening its store.
const playbackSource = source.slice(source.indexOf("async function companionPlaybackStream("),
  source.indexOf("async function companionFavoriteImage("));

const videoRouteStart = source.indexOf('    if (["/api/tv/videos",');
const videoRouteEnd = source.indexOf('    if ((url.pathname === "/api/tv/dns"', videoRouteStart);
assert.ok(videoRouteStart >= 0 && videoRouteEnd > videoRouteStart);
function videoProxyHarness({ authenticated = true, body = {}, result = { statusCode: 200, body: { items: [] } } } = {}) {
  const calls = [];
  const context = vm.createContext({ URLSearchParams, Buffer, encodeURIComponent,
    syncAccountFromRequest: () => { if (!authenticated) throw Object.assign(new Error("Connexion requise."), { statusCode: 401 }); },
    syncError: (message, statusCode = 400) => Object.assign(new Error(message), { statusCode }),
    readJson: async () => body,
    requestTv: async (...args) => { calls.push(args); return result; },
    companionFavoriteImage: async () => ({ type: "image/jpeg", data: Buffer.from("fixture-image") }),
    json: (_, status, value) => ({ status, value })
  });
  const execute = vm.runInContext('(async function(request, response, url, target) {' + source.slice(videoRouteStart, videoRouteEnd) + '})', context);
  return { calls, run: (path, method = "GET") => execute({ method }, {}, new URL("http://fixture.test" + path), { tv: "192.0.2.163", port: 8093 }) };
}

test("Videos reads forward only folder/page handles to the selected TV", async () => {
  const h = videoProxyHarness();
  const response = await h.run("/api/tv/videos?folder=00000000-0000-4000-8000-000000000001&page=2&path=smb://private-host");
  assert.equal(response.status, 200);
  assert.equal(h.calls[0][0].tv, "192.0.2.163");
  assert.equal(h.calls[0][2], "/api/videos?folder=00000000-0000-4000-8000-000000000001&page=2");
  assert.equal(h.calls[0][4], 60000);
});
test("An unauthenticated Videos deletion never reaches the TV", async () => {
  const h = videoProxyHarness({ authenticated: false });
  await assert.rejects(h.run("/api/tv/videos/delete", "POST"), error => error.statusCode === 401);
  assert.equal(h.calls.length, 0);
});
test("Videos deletion rejects missing confirmation and arbitrary paths", async () => {
  for (const body of [{ id: "00000000-0000-4000-8000-000000000001" }, { id: "smb://fixture/videos/movie.mkv", confirm: true }, { id: "../videos", confirm: true }]) {
    const h = videoProxyHarness({ body });
    await assert.rejects(h.run("/api/tv/videos/delete", "POST"), /Confirme/); assert.equal(h.calls.length, 0);
  }
});
test("Confirmed Videos deletion keeps the asynchronous operation handle", async () => {
  const body = { id: "00000000-0000-4000-8000-000000000001", confirm: true };
  const h = videoProxyHarness({ body, result: { statusCode: 202, body: { status: "importing", id: "operation" } } });
  const response = await h.run("/api/tv/videos/delete", "POST");
  assert.equal(response.status, 202); assert.equal(response.value.status, "importing"); assert.deepEqual(h.calls[0][3], body);
});
test("Deletion status reads cannot submit a new delete or an arbitrary TV route", async () => {
  const h = videoProxyHarness();
  await assert.rejects(h.run("/api/tv/videos/status?id=..%2Fproviders"), /invalide/); assert.equal(h.calls.length, 0);
  await h.run("/api/tv/videos/status?id=00000000-0000-4000-8000-000000000001");
  assert.equal(h.calls[0][1], "GET"); assert.equal(h.calls[0][2], "/api/status/00000000-0000-4000-8000-000000000001");
});
test("Videos artwork proxies the selected image without publishing its upstream URL", async () => {
  const h = videoProxyHarness({ result: { statusCode: 200, body: { url: "https://image.test/private-image.jpg" } } });
  const response = await h.run("/api/tv/videos/artwork?id=00000000-0000-4000-8000-000000000001");
  assert.match(response.value.image, /^data:image\/jpeg;base64,/); assert.equal(JSON.stringify(response).includes("image.test"), false);
});
test("Older TVs report the missing Videos feature without leaking their raw response", async () => {
  const h = videoProxyHarness({ result: { statusCode: 404, body: { message: "private-path" } } });
  const response = await h.run("/api/tv/videos");
  assert.equal(response.status, 404); assert.match(response.value.error, /version de Jojo Home/); assert.equal(response.value.error.includes("private-path"), false);
});
test("Unsupported Videos methods never reach the TV", async () => {
  for (const [path, method] of [["/api/tv/videos", "POST"], ["/api/tv/videos/artwork", "DELETE"], ["/api/tv/videos/status", "DELETE"], ["/api/tv/videos/delete", "GET"]]) {
    const h = videoProxyHarness(); assert.equal((await h.run(path, method)).status, 405); assert.equal(h.calls.length, 0);
  }
});

function favoriteRemovalHarness(records, accountId = "primary") {
  const secret = { accountId: "owner", profileId: 1, key: "jojo-home-iptv", value: { schema: 1, records } };
  let writes = 0;
  const context = vm.createContext({ Date, Map, Set, Object,
    store: { sync: { profileSecrets: [secret] } },
    syncError: (message, statusCode = 400) => Object.assign(new Error(message), { statusCode }),
    syncProfileSecretSnapshot: () => secret,
    decryptSyncSecret: value => value, encryptSyncSecret: value => value,
    companionHomeAccountFavorites: async () => ({ account: { id: accountId }, items: [{ item: { id: "movie" } }] }),
    persistStore: async () => { writes++; }
  });
  vm.runInContext(source.slice(source.indexOf("function mergeHomeIptvSnapshot("), source.indexOf("// Free TV/OQEE credentials")), context);
  vm.runInContext(source.slice(source.indexOf("function companionHomeAccountId("), source.indexOf("function companionAccountOrnaments(")), context);
  vm.runInContext(source.slice(source.indexOf("async function removeCompanionHomeFavorite("), source.indexOf("async function changeCompanionHomeAccount(")), context);
  return { context, writes: () => writes, remove: (body = { confirm: true }, id = "movie") => context.removeCompanionHomeFavorite("owner", accountId, id, body), records: () => context.store.sync.profileSecrets[0].value.records };
}
function favoriteRecords() {
  const record = (value, changedAt = 10) => ({ value, changedAt, writer: "fixture" });
  return { legacyAccount: record("primary"), "profile:primary": record('{"name":"Primary"}'), "profile:secondary": record('{"name":"Secondary"}'),
    "account:primary:favorite:movie": record("primary-movie"), "favorite:movie": record("primary-movie"),
    "account:secondary:favorite:movie": record("secondary-movie", 11), "account:primary:favorite:other": record("other"), "source:provider": record("private-provider") };
}
test("Removing a primary favorite clears its mirror and preserves the other account and provider", async () => {
  const h = favoriteRemovalHarness(favoriteRecords()); await h.remove(); const records = h.records();
  assert.equal(records["account:primary:favorite:movie"].value, null); assert.equal(records["favorite:movie"].value, null);
  assert.equal(records["account:secondary:favorite:movie"].value, "secondary-movie"); assert.equal(records["source:provider"].value, "private-provider");
  assert.equal(records["account:primary:favorite:other"].value, "other"); assert.equal(h.writes(), 1);
});
test("Removing a secondary favorite restores a legacy mirror instead of deleting the primary favorite", async () => {
  const records = favoriteRecords(); records["favorite:movie"] = { ...records["account:secondary:favorite:movie"] };
  const h = favoriteRemovalHarness(records, "secondary"); await h.remove();
  assert.equal(h.records()["account:secondary:favorite:movie"].value, null); assert.equal(h.records()["favorite:movie"].value, "primary-movie");
  assert.equal(h.records()["account:primary:favorite:movie"].value, "primary-movie");
});
test("A removal also tombstones aliased copies so they cannot resurrect the favorite", async () => {
  const records = favoriteRecords(); records["accountAlias:old"] = { changedAt: 10, writer: "fixture", value: "primary" };
  records["account:old:favorite:movie"] = { changedAt: 20, writer: "fixture", value: "old-movie" };
  const h = favoriteRemovalHarness(records); await h.remove();
  assert.equal(h.records()["account:old:favorite:movie"].value, null); assert.ok(h.records()["account:primary:favorite:movie"].changedAt > 20);
});
test("Missing confirmation, absent favorites and removed accounts cannot change stored data", async () => {
  const h = favoriteRemovalHarness(favoriteRecords());
  await assert.rejects(h.remove({}), /Confirme/); await assert.rejects(h.remove({ confirm: true }, "not-owned"), /introuvable/);
  assert.equal(h.writes(), 0);
  const records = favoriteRecords(); records["profile:primary"].value = null;
  const removed = favoriteRemovalHarness(records); await assert.rejects(removed.remove(), /n’existe plus/); assert.equal(removed.writes(), 0);
  const unavailable = favoriteRemovalHarness(favoriteRecords()); unavailable.context.syncProfileSecretSnapshot = () => null;
  await assert.rejects(unavailable.remove(), /n’est plus disponible/); assert.equal(unavailable.writes(), 0);
});

function videoClientHarness({ deletion = async () => ({ id: "operation" }), status = async () => ({ status: "success", message: "Suppression confirmée." }) } = {}) {
  const elements = new Map(), calls = [], video = { id: "00000000-0000-4000-8000-000000000001", name: "Vidéo de test", directory: false, size: 100 };
  const element = id => {
    if (!elements.has(id)) elements.set(id, { value: "", hidden: false, disabled: false, textContent: "", innerHTML: "",
      querySelectorAll: () => [], addEventListener(name, action) { this[name] = action; }, focus() {},
      showModal() { queueMicrotask(() => this.close("delete")); }, close(value) { this.returnValue = value; this.closeEvent?.(); },
    });
    return elements.get(id);
  };
  element("tvIp").value = "192.0.2.163"; element("tvPort").value = "8093";
  element("contentDeleteDialog").addEventListener = (name, action) => { element("contentDeleteDialog").closeEvent = action; };
  const navigation = element("navigation"), context = vm.createContext({ URLSearchParams, AbortController, setTimeout, queueMicrotask,
    fmt: String, window: {}, document: { getElementById: element, querySelector: () => navigation },
    secure: async (url, options = {}) => {
      calls.push({ path: url.split("?")[0], method: options.method || "GET" });
      if (url.includes("/videos/status")) return status();
      if (url.includes("/videos/delete")) return deletion();
      if (url.includes("/videos/artwork")) return {};
      return { items: [video], total: 1, nextPage: null };
    }
  });
  vm.runInContext(source.slice(source.indexOf("function companionVideosClient("), source.indexOf("function companionAvatarDashboard(")) + "companionVideosClient();", context);
  const button = { dataset: { videoDelete: video.id }, disabled: false };
  return { calls, element, ready: async () => { navigation.click(); await new Promise(setImmediate); },
    remove: () => element("companionVideosGrid").onclick({ target: { closest: selector => selector === "[data-video-delete]" ? button : null } }) };
}

test("An expired deletion status reloads the listing without claiming success or polling forever", async () => {
  const h = videoClientHarness({ status: async () => ({ status: "not_found" }) }); await h.ready(); await h.remove();
  assert.match(h.element("companionVideosStatus").textContent, /a expiré/);
  assert.equal(h.element("videosTvIp").disabled, false);
  assert.equal(h.calls.filter(call => call.path.endsWith("/status")).length, 1);
  assert.equal(h.calls.filter(call => call.path === "/api/tv/videos").length, 2);
});

test("A second delete cannot be submitted while the first request is awaiting its operation handle", async () => {
  let finish;
  const h = videoClientHarness({ deletion: () => new Promise(resolve => { finish = resolve; }) }); await h.ready();
  const pending = h.remove(); await new Promise(setImmediate); await h.remove();
  assert.equal(h.calls.filter(call => call.method === "POST").length, 1);
  assert.equal(h.element("videosTvIp").disabled, true);
  finish({ id: "operation" }); await pending;
  assert.equal(h.element("videosTvIp").disabled, false);
});

test("Rechecking a lost deletion status sends only a status read and never repeats the destructive request", async () => {
  let attempts = 0;
  const h = videoClientHarness({ status: async () => { if (++attempts === 1) throw new Error("Connexion interrompue."); return { status: "success", message: "Suppression confirmée." }; } });
  await h.ready(); await h.remove();
  assert.equal(h.element("companionVideosCheck").hidden, false); assert.equal(h.element("videosTvIp").disabled, true);
  await h.element("companionVideosCheck").onclick();
  assert.equal(h.calls.filter(call => call.method === "POST").length, 1);
  assert.equal(attempts, 2); assert.equal(h.element("videosTvIp").disabled, false);
  assert.equal(h.element("companionVideosStatus").textContent, "Suppression confirmée.");
});

function harness(overrides = {}) {
  const calls = { fetch: [], probe: [], encoder: [], removed: [] };
  const context = vm.createContext({ URL, Headers, Buffer, AbortController, AbortSignal, Date, Map, Set,
    setTimeout, clearTimeout, setInterval, clearInterval, crypto, Readable, COMPANION_PLAYBACK_TTL_MS: 3600000,
    companionPlaybackSessions: new Map(), path: require("node:path"), os: require("node:os"),
    syncError: (message, statusCode = 400) => Object.assign(new Error(message), { statusCode }),
    companionPlaybackFetch: async (url, headers) => {
      calls.fetch.push({ url, headers });
      return { url, response: { ok: true, headers: new Headers({ "content-type": "video/mp2t" }),
        body: new ReadableStream({ start(controller) { controller.enqueue(new Uint8Array([0x47])); controller.close(); } }) } };
    },
    execFileAsync: async (command, args) => {
      calls.probe.push({ command, args });
      return { stdout: JSON.stringify({ streams: [
        { index: 0, codec_type: "video", codec_name: "h264", width: 1920, height: 1080 },
        { index: 1, codec_type: "audio", codec_name: "aac", disposition: { default: 1 }, tags: { language: "fra" } }
      ], format: {} }) };
    },
    fsp: { mkdtemp: async () => require("node:path").join(require("node:os").tmpdir(), "jojo-companion-playback-test"),
      readFile: async () => "#EXTM3U\n#EXTINF:2,\nstream0.ts\n", rm: async directory => { calls.removed.push(directory); } },
    spawn: (command, args) => {
      calls.encoder.push({ command, args });
      const child = new EventEmitter();
      child.pid = 1; child.exitCode = null; child.stderr = { resume() {} };
      child.kill = () => { child.exitCode = 0; child.emit("close"); };
      return child;
    }, ...overrides
  });
  vm.runInContext(playbackSource, context);
  return { context, calls, close() { for (const id of context.companionPlaybackSessions.keys()) context.closeCompanionPlayback(id); } };
}

function favorite(url) {
  return { item: { id: "live:test", type: "LIVE", streamUrl: url }, source: { type: "M3U", headers: {} } };
}

test("Freebox RTSP is relayed to HLS automatically using UDP for probe and playback", async () => {
  const h = harness();
  try {
    const url = "rtsp://mafreebox.freebox.fr/fbxtv_pub/stream?namespace=1&service=201";
    const result = await h.context.createCompanionPlayback("owner-1", favorite(url), { port: 8095 });
    assert.equal(result.format, "hls");
    assert.equal(result.live, true);
    assert.equal(result.relay.compatible, false);
    assert.match(result.url, /^\/api\/companion\/playback\/[a-f0-9]{64}\/[a-f0-9]{64}$/);
    assert.equal(result.url.includes("rtsp:"), false);
    assert.equal(h.calls.fetch.length, 0);
    assert.equal(h.calls.probe[0].command, "ffprobe");
    assert.equal(h.calls.probe[0].args.at(-1), url);
    assert.equal(h.calls.probe[0].args[h.calls.probe[0].args.indexOf("-rtsp_transport") + 1], "udp");
    const args = h.calls.encoder[0].args;
    assert.equal(args[args.indexOf("-rtsp_transport") + 1], "udp");
    assert.equal(args.includes("-rw_timeout"), false);
    assert.equal(args[args.indexOf("-i") + 1], url);
    assert.equal(args[args.indexOf("-c:v") + 1], "copy");
    assert.equal(args[args.indexOf("-c:a") + 1], "aac");
    assert.equal(h.context.companionPlaybackSessions.get(result.session).ownerId, "owner-1");
    h.context.closeCompanionPlayback(result.session);
    assert.equal(h.context.companionPlaybackSessions.size, 0);
    assert.equal(h.calls.removed.length, 1);
  } finally { h.close(); }
});

test("Freebox TNT addresses also use the relay without changing the tuner in the stored URL", async () => {
  const h = harness();
  try {
    const url = "rtsp://192.168.1.254/fbxdvb/stream?tsid=6&nid=8442&sid=1537&frontend=2";
    await h.context.createCompanionPlayback("owner-2", favorite(url), { port: 8095, compatible: true });
    const args = h.calls.encoder[0].args;
    assert.equal(args[args.indexOf("-i") + 1], url);
    assert.equal(args[args.indexOf("-c:v") + 1], "libx264");
  } finally { h.close(); }
});

test("HTTP MPEG-TS playback keeps the original proxy path", async () => {
  const h = harness();
  try {
    const result = await h.context.createCompanionPlayback("owner-1", favorite("https://provider.test/live.ts"), { port: 8095 });
    assert.equal(result.format, "mpegts");
    assert.equal(result.relay, undefined);
    assert.equal(h.calls.fetch.length, 1);
    assert.equal(h.calls.probe.length, 0);
    assert.equal(h.calls.encoder.length, 0);
  } finally { h.close(); }
});

test("Freebox track selection stays valid when RTP stream discovery changes the global indexes", async () => {
  const h = harness({ execFileAsync: async () => ({ stdout: JSON.stringify({ streams: [
    { index: 0, codec_type: "subtitle", codec_name: "dvb_teletext" },
    { index: 1, codec_type: "video", codec_name: "h264" },
    { index: 2, codec_type: "audio", codec_name: "aac" },
    { index: 3, codec_type: "audio", codec_name: "aac" }
  ], format: {} }) }) });
  try {
    const result = await h.context.createCompanionPlayback("owner-1", favorite("rtsp://mafreebox.freebox.fr/fbxtv_pub/stream"), { audio: 1 });
    assert.equal(result.media.video.id, 0);
    assert.equal(result.media.audio[1].id, 1);
    const args = h.calls.encoder[0].args;
    assert.deepEqual(Array.from(args).filter((value, index) => args[index - 1] === "-map"), ["0:v:0", "0:a:1"]);
  } finally { h.close(); }
});

test("HTTP provider credentials continue through the proxy instead of subprocess arguments", () => {
  const h = harness();
  const session = { id: "a".repeat(64), url: "https://user:secret@provider.test/movie.mp4", port: 8095, resources: new Map() };
  const input = h.context.companionPlaybackInput(session);
  assert.match(input.url, /^http:\/\/127\.0\.0\.1:8095\/api\/companion\/playback\//);
  assert.equal(input.url.includes("secret"), false);
  assert.equal(input.args.length, 0);
});

test("The direct RTSP path rejects credentials and does not claim generic RTSP support", () => {
  const h = harness();
  assert.equal(h.context.companionFreeboxRtspUrl("rtsp://user:secret@mafreebox.freebox.fr/fbxtv_pub/stream"), false);
  assert.equal(h.context.companionFreeboxRtspUrl("rtsp://camera.test/video"), false);
  assert.equal(h.context.companionFreeboxRtspUrl("rtsps://mafreebox.freebox.fr/fbxtv_pub/stream"), false);
  assert.throws(() => h.context.companionPlaybackInput({ url: "rtsp://user:secret@mafreebox.freebox.fr/fbxtv_pub/stream", resources: new Map() }),
    error => error.statusCode === 422);
});

test("Unavailable Freebox channels return an error and leave no playback session", async () => {
  const h = harness({ execFileAsync: async () => { throw new Error("RTSP 500 private-upstream-details"); } });
  await assert.rejects(h.context.createCompanionPlayback("owner-1", favorite("rtsp://mafreebox.freebox.fr/fbxdvb/stream?frontend=4")),
    error => error.statusCode === 503 && error.message.includes("Freebox") && !error.message.includes("private-upstream"));
  assert.equal(h.context.companionPlaybackSessions.size, 0);
  assert.equal(h.calls.encoder.length, 0);
});

test("Cancelling relay preparation closes the session before starting FFmpeg", async () => {
  const h = harness();
  await assert.rejects(h.context.createCompanionPlayback("owner-1", favorite("rtsp://mafreebox.freebox.fr/fbxtv_pub/stream"), { isCancelled: () => true }),
    error => error.statusCode === 499);
  assert.equal(h.context.companionPlaybackSessions.size, 0);
  assert.equal(h.calls.encoder.length, 0);
});

test("HTTP remux still uses the credential-free local proxy for FFprobe and FFmpeg", async () => {
  const h = harness();
  try {
    const result = await h.context.createCompanionPlayback("owner-1", favorite("https://provider.test/live.ts"), { port: 8095, relay: true });
    assert.equal(result.format, "hls");
    assert.equal(h.calls.fetch.length, 1);
    assert.match(h.calls.probe[0].args.at(-1), /^http:\/\/127\.0\.0\.1:8095\/api\/companion\/playback\//);
    const args = h.calls.encoder[0].args;
    assert.equal(args.includes("-rtsp_transport"), false);
    assert.equal(args[args.indexOf("-rw_timeout") + 1], "15000000");
    assert.match(args[args.indexOf("-i") + 1], /^http:\/\/127\.0\.0\.1:8095\/api\/companion\/playback\//);
  } finally { h.close(); }
});

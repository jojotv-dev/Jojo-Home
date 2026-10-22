"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const vm = require("node:vm");
const fs = require("node:fs");
const { setImmediate: yieldTurn } = require("node:timers/promises");
const source = fs.readFileSync(require.resolve("./server.js"), "utf8");
const NOW = 1_800_000_000_000;

// Run the production queue and sync routes with fictional in-memory storage.
// Never require the server entry point, open a database, or start a listener.
function span(start, end) {
  const from = source.indexOf(start);
  const to = source.indexOf(end, from);
  assert.ok(from >= 0 && to > from, `Missing source span: ${start}`);
  return source.slice(from, to);
}

function definition(name) {
  const from = source.indexOf(`function ${name}(`);
  const to = source.indexOf("\n}", from);
  assert.ok(from >= 0 && to > from, `Missing function: ${name}`);
  return source.slice(from, to + 2);
}

const queueSource = span("let writeQueue = Promise.resolve();", "let syncSqliteStore = null;");
const functions = ["persistStore", "compactSyncTombstones", "appendSyncEvent", "compactSyncEvents",
  "requestDeviceId", "syncValueEqual", "syncError", "syncProfileId", "syncWatchProgressSnapshot",
  "syncWatchProgressEntries", "syncWatchProgressKeys", "syncWatchProgressDeletions",
  "syncFavoriteSnapshot", "syncFavoriteKey", "syncFavoritesPayload", "syncFavoriteKeys",
  "syncSettingsSnapshot", "syncSettingsPayload", "syncProfileBlobSnapshot", "syncProfileBlobKey"].map(definition).join("\n");
const routes = {
  progress: { source: span("    const syncWatchProgressMatch =", "    const syncWatchedItemsMatch ="),
    path: "watch-progress", field: "entries" },
  favorites: { source: span("    const syncFavoritesMatch =", "    const syncSettingsMatch ="),
    path: "iptv-favorites", field: "favorites" },
  settings: { source: span("    const syncSettingsMatch =", "    const syncWatchProgressMatch ="),
    path: "settings", field: "settings" },
  blobs: { source: span("    const syncBlobMatch =", '    if (request.method === "POST" && url.pathname === "/api/heic/preview")'),
    path: "blobs/fixture-settings", field: "value" }
};
const copy = value => JSON.parse(JSON.stringify(value));

function deferred() {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
}

function entry(overrides = {}) {
  return { key: "fixture-series|1|2", contentId: "fixture-series", contentType: "series",
    videoId: "fixture-episode-2", season: 1, episode: 2, position: 60_000, duration: 120_000,
    lastWatched: NOW - 5_000, ...overrides };
}

async function harness({ sqlite = true, domain = "progress", adapter = null } = {}) {
  const route = routes[domain];
  const store = { sync: { favorites: [], settings: [], profileBlobs: [], watchProgress: [], watchedItems: [], events: [], nextRevision: 0 } };
  const files = new Map();
  const operations = [];
  const counts = new Map();
  const faults = new Map();
  const successes = [];
  const clock = { now: NOW };
  const timers = [];
  const sqliteState = { snapshot: null, events: [] };
  const storePath = "/fictional/store.json";
  let gate = null;
  let activeWrites = 0;
  let maximumWrites = 0;

  function step(stage) {
    operations.push(stage);
    const count = (counts.get(stage) || 0) + 1;
    counts.set(stage, count);
    const faultKey = `${stage}:${count}`;
    if (faults.has(faultKey)) {
      const error = faults.get(faultKey);
      faults.delete(faultKey);
      throw error;
    }
  }

  const context = vm.createContext({
    store, STORE_PATH: storePath,
    Date: class extends Date { static now() { return clock.now; } },
    console: { error() {} },
    setTimeout(callback, milliseconds) {
      const timer = { callback, milliseconds, cancelled: false, unref() {} };
      timers.push(timer);
      return timer;
    },
    clearTimeout(timer) { timer.cancelled = true; },
    syncSqliteStore: adapter || (sqlite ? {
      compact() { step("sqlite.compact"); },
      saveSnapshot(sync) { step("sqlite.snapshot"); sqliteState.snapshot = copy(sync); },
      appendEvent(event) { step("sqlite.event"); sqliteState.events.push(copy(event)); }
    } : null),
    fsp: {
      async writeFile(target, value, options) {
        activeWrites++;
        maximumWrites = Math.max(maximumWrites, activeWrites);
        try {
          step("json.write");
          assert.equal(target, storePath + ".tmp");
          assert.equal(options.mode, 0o600);
          const currentGate = gate;
          gate = null;
          if (currentGate) { currentGate.started.resolve(); await currentGate.released.promise; }
          files.set(target, value);
        } finally { activeWrites--; }
      },
      async rename(from, to) {
        step("json.rename");
        assert.equal(from, storePath + ".tmp");
        assert.equal(to, storePath);
        assert.ok(files.has(from));
        files.set(to, files.get(from));
        files.delete(from);
      },
      async chmod(target, mode) {
        step("json.chmod");
        assert.equal(target, storePath);
        assert.equal(mode, 0o600);
      }
    },
    syncAccountFromRequest(request) {
      if (!request.accountId) throw Object.assign(new Error("Fixture authentication required"), { statusCode: 401 });
      return { id: request.accountId };
    },
    readJson: async request => request.body,
    json: (_, status, body) => {
      const result = { status, body: copy(body) };
      successes.push(result);
      return result;
    }
  });
  vm.runInContext(queueSource + functions, context);
  const execute = vm.runInContext("(async function(request, response, url) {" + route.source + "})", context);
  const request = (method, body, { accountId = "fixture-owner", profileId = 1, conditional = false } = {}) => execute({
    method, body, accountId, headers: { "x-jojotv-device-id": "fixture-device" }
  }, {}, new URL(`http://fixture.test/api/sync/profiles/${profileId}/${route.path}${conditional ? "/conditional" : ""}`));

  // Match production startup: the initial store is persisted before serving requests.
  await context.persistStore();
  operations.length = 0;
  counts.clear();
  maximumWrites = 0;
  return {
    store, operations, successes, clock, sqliteState, timers, context,
    async runCheckpointTimer() {
      const timer = timers.find(item => !item.cancelled);
      assert.ok(timer);
      timer.cancelled = true;
      timer.callback();
      await vm.runInContext("writeQueue", context);
    },
    persist: () => context.persistStore(),
    persisted: () => JSON.parse(files.get(storePath)),
    put: (value, options) => request("PUT", { [route.field]: value }, options),
    remove: (keys, options) => request("DELETE", { keys }, options),
    removeConditionally: (deletions, options) => request("DELETE", { deletions }, { ...options, conditional: true }),
    get: options => request("GET", undefined, options),
    writeCount: () => counts.get("json.write") || 0,
    maximumWrites: () => maximumWrites,
    failAt(stage, count, error = new Error(`Fixture failure: ${stage}`)) {
      faults.set(`${stage}:${count}`, error);
      return error;
    },
    failNext(stage, error) { return this.failAt(stage, (counts.get(stage) || 0) + 1, error); },
    holdNextWrite() {
      assert.equal(gate, null);
      gate = { started: deferred(), released: deferred() };
      const currentGate = gate;
      return { started: currentGate.started.promise, release: () => currentGate.released.resolve() };
    },
    clearOperations() { operations.length = 0; counts.clear(); maximumWrites = 0; }
  };
}

function reorderObjectKeys(value) {
  if (Array.isArray(value)) return value.map(reorderObjectKeys);
  if (!value || typeof value !== "object") return value;
  return Object.fromEntries(Object.keys(value).reverse().map(key => [key, reorderObjectKeys(value[key])]));
}

const snapshotRoutes = [
  { domain: "favorites", collection: "favorites", value: version => [{
    providerKey: "fixture-provider", contentType: "SERIES", remoteContentId: "fixture-series",
    sortOrder: version, addedAt: NOW - 10_000, updatedAt: NOW - 5_000 + version
  }] },
  { domain: "settings", collection: "settings", value: version => ({
    order: ["fixture-first", "fixture-second"], options: { layout: "fixture", scale: version }
  }) },
  { domain: "blobs", collection: "profileBlobs", value: version => ({
    allSettings: { layout: { type: "int", value: version, changedAt: NOW - 5_000 + version, writer: "fixture-device" } },
    appOrder: ["fixture-first", "fixture-second"]
  }) }
];

test("JSON equality ignores object property order while retaining array order, types and own properties", () => {
  const equal = vm.runInNewContext("(" + definition("syncValueEqual") + ")");
  const value = { allSettings: { layout: { type: "int", value: 1 } }, appOrder: ["first", "second"] };
  assert.equal(equal(value, reorderObjectKeys(value)), true);
  assert.equal(equal(value, { ...value, appOrder: ["second", "first"] }), false);
  assert.equal(equal({ value: 1 }, { value: "1" }), false);
  assert.equal(equal({ value: null }, { value: {} }), false);
  assert.equal(equal({ value: true }, { value: 1 }), false);
  assert.equal(equal({ value: 1 }, { value: 2 }), false);
  assert.equal(equal({ value: 1 }, { value: 1, extra: null }), false);
  assert.equal(equal(JSON.parse('{"__proto__":{"value":1}}'), {}), false);
});

for (const route of snapshotRoutes) {
  const make = options => harness({ domain: route.domain, ...options });
  const persisted = h => h.persisted().sync[route.collection];

  test(`${route.domain}: identical and reordered snapshots create no extra write or revision`, async () => {
    const h = await make();
    const first = await h.put(route.value(1));
    h.clearOperations();
    for (const value of [route.value(1), reorderObjectKeys(route.value(1))]) {
      const response = await h.put(value);
      assert.equal(response.body.unchanged, true);
      assert.equal(response.body.revision, first.body.revision);
      assert.equal(response.body.updatedAt, first.body.updatedAt);
    }
    assert.deepEqual(h.operations, []);
    assert.equal(h.store.sync.events.length, 1);
    assert.equal(h.sqliteState.events.length, 1);
  });

  test(`${route.domain}: a real value change still persists a new revision`, async () => {
    const h = await make();
    await h.put(route.value(1));
    const before = copy(persisted(h));
    h.clock.now += 1_000;
    const response = await h.put(route.value(2));
    assert.equal(response.body.unchanged, undefined);
    assert.equal(response.body.revision, 2);
    assert.equal(response.body.updatedAt, h.clock.now);
    assert.equal(h.writeCount(), 2);
    assert.notDeepEqual(persisted(h), before);
    assert.deepEqual(copy(h.sqliteState.snapshot[route.collection]), persisted(h));
  });

  for (const stage of ["sqlite.compact", "sqlite.snapshot", "json.write", "json.rename", "json.chmod"]) {
    test(`${route.domain}: an identical retry repairs a ${stage} failure before success`, async () => {
      const h = await make();
      const error = h.failNext(stage);
      await assert.rejects(h.put(route.value(1)), received => received === error);
      assert.equal(h.successes.length, 0);
      const response = await h.put(route.value(1));
      assert.equal(response.body.unchanged, true);
      assert.equal(response.body.revision, 1);
      assert.equal(persisted(h).length, 1);
      assert.deepEqual(copy(h.sqliteState.snapshot[route.collection]), persisted(h));
      assert.equal(h.store.sync.events.length, 1);
      assert.equal(h.sqliteState.events.length, 1);
      assert.equal(h.maximumWrites(), 1);
    });
  }

  test(`${route.domain}: the JSON fallback retries a failed save before acknowledging`, async () => {
    const h = await make({ sqlite: false });
    const error = h.failNext("json.rename");
    await assert.rejects(h.put(route.value(1)), received => received === error);
    assert.equal(persisted(h).length, 0);
    const response = await h.put(route.value(1));
    assert.equal(response.body.revision, 1);
    assert.equal(persisted(h).length, 1);
    assert.equal(h.store.sync.events.length, 1);
  });

  test(`${route.domain}: repeated failed retries cannot acknowledge an unpersisted snapshot`, async () => {
    const h = await make();
    for (const stage of ["sqlite.snapshot", "json.rename"]) {
      const error = h.failNext(stage);
      await assert.rejects(h.put(route.value(1)), received => received === error);
      assert.equal(h.successes.length, 0);
    }
    const response = await h.put(route.value(1));
    assert.equal(response.body.revision, 1);
    assert.equal(persisted(h).length, 1);
    assert.equal(h.store.sync.events.length, 1);
  });

  test(`${route.domain}: unchanged waits for an in-flight save without duplicating it`, async () => {
    const h = await make();
    const gate = h.holdNextWrite();
    const first = h.put(route.value(1));
    await gate.started;
    const unchanged = h.put(reorderObjectKeys(route.value(1)));
    await yieldTurn();
    assert.equal(h.successes.length, 0);
    assert.equal(h.writeCount(), 1);
    const results = Promise.all([first, unchanged]);
    gate.release();
    const responses = await results;
    assert.equal(responses[1].body.unchanged, true);
    assert.equal(h.writeCount(), 1);
    assert.equal(persisted(h).length, 1);
  });

  test(`${route.domain}: an unchanged waiter recovers when the in-flight save fails`, async () => {
    const h = await make();
    const gate = h.holdNextWrite();
    const error = h.failNext("json.rename");
    const first = h.put(route.value(1));
    await gate.started;
    const unchanged = h.put(route.value(1));
    await yieldTurn();
    assert.equal(h.successes.length, 0);
    const results = Promise.allSettled([first, unchanged]);
    gate.release();
    const settled = await results;
    assert.deepEqual(settled.map(item => item.status), ["rejected", "fulfilled"]);
    assert.equal(settled[0].reason, error);
    assert.equal(settled[1].value.body.revision, 1);
    assert.equal(h.writeCount(), 2);
    assert.equal(persisted(h).length, 1);
    assert.equal(h.store.sync.events.length, 1);
  });

  test(`${route.domain}: an older successful save cannot acknowledge a newer failed revision`, async () => {
    const h = await make();
    const gate = h.holdNextWrite();
    const first = h.put(route.value(1));
    await gate.started;
    const error = h.failAt("json.rename", 2);
    const newer = h.put(route.value(2));
    await yieldTurn();
    assert.equal(h.store.sync.nextRevision, 2);
    const retry = h.put(route.value(2));
    const results = Promise.allSettled([first, newer, retry]);
    gate.release();
    const settled = await results;
    assert.deepEqual(settled.map(item => item.status), ["fulfilled", "rejected", "fulfilled"]);
    assert.equal(settled[1].reason, error);
    assert.equal(settled[2].value.body.revision, 2);
    assert.equal(h.writeCount(), 3);
    assert.equal(h.store.sync.events.length, 2);
    assert.deepEqual(copy(h.sqliteState.snapshot[route.collection]), persisted(h));
  });

  test(`${route.domain}: a waiting unchanged reply retains its captured revision and timestamp`, async () => {
    const h = await make();
    const gate = h.holdNextWrite();
    const first = h.put(route.value(1));
    await gate.started;
    const unchanged = h.put(route.value(1));
    await yieldTurn();
    h.clock.now += 1_000;
    const error = h.failAt("json.rename", 2);
    const newer = h.put(route.value(2));
    await yieldTurn();
    const results = Promise.allSettled([first, unchanged, newer]);
    gate.release();
    const settled = await results;
    assert.deepEqual(settled.map(item => item.status), ["fulfilled", "fulfilled", "rejected"]);
    assert.equal(settled[2].reason, error);
    assert.equal(settled[1].value.body.revision, 1);
    assert.equal(settled[1].value.body.updatedAt, NOW);
    assert.equal(h.persisted().sync.nextRevision, 1);
  });

  test(`${route.domain}: persistence recovery preserves other accounts and profiles`, async () => {
    const h = await make();
    await h.put(route.value(2), { profileId: 2 });
    await h.put(route.value(3), { accountId: "fixture-other" });
    const before = copy(persisted(h));
    const error = h.failNext("json.write");
    await assert.rejects(h.put(route.value(1)), received => received === error);
    await h.put(route.value(1));
    assert.deepEqual(persisted(h).slice(0, 2), before);
    assert.equal(persisted(h).length, 3);
  });

  test(`${route.domain}: authentication and profile validation precede the unchanged branch`, async () => {
    const h = await make();
    await h.put(route.value(1));
    h.clearOperations();
    for (const operation of [() => h.put(route.value(1), { accountId: null }),
      () => h.get({ accountId: null })]) {
      await assert.rejects(operation(), error => error.statusCode === 401);
    }
    await assert.rejects(h.put(route.value(1), { profileId: 0 }), error => error.statusCode === 400);
    assert.deepEqual(h.operations, []);
    assert.equal(h.store.sync.nextRevision, 1);
  });
}

for (const stage of ["sqlite.compact", "sqlite.snapshot", "json.write", "json.rename", "json.chmod"]) {
  test(`A ${stage} failure reaches its caller and does not poison the next save`, async () => {
    const h = await harness();
    const error = h.failNext(stage);
    await assert.rejects(h.persist(), received => received === error);
    h.store.sync.nextRevision = 1;
    await h.persist();
    assert.equal(h.persisted().sync.nextRevision, 1);
    assert.equal(h.sqliteState.snapshot.nextRevision, 1);
    assert.equal(h.maximumWrites(), 1);
  });
}

test("Already queued saves remain serial and only the failing attempt rejects", async () => {
  const h = await harness();
  const gate = h.holdNextWrite();
  const error = h.failNext("json.rename");
  const first = h.persist();
  await gate.started;
  const results = Promise.allSettled([first, h.persist(), h.persist()]);
  assert.equal(h.writeCount(), 1);
  gate.release();
  const settled = await results;
  assert.deepEqual(settled.map(item => item.status), ["rejected", "fulfilled", "fulfilled"]);
  assert.equal(settled[0].reason, error);
  assert.equal(h.writeCount(), 3);
  assert.equal(h.maximumWrites(), 1);
});

test("A delayed conditional delete preserves a newer stopped checkpoint, including a rewind", async () => {
  const h = await harness();
  const stopped = entry({ position: 15_000, lastWatched: NOW - 1_000 });
  await h.put([stopped]);
  const revision = h.store.sync.nextRevision;
  h.clock.now = NOW + 120_000;
  const result = await h.removeConditionally([{ key: stopped.key, deletedAt: NOW - 2_000 }]);
  assert.equal(result.body.preserved, 1);
  assert.equal(result.body.deleted, 0);
  assert.equal(h.store.sync.nextRevision, revision);
  assert.equal((await h.get()).body.entries[0].position, 15_000);
  assert.deepEqual((await h.get()).body.tombstones, []);
});

test("Conditional batches delete older entries, retain newer ones, and preserve the original action date", async () => {
  const h = await harness();
  const old = entry();
  const newer = entry({ key: "newer", lastWatched: NOW - 1_000 });
  await h.put([old, newer]);
  h.clock.now = NOW + 120_000;
  const deletedAt = NOW - 2_000;
  const result = await h.removeConditionally([old, newer].map(item => ({ key: item.key, deletedAt })));
  assert.equal(result.body.deleted, 1);
  assert.equal(result.body.preserved, 1);
  const saved = h.persisted().sync.watchProgress[0];
  assert.deepEqual(saved.entries.map(item => item.key), [newer.key]);
  assert.deepEqual(saved.tombstones, [{ key: old.key, deletedAt }]);
  assert.deepEqual(copy(h.store.sync.events.at(-1).keys), [old.key]);
});

test("Conditional retries never refresh or regress an existing deletion timestamp", async () => {
  const h = await harness();
  const item = entry();
  await h.put([item]);
  const deletedAt = item.lastWatched;
  await h.removeConditionally([{ key: item.key, deletedAt }]);
  const revision = h.store.sync.nextRevision;
  h.clock.now += 2 * 24 * 60 * 60 * 1_000;
  await h.removeConditionally([{ key: item.key, deletedAt }]);
  await h.removeConditionally([{ key: item.key, deletedAt: deletedAt - 1 }]);
  assert.equal(h.store.sync.nextRevision, revision);
  assert.deepEqual((await h.get()).body.tombstones, [{ key: item.key, deletedAt }]);
});

test("A new playback survives when a conditional delete is retried after its first acknowledgement", async () => {
  const h = await harness();
  const old = entry();
  const deletedAt = NOW - 2_000;
  await h.put([old]);
  await h.removeConditionally([{ key: old.key, deletedAt }]);
  const replay = entry({ position: 10_000, lastWatched: NOW - 1_000 });
  await h.put([replay]);
  await h.removeConditionally([{ key: old.key, deletedAt }]);
  assert.equal((await h.get()).body.entries[0].position, replay.position);
  assert.deepEqual((await h.get()).body.tombstones, []);
});

test("A failed conditional persist is repaired before an identical retry is acknowledged", async () => {
  const h = await harness();
  const item = entry();
  await h.put([item]);
  h.failNext("json.write");
  const intent = [{ key: item.key, deletedAt: NOW - 2_000 }];
  await assert.rejects(h.removeConditionally(intent));
  assert.equal(h.persisted().sync.watchProgress[0].entries.length, 1);
  const revision = h.store.sync.nextRevision;
  h.clock.now += 120_000;
  await h.removeConditionally(intent);
  const saved = h.persisted().sync;
  assert.equal(saved.nextRevision, revision);
  assert.equal(saved.watchProgress[0].entries.length, 0);
  assert.equal(saved.watchProgress[0].tombstones[0].deletedAt, intent[0].deletedAt);
});

test("Conditional deletion validation rejects undated, malformed and unsafe timestamps before mutation", async () => {
  const h = await harness();
  const item = entry();
  await h.put([item]);
  const before = h.persisted();
  for (const deletedAt of [undefined, null, 0, -1, "100", 1.5, Infinity, Number.MAX_SAFE_INTEGER + 1]) {
    await assert.rejects(h.removeConditionally([{ key: item.key, deletedAt }]), error => error.statusCode === 400);
  }
  await assert.rejects(h.removeConditionally([{ key: " ", deletedAt: NOW }]), error => error.statusCode === 400);
  assert.deepEqual(h.persisted(), before);
});

test("The guarded route refuses other methods and keeps authentication and account/profile isolation", async () => {
  const h = await harness();
  const item = entry();
  await h.put([item]);
  const intent = [{ key: item.key, deletedAt: NOW }];
  await assert.rejects(h.get({ conditional: true }), error => error.statusCode === 405);
  await assert.rejects(h.removeConditionally(intent, { accountId: null }), error => error.statusCode === 401);
  await h.removeConditionally(intent, { accountId: "other-account" });
  await h.removeConditionally(intent, { profileId: 2 });
  assert.equal((await h.get()).body.entries.length, 1);
  assert.deepEqual((await h.get()).body.tombstones, []);
});

test("The atomic JSON fallback also recovers without SQLite", async () => {
  const h = await harness({ sqlite: false });
  const error = h.failNext("json.rename");
  await assert.rejects(h.persist(), received => received === error);
  await h.persist();
  assert.equal(h.writeCount(), 2);
  assert.equal(h.operations.some(stage => stage.startsWith("sqlite.")), false);
});

test("A successfully saved unchanged checkpoint creates no write, revision, or event", async () => {
  const h = await harness();
  const first = await h.put([entry()]);
  h.clearOperations();
  const again = await h.put([entry()]);
  assert.equal(again.status, 200);
  assert.equal(again.body.unchanged, true);
  assert.equal(again.body.revision, first.body.revision);
  assert.deepEqual(h.operations, []);
  assert.equal(h.store.sync.events.length, 1);
  assert.equal(h.sqliteState.events.length, 1);
});

test("An identical retry persists a failed checkpoint before acknowledging it", async () => {
  const h = await harness();
  const error = h.failNext("json.write");
  await assert.rejects(h.put([entry()]), received => received === error);
  assert.equal(h.successes.length, 0);
  assert.equal(h.persisted().sync.watchProgress.length, 0);
  const again = await h.put([entry()]);
  assert.equal(again.status, 200);
  assert.equal(again.body.unchanged, true);
  assert.equal(again.body.revision, 1);
  assert.equal(h.persisted().sync.watchProgress[0].entries[0].position, 60_000);
  assert.equal(h.store.sync.events.length, 1);
  assert.equal(h.sqliteState.events.length, 1);
});

test("Repeated failed identical retries cannot report success", async () => {
  const h = await harness();
  const firstError = h.failNext("sqlite.snapshot");
  await assert.rejects(h.put([entry()]), received => received === firstError);
  const secondError = h.failNext("json.rename");
  await assert.rejects(h.put([entry()]), received => received === secondError);
  assert.equal(h.successes.length, 0);
  const result = await h.put([entry()]);
  assert.equal(result.status, 200);
  assert.equal(result.body.revision, 1);
  assert.equal(h.persisted().sync.nextRevision, 1);
  assert.equal(h.store.sync.events.length, 1);
});

test("A delayed retry saves a single content entry and preserves completion metadata", async () => {
  const h = await harness();
  const completed = entry({ position: 118_000, progressPercent: 98.33 });
  const error = h.failNext("json.write");
  await assert.rejects(h.put([completed]), received => received === error);
  h.clock.now += 15_000;
  const result = await h.put([completed]);
  assert.equal(result.status, 200);
  const entries = h.persisted().sync.watchProgress[0].entries;
  assert.equal(entries.length, 1);
  for (const field of ["key", "contentId", "contentType", "videoId", "season", "episode", "position", "duration", "lastWatched", "progressPercent"]) {
    assert.equal(entries[0][field], completed[field]);
  }
});

test("An unchanged checkpoint waits for its in-flight save without adding another save", async () => {
  const h = await harness();
  const gate = h.holdNextWrite();
  const first = h.put([entry()]);
  await gate.started;
  const second = h.put([entry()]);
  await yieldTurn();
  assert.equal(h.successes.length, 0);
  assert.equal(h.writeCount(), 1);
  const results = Promise.all([first, second]);
  gate.release();
  const responses = await results;
  assert.deepEqual(responses.map(item => item.status), [200, 200]);
  assert.equal(responses[1].body.unchanged, true);
  assert.equal(h.writeCount(), 1);
  assert.equal(h.persisted().sync.watchProgress[0].entries[0].position, 60_000);
});

test("Completing an older save cannot mark a newer failed revision as persisted", async () => {
  const h = await harness();
  const gate = h.holdNextWrite();
  const first = h.put([entry()]);
  await gate.started;
  const error = h.failAt("json.rename", 2);
  const newer = entry({ position: 90_000, lastWatched: NOW - 1_000 });
  const second = h.put([newer]);
  await yieldTurn();
  assert.equal(h.store.sync.nextRevision, 2);
  const retry = h.put([newer]);
  const results = Promise.allSettled([first, second, retry]);
  gate.release();
  const settled = await results;
  assert.deepEqual(settled.map(item => item.status), ["fulfilled", "rejected", "fulfilled"]);
  assert.equal(settled[1].reason, error);
  assert.equal(settled[2].value.body.revision, 2);
  assert.equal(h.writeCount(), 3);
  assert.equal(h.persisted().sync.watchProgress[0].entries[0].position, 90_000);
  assert.equal(h.store.sync.events.length, 2);
});

test("A waiting unchanged response cannot advertise a newer uncommitted checkpoint", async () => {
  const h = await harness();
  const gate = h.holdNextWrite();
  const first = h.put([entry()]);
  await gate.started;
  const unchanged = h.put([entry()]);
  await yieldTurn();
  h.clock.now += 1_000;
  const error = h.failAt("json.rename", 2);
  const second = h.put([entry({ position: 90_000, lastWatched: NOW + 500 })]);
  await yieldTurn();
  assert.equal(h.store.sync.nextRevision, 2);
  const results = Promise.allSettled([first, unchanged, second]);
  gate.release();
  const settled = await results;
  assert.deepEqual(settled.map(item => item.status), ["fulfilled", "fulfilled", "rejected"]);
  assert.equal(settled[2].reason, error);
  assert.equal(settled[1].value.body.revision, 1);
  assert.equal(settled[1].value.body.updatedAt, NOW);
  assert.equal(h.persisted().sync.watchProgress[0].entries[0].position, 60_000);
});

test("A journal append error cannot leave an in-memory checkpoint falsely acknowledged", async () => {
  const h = await harness();
  const error = h.failNext("sqlite.event");
  await assert.rejects(h.put([entry()]), received => received === error);
  assert.equal(h.successes.length, 0);
  const result = await h.put([entry()]);
  assert.equal(result.status, 200);
  assert.equal(result.body.revision, 1);
  assert.equal(h.persisted().sync.watchProgress[0].entries[0].position, 60_000);
  assert.equal(h.persisted().sync.events.length, 1);
});

test("A failed deletion is saved before a stale PUT is acknowledged and cannot resurrect progress", async () => {
  const h = await harness();
  await h.put([entry()]);
  h.clock.now += 100;
  const error = h.failNext("json.write");
  await assert.rejects(h.remove([entry().key]), received => received === error);
  assert.equal(h.persisted().sync.watchProgress[0].entries.length, 1);
  const result = await h.put([entry()]);
  assert.equal(result.body.unchanged, true);
  assert.equal(result.body.revision, 2);
  const saved = h.persisted().sync.watchProgress[0];
  assert.equal(saved.entries.length, 0);
  assert.deepEqual(saved.tombstones, [{ key: entry().key, deletedAt: h.clock.now }]);
  assert.equal(h.store.sync.events.length, 2);
});

test("Recovering one profile preserves other accounts and profiles", async () => {
  const h = await harness();
  await h.put([entry({ position: 10_000 })], { profileId: 2 });
  await h.put([entry({ position: 20_000 })], { accountId: "fixture-other" });
  const before = copy(h.store.sync.watchProgress);
  const error = h.failNext("json.write");
  await assert.rejects(h.put([entry()]), received => received === error);
  await h.put([entry()]);
  assert.deepEqual(h.persisted().sync.watchProgress.slice(0, 2), before);
});

test("Unauthenticated requests cannot read or write progress, including the unchanged path", async () => {
  const h = await harness();
  await h.put([entry()]);
  h.clearOperations();
  for (const operation of [() => h.put([entry()], { accountId: null }),
    () => h.get({ accountId: null }), () => h.remove([entry().key], { accountId: null })]) {
    await assert.rejects(operation(), error => error.statusCode === 401);
  }
  assert.deepEqual(h.operations, []);
  assert.equal(h.store.sync.nextRevision, 1);
});

// Startup tests use only tiny fictional files in a fresh temporary directory.
// The real SQLite adapter is exercised, without starting the Companion server.
const os = require("node:os");
const path = require("node:path");
const crypto = require("node:crypto");
const { DatabaseSync } = require("node:sqlite");
const { createSyncSqliteStore } = require("./sync-sqlite-store");

function snapshot(revision, events = []) {
  return { nextRevision: revision, events, tokenSecret: "fixture-only-token-secret-32-characters",
    accounts: [], favorites: [], settings: [], profileBlobs: [], profilePins: [], pairings: [],
    watchProgress: [{ accountId: "fixture-owner", profileId: 1, entries: [entry()], tombstones: [] }],
    watchedItems: [] };
}

function event(revision, overrides = {}) {
  return { revision, accountId: "fixture-owner", profileId: 1, domain: "watch_progress",
    operation: "upsert", keys: ["fixture-key"], updatedAt: NOW, deviceId: "fixture-device", ...overrides };
}

function temporaryDatabase(t) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "jojo-startup-test-"));
  const handles = [];
  t.after(() => {
    for (const handle of handles.reverse()) handle.close();
    assert.equal(path.dirname(directory), os.tmpdir());
    assert.ok(path.basename(directory).startsWith("jojo-startup-test-"));
    fs.rmSync(directory, { recursive: true, force: true });
  });
  return { directory, track(handle) { handles.push(handle); return handle; } };
}

async function startFixture(t, { json, saved = null, events = [], progress = [], sqlite = true, seedWal = false, required = false }) {
  const fixture = temporaryDatabase(t);
  const storePath = path.join(fixture.directory, "store.json");
  fs.writeFileSync(storePath, JSON.stringify({ history: [], sync: json, ...(required ? { sqliteProgressRequired: true } : {}) }));
  if (sqlite && seedWal) {
    const child = require("node:child_process").spawnSync(process.execPath, ["-e", `
      const { createSyncSqliteStore } = require(process.argv[1]);
      const adapter = createSyncSqliteStore(process.argv[2]);
      const fixture = JSON.parse(require("node:fs").readFileSync(0, "utf8"));
      adapter.saveSnapshot(fixture.saved);
      for (const event of fixture.events) adapter.appendEvent(event);
      if (fixture.progress.length) adapter.saveProgress(fixture.progress);
      process.exit(0); // Simulate exit without SQLite close/checkpoint.
    `, require.resolve("./sync-sqlite-store"), fixture.directory],
    { input: JSON.stringify({ saved, events, progress }), encoding: "utf8", windowsHide: true });
    assert.equal(child.status, 0, child.stderr);
    assert.ok(fs.statSync(path.join(fixture.directory, "sync.db-wal")).size > 0);
  } else if (sqlite) {
    const seed = createSyncSqliteStore(fixture.directory);
    if (saved) seed.saveSnapshot(saved);
    for (const item of events) seed.appendEvent(item);
    if (progress.length) seed.saveProgress(progress);
    seed.close();
  }
  const context = vm.createContext({
    DATA_DIR: fixture.directory, STORE_PATH: storePath,
    SYNC_ASSET_DIR: path.join(fixture.directory, "assets"),
    LOCAL_M3U_DIR: path.join(fixture.directory, "m3u"),
    BACKUP_DIR: path.join(fixture.directory, "backups"),
    FREEBOX_THUMBNAIL_DIR: path.join(fixture.directory, "thumbnails"),
    fsp: fs.promises, crypto, store: { sync: {} },
    async pruneObsoleteBackups() {},
    createSyncSqliteStore(directory) {
      return sqlite ? fixture.track(createSyncSqliteStore(directory)) : null;
    }
  });
  vm.runInContext(queueSource + "let syncSqliteStore = null;\n" +
    ["persistStore", "compactSyncTombstones", "appendSyncEvent", "compactSyncEvents"].map(definition).join("\n") +
    span("async function initialiseStore() {", "\nfunction persistStore("), context);
  await context.initialiseStore();
  return { context, fixture, saved: () => JSON.parse(fs.readFileSync(storePath, "utf8")),
    database: () => fixture.track(new DatabaseSync(path.join(fixture.directory, "sync.db"))) };
}

test("Startup keeps a journal revision above an older snapshot and appends after it", async t => {
  const h = await startFixture(t, { json: snapshot(10), saved: snapshot(20), events: [event(90)] });
  assert.equal(h.context.store.sync.nextRevision, 90);
  assert.equal(h.saved().sync.nextRevision, 90);
  assert.deepEqual(copy(h.context.store.sync.watchProgress), snapshot(20).watchProgress);
  assert.equal(h.context.appendSyncEvent("fixture-owner", 1, "watch_progress", "upsert", []), 91);
  assert.equal(h.database().prepare("SELECT MAX(revision) AS revision FROM sync_events").get().revision, 91);
});

test("Startup reads the latest journal revision from an uncheckpointed WAL", async t => {
  const h = await startFixture(t, { json: snapshot(10), saved: snapshot(20), events: [event(90)], seedWal: true });
  assert.equal(h.context.store.sync.nextRevision, 90);
  assert.equal(h.context.appendSyncEvent("fixture-owner", 1, "watch_progress", "upsert", []), 91);
});

test("Startup retains a JSON counter higher than the SQLite snapshot and journal", async t => {
  const h = await startFixture(t, { json: snapshot(100), saved: snapshot(20), events: [event(90)] });
  assert.equal(h.context.store.sync.nextRevision, 100);
  assert.equal(h.saved().sync.nextRevision, 100);
});

test("JSON fallback never starts below a revision retained in its events", async t => {
  const h = await startFixture(t, { json: snapshot(20, [event(90)]), sqlite: false });
  assert.equal(h.context.store.sync.nextRevision, 90);
  assert.equal(h.context.appendSyncEvent("fixture-owner", 1, "watch_progress", "upsert", []), 91);
});

test("A snapshot event above its declared counter also raises the startup revision", async t => {
  const h = await startFixture(t, { json: snapshot(10), saved: snapshot(20, [event(100)]), events: [event(90)] });
  assert.equal(h.context.store.sync.nextRevision, 100);
  assert.equal(h.context.appendSyncEvent("fixture-owner", 1, "watch_progress", "upsert", []), 101);
});

test("Startup importing older JSON events preserves existing journal rows", async t => {
  const h = await startFixture(t, { json: snapshot(100, [event(90, { operation: "delete" })]),
    saved: snapshot(20), events: [event(90)] });
  assert.equal(h.context.store.sync.nextRevision, 100);
  const row = h.database().prepare("SELECT operation FROM sync_events WHERE revision=90").get();
  assert.equal(row.operation, "upsert");
});

test("Appending an existing revision fails without overwriting the stored event", t => {
  const fixture = temporaryDatabase(t);
  const adapter = fixture.track(createSyncSqliteStore(fixture.directory));
  adapter.appendEvent(event(90));
  assert.throws(() => adapter.appendEvent(event(90, { operation: "delete" })));
  const database = fixture.track(new DatabaseSync(path.join(fixture.directory, "sync.db")));
  assert.equal(database.prepare("SELECT operation FROM sync_events WHERE revision=90").get().operation, "upsert");
});

test("An empty journal reports revision zero and can import duplicate revisions safely", t => {
  const fixture = temporaryDatabase(t);
  const adapter = fixture.track(createSyncSqliteStore(fixture.directory));
  assert.equal(adapter.latestRevision(), 0);
  adapter.importEvents([event(2), event(5), event(5, { operation: "delete" })]);
  assert.equal(adapter.latestRevision(), 5);
  const database = fixture.track(new DatabaseSync(path.join(fixture.directory, "sync.db")));
  assert.equal(database.prepare("SELECT COUNT(*) AS count FROM sync_events").get().count, 2);
  assert.equal(database.prepare("SELECT operation FROM sync_events WHERE revision=5").get().operation, "upsert");
});

test("An invalid imported event rolls back the batch rather than silently dropping it", t => {
  const fixture = temporaryDatabase(t);
  const adapter = fixture.track(createSyncSqliteStore(fixture.directory));
  adapter.appendEvent(event(2));
  assert.throws(() => adapter.importEvents([event(3), event(4, { accountId: null })]));
  assert.equal(adapter.latestRevision(), 2);
});

function largePersistenceSnapshot(revision) {
  const value = snapshot(revision, [event(revision)]);
  value.profileBlobs = [{ accountId: "fixture-owner", profileId: 1, key: "fixture-cold-data",
    value: { json: "fixture-".repeat(32 * 1024) } }];
  value.futureField = { text: "é 🚀", flags: [true, false, null], nested: { value: 7 } };
  return value;
}

test("Large snapshots retain every JSON value without mutating the live state", t => {
  const fixture = temporaryDatabase(t);
  const adapter = fixture.track(createSyncSqliteStore(fixture.directory));
  const value = largePersistenceSnapshot(1000);
  const original = JSON.stringify(value);
  adapter.saveSnapshot(value);
  assert.equal(JSON.stringify(value), original);
  assert.deepEqual(adapter.loadSnapshot().value, value);
  const database = fixture.track(new DatabaseSync(path.join(fixture.directory, "sync.db")));
  const row = database.prepare("SELECT payload, typeof(payload) AS type FROM sync_snapshot").get();
  assert.equal(row.type, "text");
  assert.deepEqual(JSON.parse(row.payload), value); // Also the original Companion reader.
  assert.equal(Buffer.byteLength(row.payload) % (64 * 1024), 0);
});

test("Snapshot alignment counts UTF-8 bytes and preserves non-ASCII content", t => {
  const fixture = temporaryDatabase(t);
  const adapter = fixture.track(createSyncSqliteStore(fixture.directory));
  const value = largePersistenceSnapshot(1000);
  value.profileBlobs[0].value.json = "é🚀".repeat(30 * 1024);
  adapter.saveSnapshot(value);
  const database = fixture.track(new DatabaseSync(path.join(fixture.directory, "sync.db")));
  const row = database.prepare("SELECT payload, length(CAST(payload AS BLOB)) AS bytes FROM sync_snapshot").get();
  assert.equal(row.bytes % (64 * 1024), 0);
  assert.ok(row.bytes >= Buffer.byteLength(JSON.stringify(value)));
  assert.deepEqual(JSON.parse(row.payload), value);
});

test("Small snapshots remain compact JSON without a large padding allocation", t => {
  const fixture = temporaryDatabase(t);
  const adapter = fixture.track(createSyncSqliteStore(fixture.directory));
  const value = snapshot(1000);
  adapter.saveSnapshot(value);
  const database = fixture.track(new DatabaseSync(path.join(fixture.directory, "sync.db")));
  const row = database.prepare("SELECT payload FROM sync_snapshot").get();
  assert.equal(Buffer.byteLength(row.payload), Buffer.byteLength(JSON.stringify(value)));
  assert.deepEqual(JSON.parse(row.payload), value);
});

test("Existing unpadded snapshots remain readable and are aligned on a later save", t => {
  const fixture = temporaryDatabase(t);
  const adapter = fixture.track(createSyncSqliteStore(fixture.directory));
  const database = fixture.track(new DatabaseSync(path.join(fixture.directory, "sync.db")));
  const original = largePersistenceSnapshot(1000);
  database.prepare("INSERT INTO sync_snapshot VALUES(1, ?, ?, ?)")
    .run(1000, NOW, JSON.stringify(original));
  assert.deepEqual(adapter.loadSnapshot().value, original);
  const updated = largePersistenceSnapshot(1001);
  updated.watchProgress[0].entries[0].position = 70_000;
  adapter.saveSnapshot(updated);
  assert.deepEqual(JSON.parse(database.prepare("SELECT payload FROM sync_snapshot").get().payload), updated);
  assert.deepEqual(adapter.loadSnapshot().value, updated);
});

test("Aligned snapshots grow and shrink across boundaries without losing fields", t => {
  const fixture = temporaryDatabase(t);
  const adapter = fixture.track(createSyncSqliteStore(fixture.directory));
  for (const [index, bytes] of [70_000, 330_000, 140_000, 9_000].entries()) {
    const value = largePersistenceSnapshot(1000 + index);
    value.profileBlobs[0].value.json = "x".repeat(bytes);
    adapter.saveSnapshot(value);
    assert.deepEqual(adapter.loadSnapshot().value, value);
  }
});

test("A small progress update does not rewrite the unchanged megabyte into the WAL", t => {
  const fixture = temporaryDatabase(t);
  const adapter = fixture.track(createSyncSqliteStore(fixture.directory));
  const previous = largePersistenceSnapshot(1000);
  previous.profileBlobs[0].value.json = "x".repeat(1024 * 1024);
  adapter.saveSnapshot(previous);
  const wal = path.join(fixture.directory, "sync.db-wal");
  const before = fs.statSync(wal).size;
  const changed = copy(previous);
  changed.nextRevision++;
  changed.watchProgress[0].entries[0].position = 600_001;
  changed.events.push(event(1001));
  assert.notEqual(Buffer.byteLength(JSON.stringify(changed)), Buffer.byteLength(JSON.stringify(previous)));
  adapter.saveSnapshot(changed);
  const growth = fs.statSync(wal).size - before;
  assert.ok(growth > 0 && growth < 128 * 1024, `Unchanged cold data was rewritten: ${growth} WAL bytes`);
  assert.deepEqual(adapter.loadSnapshot().value, changed);
});

test("A busy writer leaves the previous large snapshot intact and a retry persists it", t => {
  const fixture = temporaryDatabase(t);
  const adapter = fixture.track(createSyncSqliteStore(fixture.directory));
  const previous = largePersistenceSnapshot(1000);
  adapter.saveSnapshot(previous);
  const writer = fixture.track(new DatabaseSync(path.join(fixture.directory, "sync.db")));
  writer.exec("BEGIN IMMEDIATE");
  const changed = largePersistenceSnapshot(1001);
  changed.watchProgress[0].entries[0].position = 70_000;
  try {
    assert.throws(() => adapter.saveSnapshot(changed), /locked/);
    assert.deepEqual(adapter.loadSnapshot().value, previous);
  } finally {
    writer.exec("ROLLBACK");
  }
  adapter.saveSnapshot(changed);
  assert.deepEqual(adapter.loadSnapshot().value, changed);
});

test("Startup recovers an aligned snapshot from the WAL before the older JSON", async t => {
  const saved = largePersistenceSnapshot(1001);
  saved.watchProgress[0].entries[0].position = 77_000;
  saved.watchProgress[0].tombstones = [{ key: "fixture-deleted", deletedAt: NOW - 1_000 }];
  const h = await startFixture(t, { json: snapshot(1000), saved, events: [event(1002)], seedWal: true });
  assert.equal(h.context.store.sync.nextRevision, 1002);
  assert.deepEqual(copy(h.context.store.sync.watchProgress), saved.watchProgress);
  assert.deepEqual(copy(h.context.store.sync.profileBlobs), saved.profileBlobs);
  assert.equal(h.saved().sync.nextRevision, 1002);
  assert.equal(h.context.appendSyncEvent("fixture-owner", 1, "watch_progress", "upsert", []), 1003);
});

test("Large compact JSON remains a complete fallback when SQLite is unavailable", async t => {
  const value = largePersistenceSnapshot(1000);
  const h = await startFixture(t, { json: value, sqlite: false });
  assert.deepEqual(copy(h.context.store.sync.watchProgress), value.watchProgress);
  assert.deepEqual(copy(h.context.store.sync.profileBlobs), value.profileBlobs);
  assert.deepEqual(h.saved().sync.futureField, value.futureField);
  assert.equal(h.saved().sync.nextRevision, 1000);
});

function progressChange(revision, { accountId = "fixture-owner", profileId = 1,
  entries = [entry({ position: revision * 1_000 })], tombstones = [], keys = null } = {}) {
  keys ||= [...new Set([...entries, ...tombstones].map(item => item.key))];
  const updatedAt = NOW + revision;
  return { revision, accountId, profileId, updatedAt, keys, entries, tombstones,
    event: event(revision, { accountId, profileId, updatedAt, keys: keys.slice(0, 500),
      operation: tombstones.length ? "delete" : "upsert" }) };
}

function realProgressFixture(t) {
  const fixture = temporaryDatabase(t);
  const adapter = fixture.track(createSyncSqliteStore(fixture.directory));
  const database = fixture.track(new DatabaseSync(path.join(fixture.directory, "sync.db")));
  return { fixture, adapter, database };
}

test("A checkpoint commits only changed rows and its event, without a complete JSON or SQLite snapshot", async t => {
  const { adapter, database } = realProgressFixture(t);
  const h = await harness({ adapter });
  h.store.sync.futureColdField = { text: "fixture".repeat(200_000) };
  await h.persist();
  h.clearOperations();
  const before = database.prepare("SELECT payload FROM sync_snapshot").get().payload;
  await h.put([entry()]);
  assert.equal(h.writeCount(), 0);
  assert.equal(database.prepare("SELECT payload FROM sync_snapshot").get().payload, before);
  assert.equal(database.prepare("SELECT COUNT(*) AS n FROM sync_progress_deltas").get().n, 1);
  assert.equal(database.prepare("SELECT COUNT(*) AS n FROM sync_events").get().n, 1);
  const recovered = adapter.loadSnapshot();
  assert.equal(recovered.revision, 1);
  assert.deepEqual(recovered.value.watchProgress, copy(h.store.sync.watchProgress));
  assert.deepEqual(recovered.value.futureColdField, h.store.sync.futureColdField);
  assert.equal(h.persisted().sync.nextRevision, 0);
  assert.equal(h.timers.filter(timer => !timer.cancelled).length, 1);
  assert.equal(h.timers.at(-1).milliseconds, 300_000);
});

test("Incremental progress and deletes recover without mixing accounts or profiles", async t => {
  const { adapter } = realProgressFixture(t);
  const h = await harness({ adapter });
  await h.put([entry({ position: 1_000 })]);
  await h.put([entry({ position: 2_000 })], { profileId: 2 });
  await h.put([entry({ position: 3_000 })], { accountId: "fixture-other" });
  await h.removeConditionally([{ key: entry().key, deletedAt: NOW - 1_000 }]);
  const recovered = adapter.loadSnapshot().value;
  assert.deepEqual(recovered.watchProgress, copy(h.store.sync.watchProgress));
  assert.equal(recovered.watchProgress[0].entries.length, 0);
  assert.equal(recovered.watchProgress[1].entries[0].position, 2_000);
  assert.equal(recovered.watchProgress[2].entries[0].position, 3_000);
  assert.equal(recovered.events.length, 4);
  assert.equal(h.writeCount(), 0);
});

test("Incremental batches retain all rows beyond the event's 500-key display limit", async t => {
  const { adapter, database } = realProgressFixture(t);
  const h = await harness({ adapter });
  const items = Array.from({ length: 650 }, (_, i) => entry({ key: `fixture-${i}`, videoId: `episode-${i}` }));
  await h.put(items);
  assert.equal(adapter.loadSnapshot().value.watchProgress[0].entries.length, 650);
  assert.equal(JSON.parse(database.prepare("SELECT keys_json FROM sync_events").get().keys_json).length, 500);
  await h.remove(items.map(item => item.key));
  const recovered = adapter.loadSnapshot().value.watchProgress[0];
  assert.equal(recovered.entries.length, 0);
  assert.equal(recovered.tombstones.length, 650);
  assert.equal(h.writeCount(), 0);
});

test("A locked incremental transaction returns failure, then an identical retry commits before acknowledgement", async t => {
  const { adapter, database } = realProgressFixture(t);
  const h = await harness({ adapter });
  database.exec("BEGIN IMMEDIATE");
  try {
    await assert.rejects(h.put([entry()]), /locked/);
    assert.equal(h.successes.length, 0);
    assert.equal(database.prepare("SELECT COUNT(*) AS n FROM sync_events").get().n, 0);
    assert.equal(database.prepare("SELECT COUNT(*) AS n FROM sync_progress_deltas").get().n, 0);
  } finally { database.exec("ROLLBACK"); }
  const response = await h.put([entry()]);
  assert.equal(response.body.unchanged, true);
  assert.equal(response.body.revision, 1);
  assert.equal(adapter.loadSnapshot().value.watchProgress[0].entries[0].position, 60_000);
  assert.equal(h.writeCount(), 0);
});

test("Failure after event insertion rolls back both the event and checkpoint", async t => {
  const { adapter, database } = realProgressFixture(t);
  const h = await harness({ adapter });
  database.exec("CREATE TRIGGER fixture_abort BEFORE INSERT ON sync_progress_deltas BEGIN SELECT RAISE(ABORT, 'fixture checkpoint failure'); END");
  await assert.rejects(h.put([entry()]), /fixture checkpoint failure/);
  assert.equal(database.prepare("SELECT COUNT(*) AS n FROM sync_events").get().n, 0);
  assert.equal(database.prepare("SELECT COUNT(*) AS n FROM sync_progress_deltas").get().n, 0);
  assert.equal(h.successes.length, 0);
  database.exec("DROP TRIGGER fixture_abort");
  await h.put([entry()]);
  assert.equal(adapter.loadSnapshot().revision, 1);
  assert.equal(database.prepare("SELECT COUNT(*) AS n FROM sync_events").get().n, 1);
});

test("A newer checkpoint retries all uncommitted predecessors and keeps event order", async t => {
  const { adapter, database } = realProgressFixture(t);
  const h = await harness({ adapter });
  database.exec("BEGIN IMMEDIATE");
  try { await assert.rejects(h.put([entry()]), /locked/); }
  finally { database.exec("ROLLBACK"); }
  await h.put([entry({ position: 20_000, lastWatched: NOW })]);
  const saved = adapter.loadSnapshot();
  assert.equal(saved.value.watchProgress[0].entries[0].position, 20_000);
  assert.deepEqual(saved.value.events.map(item => item.revision), [1, 2]);
  assert.equal(saved.revision, 2);
});

test("The periodic checkpoint exports complete JSON and folds committed deltas atomically", async t => {
  const { adapter, database } = realProgressFixture(t);
  const h = await harness({ adapter });
  await h.put([entry()]);
  await h.put([entry({ position: 90_000, lastWatched: NOW })]);
  await h.runCheckpointTimer();
  assert.equal(h.writeCount(), 1);
  assert.equal(h.persisted().sync.nextRevision, 2);
  assert.deepEqual(h.persisted().sync.watchProgress, adapter.loadSnapshot().value.watchProgress);
  assert.equal(database.prepare("SELECT COUNT(*) AS n FROM sync_progress_deltas").get().n, 0);
  assert.equal(JSON.parse(database.prepare("SELECT payload FROM sync_snapshot").get().payload).nextRevision, 2);
  await h.put([entry({ position: 95_000, lastWatched: NOW + 1 })]);
  assert.equal(h.writeCount(), 1);
  assert.equal(adapter.loadSnapshot().revision, 3);
});

test("Failed JSON export leaves durable SQLite current and is retried before the next progress acknowledgement", async t => {
  const { adapter } = realProgressFixture(t);
  const h = await harness({ adapter });
  await h.put([entry()]);
  h.failNext("json.rename");
  await h.runCheckpointTimer();
  assert.equal(h.persisted().sync.nextRevision, 0);
  assert.equal(adapter.loadSnapshot().revision, 1);
  await h.put([entry({ position: 85_000, lastWatched: NOW })]);
  assert.equal(h.persisted().sync.nextRevision, 2);
  assert.equal(adapter.loadSnapshot().revision, 2);
});

test("A progress request queued during a full export survives until its own durable commit", async t => {
  const { adapter } = realProgressFixture(t);
  const h = await harness({ adapter });
  const gate = h.holdNextWrite();
  const full = h.persist();
  await gate.started;
  const progress = h.put([entry()]);
  gate.release();
  await Promise.all([full, progress]);
  assert.equal(adapter.loadSnapshot().revision, 1);
  assert.equal(adapter.loadSnapshot().value.watchProgress[0].entries[0].position, 60_000);
});

test("A full mutation queued while an export waits cannot be skipped by incremental work", async t => {
  const { adapter } = realProgressFixture(t);
  const h = await harness({ adapter });
  const gate = h.holdNextWrite();
  const first = h.persist();
  await gate.started;
  h.store.sync.futureColdField = { fixture: "must survive" };
  const cold = h.persist();
  const progress = h.put([entry()]);
  gate.release();
  await Promise.all([first, cold, progress]);
  assert.deepEqual(adapter.loadSnapshot().value.futureColdField, { fixture: "must survive" });
  assert.equal(adapter.loadSnapshot().revision, 1);
  assert.equal(h.writeCount(), 2);
});

test("Crash recovery replays incremental WAL over an old JSON and preserves a rewind and deletion", async t => {
  const saved = largePersistenceSnapshot(10);
  const changes = [progressChange(11, { entries: [entry({ position: 12_000 })] }),
    progressChange(12, { entries: [], tombstones: [{ key: "fixture-deleted", deletedAt: NOW }] })];
  const h = await startFixture(t, { json: snapshot(10), saved, progress: changes, seedWal: true, required: true });
  assert.equal(h.saved().sync.nextRevision, 12);
  assert.equal(h.saved().sync.watchProgress[0].entries[0].position, 12_000);
  assert.deepEqual(h.saved().sync.watchProgress[0].tombstones, changes[1].tombstones);
  assert.deepEqual(h.saved().sync.profileBlobs, saved.profileBlobs);
  assert.equal(h.database().prepare("SELECT COUNT(*) AS n FROM sync_progress_deltas").get().n, 0);
});

test("A store marked SQLite-required refuses to serve stale JSON when SQLite is unavailable", async t => {
  await assert.rejects(startFixture(t, { json: snapshot(10), sqlite: false, required: true }), /SQLite est nécessaire/);
});

test("A store marked SQLite-required refuses an empty database instead of restoring stale JSON", async t => {
  await assert.rejects(startFixture(t, { json: snapshot(10), required: true }), /Snapshot SQLite requis/);
});

test("A failed non-progress journal append forces a full retry before a later checkpoint succeeds", async t => {
  const { adapter, database } = realProgressFixture(t);
  const h = await harness({ adapter });
  const append = adapter.appendEvent;
  const error = new Error("Fixture journal failure");
  h.store.sync.futureColdField = { fixture: "must survive" };
  adapter.appendEvent = () => { throw error; };
  assert.throws(() => h.context.appendSyncEvent("fixture-owner", 1, "settings", "upsert", ["fixture"]), received => received === error);
  adapter.appendEvent = append;
  const response = await h.put([entry()]);
  assert.equal(response.status, 200);
  assert.equal(response.body.revision, 2);
  assert.deepEqual(adapter.loadSnapshot().value.futureColdField, { fixture: "must survive" });
  assert.equal(database.prepare("SELECT COUNT(*) AS n FROM sync_progress_deltas").get().n, 0);
  assert.equal(h.writeCount(), 1);
});

test("A missing or corrupt base cannot silently discard durable progress deltas", t => {
  const { adapter, database } = realProgressFixture(t);
  adapter.saveSnapshot(snapshot(10));
  adapter.saveProgress([progressChange(11)]);
  database.exec("UPDATE sync_snapshot SET payload='broken-json'");
  assert.throws(() => adapter.loadSnapshot());
  database.exec("DELETE FROM sync_snapshot");
  assert.throws(() => adapter.loadSnapshot(), /sans snapshot/);
});

test("Idempotent checkpoint retry never duplicates events and conflicting revisions are refused", t => {
  const { adapter, database } = realProgressFixture(t);
  adapter.saveSnapshot(snapshot(10));
  const change = progressChange(11);
  adapter.saveProgress([change]);
  adapter.saveProgress([change]);
  assert.equal(database.prepare("SELECT COUNT(*) AS n FROM sync_progress_deltas").get().n, 1);
  assert.equal(database.prepare("SELECT COUNT(*) AS n FROM sync_events").get().n, 1);
  assert.throws(() => adapter.saveProgress([progressChange(11, { accountId: "fixture-other" })]), /Conflit/);
  assert.equal(adapter.loadSnapshot().value.watchProgress[0].accountId, "fixture-owner");
});

test("An incoherent account or event rolls back an entire incremental batch", t => {
  const { adapter, database } = realProgressFixture(t);
  adapter.saveSnapshot(snapshot(10));
  const invalid = progressChange(12);
  invalid.event.accountId = "fixture-other";
  assert.throws(() => adapter.saveProgress([progressChange(11), invalid]), /incohérent/);
  assert.equal(database.prepare("SELECT COUNT(*) AS n FROM sync_progress_deltas").get().n, 0);
  assert.equal(database.prepare("SELECT COUNT(*) AS n FROM sync_events").get().n, 0);
  assert.equal(adapter.loadSnapshot().revision, 10);
});

test("A pre-existing different journal event cannot be impersonated by a progress delta", t => {
  const { adapter, database } = realProgressFixture(t);
  adapter.saveSnapshot(snapshot(10));
  adapter.appendEvent(event(11, { accountId: "fixture-other" }));
  assert.throws(() => adapter.saveProgress([progressChange(11)]), /Conflit/);
  assert.equal(database.prepare("SELECT COUNT(*) AS n FROM sync_progress_deltas").get().n, 0);
});

test("A stale full snapshot cannot overwrite a newer committed delta", t => {
  const { adapter } = realProgressFixture(t);
  adapter.saveSnapshot(snapshot(10));
  adapter.saveProgress([progressChange(11)]);
  assert.throws(() => adapter.saveSnapshot(snapshot(10)), /antérieur/);
  assert.equal(adapter.loadSnapshot().revision, 11);
});

test("Sixty individual checkpoints keep the complete snapshot unchanged and write less than two megabytes of WAL", t => {
  const { adapter, database, fixture } = realProgressFixture(t);
  adapter.saveSnapshot(largePersistenceSnapshot(10));
  database.exec("PRAGMA wal_checkpoint(TRUNCATE)");
  const before = database.prepare("SELECT payload FROM sync_snapshot").get().payload;
  for (let revision = 11; revision <= 70; revision++) adapter.saveProgress([progressChange(revision)]);
  const bytes = fs.statSync(path.join(fixture.directory, "sync.db-wal")).size;
  assert.ok(bytes < 2_000_000, `Incremental WAL unexpectedly grew to ${bytes} bytes`);
  assert.equal(database.prepare("SELECT payload FROM sync_snapshot").get().payload, before);
  assert.equal(adapter.loadSnapshot().revision, 70);
  assert.equal(database.prepare("SELECT COUNT(*) AS n FROM sync_progress_deltas").get().n, 60);
});

test("A later full profile deletion prevents old deltas from resurrecting its progress", t => {
  const { adapter, database } = realProgressFixture(t);
  adapter.saveSnapshot(snapshot(10));
  adapter.saveProgress([progressChange(11), progressChange(12, { profileId: 2 })]);
  const current = adapter.loadSnapshot().value;
  current.watchProgress = current.watchProgress.filter(item => item.profileId !== 1);
  current.nextRevision = 13;
  adapter.saveSnapshot(current);
  assert.deepEqual(adapter.loadSnapshot().value.watchProgress.map(item => item.profileId), [2]);
  assert.equal(database.prepare("SELECT COUNT(*) AS n FROM sync_progress_deltas").get().n, 0);
});

test("Restart restores a profile created entirely by deltas and preserves entry order", async t => {
  const saved = snapshot(10);
  const first = entry({ key: "fixture-first" });
  const second = entry({ key: "fixture-second" });
  const changes = [progressChange(11, { profileId: 2, entries: [first, second] }),
    progressChange(12, { profileId: 2, entries: [{ ...first, position: 15_000 }] })];
  const h = await startFixture(t, { json: snapshot(10), saved, progress: changes, seedWal: true, required: true });
  const profile = h.saved().sync.watchProgress.find(item => item.profileId === 2);
  assert.deepEqual(profile.entries.map(item => item.key), [first.key, second.key]);
  assert.equal(profile.entries[0].position, 15_000);
  assert.equal(h.saved().sqliteProgressRequired, true);
});

test("Graceful shutdown checkpoints recent progress before closing SQLite and reporting success", async t => {
  const { adapter, database } = realProgressFixture(t);
  const h = await harness({ adapter });
  await h.put([entry()]);
  const handlers = new Map();
  const exits = [];
  let closed = false;
  const originalClose = adapter.close.bind(adapter);
  adapter.close = () => { if (!closed) { originalClose(); closed = true; } };
  Object.assign(h.context, {
    initialiseStore: async () => {}, http: { createServer() { return {
      listen() { return this; }, close(done) { done(); }
    }; } }, PORT: 0, route() {}, RETRY_INTERVAL_MS: 1_000,
    setInterval() { return { unref() {} }; }, retryWaitingProviders() {},
    runScheduledBackups() {}, runFreeTvRecurringRecords() {}, JOJO_HOME_GUARD_TARGET: "",
    process: { once(name, handler) { handlers.set(name, handler); }, exit(code) { exits.push(code); } }
  });
  vm.runInContext(source.slice(source.lastIndexOf("initialiseStore().then(() => {")), h.context);
  await yieldTurn();
  handlers.get("SIGTERM")();
  await yieldTurn();
  assert.deepEqual(exits, [0]);
  assert.equal(closed, true);
  assert.equal(h.persisted().sync.nextRevision, 1);
  assert.equal(database.prepare("SELECT COUNT(*) AS n FROM sync_progress_deltas").get().n, 0);
});

"use strict";

const http = require("node:http");
const fs = require("node:fs");
const fsp = require("node:fs/promises");
const path = require("node:path");
const os = require("node:os");
const crypto = require("node:crypto");
const { Readable } = require("node:stream");
const { execFile, spawn } = require("node:child_process");
const { promisify } = require("node:util");
const { isIP } = require("node:net");
const { createStartupGuardian } = require("./startup-guardian");
const { createSyncSqliteStore } = require("./sync-sqlite-store");

const PORT = Number(process.env.JOJOTV_COMPANION_PORT || 8095);
const DATA_DIR = process.env.JOJOTV_COMPANION_DATA_DIR || path.join(__dirname, "data");
const STORE_PATH = path.join(DATA_DIR, "store.json");
const SYNC_ASSET_DIR = path.join(DATA_DIR, "sync-assets");
const LOCAL_M3U_DIR = path.join(DATA_DIR, "local-m3u");
const BACKUP_DIR = path.join(DATA_DIR, "backups");
const FREEBOX_THUMBNAIL_DIR = path.join(DATA_DIR, "freebox-thumbnails");
const HEIC_PREVIEW_DIR = path.join(DATA_DIR, "heic-previews");
const FREEBOX_PHOTO_DIR = "/mnt/Freebox/Photos";
const BACKUP_RETENTION = 7;
const BACKUP_APPS = new Set(["jojohome"]);
// The Companion can run on a NAS configured in UTC.  Backup policy is a
// user-facing French local time, so never derive it from the server clock.
const BACKUP_TIME_ZONE = "Europe/Paris";
// Profile settings and a sizeable personal library can exceed the original
// provider-form limit.  Keep a bounded but practical ceiling for authenticated
// NAS snapshots.
const MAX_BODY_BYTES = 32 * 1024 * 1024;
const MAX_HEIC_BODY_BYTES = 40 * 1024 * 1024;
const MAX_SYNC_ASSET_BYTES = 100 * 1024 * 1024;
// Backups are JSON snapshots.  Keep their embedded visual payload bounded: a
// large media file (for example a video accidentally registered as artwork)
// must never exhaust the small NAS while being converted to Base64.
const MAX_BACKUP_EMBEDDED_ASSET_BYTES = 8 * 1024 * 1024;
const MAX_BACKUP_EMBEDDED_ASSETS_BYTES = 24 * 1024 * 1024;
const MAX_LOCAL_M3U_BYTES = 32 * 1024 * 1024;
const RETRY_INTERVAL_MS = 30_000;
const JOJO_HOME_GUARD_TARGET = String(process.env.JOJO_HOME_GUARD_TARGET || "").trim();
// A short poll keeps the post-boot handoff responsive without monitoring foreground use.
const JOJO_HOME_GUARD_INTERVAL_MS = Math.max(1_000, Number(process.env.JOJO_HOME_GUARD_INTERVAL_MS || 30_000) || 30_000);
const JOJO_HOME_GUARD_LAUNCH_DELAY_MS = Math.max(0, Number(process.env.JOJO_HOME_GUARD_LAUNCH_DELAY_MS || 8_000) || 8_000);
const PAIRING_TTL_MS = 5 * 60 * 1000;
const PAIRING_POLL_INTERVAL_SECONDS = 2;
const FINAL_STATUSES = new Set(["success", "error", "not_found"]);
const execFileAsync = promisify(execFile);
const freeboxThumbnailJobs = new Map();
const companionPlaybackSessions = new Map();
const companionEpisodeLists = new Map();
const COMPANION_PLAYBACK_TTL_MS = 6 * 60 * 60 * 1000;
const heicPreviewJobs = new Map();
const heicDecodeWaiters = [];
const heicFullDecodeWaiters = [];
let heicDecodeCount = 0;
let heicFullDecodeCount = 0;
let heicPreviewPrunedAt = 0;
const FREEBOX_THUMBNAIL_VERSION = "v1";
const FREEBOX_THUMBNAIL_POSITIONS = [0.28, 0.12, 0.44, 0.60, 0.76, 0.88];

let store = { history: [], sync: { accounts: [], favorites: [], settings: [], watchProgress: [], watchedItems: [], profileBlobs: [], profilePins: [], pairings: [], backups: [], backupPolicies: [], freeTvRecurringRecords: [], events: [], nextRevision: 0, tokenSecret: "" } };
let writeQueue = Promise.resolve();
let persistedSyncRevision = -1;
let pendingProgressWrites = [];
let fullStoreVersion = 0;
let persistedFullStoreVersion = -1;
let progressCheckpointTimer = null;
let syncSqliteStore = null;
let retryInProgress = false;
let lastScheduledBackupDay = "";

function isPrivateIpv4(value) {
  const parts = String(value || "").split(".").map(Number);
  if (parts.length !== 4 || parts.some((part) => !Number.isInteger(part) || part < 0 || part > 255)) return false;
  return parts[0] === 10 ||
    (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31) ||
    (parts[0] === 192 && parts[1] === 168);
}

function targetFrom(input) {
  const tv = String(input.tv || "").trim();
  const port = Number(input.port || 8093);
  if (!isPrivateIpv4(tv)) throw new Error("Adresse TV locale invalide.");
  if (port !== 8093) throw new Error("Seul JojoHome IPTV est pris en charge (port 8093).");
  return { tv, port };
}

function providerPayload(input) {
  const type = String(input.type || "").trim().toLowerCase();
  const payload = {
    type,
    name: String(input.name || "").trim(),
    serverUrl: String(input.serverUrl || "").trim(),
    username: String(input.username || "").trim(),
    password: String(input.password || "").trim(),
    m3uUrl: String(input.m3uUrl || "").trim(),
    portalUrl: String(input.portalUrl || "").trim(),
    macAddress: String(input.macAddress || "").trim(),
    serialNumber: String(input.serialNumber || "").trim(),
    deviceId: String(input.deviceId || "").trim(),
    deviceId2: String(input.deviceId2 || "").trim()
  };
  if (!payload.name) throw new Error("Nom du fournisseur obligatoire.");
  if (type === "xtream" && (!payload.serverUrl || !payload.username)) throw new Error("URL et identifiant Xtream obligatoires.");
  if (type === "m3u" && !payload.m3uUrl) throw new Error("URL M3U obligatoire.");
  if (type === "stalker" && (!payload.portalUrl || !payload.macAddress)) throw new Error("Portail et adresse MAC obligatoires.");
  if (!["xtream", "m3u", "stalker"].includes(type)) throw new Error("Type de fournisseur inconnu.");
  if (input.dns != null) {
    if (typeof input.dns !== "object" || Array.isArray(input.dns)) throw new Error("Configuration DNS invalide.");
    const dns = { mode: String(input.dns.mode || "").trim().toLowerCase(),
      primary: String(input.dns.primary || "").trim(), secondary: String(input.dns.secondary || "").trim() };
    if (!dns.mode || dns.mode === "custom" && (!isIP(dns.primary) || dns.secondary && !isIP(dns.secondary)))
      throw new Error("Saisis une adresse IP DNS valide (IPv4 ou IPv6).");
    payload.dns = dns;
  }
  if (input.clearDns === true) payload.clearDns = true;
  return payload;
}

function playerProviderRecords() {
  const seen = new Set();
  return store.history.filter((record) => {
    if (!record.payload || !["m3u", "xtream"].includes(record.payload.type)) return false;
    const key = JSON.stringify(record.payload);
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  }).map((record) => ({ id: record.id, name: record.payload.name, type: record.payload.type }));
}

function playerRecord(id) {
  const record = store.history.find((item) => item.id === id && item.payload);
  if (!record) throw new Error("Fournisseur lecteur introuvable.");
  return record;
}

async function fetchRemote(url) {
  const response = await fetch(url, { redirect: "follow", signal: AbortSignal.timeout(20_000) });
  if (!response.ok) throw new Error(`Serveur IPTV HTTP ${response.status}.`);
  return response;
}

function xtreamBase(payload) {
  return String(payload.serverUrl || "").trim().replace(/\/+$/, "");
}

async function xtreamItems(payload, section) {
  const action = section === "live" ? "get_live_streams" : section === "movies" ? "get_vod_streams" : "get_series";
  const url = new URL(`${xtreamBase(payload)}/player_api.php`);
  url.searchParams.set("username", payload.username);
  url.searchParams.set("password", payload.password);
  url.searchParams.set("action", action);
  const values = await (await fetchRemote(url)).json();
  return (Array.isArray(values) ? values : []).map((item) => ({
    id: String(item.stream_id || item.series_id || ""),
    title: String(item.name || "Sans titre"),
    poster: String(item.stream_icon || item.cover || ""),
    extension: String(item.container_extension || "mp4"),
    section
  })).filter((item) => item.id);
}

async function m3uItems(payload) {
  const text = await (await fetchRemote(payload.m3uUrl)).text();
  const lines = text.split(/\r?\n/); const items = [];
  let title = ""; let poster = "";
  for (const line of lines) {
    if (line.startsWith("#EXTINF:")) {
      title = line.split(",").slice(1).join(",").trim() || "Sans titre";
      poster = (line.match(/tvg-logo="([^"]*)"/) || [])[1] || "";
    } else if (line && !line.startsWith("#") && title) {
      items.push({ id: String(items.length), title, poster, section: "live", url: line.trim() }); title = ""; poster = "";
    }
  }
  return items;
}

async function playerItems(record, section) {
  if (record.payload.type === "m3u") return section === "live" ? m3uItems(record.payload) : [];
  return xtreamItems(record.payload, section);
}

async function xtreamEpisodes(payload, seriesId) {
  const url = new URL(`${xtreamBase(payload)}/player_api.php`);
  url.searchParams.set("username", payload.username);
  url.searchParams.set("password", payload.password);
  url.searchParams.set("action", "get_series_info");
  url.searchParams.set("series_id", seriesId);
  const info = await (await fetchRemote(url)).json();
  return Object.values(info.episodes || {}).flatMap((episodes) => episodes).map((episode) => ({
    id: String(episode.id || ""),
    title: `S${String(episode.season || 0).padStart(2, "0")}E${String(episode.episode_num || 0).padStart(2, "0")} · ${episode.title || "Épisode"}`,
    poster: String(episode.info?.movie_image || ""),
    extension: String(episode.container_extension || "mp4"),
    section: "series"
  })).filter((episode) => episode.id);
}

function xtreamStreamUrl(payload, section, item) {
  const prefix = section === "live" ? "live" : section === "movies" ? "movie" : "series";
  return `${xtreamBase(payload)}/${prefix}/${encodeURIComponent(payload.username)}/${encodeURIComponent(payload.password)}/${encodeURIComponent(item.id)}.${encodeURIComponent(item.extension || "mp4")}`;
}

async function initialiseStore() {
  await fsp.mkdir(DATA_DIR, { recursive: true, mode: 0o700 });
  await fsp.mkdir(SYNC_ASSET_DIR, { recursive: true, mode: 0o700 });
  await fsp.mkdir(LOCAL_M3U_DIR, { recursive: true, mode: 0o700 });
  await fsp.mkdir(BACKUP_DIR, { recursive: true, mode: 0o700 });
  await fsp.mkdir(FREEBOX_THUMBNAIL_DIR, { recursive: true, mode: 0o700 });
  try {
    store = JSON.parse(await fsp.readFile(STORE_PATH, "utf8"));
    if (!Array.isArray(store.history)) store.history = [];
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
    await persistStore();
  }
  if (!store.sync || typeof store.sync !== "object") store.sync = {};
  if (!store.tvPresence || typeof store.tvPresence !== "object" || Array.isArray(store.tvPresence)) store.tvPresence = {};
  if (!Array.isArray(store.sync.accounts)) store.sync.accounts = [];
  if (!Array.isArray(store.sync.favorites)) store.sync.favorites = [];
  if (!Array.isArray(store.sync.settings)) store.sync.settings = [];
  if (!Array.isArray(store.sync.watchProgress)) store.sync.watchProgress = [];
  if (!Array.isArray(store.sync.watchedItems)) store.sync.watchedItems = [];
  if (!Array.isArray(store.sync.profileBlobs)) store.sync.profileBlobs = [];
  if (!Array.isArray(store.sync.profileAssets)) store.sync.profileAssets = [];
  if (!Array.isArray(store.sync.profileSecrets)) store.sync.profileSecrets = [];
  if (!Array.isArray(store.sync.profilePins)) store.sync.profilePins = [];
  if (!Array.isArray(store.sync.pairings)) store.sync.pairings = [];
  if (!Array.isArray(store.sync.freeTvRecurringRecords)) store.sync.freeTvRecurringRecords = [];
  if (!Array.isArray(store.sync.events)) store.sync.events = [];
  const latestEventRevision = (events) => events.reduce((latest, item) =>
    Number.isSafeInteger(item.revision) && item.revision > latest ? item.revision : latest, 0);
  if (!Number.isSafeInteger(store.sync.nextRevision) || store.sync.nextRevision < 0) {
    store.sync.nextRevision = latestEventRevision(store.sync.events);
  }
  let revisionFloor = Math.max(store.sync.nextRevision, latestEventRevision(store.sync.events));
  if (typeof store.sync.tokenSecret !== "string" || store.sync.tokenSecret.length < 32) {
    store.sync.tokenSecret = crypto.randomBytes(32).toString("base64url");
  }
  syncSqliteStore = createSyncSqliteStore(DATA_DIR);
  if (!syncSqliteStore && store.sqliteProgressRequired) {
    throw new Error("SQLite est nécessaire pour récupérer les checkpoints récents ; aucun repli vers un JSON ancien.");
  }
  if (syncSqliteStore) {
    const sqliteSnapshot = syncSqliteStore.loadSnapshot();
    if (store.sqliteProgressRequired && !sqliteSnapshot?.value) {
      throw new Error("Snapshot SQLite requis ; aucun repli vers un JSON ancien.");
    }
    revisionFloor = Math.max(revisionFloor, sqliteSnapshot?.revision || 0, syncSqliteStore.latestRevision());
    if (sqliteSnapshot?.value && Number(sqliteSnapshot.revision) >= Number(store.sync.nextRevision || 0)) {
      store.sync = sqliteSnapshot.value;
      if (!Array.isArray(store.sync.events)) store.sync.events = [];
      if (!Number.isSafeInteger(store.sync.nextRevision)) store.sync.nextRevision = sqliteSnapshot.revision || 0;
    } else {
      syncSqliteStore.importEvents(store.sync.events);
    }
    if (typeof syncSqliteStore.saveProgress === "function") store.sqliteProgressRequired = true;
  }
  store.sync.nextRevision = Math.max(revisionFloor, store.sync.nextRevision, latestEventRevision(store.sync.events));
  if (!Array.isArray(store.sync.backups)) store.sync.backups = [];
  if (!Array.isArray(store.sync.backupPolicies)) store.sync.backupPolicies = [];
  if (!Array.isArray(store.sync.manualRestores)) store.sync.manualRestores = [];
  await pruneObsoleteBackups();
  await persistStore();
}

function persistStore(progress = null) {
  if (progress && !progress.retry) {
    const { snapshot, keys } = progress;
    const selected = new Set(keys);
    const event = store.sync.events.at(-1);
    pendingProgressWrites.push(JSON.parse(JSON.stringify({
      revision: event.revision, accountId: snapshot.accountId, profileId: snapshot.profileId,
      updatedAt: snapshot.updatedAt, keys: [...selected], event,
      entries: snapshot.entries.filter(item => selected.has(item.key)),
      tombstones: snapshot.tombstones.filter(item => selected.has(item.key))
    })));
  }
  if (!progress) fullStoreVersion++;
  const writeAttempt = writeQueue.then(async () => {
    const revision = store.sync.nextRevision;
    const version = fullStoreVersion;
    if (progress && syncSqliteStore?.saveProgress && version === persistedFullStoreVersion) {
      if (persistedSyncRevision >= revision) return;
      // The changed rows and their events commit together before replying.
      syncSqliteStore.saveProgress(pendingProgressWrites.filter(item => item.revision <= revision));
      persistedSyncRevision = revision;
      pendingProgressWrites = pendingProgressWrites.filter(item => item.revision > revision);
      if (!progressCheckpointTimer) {
        progressCheckpointTimer = setTimeout(() => {
          progressCheckpointTimer = null;
          persistStore().catch(error => console.error("Checkpoint complet différé :", error.name));
        }, 5 * 60 * 1_000);
        progressCheckpointTimer.unref();
      }
      return;
    }
    compactSyncTombstones();
    if (syncSqliteStore) syncSqliteStore.compact(Date.now() - 30 * 24 * 60 * 60 * 1000, 1_000);
    if (syncSqliteStore) syncSqliteStore.saveSnapshot(store.sync);
    const temporary = `${STORE_PATH}.tmp`;
    await fsp.writeFile(temporary, JSON.stringify(store), { mode: 0o600 });
    await fsp.rename(temporary, STORE_PATH);
    await fsp.chmod(STORE_PATH, 0o600);
    persistedSyncRevision = revision;
    persistedFullStoreVersion = version;
    pendingProgressWrites = pendingProgressWrites.filter(item => item.revision > revision);
    if (progressCheckpointTimer) clearTimeout(progressCheckpointTimer);
    progressCheckpointTimer = null;
  });
  // Preserve this attempt's error for its caller without blocking later saves.
  writeQueue = writeAttempt.catch(() => {});
  return writeAttempt;
}

function compactSyncTombstones() {
  const cutoff = Date.now() - 90 * 24 * 60 * 60 * 1000;
  for (const snapshot of [...(store.sync.favorites || []), ...(store.sync.watchProgress || []), ...(store.sync.watchedItems || [])]) {
    if (!Array.isArray(snapshot.tombstones)) continue;
    snapshot.tombstones = snapshot.tombstones.filter((item) => Number(item.deletedAt) >= cutoff);
  }
}

function appendSyncEvent(accountId, profileId, domain, operation, keys, updatedAt = Date.now(), deviceId = "", deferProgress = false) {
  // A failed non-progress journal write must still force a complete retry.
  if (!deferProgress || !syncSqliteStore?.saveProgress) fullStoreVersion++;
  const revision = ++store.sync.nextRevision;
  store.sync.events.push({
    revision,
    accountId,
    profileId,
    domain,
    operation,
    keys: Array.isArray(keys) ? keys.slice(0, 500) : [],
    updatedAt,
    deviceId: String(deviceId || "").slice(0, 80)
  });
  if (syncSqliteStore && !(deferProgress && syncSqliteStore.saveProgress)) {
    syncSqliteStore.appendEvent(store.sync.events[store.sync.events.length - 1]);
  }
  compactSyncEvents();
  return revision;
}

function compactSyncEvents() {
  const maximum = 10_000;
  const cutoff = Date.now() - 30 * 24 * 60 * 60 * 1000;
  if (store.sync.events.length <= maximum) {
    store.sync.events = store.sync.events.filter((item, index, values) =>
      Number(item.updatedAt) >= cutoff || index >= values.length - 1_000
    );
    return;
  }
  store.sync.events = store.sync.events.slice(-maximum);
}

function requestDeviceId(request) {
  return String(request.headers["x-jojotv-device-id"] || "").trim().slice(0, 80);
}

// Repeated PUTs are common while a TV is in the foreground.  They must be
// idempotent: writing an identical snapshot should not create a new revision
// or rewrite the whole NAS store, otherwise the change feed becomes a hot loop
// and all other sync domains start timing out.
function syncValueEqual(left, right) {
  if (left === right) return true;
  if (left === null || right === null || typeof left !== "object" || typeof right !== "object") return false;
  if (Array.isArray(left) || Array.isArray(right)) {
    return Array.isArray(left) && Array.isArray(right) && left.length === right.length &&
      left.every((value, index) => syncValueEqual(value, right[index]));
  }
  const keys = Object.keys(left);
  return keys.length === Object.keys(right).length && keys.every((key) =>
    Object.prototype.hasOwnProperty.call(right, key) && syncValueEqual(left[key], right[key]));
}

function json(response, status, body) {
  response.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
  response.end(JSON.stringify(body));
}

function text(response, status, body, contentType = "text/plain; charset=utf-8") {
  response.writeHead(status, { "content-type": contentType, "cache-control": "no-store" });
  response.end(body);
}

function readJson(request) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    let tooLarge = false;
    request.on("data", (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        tooLarge = true;
        return;
      }
      chunks.push(chunk);
    });
    request.on("end", () => {
      if (tooLarge) {
        const error = new Error("Requête trop volumineuse.");
        error.statusCode = 413;
        reject(error);
        return;
      }
      const body = size ? Buffer.concat(chunks, size).toString("utf8") : "";
      try { resolve(body ? JSON.parse(body) : {}); } catch { reject(new Error("JSON invalide.")); }
    });
    request.on("error", reject);
  });
}

function readBuffer(request, maxBytes) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    request.on("data", (chunk) => {
      size += chunk.length;
      if (size > maxBytes) {
        request.destroy(new Error("Image HEIC trop volumineuse."));
        return;
      }
      chunks.push(chunk);
    });
    request.on("end", () => resolve(Buffer.concat(chunks)));
    request.on("error", reject);
  });
}

// libheif and Python are already installed on the NAS. Read the small embedded
// image instead of decoding every pixel of the original for a grid thumbnail.
// No thumbnail (or unavailable helper) falls back to the existing converter.
const HEIC_THUMBNAIL_SCRIPT = String.raw`
import ctypes as c, ctypes.util, os, sys
class Error(c.Structure):
    _fields_ = [('code', c.c_int), ('subcode', c.c_int), ('message', c.c_char_p)]
lib = c.CDLL(ctypes.util.find_library('heif'))
ptr = c.c_void_p
def api(name, result, *args):
    function = getattr(lib, name)
    function.restype, function.argtypes = result, args
    return function
def checked(error):
    if error.code: raise RuntimeError(error.message.decode('utf-8', 'replace'))
init = api('heif_init', Error, ptr)
free = api('heif_deinit', None)
alloc = api('heif_context_alloc', ptr)
close = api('heif_context_free', None, ptr)
read = api('heif_context_read_from_file', Error, ptr, c.c_char_p, ptr)
threads = api('heif_context_set_max_decoding_threads', None, ptr, c.c_int)
primary = api('heif_context_get_primary_image_handle', Error, ptr, c.POINTER(ptr))
release = api('heif_image_handle_release', None, ptr)
count = api('heif_image_handle_get_number_of_thumbnails', c.c_int, ptr)
ids = api('heif_image_handle_get_list_of_thumbnail_IDs', c.c_int, ptr, c.POINTER(c.c_uint32), c.c_int)
thumbnail = api('heif_image_handle_get_thumbnail', Error, ptr, c.c_uint32, c.POINTER(ptr))
width = api('heif_image_handle_get_width', c.c_int, ptr)
height = api('heif_image_handle_get_height', c.c_int, ptr)
decode = api('heif_decode_image', Error, ptr, c.POINTER(ptr), c.c_int, c.c_int, ptr)
image_width = api('heif_image_get_width', c.c_int, ptr, c.c_int)
image_height = api('heif_image_get_height', c.c_int, ptr, c.c_int)
plane = api('heif_image_get_plane_readonly', ptr, ptr, c.c_int, c.POINTER(c.c_int))
release_image = api('heif_image_release', None, ptr)
profile_size = api('heif_image_handle_get_raw_color_profile_size', c.c_size_t, ptr)
profile_read = api('heif_image_handle_get_raw_color_profile', Error, ptr, ptr)
def save_profile(handle):
    length = profile_size(handle)
    if 0 < length <= 1024 * 1024:
        profile = c.create_string_buffer(length)
        checked(profile_read(handle, profile))
        with open(sys.argv[2] + '.icc', 'wb') as output:
            os.chmod(sys.argv[2] + '.icc', 0o600)
            output.write(profile.raw)
checked(init(None))
context, handle, image = alloc(), ptr(), ptr()
handles = []
try:
    checked(read(context, os.fsencode(sys.argv[1]), None))
    threads(context, 2)
    checked(primary(context, c.byref(handle)))
    save_profile(handle)
    if len(sys.argv) > 3 and sys.argv[3] == 'profile': sys.exit(0)
    length = min(count(handle), 16)
    if not length: raise RuntimeError('No embedded thumbnail')
    items = (c.c_uint32 * length)()
    length = ids(handle, items, length)
    for item in items[:length]:
        candidate = ptr()
        checked(thumbnail(handle, item, c.byref(candidate)))
        handles.append(candidate)
    usable = [item for item in handles if 128 <= max(width(item), height(item)) <= 1024]
    if not usable: raise RuntimeError('No suitable embedded thumbnail')
    selected = max(usable, key=lambda item: width(item) * height(item))
    checked(decode(selected, c.byref(image), 1, 10, None))
    w, h, stride = image_width(image, 10), image_height(image, 10), c.c_int()
    pixels = plane(image, 10, c.byref(stride))
    if not pixels or not 0 < w <= 1024 or not 0 < h <= 1024 or stride.value < w * 3:
        raise RuntimeError('Invalid thumbnail plane')
    with open(sys.argv[2], 'wb') as output:
        os.chmod(sys.argv[2], 0o600)
        output.write(('P6\n%d %d\n255\n' % (w, h)).encode('ascii'))
        for row in range(h): output.write(c.string_at(pixels + row * stride.value, w * 3))
    if profile_size(selected): save_profile(selected)
finally:
    if image: release_image(image)
    for item in handles: release(item)
    if handle: release(handle)
    close(context)
    free()
`;

async function pruneHeicPreviews() {
  if (Date.now() - heicPreviewPrunedAt < 10_000) return;
  heicPreviewPrunedAt = Date.now();
  const entries = await fsp.readdir(HEIC_PREVIEW_DIR);
  const files = (await Promise.all(entries.filter((name) => /^[a-f0-9]{64}\.jpg$/.test(name)).map(async (name) => {
    const file = path.join(HEIC_PREVIEW_DIR, name);
    const stat = await fsp.stat(file).catch(() => null);
    return stat && stat.isFile() ? { file, size: stat.size, modified: stat.mtimeMs } : null;
  }))).filter(Boolean).sort((left, right) => left.modified - right.modified);
  let size = files.reduce((total, file) => total + file.size, 0);
  for (const file of files) {
    if (size <= 128 * 1024 * 1024) break;
    await fsp.unlink(file.file).catch(() => {});
    size -= file.size;
  }
}

async function convertHeicPreview(source, maxDimension, cacheFile, sourceFile = null) {
  // Keep thumbnail capacity available during a slow full-resolution decode.
  const fullResolution = maxDimension > 320;
  if (fullResolution) {
    if (heicFullDecodeCount >= 1) await new Promise((resolve) => heicFullDecodeWaiters.push(resolve));
    else heicFullDecodeCount++;
  } else {
    if (heicDecodeCount >= 2) await new Promise((resolve) => heicDecodeWaiters.push(resolve));
    else heicDecodeCount++;
  }
  const token = crypto.randomUUID();
  const input = sourceFile || path.join("/tmp", `jojotv-${token}.heic`);
  const thumbnail = path.join("/tmp", `jojotv-${token}-thumbnail.ppm`);
  const thumbnailProfile = `${thumbnail}.icc`;
  const output = path.join("/tmp", `jojotv-${token}-preview.jpg`);
  const pendingCache = `${cacheFile}.${token}.tmp`;
  try {
    if (!sourceFile) await fsp.writeFile(input, source, { mode: 0o600 });
    let conversionInput = `${input}[0]`;
    try {
      const mode = maxDimension <= 320 ? [] : ["profile"];
      await execFileAsync("python3", ["-c", HEIC_THUMBNAIL_SCRIPT, input, thumbnail, ...mode], { timeout: 5_000, maxBuffer: 64 * 1024 });
      if (maxDimension <= 320) conversionInput = thumbnail;
    } catch (_) { /* HEIC without an embedded thumbnail: use the original. */ }
    const hasProfile = await fsp.access(thumbnailProfile).then(() => true, () => false);
    await execFileAsync(
      "convert",
      ["-limit", "thread", "2", conversionInput, "-auto-orient", "-resize", `${maxDimension}x${maxDimension}>`,
        "+profile", "exif", "+profile", "xmp", "+profile", "iptc", ...(hasProfile ? ["-profile", thumbnailProfile] : []),
        "-quality", maxDimension > 320 ? "92" : "82", output],
      { timeout: 30_000, maxBuffer: 1024 * 1024 }
    );
    const preview = await fsp.readFile(output);
    await fsp.mkdir(HEIC_PREVIEW_DIR, { recursive: true, mode: 0o700 });
    await fsp.writeFile(pendingCache, preview, { mode: 0o600 });
    await fsp.rename(pendingCache, cacheFile);
    await pruneHeicPreviews().catch(() => {});
    return preview;
  } finally {
    const temporaryFiles = [thumbnail, thumbnailProfile, output, pendingCache];
    if (!sourceFile) temporaryFiles.push(input);
    await Promise.all(temporaryFiles.map((file) => fsp.unlink(file).catch(() => {})));
    const next = (fullResolution ? heicFullDecodeWaiters : heicDecodeWaiters).shift();
    if (next) next();
    else if (fullResolution) heicFullDecodeCount--;
    else heicDecodeCount--;
  }
}

async function heicPreview(request, maxDimension) {
  const source = await readBuffer(request, MAX_HEIC_BODY_BYTES);
  if (!source.length) throw new Error("Image HEIC vide.");
  const key = crypto.createHash("sha256").update(`heic-v2:${maxDimension}:`).update(source).digest("hex");
  const cacheFile = path.join(HEIC_PREVIEW_DIR, `${key}.jpg`);
  const cached = await fsp.readFile(cacheFile).catch(() => null);
  if (cached) return cached;
  if (heicPreviewJobs.has(key)) return heicPreviewJobs.get(key);
  const job = convertHeicPreview(source, maxDimension, cacheFile);
  heicPreviewJobs.set(key, job);
  try { return await job; }
  finally { heicPreviewJobs.delete(key); }
}

async function freeboxPhotoPreview(accountId, profileId, smbPath, maxDimension) {
  // Resolve both paths: an authenticated request may only read HEIC files from
  // the mounted Photos folder, including when a symbolic link is encountered.
  const value = String(smbPath || "");
  if (value.length > 4096 || value.includes("\0") || !/^\/Freebox\/Photos\/.+\.(heic|heif)$/i.test(value)) {
    throw syncError("Chemin photo Freebox invalide.");
  }
  const root = await fsp.realpath(FREEBOX_PHOTO_DIR);
  const relative = value.replace(/^\/Freebox\/Photos\//i, "");
  const candidate = path.resolve(root, relative);
  if (!candidate.startsWith(root + path.sep)) throw syncError("Chemin photo Freebox invalide.", 403);
  const file = await fsp.realpath(candidate);
  if (!file.startsWith(root + path.sep)) throw syncError("Chemin photo Freebox invalide.", 403);
  const stat = await fsp.stat(file);
  if (!stat.isFile() || !stat.size || stat.size > MAX_HEIC_BODY_BYTES) throw syncError("Photo Freebox invalide.");
  const key = crypto.createHash("sha256").update(JSON.stringify({
    version: "freebox-photo-v2", accountId, profileId, path: relative,
    size: stat.size, modified: stat.mtimeMs, maxDimension
  })).digest("hex");
  const cacheFile = path.join(HEIC_PREVIEW_DIR, `${key}.jpg`);
  const cached = await fsp.readFile(cacheFile).catch(() => null);
  if (cached) return cached;
  if (heicPreviewJobs.has(key)) return heicPreviewJobs.get(key);
  // The converter opens the mounted original read-only. Never copy its full
  // contents through the TV or include it in temporary-file cleanup.
  const job = convertHeicPreview(null, maxDimension, cacheFile, file);
  heicPreviewJobs.set(key, job);
  try { return await job; }
  finally { heicPreviewJobs.delete(key); }
}

function requestTv(target, method, route, body, timeout = 8000) {
  return new Promise((resolve, reject) => {
    const data = body ? JSON.stringify(body) : null;
    const request = http.request({
      host: target.tv,
      port: target.port,
      path: route,
      method,
      timeout,
      headers: data ? { "content-type": "application/json", "content-length": Buffer.byteLength(data) } : undefined
    }, (response) => {
      let raw = "";
      response.setEncoding("utf8");
      response.on("data", (chunk) => { raw += chunk; });
      response.on("end", () => {
        let parsed;
        try { parsed = raw ? JSON.parse(raw) : {}; } catch { parsed = { message: raw }; }
        resolve({ statusCode: response.statusCode || 500, body: parsed });
      });
    });
    request.on("timeout", () => {
      const error = new Error("Jojo Home ne répond pas.");
      error.retryable = true;
      request.destroy(error);
    });
    request.on("error", (error) => {
      if (["ECONNREFUSED", "EHOSTUNREACH", "ENETUNREACH", "ETIMEDOUT", "ECONNRESET"].includes(error.code)) {
        error.retryable = true;
      }
      reject(error);
    });
    if (data) request.write(data);
    request.end();
  });
}

async function testTv(target) {
  try {
    const ping = await requestTv(target, "GET", "/api/ping");
    if (ping.statusCode >= 200 && ping.statusCode < 300) {
      return { reachable: true, message: ping.body.message || "JojoHome IPTV répond directement." };
    }
  } catch (_) { }
  return {
    reachable: false,
    message: `Jojo Home IPTV ne répond pas à ${target.tv}:${target.port}. Vérifie l’adresse et que Jojo Home est actif sur la TV.`
  };
}

function publicRecord(record) {
  return {
    id: record.id,
    createdAt: record.createdAt,
    updatedAt: record.updatedAt,
    target: record.target,
    provider: record.action
      ? { type: "action", name: record.action.action, providerId: record.action.providerId }
      : { type: record.payload.type, name: record.payload.name },
    remoteId: record.remoteId || null,
    status: record.status,
    message: record.message
  };
}

function syncError(message, statusCode = 400) {
  const error = new Error(message);
  error.statusCode = statusCode;
  return error;
}

function normaliseSyncEmail(value) {
  const email = String(value || "").trim().toLowerCase();
  if (!/^\S+@\S+\.\S+$/.test(email)) throw syncError("Adresse e-mail invalide.");
  return email;
}

function passwordHash(password) {
  const salt = crypto.randomBytes(16);
  const hash = crypto.scryptSync(String(password || ""), salt, 64);
  return `${salt.toString("base64url")}:${hash.toString("base64url")}`;
}

function passwordMatches(password, encoded) {
  const [saltText, hashText] = String(encoded || "").split(":");
  if (!saltText || !hashText) return false;
  const actual = crypto.scryptSync(String(password || ""), Buffer.from(saltText, "base64url"), 64);
  const expected = Buffer.from(hashText, "base64url");
  return expected.length === actual.length && crypto.timingSafeEqual(expected, actual);
}

function syncTokenFor(account) {
  const payload = Buffer.from(JSON.stringify({ accountId: account.id, exp: Date.now() + 180 * 24 * 60 * 60 * 1000 })).toString("base64url");
  const signature = crypto.createHmac("sha256", store.sync.tokenSecret).update(payload).digest("base64url");
  return `${payload}.${signature}`;
}

function pairingSecret() {
  return crypto.randomBytes(32).toString("base64url");
}

function pairingSecretDigest(value) {
  return crypto.createHash("sha256").update(String(value || "")).digest("base64url");
}

function pairingSecretMatches(actual, expectedDigest) {
  const left = Buffer.from(pairingSecretDigest(actual));
  const right = Buffer.from(String(expectedDigest || ""));
  return left.length === right.length && left.length > 0 && crypto.timingSafeEqual(left, right);
}

function pruneExpiredPairings() {
  const now = Date.now();
  const before = store.sync.pairings.length;
  store.sync.pairings = store.sync.pairings.filter((pairing) => Number(pairing.expiresAt) > now);
  return before !== store.sync.pairings.length;
}

function pairingForDevice(id, deviceSecret) {
  pruneExpiredPairings();
  const pairing = store.sync.pairings.find((item) => item.id === id);
  if (!pairing || !pairingSecretMatches(deviceSecret, pairing.deviceSecretHash)) {
    throw syncError("Code d'appairage NAS invalide ou expiré.", 404);
  }
  return pairing;
}

function pairingForApproval(id, approvalCode) {
  pruneExpiredPairings();
  const pairing = store.sync.pairings.find((item) => item.id === id);
  if (!pairing || !pairingSecretMatches(approvalCode, pairing.approvalCodeHash)) {
    throw syncError("Code QR NAS invalide ou expiré.", 404);
  }
  return pairing;
}

function pairingPage(id, approvalCode) {
  const safeId = JSON.stringify(String(id || ""));
  const safeCode = JSON.stringify(String(approvalCode || ""));
  return `<!doctype html><html lang="fr"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Jojo Home — Appairage NAS</title><style>body{margin:0;background:#101a2e;color:#f5f7ff;font:16px system-ui,sans-serif;display:grid;min-height:100vh;place-items:center}.card{width:min(420px,calc(100% - 36px));padding:28px;border-radius:18px;background:#182642;box-shadow:0 12px 40px #0008}h1{margin-top:0}label,input,button{display:block;width:100%;box-sizing:border-box}label{margin-top:16px;color:#c5d2ee}input{margin-top:6px;padding:12px;border:1px solid #43567f;border-radius:10px;background:#0e1729;color:#fff}button{margin-top:22px;padding:13px;border:0;border-radius:10px;background:#FFB309;color:#151515;font-weight:700}.hint{color:#b9c6df}.status{min-height:24px;margin-top:16px}</style><main class="card"><h1>Ajouter cette TV à Jojo Home</h1><p class="hint">Connecte-toi avec ton compte NAS puis valide l'appairage. La TV récupérera ensuite ses profils et sa synchronisation.</p><form id="form"><label>E-mail NAS<input id="email" type="email" required autocomplete="email"></label><label>Mot de passe NAS<input id="password" type="password" required autocomplete="current-password"></label><button>Autoriser cette TV</button></form><p id="status" class="status"></p></main><script>const id=${safeId},code=${safeCode},form=document.getElementById('form'),status=document.getElementById('status');form.addEventListener('submit',async e=>{e.preventDefault();status.textContent='Validation…';const r=await fetch('/api/sync/pairings/'+encodeURIComponent(id)+'/approve',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({approvalCode:code,email:document.getElementById('email').value,password:document.getElementById('password').value})});const data=await r.json().catch(()=>({}));status.textContent=r.ok?'TV autorisée. Tu peux revenir sur Jojo Home.':(data.error||'Validation impossible.');if(r.ok)form.style.display='none'});</script></html>`;
}

function syncAccountFromRequest(request) {
  const token = String(request.headers.authorization || "").replace(/^Bearer\s+/i, "");
  const [payload, signature] = token.split(".");
  if (!payload || !signature) throw syncError("Connexion NAS requise.", 401);
  const expected = crypto.createHmac("sha256", store.sync.tokenSecret).update(payload).digest("base64url");
  if (expected.length !== signature.length || !crypto.timingSafeEqual(Buffer.from(expected), Buffer.from(signature))) {
    throw syncError("Session NAS invalide.", 401);
  }
  let data;
  try { data = JSON.parse(Buffer.from(payload, "base64url").toString("utf8")); } catch { throw syncError("Session NAS invalide.", 401); }
  if (!data.accountId || !Number.isFinite(data.exp) || data.exp < Date.now()) throw syncError("Session NAS expirée.", 401);
  const account = store.sync.accounts.find((item) => item.id === data.accountId);
  if (!account) throw syncError("Compte NAS introuvable.", 401);
  return account;
}

function syncAccountPublic(account) {
  return { id: account.id, email: account.email, createdAt: account.createdAt };
}

function syncFavoritesPayload(value) {
  if (!Array.isArray(value)) throw syncError("Liste de favoris invalide.");
  if (value.length > 20_000) throw syncError("Trop de favoris.");
  const seen = new Set();
  return value.map((item) => {
    const providerKey = String(item.providerKey || "").trim();
    const contentType = String(item.contentType || "").trim();
    const remoteContentId = String(item.remoteContentId || "").trim();
    if (!providerKey || !remoteContentId || !["LIVE", "MOVIE", "SERIES"].includes(contentType)) {
      throw syncError("Favori IPTV invalide.");
    }
    const key = `${providerKey}|${contentType}|${remoteContentId}`;
    if (seen.has(key)) return null;
    seen.add(key);
    return {
      providerKey,
      contentType,
      remoteContentId,
      sortOrder: Number.isInteger(item.sortOrder) ? item.sortOrder : 0,
      addedAt: Number.isFinite(item.addedAt) ? item.addedAt : Date.now(),
      updatedAt: Number.isFinite(item.updatedAt) ? item.updatedAt : Date.now()
    };
  }).filter(Boolean);
}

function syncFavoriteKey(item) {
  return `${item.providerKey}|${item.contentType}|${item.remoteContentId}`;
}

function syncFavoriteKeys(value) {
  if (!Array.isArray(value) || value.length > 20_000) throw syncError("Suppressions de favoris invalides.");
  return [...new Set(value.map((key) => String(key || "").trim()).filter(Boolean))];
}

function syncProfileId(value) {
  const profileId = Number(value);
  if (!Number.isInteger(profileId) || profileId <= 0 || profileId > 100_000) throw syncError("Profil NAS invalide.");
  return profileId;
}

function syncFavoriteSnapshot(accountId, profileId) {
  return store.sync.favorites.find((item) => item.accountId === accountId && item.profileId === profileId) || null;
}

function syncSettingsSnapshot(accountId, profileId) {
  return store.sync.settings.find((item) => item.accountId === accountId && item.profileId === profileId) || null;
}

function syncSettingsPayload(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw syncError("Réglages de profil NAS invalides.");
  const encoded = JSON.stringify(value);
  if (encoded.length > 8 * 1024 * 1024) throw syncError("Réglages de profil trop volumineux.");
  return value;
}

function syncBackupApp(value) {
  const app = String(value || "").trim().toLowerCase();
  if (!BACKUP_APPS.has(app)) throw syncError("Application de sauvegarde invalide.");
  return app;
}

function syncBackupFilePath(accountId, app, backupId) {
  const accountDirectory = crypto.createHash("sha256").update(String(accountId)).digest("hex").slice(0, 24);
  return path.join(BACKUP_DIR, accountDirectory, app, `${backupId}.json`);
}

async function accountBackupPayload(accountId, app, createdAt) {
  // Keep the backup independent from the device: settings and visual assets
  // deliberately selected by the user must survive a restore on another TV.
  // Media files, APKs and ephemeral image-loader caches remain excluded.
  const copyForAccount = (collection) => collection.filter((item) => item.accountId === accountId);
  const profileAssets = [];
  const embeddedAssets = [];
  let embeddedAssetsBytes = 0;
  for (const asset of copyForAccount(store.sync.profileAssets)) {
    const data = await fsp.readFile(syncAssetPath(accountId, asset.profileId, asset.key)).catch(() => null);
    if (!data || data.length > MAX_BACKUP_EMBEDDED_ASSET_BYTES || embeddedAssetsBytes + data.length > MAX_BACKUP_EMBEDDED_ASSETS_BYTES) continue;
    profileAssets.push(asset);
    embeddedAssetsBytes += data.length;
    embeddedAssets.push({
      profileId: asset.profileId,
      key: asset.key,
      contentType: asset.contentType || "application/octet-stream",
      data: data.toString("base64")
    });
  }
  return {
    schemaVersion: 1,
    app,
    createdAt,
    sync: {
      favorites: copyForAccount(store.sync.favorites),
      settings: copyForAccount(store.sync.settings),
      watchProgress: copyForAccount(store.sync.watchProgress),
      watchedItems: copyForAccount(store.sync.watchedItems),
      profileBlobs: copyForAccount(store.sync.profileBlobs),
      profileSecrets: copyForAccount(store.sync.profileSecrets),
      profilePins: copyForAccount(store.sync.profilePins),
      profileAssets,
      embeddedAssets
    }
  };
}

async function removeBackupFiles(backups) {
  const root = path.resolve(BACKUP_DIR) + path.sep;
  for (const backup of backups) {
    const file = path.resolve(syncBackupFilePath(backup.accountId, backup.app, backup.id));
    if (!file.startsWith(root)) throw new Error("Chemin de sauvegarde invalide.");
    await fsp.unlink(file).catch((error) => { if (error.code !== "ENOENT") throw error; });
  }
  const removed = new Set(backups);
  store.sync.backups = store.sync.backups.filter((item) => !removed.has(item));
}

async function pruneAccountBackups(accountId) {
  const stale = store.sync.backups
    .filter((item) => item.accountId === accountId && item.app === "jojohome")
    .reverse()
    .sort((left, right) => right.createdAt - left.createdAt)
    .slice(BACKUP_RETENTION);
  await removeBackupFiles(stale);
}

async function pruneObsoleteBackups() {
  await removeBackupFiles(store.sync.backups.filter((item) => item.app === "jojotv"));
  // Include old backup files whose metadata is no longer in the NAS index.
  const root = path.resolve(BACKUP_DIR);
  for (const account of await fsp.readdir(root, { withFileTypes: true })) {
    if (!account.isDirectory() || !/^[a-f0-9]{24}$/.test(account.name)) continue;
    const legacy = path.resolve(root, account.name, "jojotv");
    if (!legacy.startsWith(root + path.sep)) throw new Error("Chemin de sauvegarde invalide.");
    await fsp.rm(legacy, { recursive: true, force: true });
  }
  store.sync.backupPolicies = store.sync.backupPolicies.filter((item) => item.app !== "jojotv");
  store.sync.manualRestores = store.sync.manualRestores.filter((item) => item.app !== "jojotv");
  const accounts = new Set(store.sync.backups.filter((item) => item.app === "jojohome").map((item) => item.accountId));
  for (const accountId of accounts) await pruneAccountBackups(accountId);
}

async function createAccountBackup(accountId, app, reason) {
  app = syncBackupApp(app);
  const createdAt = Date.now();
  const id = `${createdAt}-${crypto.randomUUID()}`;
  const payload = await accountBackupPayload(accountId, app, createdAt);
  const encoded = Buffer.from(JSON.stringify(payload), "utf8");
  const target = syncBackupFilePath(accountId, app, id);
  await fsp.mkdir(path.dirname(target), { recursive: true, mode: 0o700 });
  const temporary = `${target}.tmp`;
  await fsp.writeFile(temporary, encoded, { mode: 0o600 });
  await fsp.rename(temporary, target);

  const metadata = { id, accountId, app, createdAt, size: encoded.length, reason };
  store.sync.backups.push(metadata);
  await pruneAccountBackups(accountId);
  await persistStore();
  return metadata;
}

async function restoreAccountBackup(accountId, app, backupId) {
  app = syncBackupApp(app);
  const metadata = store.sync.backups.find((item) => item.accountId === accountId && item.app === app && item.id === backupId);
  if (!metadata) throw syncError("Sauvegarde introuvable.", 404);
  const raw = await fsp.readFile(syncBackupFilePath(accountId, app, backupId), "utf8").catch(() => null);
  if (!raw) throw syncError("Fichier de sauvegarde absent.", 404);
  const payload = JSON.parse(raw);
  if (payload?.schemaVersion !== 1 || payload.app !== app || !payload.sync) throw syncError("Sauvegarde invalide.");
  const keys = ["favorites", "settings", "watchProgress", "watchedItems", "profileBlobs", "profileSecrets", "profilePins", "profileAssets"];
  const previousAssets = store.sync.profileAssets.filter((item) => item.accountId === accountId);
  keys.forEach((key) => {
    const restored = Array.isArray(payload.sync[key]) ? payload.sync[key].filter((item) => item.accountId === accountId) : [];
    store.sync[key] = store.sync[key].filter((item) => item.accountId !== accountId).concat(restored);
  });
  await Promise.all(previousAssets.map((item) => fsp.unlink(syncAssetPath(accountId, item.profileId, item.key)).catch(() => {})));
  const validAssets = new Set(
    store.sync.profileAssets
      .filter((item) => item.accountId === accountId)
      .map((item) => `${item.profileId}:${item.key}`)
  );
  for (const asset of Array.isArray(payload.sync.embeddedAssets) ? payload.sync.embeddedAssets : []) {
    const profileId = Number(asset?.profileId);
    const key = String(asset?.key || "");
    if (!Number.isInteger(profileId) || profileId <= 0 || !/^[a-z0-9_-]{1,64}$/i.test(key)) continue;
    if (!validAssets.has(`${profileId}:${key}`) || typeof asset.data !== "string") continue;
    const data = Buffer.from(asset.data, "base64");
    if (!data.length || data.length > MAX_SYNC_ASSET_BYTES) continue;
    const target = syncAssetPath(accountId, profileId, key);
    const temporary = `${target}.tmp`;
    await fsp.writeFile(temporary, data, { mode: 0o600 });
    await fsp.rename(temporary, target);
  }
  await persistStore();
  return metadata;
}

function companionProfilesFor(accountId) {
  const directory = store.sync.profileBlobs.find((item) =>
    item.accountId === accountId && item.profileId === 1 && item.key === "profiles"
  );
  let profiles = [];
  try {
    const json = String(directory?.value?.json || "");
    profiles = JSON.parse(json);
  } catch (_) { }
  const knownIds = new Set([
    ...profiles.map((item) => Number(item?.id)),
    ...store.sync.favorites.filter((item) => item.accountId === accountId).map((item) => item.profileId),
    ...store.sync.watchProgress.filter((item) => item.accountId === accountId).map((item) => item.profileId)
  ].filter((id) => Number.isInteger(id) && id > 0));
  return [...knownIds].sort((a, b) => a - b).map((profileId) => {
    const profile = profiles.find((item) => Number(item?.id) === profileId) || {};
    const favorite = syncFavoriteSnapshot(accountId, profileId);
    return {
      profileId,
      name: String(profile.name || `Profil ${profileId}`).slice(0, 80),
      avatarColorHex: String(profile.avatarColorHex || "#1E88E5"),
      iptvFavorites: Array.isArray(favorite?.favorites) ? favorite.favorites.length : 0,
      liveFavorites: Array.isArray(favorite?.favorites) ? favorite.favorites.filter((item) => item.contentType === "LIVE").length : 0,
      movieFavorites: Array.isArray(favorite?.favorites) ? favorite.favorites.filter((item) => item.contentType === "MOVIE").length : 0,
      seriesFavorites: Array.isArray(favorite?.favorites) ? favorite.favorites.filter((item) => item.contentType === "SERIES").length : 0
    };
  });
}

function companionHomeAccountId(records, id) {
  let current = id;
  const seen = new Set();
  while (!seen.has(current)) {
    seen.add(current);
    const next = records["accountAlias:" + current]?.value;
    if (next == null) return current;
    current = next;
  }
  return id;
}

function companionAccountOrnaments() {
  // Matches the named palette in MainActivity.themeColorChoices()/themeColor().
  return [
    {"name": "Bleu sombre", "hex": "#202F52"},
    {"name": "Gris foncé", "hex": "#393F49"},
    {"name": "Vert minéral", "hex": "#2E7D32"},
    {"name": "Rouge pétant", "hex": "#DC3F3F"},
    {"name": "Jaune moutarde", "hex": "#D3A033"},
    {"name": "Turquoise", "hex": "#009999"},
    {"name": "Violet profond", "hex": "#673AB7"},
    {"name": "Orange brûlé", "hex": "#C45F20"},
    {"name": "Bleu acier", "hex": "#4A6996"},
    {"name": "Gris clair", "hex": "#BDBDBD"},
    {"name": "Rose", "hex": "#F06292"},
    {"name": "Indigo", "hex": "#3F51B5"},
    {"name": "Bleu", "hex": "#2196F3"},
    {"name": "Cyan", "hex": "#00BCD4"},
    {"name": "Sarcelle", "hex": "#009688"},
    {"name": "Vert", "hex": "#4CAF50"},
    {"name": "Citron", "hex": "#CDDC39"},
    {"name": "Jaune", "hex": "#FFEB3B"},
    {"name": "Blanc", "hex": "#FFFFFF"},
    {"name": "Noir", "hex": "#08080A"},
    {"name": "Noir AMOLED", "hex": "#000000"}
  ];
}

async function companionHomeAccountsFor(accountId, requestedId = null) {
  const snapshot = syncProfileSecretSnapshot(accountId, 1, "jojo-home-iptv");
  if (!snapshot) return [];
  const records = mergeHomeIptvSnapshot(null, decryptSyncSecret(snapshot.value)).records;
  const resolveId = id => companionHomeAccountId(records, id);
  const valueOf = record => {
    try { return JSON.parse(record.value); } catch (_) { return null; }
  };
  const entries = Object.entries(records);
  const sources = new Map(entries.filter(([key, record]) => key.startsWith("source:") && record.value != null)
    .map(([key, record]) => [key.slice("source:".length), valueOf(record) || {}]));
  const favorites = new Map();
  const include = (key, record) => {
    const previous = favorites.get(key);
    favorites.set(key, previous ? mergeHomeIptvSnapshot({ records: { item: previous } }, { records: { item: record } }).records.item : record);
  };
  const legacyId = resolveId(records.legacyAccount?.value || "");
  const otherWrites = new Set();
  for (const [key, record] of entries) {
    const match = key.match(/^account:([^:]+):favorite:(.+)$/);
    if (!match) continue;
    const id = resolveId(match[1]);
    include("account:" + id + ":favorite:" + match[2], record);
    if (id !== legacyId) otherWrites.add(JSON.stringify(["favorite:" + match[2], record.changedAt, record.writer, record.value ?? null]));
  }
  // The old unscoped mirror belongs only to the original account, as on the TV.
  if (legacyId) for (const [key, record] of entries) {
    if (!key.startsWith("favorite:")) continue;
    const scoped = favorites.get("account:" + legacyId + ":" + key);
    const signature = JSON.stringify([key, record.changedAt, record.writer, record.value ?? null]);
    if (!otherWrites.has(signature) || scoped && scoped.changedAt === record.changedAt && scoped.writer === record.writer && scoped.value === record.value) {
      include("account:" + legacyId + ":" + key, record);
    }
  }
  const catalog = await readAvatarCatalog();
  const avatars = new Map(catalog.avatars.map(avatar => [avatar.id, avatar]));
  return entries.filter(([key, record]) => key.startsWith("profile:") && record.value != null &&
      resolveId(key.slice("profile:".length)) === key.slice("profile:".length) &&
      (requestedId === null || key.slice("profile:".length) === resolveId(requestedId)))
    .map(([key, record]) => {
      const profile = valueOf(record);
      if (!profile || typeof profile.name !== "string" || !profile.name.trim()) return null;
      const id = key.slice("profile:".length);
      const prefix = "account:" + id + ":favorite:";
      const items = new Map();
      for (const [favoriteKey, favoriteRecord] of favorites) {
        if (!favoriteKey.startsWith(prefix) || favoriteRecord.value == null) continue;
        const item = valueOf(favoriteRecord);
        if (item && typeof item.id === "string" && sources.has(item.sourceId) && ["LIVE", "MOVIE", "SERIES", "EPISODE"].includes(item.type)) items.set(item.id, item);
      }
      const types = [...items.values()].map(item => item.type);
      return { id, name: profile.name.trim().slice(0, 80), avatarId: typeof profile.avatar === "string" ? profile.avatar : "",
        ornamentColor: companionAccountOrnaments().find(color => color.hex === String(profile.ornamentColor || "").toUpperCase())?.hex || "#D3A033",
        avatar: avatars.get(profile.avatar) || catalog.avatars.find(avatar => avatar.emoji && avatar.emoji === profile.avatar) || null, iptvFavorites: types.length,
        liveFavorites: types.filter(type => type === "LIVE").length, movieFavorites: types.filter(type => type === "MOVIE").length,
        seriesFavorites: types.filter(type => type === "SERIES" || type === "EPISODE").length,
        ...(requestedId === null ? {} : { items: [...items.values()].map(item => ({ item, source: sources.get(item.sourceId) })), legacy: id === legacyId }) };
    }).filter(Boolean).sort((left, right) => left.id < right.id ? -1 : left.id > right.id ? 1 : 0);
}

async function companionHomeAccountFavorites(accountId, requestedId) {
  const [result] = await companionHomeAccountsFor(accountId, requestedId);
  if (!result) throw syncError("Compte introuvable.", 404);
  const { items, legacy = false, ...account } = result;
  return { account, items, legacy };
}

function companionFavoriteArtwork(item, source) {
  for (const value of [item.artwork, item.backdrop]) {
    if (typeof value !== "string" || !value.trim()) continue;
    try {
      const url = new URL(value.trim(), source.url || undefined);
      if (["http:", "https:"].includes(url.protocol)) return url;
    } catch (_) {}
  }
  return null;
}

function companionFavoriteDetails({ item, source }, accountId) {
  const field = (key, limit = 160) => typeof item[key] === "string" ? item[key].trim().slice(0, limit) : "";
  return { id: item.id, type: item.type, title: field("title", 300) || ({ LIVE: "Chaîne", MOVIE: "Film", SERIES: "Série", EPISODE: "Épisode" })[item.type],
    provider: typeof source.name === "string" ? source.name.trim().slice(0, 120) : "",
    category: field("categoryName"), year: field("year", 40), genre: field("genre"), rating: field("rating", 40), duration: field("duration", 40),
    description: field("description", 2000), cast: field("cast", 1000), director: field("director", 300),
    season: Number.isInteger(item.season) && item.season > 0 ? item.season : 0,
    episode: Number.isInteger(item.episode) && item.episode > 0 ? item.episode : 0,
    artwork: companionFavoriteArtwork(item, source) ? "/api/companion/accounts/" + encodeURIComponent(accountId) + "/favorites/" + encodeURIComponent(item.id) + "/artwork" : "" };
}

function companionPlaybackId(source, type, providerId) {
  return `iptv:${source.id}:${type.toLowerCase()}:${crypto.createHash("sha256").update(String(providerId)).digest("hex")}`;
}

function companionPlaybackContentId(id, accountId, legacy) {
  return id.startsWith("iptv:") && accountId && !legacy ? `iptv:account:${accountId}:${id.slice(5)}` : id;
}

function companionProfileNameKey(value) {
  return String(value || "").normalize("NFD").replace(/[\u0300-\u036f]/g, "").trim().toLocaleLowerCase("fr");
}

async function companionPlaybackFetch(address, headers, signal) {
  let url = new URL(address), forwarded = new Headers(headers || {});
  for (const name of ["host", "connection", "content-length"]) forwarded.delete(name);
  for (let redirects = 0; redirects <= 5; redirects++) {
    if (!["http:", "https:"].includes(url.protocol)) throw syncError("Ce flux nécessite un relais HTTP avant sa lecture dans le navigateur.", 422);
    if (url.username || url.password) {
      forwarded.set("authorization", "Basic " + Buffer.from(decodeURIComponent(url.username) + ":" + decodeURIComponent(url.password)).toString("base64"));
      url.username = ""; url.password = "";
    }
    const response = await fetch(url, { headers: forwarded, redirect: "manual", signal });
    if (![301, 302, 303, 307, 308].includes(response.status)) return { response, url: url.href, headers: Object.fromEntries(forwarded) };
    const location = response.headers.get("location");
    await response.body?.cancel();
    if (!location) throw syncError("Le fournisseur n’a pas fourni d’adresse de lecture.", 502);
    const next = new URL(location, url);
    if (next.origin !== url.origin) forwarded = new Headers([...forwarded].filter(([name]) => ["user-agent", "referer", "range", "accept"].includes(name)));
    url = next;
  }
  throw syncError("Le fournisseur redirige ce flux trop souvent.", 502);
}

async function companionPlaybackJson(url, headers) {
  try {
    const { response } = await companionPlaybackFetch(url, headers, AbortSignal.timeout(20_000));
    if (!response.ok) { await response.body?.cancel(); throw syncError(`Le fournisseur répond HTTP ${response.status}.`, 502); }
    return await response.json();
  } catch (error) { throw error.statusCode ? error : syncError("Le fournisseur IPTV est inaccessible ou a envoyé une réponse incompatible.", 502); }
}

async function companionStalkerSession(source) {
  const original = new URL(source.url), root = original.pathname.replace(/\/+$/, "").replace(/\/c$/, "");
  const endpoints = original.pathname.endsWith(".php") ? [original] : [...new Set([root + "/portal.php", root + "/server/load.php", root + "/stalker_portal/server/load.php"])].map(value => new URL(value, original));
  for (const endpoint of endpoints) {
    let token = "";
    const cookies = new Map();
    const headers = (auth = true, profile = false) => {
      const values = new Headers(source.headers || {});
      if (!values.has("user-agent")) values.set("user-agent", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 MAG200 stbapp ver: 2 rev: 250 Safari/533.3");
      values.set("x-user-agent", "Model: MAG254; Link: WiFi"); values.set("referer", source.url);
      const pairs = ["mac=" + (endpoint.pathname.includes("stalker_portal") ? encodeURIComponent(source.mac) : source.mac), "stb_lang=fr", "timezone=Europe%2FParis", ...[...cookies].map(([name, value]) => name + "=" + value)];
      if (auth && token) {
        values.set("authorization", "Bearer " + token);
        if (!(profile && endpoint.pathname.includes("stalker_portal"))) pairs.push("token=" + token);
      } else values.delete("authorization");
      values.set("cookie", pairs.join("; "));
      return Object.fromEntries(values);
    };
    const api = async (type, action, parameters = {}, auth = true) => {
      const url = new URL(endpoint); url.search = "";
      for (const [key, value] of Object.entries({ type, action, JsHttpRequest: "1-xml", ...parameters })) url.searchParams.set(key, String(value));
      const { response } = await companionPlaybackFetch(url, headers(auth, action === "get_profile"), AbortSignal.timeout(20_000));
      if (!response.ok) { await response.body?.cancel(); throw syncError("Le portail a refusé la requête.", 502); }
      for (const cookie of response.headers.getSetCookie()) {
        const pair = cookie.split(";", 1)[0], separator = pair.indexOf("="), name = pair.slice(0, separator);
        if (separator > 0 && !["mac", "stb_lang", "timezone", "token"].includes(name)) cookies.set(name, pair.slice(separator + 1));
      }
      const data = await response.json(), value = data.js;
      if (value == null || value.error || Number(value.not_valid_token) === 1) throw syncError("Le portail a refusé la session IPTV.", 502);
      return value;
    };
    try {
      token = String((await api("stb", "handshake", { token: "" }, false)).token || "");
      if (!token) continue;
      const profile = await api("stb", "get_profile", { hd: "1", stb_type: "MAG254", ...(source.deviceId ? { device_id: source.deviceId, device_id2: source.deviceId } : {}) });
      if (Number(profile.blocked) === 1) throw syncError("Le portail a désactivé ce terminal IPTV.", 403);
      token = String(profile.token || token);
      return { api, headers, endpoint };
    } catch (error) { if (error.statusCode === 403) throw error; }
  }
  throw syncError("Impossible d’ouvrir le portail IPTV. Vérifie la MAC autorisée et l’adresse du fournisseur.", 502);
}

async function companionM3uEpisodes({ item, source }) {
  const { response } = await companionPlaybackFetch(source.url, source.headers, AbortSignal.timeout(20_000));
  if (!response.ok) { await response.body?.cancel(); throw syncError("La playlist IPTV est indisponible.", 502); }
  const text = await response.text(), episodes = new Map();
  let info = "", headers = { ...source.headers };
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim().replace(/^\uFEFF/, "");
    if (/^#EXTINF:/i.test(line)) { info = line; headers = { ...source.headers }; }
    else if (/^#EXTVLCOPT:/i.test(line)) {
      const match = line.match(/^#EXTVLCOPT:(http-user-agent|http-referr?er)=(.*)$/i);
      if (match) headers[match[1].toLowerCase() === "http-user-agent" ? "User-Agent" : "Referer"] = match[2];
    } else if (info && line && !line.startsWith("#")) {
      const title = info.replace(/^#EXTINF:(?:[^,"']|"[^"]*"|'[^']*')*,/, "").trim();
      const episode = title.match(/\bS(\d{1,2})\s*E(\d{1,3})\b/i), seriesId = episode ? title.slice(0, episode.index).trim() : title;
      if (seriesId === item.seriesId && episode) {
        const [address, options] = line.split("|", 2), url = new URL(address, source.url).href;
        for (const [name, value] of new URLSearchParams(options || "")) if (name && value) headers[name] = value;
        const providerId = crypto.createHash("sha256").update(address).digest("hex");
        const id = companionPlaybackId(source, "EPISODE", providerId);
        const artwork = info.match(/(?:tvg-logo|logo)\s*=\s*"([^"]+)"/i)?.[1] || item.artwork;
        episodes.set(id, { ...item, id, providerId, type: "EPISODE", title, artwork, streamUrl: url, season: Number(episode[1]), episode: Number(episode[2]), headers: { ...headers } });
      }
      info = "";
    }
  }
  return [...episodes.values()];
}

async function companionPlaybackEpisodes(favorite) {
  const { item, source } = favorite;
  let episodes;
  if (source.type === "XTREAM") {
    const url = new URL(String(source.url).replace(/\/+$/, "") + "/player_api.php");
    for (const [key, value] of Object.entries({ username: source.username, password: source.password, action: "get_series_info", series_id: item.providerId })) url.searchParams.set(key, value);
    const info = await companionPlaybackJson(url, source.headers);
    episodes = Object.entries(info.episodes || {}).flatMap(([season, values]) => (Array.isArray(values) ? values : []).filter(value => value.id != null).map(value => ({
      ...item, id: companionPlaybackId(source, "EPISODE", value.id), providerId: String(value.id), type: "EPISODE", seriesId: item.providerId,
      title: String(value.title || "Épisode " + value.episode_num), season: Number(value.season) || Number(season) || 1, episode: Number(value.episode_num) || 0,
      artwork: String(value.info?.movie_image || value.movie_image || value.screenshot_uri || item.artwork || ""),
      streamUrl: xtreamStreamUrl({ serverUrl: source.url, username: source.username, password: source.password }, "series", { id: value.id, extension: value.container_extension || "mp4" })
    })));
  } else if (source.type === "STALKER") {
    const session = await companionStalkerSession(source);
    const ordered = async parameters => {
      const values = new Map();
      for (let page = 1; page <= 100; page++) {
        const data = await session.api("series", "get_ordered_list", { ...parameters, p: page });
        const batch = Array.isArray(data) ? data : Array.isArray(data.data) ? data.data : [];
        const fresh = batch.filter(value => value.id != null && !values.has(String(value.id)));
        fresh.forEach(value => values.set(String(value.id), value));
        if (!fresh.length || values.size >= (Number(data.total_items) || batch.length)) break;
      }
      return [...values.values()];
    };
    const seasons = await ordered({ movie_id: item.providerId }); episodes = [];
    for (const [index, season] of seasons.entries()) {
      const number = Number(season.season_number) || Number(String(season.name || "").match(/(?:saison|season)\s*(\d+)/i)?.[1]) || index + 1;
      const embedded = Array.isArray(season.series) ? season.series.map(Number).filter(value => Number.isInteger(value) && value > 0) : [];
      const values = embedded.length ? embedded.map(episode => ({ id: season.id + ":" + episode, providerId: String(season.id), episode, cmd: season.cmd || "/media/file_" + season.id + ".mpg" }))
        : (await ordered({ movie_id: item.providerId, season_id: season.id, episode_id: "0" })).map((value, episodeIndex) => ({ ...value, providerId: String(value.id), episode: Number(value.series_number || value.episode_num) || episodeIndex + 1 }));
      episodes.push(...values.map(value => ({ ...item, id: companionPlaybackId(source, "EPISODE", value.id), providerId: value.providerId, type: "EPISODE", seriesId: item.providerId, season: number, episode: value.episode,
        title: String(value.name || item.title + " · S" + number + " E" + value.episode), artwork: String(value.screenshot_uri || value.screenshot || value.picture || value.cover || item.artwork || ""),
        command: value.cmd || "/media/file_" + value.id + ".mpg", streamUrl: "" })));
    }
  } else if (source.type === "M3U") episodes = await companionM3uEpisodes(favorite);
  else throw syncError("Ce fournisseur ne propose pas la lecture des séries dans le Companion.", 422);
  return episodes.sort((left, right) => left.season - right.season || left.episode - right.episode);
}

function companionCachedPlaybackEpisodes(ownerId, accountId, favorite) {
  const key = JSON.stringify([ownerId, accountId, favorite.item.id]), now = Date.now();
  const cached = companionEpisodeLists.get(key);
  if (cached?.expiresAt > now) return cached.promise;
  const entry = { expiresAt: now + 60_000, promise: null };
  entry.promise = companionPlaybackEpisodes(favorite).catch(error => {
    if (companionEpisodeLists.get(key) === entry) companionEpisodeLists.delete(key);
    throw error;
  });
  companionEpisodeLists.set(key, entry);
  while (companionEpisodeLists.size > 32) companionEpisodeLists.delete(companionEpisodeLists.keys().next().value);
  return entry.promise;
}

async function companionPlaybackStream({ item, source }) {
  let url = item.streamUrl, headers = { ...source.headers, ...item.headers };
  if (source.type === "XTREAM") {
    let extension = item.type === "LIVE" ? "m3u8" : "mp4";
    try { extension = new URL(item.streamUrl).pathname.match(/\.([a-z0-9]+)$/i)?.[1] || extension; } catch (_) {}
    url = xtreamStreamUrl({ serverUrl: source.url, username: source.username, password: source.password }, item.type === "LIVE" ? "live" : item.type === "MOVIE" ? "movies" : "series", { id: item.providerId, extension });
    headers = { ...source.headers };
  } else if (source.type === "STALKER") {
    const session = await companionStalkerSession(source);
    const link = await session.api(item.type === "LIVE" ? "itv" : "vod", "create_link", { cmd: item.command, forced_storage: "undefined", disable_ad: "0", download: "0", ...(item.type === "EPISODE" ? { series: item.episode } : {}) });
    url = String(link.cmd || "").match(/https?:\/\/[^\s]+/i)?.[0];
    if (!url) throw syncError("Le portail n’a pas fourni d’adresse de lecture HTTP.", 502);
    headers = new URL(url).host === session.endpoint.host ? session.headers() : { ...source.headers, ...Object.fromEntries(Object.entries(session.headers(false)).filter(([name]) => ["user-agent", "referer"].includes(name))) };
  }
  if (!url) throw syncError("Ce contenu ne possède pas d’adresse de lecture.", 422);
  return { url, headers };
}

function closeCompanionPlayback(id) {
  const session = companionPlaybackSessions.get(id);
  if (!session) return;
  companionPlaybackSessions.delete(id); session.controllers.forEach(controller => controller.abort());
  clearInterval(session.idleTimer);
  const cleanup = () => {
    if (session.directory && path.dirname(session.directory) === os.tmpdir() && path.basename(session.directory).startsWith("jojo-companion-playback-")) {
      fsp.rm(session.directory, { recursive: true, force: true }).catch(() => {});
    }
  };
  if (session.encoder?.pid && session.encoder.exitCode === null) { session.encoder.once("close", cleanup); session.encoder.kill(); }
  else cleanup();
}

function companionFreeboxRtspUrl(address) {
  const url = new URL(address);
  return url.protocol === "rtsp:" && !url.username && !url.password &&
    (url.hostname === "mafreebox.freebox.fr" || /^\/(?:fbxtv_pub|fbxdvb)\/stream$/i.test(url.pathname));
}

function companionPlaybackInput(session) {
  // Multiposte URLs have no credentials. Other providers keep using our HTTP
  // proxy so their authentication never appears in a subprocess command line.
  if (companionFreeboxRtspUrl(session.url)) return { url: session.url, args: ["-rtsp_transport", "udp", "-timeout", "15000000"] };
  return { url: "http://127.0.0.1:" + session.port + companionPlaybackResource(session, session.url), args: [] };
}

async function companionPlaybackMedia(session) {
  if (!session.media) session.media = (async () => {
    try {
      const input = companionPlaybackInput(session);
      const result = await execFileAsync("ffprobe", ["-v", "error", "-threads", "1", ...input.args, "-analyzeduration", "5000000", "-probesize", "5000000", "-show_streams", "-show_format", "-of", "json", input.url], { timeout: 25_000, maxBuffer: 1024 * 1024 });
      const probe = JSON.parse(result.stdout), streams = probe.streams || [], picture = streams.find(stream => stream.codec_type === "video" && !stream.disposition?.attached_pic);
      const freeboxRtsp = companionFreeboxRtspUrl(session.url);
      // RTP/MP2T stream indexes can change between the probe and the live input.
      // Type-relative indexes keep a subtitle from being selected as audio/video.
      const tracks = type => streams.filter(stream => stream.codec_type === type).map((stream, index) => ({ id: freeboxRtsp ? index : stream.index, codec: stream.codec_name,
        language: String(stream.tags?.language || "").slice(0, 20), title: String(stream.tags?.title || "").slice(0, 120), default: !!stream.disposition?.default }));
      return { duration: Number(probe.format?.duration) || Number(picture?.duration) || 0,
        video: picture ? { id: freeboxRtsp ? 0 : picture.index, codec: picture.codec_name, width: picture.width, height: picture.height, transfer: picture.color_transfer } : null,
        audio: tracks("audio"), subtitles: tracks("subtitle") };
    } catch (_) { session.media = null; throw syncError(companionFreeboxRtspUrl(session.url)
      ? "Le NAS ne reçoit pas ce flux Freebox. Vérifie la disponibilité de la chaîne, l’adresse de la liste et la réception TNT pour une chaîne TNT."
      : "Le NAS n’a pas pu analyser les pistes de ce contenu. Vérifie FFprobe et la disponibilité du flux.", 503); }
  })();
  return session.media;
}

function companionPlaybackLocalResource(session, name) {
  if (name !== path.basename(name) || !/^[a-z0-9_-]+\.(?:m3u8|ts|vtt)$/i.test(name)) throw syncError("Segment vidéo invalide.", 400);
  const id = crypto.createHash("sha256").update("local:" + name).digest("hex");
  session.files.set(id, name);
  return "/api/companion/playback/" + session.id + "/" + id;
}

async function startCompanionPlaybackRelay(session, options) {
  if ([...companionPlaybackSessions.values()].some(value => value.preparingRelay || value.encoder && value.encoder.exitCode === null)) throw syncError("Le NAS prépare déjà une vidéo. Ferme l’autre lecteur avant de réessayer.", 429);
  session.preparingRelay = true;
  const media = await companionPlaybackMedia(session);
  if (options.isCancelled?.()) throw syncError("La préparation de la lecture a été annulée.", 499);
  if (!media.video) throw syncError("Ce contenu ne contient pas de piste vidéo.", 422);
  const audio = options.audio == null ? media.audio.find(track => track.default) || media.audio[0] : media.audio.find(track => track.id === options.audio);
  const subtitle = options.subtitle == null || options.subtitle === -1 ? null : media.subtitles.find(track => track.id === options.subtitle);
  if (options.audio != null && !audio || options.subtitle != null && options.subtitle !== -1 && !subtitle) throw syncError("La piste demandée n’existe pas dans ce contenu.", 400);
  const bitmap = subtitle && ["hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle"].includes(subtitle.codec);
  if (subtitle && !bitmap && !["subrip", "ass", "ssa", "webvtt", "mov_text", "text"].includes(subtitle.codec)) throw syncError("Ce format de sous-titres n’est pas pris en charge.", 422);
  if (bitmap && !options.compatible) throw syncError("Ces sous-titres nécessitent le mode compatible.", 422);
  const position = session.live ? 0 : Number(options.position || 0);
  if (!Number.isFinite(position) || position < 0 || media.duration > 0 && position >= media.duration) throw syncError("Position de lecture invalide.", 400);
  session.directory = await fsp.mkdtemp(path.join(os.tmpdir(), "jojo-companion-playback-"));
  const input = companionPlaybackInput(session);
  const streamMap = (track, type) => "0:" + (companionFreeboxRtspUrl(session.url) ? type + ":" : "") + track.id;
  const args = ["-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-filter_threads", "1", "-filter_complex_threads", "1", "-threads", "1", "-re"];
  if (!input.args.length) args.push("-rw_timeout", "15000000");
  if (position) args.push("-ss", position.toFixed(3));
  args.push(...input.args, "-i", input.url);
  const scale = "scale=w='min(1920,iw)':h='min(1080,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2";
  const hdr = ["smpte2084", "arib-std-b67"].includes(media.video.transfer);
  const filters = scale + (hdr ? ",zscale=t=linear:npl=100,format=gbrpf32le,tonemap=hable:desat=0,zscale=t=bt709:m=bt709:p=bt709,format=yuv420p" : "");
  if (bitmap) args.push("-filter_complex", "[" + streamMap(media.video, "v") + "][" + streamMap(subtitle, "s") + "]overlay," + filters + "[picture]", "-map", "[picture]");
  else { args.push("-map", streamMap(media.video, "v")); if (options.compatible) args.push("-vf", filters); }
  if (audio) args.push("-map", streamMap(audio, "a"));
  if (subtitle && !bitmap) args.push("-map", streamMap(subtitle, "s"), "-c:s", "webvtt");
  args.push("-c:v", options.compatible ? "libx264" : "copy");
  if (options.compatible) {
    args.push("-threads", "2", "-preset", "ultrafast", "-crf", "23", "-pix_fmt", "yuv420p", "-force_key_frames", "expr:gte(t,n_forced*2)");
    if (hdr) args.push("-color_trc", "bt709", "-colorspace", "bt709", "-color_primaries", "bt709");
  }
  if (audio) args.push("-c:a", "aac", "-b:a", "192k", "-ac", "2");
  const mapping = "v:0" + (audio ? ",a:0" : "") + (subtitle && !bitmap ? ",s:0,sgroup:subtitles,sname:Subtitles" : "");
  args.push("-f", "hls", "-hls_time", "2", "-hls_list_size", "15", "-hls_flags", "delete_segments+temp_file", "-var_stream_map", mapping,
    "-master_pl_name", "master.m3u8", "-hls_segment_filename", path.join(session.directory, "stream%d.ts"), path.join(session.directory, "stream.m3u8"));
  session.encoder = spawn("ffmpeg", args, { stdio: ["ignore", "ignore", "pipe"], windowsHide: true });
  session.preparingRelay = false;
  session.encoder.stderr.resume();
  session.encoder.on("error", () => { session.encodingFailed = true; });
  session.encoder.on("exit", code => { if (code !== 0) session.encodingFailed = true; });
  session.relay = { compatible: !!options.compatible, position, audio: audio?.id ?? -1, subtitle: subtitle?.id ?? -1, bitmap: !!bitmap };
  const deadline = Date.now() + 120_000;
  while (Date.now() < deadline) {
    if (options.isCancelled?.()) throw syncError("La préparation de la lecture a été annulée.", 499);
    if (!companionPlaybackSessions.has(session.id) || session.encodingFailed) throw syncError("Le NAS n’a pas pu préparer la vidéo. Vérifie les codecs FFmpeg ou réessaie la qualité originale.", 502);
    try {
      const manifest = await fsp.readFile(path.join(session.directory, "stream.m3u8"), "utf8");
      if (manifest.includes("#EXTINF:")) {
        session.accessedAt = Date.now();
        session.idleTimer = setInterval(() => { if (Date.now() - session.accessedAt > 90_000) closeCompanionPlayback(session.id); }, 15_000).unref();
        return { media, relay: session.relay, url: companionPlaybackLocalResource(session, "master.m3u8"), format: "hls" };
      }
    } catch (_) {}
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw syncError("La préparation de la vidéo sur le NAS dépasse 2 minutes. Vérifie les ressources de la VM et la disponibilité du flux.", 504);
}

function companionPlaybackResource(session, address, base) {
  const url = new URL(address, base);
  if (!["http:", "https:"].includes(url.protocol)) throw syncError("Cette playlist contient un format de flux incompatible.", 422);
  const id = crypto.createHash("sha256").update(url.href).digest("hex");
  session.resources.set(id, url.href);
  return "/api/companion/playback/" + session.id + "/" + id;
}

async function createCompanionPlayback(ownerId, favorite, options = {}) {
  for (const [id, session] of companionPlaybackSessions) if (session.expiresAt < Date.now()) closeCompanionPlayback(id);
  const own = [...companionPlaybackSessions.values()].filter(session => session.ownerId === ownerId);
  if (own.length >= 8) closeCompanionPlayback(own[0].id);
  const stream = await companionPlaybackStream(favorite);
  const controller = new AbortController(), timeout = setTimeout(() => controller.abort(), 15_000);
  let probe, session;
  try {
    const freeboxRtsp = companionFreeboxRtspUrl(stream.url);
    let format = "hls";
    if (!freeboxRtsp) {
      const result = await companionPlaybackFetch(stream.url, { ...stream.headers, ...(favorite.item.type === "LIVE" ? {} : { Range: "bytes=0-1023" }) }, controller.signal);
      probe = result.response;
      if (!probe.ok) throw syncError(`Lecture refusée par le fournisseur (HTTP ${probe.status}).`, 502);
      const reader = probe.body.getReader(), first = await reader.read();
      await reader.cancel();
      const bytes = Buffer.from(first.value || []), mime = probe.headers.get("content-type") || "", path = new URL(result.url).pathname;
      format = bytes.toString("utf8", 0, 64).trimStart().startsWith("#EXTM3U") || /mpegurl/i.test(mime) || /\.m3u8$/i.test(path) ? "hls"
        : bytes[0] === 0x47 || /mp2t/i.test(mime) || /\.(?:ts|m2ts)$/i.test(path) ? "mpegts" : "native";
    }
    // Provider redirects may contain short-lived tickets. Resolve the entry URL again on each request.
    const headers = new Headers(stream.headers); headers.delete("range");
    session = { id: crypto.randomBytes(32).toString("hex"), ownerId, url: stream.url, headers: Object.fromEntries(headers), port: options.port,
      live: favorite.item.type === "LIVE", expiresAt: Date.now() + COMPANION_PLAYBACK_TTL_MS, resources: new Map(), files: new Map(), controllers: new Set() };
    companionPlaybackSessions.set(session.id, session);
    if (freeboxRtsp) return { session: session.id, live: session.live, expiresAt: session.expiresAt,
      ...await startCompanionPlaybackRelay(session, options) };
    return { session: session.id, url: companionPlaybackResource(session, session.url), format, live: session.live, expiresAt: session.expiresAt,
      ...(options.relay || options.compatible ? await startCompanionPlaybackRelay(session, options) : {}) };
  } catch (error) { if (session) closeCompanionPlayback(session.id); await probe?.body?.cancel().catch(() => {}); throw error.statusCode ? error : syncError("Impossible d’ouvrir ce flux IPTV depuis le NAS.", 502); }
  finally { clearTimeout(timeout); controller.abort(); }
}

async function proxyCompanionPlayback(request, response, session, resourceId, url) {
  if (session.files.has(resourceId)) {
    session.accessedAt = Date.now();
    const name = session.files.get(resourceId), file = path.join(session.directory, name);
    if (name.endsWith(".m3u8")) {
      const manifest = await fsp.readFile(file, "utf8").catch(() => null);
      if (!manifest) throw syncError("Cette playlist vidéo n’est plus disponible.", 410);
      const rewritten = manifest.split(/\r?\n/).map(line => !line.trim() ? line : !line.startsWith("#") ? companionPlaybackLocalResource(session, line.trim())
        : line.replace(/URI="([^"]+)"/g, (_, address) => 'URI="' + companionPlaybackLocalResource(session, address) + '"')).join("\n");
      return text(response, 200, rewritten, "application/vnd.apple.mpegurl");
    }
    const stat = await fsp.stat(file).catch(() => null);
    if (!stat?.isFile()) throw syncError("Ce segment vidéo n’est plus disponible.", 410);
    response.writeHead(200, { "content-type": name.endsWith(".vtt") ? "text/vtt" : "video/mp2t", "content-length": stat.size, "cache-control": "no-store" });
    if (request.method === "HEAD") return response.end();
    const body = fs.createReadStream(file); response.once("close", () => body.destroy()); body.on("error", () => response.destroy()); return body.pipe(response);
  }
  const address = session.resources.get(resourceId);
  if (!address) throw syncError("Flux de lecture introuvable.", 404);
  const target = new URL(address);
  for (const name of ["_HLS_msn", "_HLS_part", "_HLS_skip"]) if (url.searchParams.has(name)) target.searchParams.set(name, url.searchParams.get(name));
  const headers = new Headers(session.headers);
  if (target.origin !== new URL(session.url).origin) for (const name of [...headers.keys()]) if (!["user-agent", "referer"].includes(name)) headers.delete(name);
  if (request.headers.range) headers.set("range", request.headers.range);
  const controller = new AbortController(); session.controllers.add(controller);
  const timeout = setTimeout(() => controller.abort(), 20_000);
  const stop = () => { clearTimeout(timeout); controller.abort(); session.controllers.delete(controller); };
  response.once("close", stop);
  try {
    const upstream = await companionPlaybackFetch(target, headers, controller.signal); clearTimeout(timeout);
    const remote = upstream.response, mime = remote.headers.get("content-type") || "application/octet-stream";
    if (!remote.ok) { await remote.body?.cancel(); throw syncError(`Le fournisseur répond HTTP ${remote.status}.`, 502); }
    const outgoing = { "content-type": mime, "cache-control": "no-store", "x-content-type-options": "nosniff" };
    for (const name of ["content-length", "content-range", "accept-ranges"]) if (remote.headers.has(name) && !remote.headers.has("content-encoding")) outgoing[name] = remote.headers.get(name);
    if (request.method === "HEAD") { await remote.body?.cancel(); response.writeHead(remote.status, outgoing); return response.end(); }
    if (/mpegurl/i.test(mime) || /\.m3u8$/i.test(new URL(upstream.url).pathname)) {
      const manifest = await remote.text();
      if (!manifest.trimStart().startsWith("#EXTM3U")) throw syncError("La playlist de lecture est invalide.", 502);
      const rewritten = manifest.split(/\r?\n/).map(line => {
        if (!line.trim()) return line;
        if (!line.startsWith("#")) return companionPlaybackResource(session, line.trim(), upstream.url);
        return line.replace(/URI="([^"]+)"/g, (_, address) => 'URI="' + (address.startsWith("data:") ? address : companionPlaybackResource(session, address, upstream.url)) + '"');
      }).join("\n");
      return text(response, 200, rewritten, "application/vnd.apple.mpegurl");
    }
    response.writeHead(remote.status, outgoing);
    const body = Readable.fromWeb(remote.body);
    body.on("error", () => response.destroy()); body.pipe(response);
  } catch (error) {
    stop();
    if (!response.destroyed && !response.headersSent) throw error.statusCode ? error : syncError("La lecture IPTV a été interrompue par le fournisseur.", 502);
    response.destroy();
  }
}

async function companionFavoriteImage({ item, source }) {
  let url = companionFavoriteArtwork(item, source);
  if (!url) throw syncError("Vignette indisponible.", 404);
  let sourceHost = "";
  try { sourceHost = new URL(source.url).host; } catch (_) {}
  const signal = AbortSignal.timeout(10_000), limit = 8 * 1024 * 1024;
  try {
    for (let redirects = 0; redirects <= 4; redirects++) {
      const headers = Object.fromEntries(Object.entries({ ...source.headers, ...item.headers })
        .filter(([key, value]) => typeof value === "string" && (url.host === sourceHost || key.toLowerCase() === "user-agent")));
      const upstream = await fetch(url, { headers, redirect: "manual", signal });
      if ([301, 302, 303, 307, 308].includes(upstream.status)) {
        await upstream.body?.cancel();
        const location = upstream.headers.get("location");
        if (!location) break;
        url = new URL(location, url);
        if (!["http:", "https:"].includes(url.protocol)) break;
        continue;
      }
      const type = (upstream.headers.get("content-type") || "").split(";")[0].trim().toLowerCase();
      if (!upstream.ok || !/^image\/(png|jpeg|webp|gif|avif|bmp|x-icon|vnd\.microsoft\.icon)$/.test(type) || Number(upstream.headers.get("content-length")) > limit) {
        await upstream.body?.cancel(); break;
      }
      const chunks = []; let length = 0;
      for await (const chunk of upstream.body) {
        length += chunk.length;
        if (length > limit) throw new Error("Image trop volumineuse.");
        chunks.push(chunk);
      }
      if (length) return { type, data: Buffer.concat(chunks, length) };
      break;
    }
  } catch (_) {}
  throw syncError("Vignette indisponible.", 502);
}

async function removeCompanionHomeFavorite(accountId, requestedId, itemId, body) {
  if (body?.confirm !== true) throw syncError("Confirme le retrait de ce favori.");
  const current = await companionHomeAccountFavorites(accountId, requestedId);
  if (!current.items.some(({ item }) => item.id === itemId)) throw syncError("Favori introuvable pour ce compte.", 404);
  const previous = syncProfileSecretSnapshot(accountId, 1, "jojo-home-iptv");
  if (!previous?.value) throw syncError("La bibliothèque n’est plus disponible.", 409);
  const snapshot = mergeHomeIptvSnapshot(null, decryptSyncSecret(previous.value));
  const records = snapshot.records, resolveId = id => companionHomeAccountId(records, id);
  const id = resolveId(current.account.id), legacyId = resolveId(records.legacyAccount?.value || "");
  if (records["profile:" + id]?.value == null) throw syncError("Ce compte n’existe plus.", 404);
  const at = Math.max(Date.now(), Object.values(records).reduce((latest, record) => Math.max(latest, record.changedAt), 0) + 1);
  const changes = Object.create(null), mark = (key, value = null) => { changes[key] = { changedAt: at, writer: "jojo-companion", value }; };
  mark("account:" + id + ":favorite:" + itemId);
  for (const [key, record] of Object.entries(records)) {
    const scoped = key.match(/^account:([^:]+):favorite:(.+)$/);
    if (!scoped || resolveId(scoped[1]) !== id || scoped[2] !== itemId) continue;
    mark(key);
    const mirror = records["favorite:" + itemId];
    if (legacyId !== id && mirror && mirror.changedAt === record.changedAt && mirror.writer === record.writer && (mirror.value ?? null) === (record.value ?? null)) {
      const primary = Object.entries(records).filter(([name]) => {
        const match = name.match(/^account:([^:]+):favorite:(.+)$/);
        return match && resolveId(match[1]) === legacyId && match[2] === itemId;
      }).reduce((result, [, value]) => result ? mergeHomeIptvSnapshot({ records: { item: result } }, { records: { item: value } }).records.item : value, null);
      mark("favorite:" + itemId, primary?.value ?? null);
    }
  }
  if (legacyId === id) mark("favorite:" + itemId);
  const value = mergeHomeIptvSnapshot(snapshot, { records: changes });
  const saved = { ...previous, value: encryptSyncSecret(value), updatedAt: Date.now() };
  const index = store.sync.profileSecrets.findIndex(item => item.accountId === accountId && item.profileId === 1 && item.key === "jojo-home-iptv");
  if (index < 0) throw syncError("La bibliothèque n’est plus disponible.", 409);
  store.sync.profileSecrets[index] = saved;
  await persistStore();
  return { deleted: true, accountId: id, id: itemId };
}

async function changeCompanionHomeAccount(accountId, requestedId, method, body) {
  if (!body || typeof body !== "object" || Array.isArray(body)) throw syncError("Compte invalide.");
  const hasName = Object.prototype.hasOwnProperty.call(body, "name");
  const hasAvatar = Object.prototype.hasOwnProperty.call(body, "avatarId");
  const hasOrnament = Object.prototype.hasOwnProperty.call(body, "ornamentColor");
  if (hasOrnament && (typeof body.ornamentColor !== "string" || !companionAccountOrnaments().some(color => color.hex === body.ornamentColor.toUpperCase()))) {
    throw syncError("Choisissez une couleur d’ornement disponible.");
  }
  if (method === "DELETE" && body.confirm !== true) throw syncError("Confirme la suppression du compte.");
  if (method === "PATCH" && !hasName && !hasAvatar && !hasOrnament) throw syncError("Aucune modification du compte.");
  if (hasAvatar) {
    if (typeof body.avatarId !== "string" || body.avatarId && !(await readAvatarCatalog()).avatars.some(avatar => avatar.id === body.avatarId)) {
      throw syncError("Cet avatar n’est plus disponible. Actualise les avatars.");
    }
  }
  // Read after loading the catalogue so a simultaneous TV edit remains authoritative.
  const previous = syncProfileSecretSnapshot(accountId, 1, "jojo-home-iptv");
  const snapshot = mergeHomeIptvSnapshot(null, previous ? decryptSyncSecret(previous.value) : {});
  const records = snapshot.records, resolveId = id => companionHomeAccountId(records, id);
  const id = resolveId(requestedId), profileRecord = records["profile:" + id];
  if (profileRecord?.value == null) throw syncError("Ce compte n’existe plus. Choisissez un autre compte.", 404);
  const profile = JSON.parse(profileRecord.value);
  const at = Math.max(Date.now(), Object.values(records).reduce((latest, record) => Math.max(latest, record.changedAt), 0) + 1);
  const writer = "jojo-companion", changes = Object.create(null);
  const mark = (key, value = null) => { changes[key] = { changedAt: at, writer, value }; };
  if (method === "PATCH") {
    const name = hasName && typeof body.name === "string" ? body.name.trim() : hasName ? "" : profile.name;
    if (!name) throw syncError("Saisissez un nom de compte.");
    if (name.length > 40) throw syncError("Le nom du compte ne doit pas dépasser 40 caractères.");
    for (const [key, record] of Object.entries(records)) {
      if (!key.startsWith("profile:") || record.value == null || key === "profile:" + id || resolveId(key.slice(8)) !== key.slice(8)) continue;
      let other;
      try { other = JSON.parse(record.value); } catch (_) { continue; }
      if (typeof other?.name === "string" && other.name.toLowerCase() === name.toLowerCase()) throw syncError("Un compte porte déjà ce nom.");
    }
    const next = { ...profile, id, name, ...(hasAvatar ? { avatar: body.avatarId } : {}),
      ...(hasOrnament ? { ornamentColor: body.ornamentColor.toUpperCase() } : {}) };
    if (JSON.stringify(next) === JSON.stringify(profile)) return { unchanged: true };
    mark("profile:" + id, JSON.stringify(next));
    mark("accountName:" + name.toLowerCase(), id);
  } else {
    mark("profile:" + id);
    const legacyId = resolveId(records.legacyAccount?.value || "");
    const primary = new Map();
    for (const [key, record] of Object.entries(records)) {
      const scoped = key.match(/^account:([^:]+):(.+)$/);
      if (!scoped || resolveId(scoped[1]) !== legacyId) continue;
      const previous = primary.get(scoped[2]);
      primary.set(scoped[2], previous ? mergeHomeIptvSnapshot({ records: { item: previous } }, { records: { item: record } }).records.item : record);
    }
    for (const [key, record] of Object.entries(records)) {
      const scoped = key.match(/^account:([^:]+):(.+)$/);
      if (scoped && resolveId(scoped[1]) === id) {
        mark(key);
        const mirror = records[scoped[2]];
        // A secondary account's old mirror must not become a primary favorite after deletion.
        if (legacyId !== id && mirror && mirror.changedAt === record.changedAt && mirror.writer === record.writer && (mirror.value ?? null) === (record.value ?? null)) {
          mark(scoped[2], primary.get(scoped[2])?.value ?? null);
        }
      } else if (legacyId === id && (key.startsWith("favorite:") || key.startsWith("setting:"))) mark(key);
    }
  }
  // Retain and advance old name registrations, as on the TV, to reject late legacy copies.
  for (const [key, record] of Object.entries(records)) {
    if (key.startsWith("accountName:") && record.value != null && resolveId(record.value) === id && !changes[key]) mark(key, id);
  }
  const value = mergeHomeIptvSnapshot(snapshot, { records: changes });
  const saved = { ...previous, accountId, profileId: 1, key: "jojo-home-iptv", value: encryptSyncSecret(value), updatedAt: Date.now() };
  const index = store.sync.profileSecrets.findIndex(item => item.accountId === accountId && item.profileId === 1 && item.key === saved.key);
  if (index >= 0) store.sync.profileSecrets[index] = saved;
  else store.sync.profileSecrets.push(saved);
  await persistStore();
  return { updatedAt: saved.updatedAt, deleted: method === "DELETE" };
}

function companionIptvProvidersFor(accountId) {
  const snapshot = syncProfileSecretSnapshot(accountId, 1, "jojo-home-iptv");
  if (!snapshot) return [];
  const { records } = mergeHomeIptvSnapshot(null, decryptSyncSecret(snapshot.value));
  return Object.entries(records).filter(([key, record]) => key.startsWith("source:") && record.value != null)
    .map(([key, record]) => {
      let source;
      try { source = JSON.parse(record.value); } catch (_) { return null; }
      if (typeof source?.name !== "string" || !source.name.trim() || !["M3U", "XTREAM", "STALKER"].includes(source.type)) return null;
      return { id: key.slice("source:".length), name: source.name.trim().slice(0, 120), type: source.type };
    }).filter(Boolean).sort((left, right) => left.name.localeCompare(right.name, "fr"));
}

async function companionOverview(account) {
  const backups = [...store.sync.backups]
    .filter((item) => item.accountId === account.id && item.app === "jojohome")
    .sort((a, b) => b.createdAt - a.createdAt)
    .map(({ id, app, createdAt, size, reason }) => ({ id, app, createdAt, size, reason }));
  const knownDevices = new Map();
  const rememberDevice = (ip, lastSeenAt) => {
    if (!isPrivateIpv4(ip)) return;
    const previous = knownDevices.get(ip);
    if (!previous || String(lastSeenAt) > String(previous)) knownDevices.set(ip, lastSeenAt);
  };
  Object.entries(store.tvPresence || {}).forEach(([ip, lastSeenAt]) => rememberDevice(ip, lastSeenAt));
  store.history.forEach((record) => rememberDevice(record?.target?.tv, record?.updatedAt || record?.createdAt));
  const devices = [...knownDevices.entries()]
    .map(([ip, lastSeenAt]) => ({ id: ip, name: `Jojo Home · ${ip}`, ip, lastSeenAt }))
    .sort((a, b) => String(b.lastSeenAt).localeCompare(String(a.lastSeenAt)));
  return {
    account: syncAccountPublic(account),
    profiles: companionProfilesFor(account.id),
    accounts: await companionHomeAccountsFor(account.id),
    providers: companionIptvProvidersFor(account.id),
    backups,
    devices,
    retention: BACKUP_RETENTION
  };
}

async function requestManualRestore(account, body) {
  if (body?.confirm !== true) throw syncError("Confirmation de restauration requise.");
  const app = syncBackupApp(body.app);
  const backupId = String(body.backupId || "").trim();
  const targetTv = String(body.targetTv || "").trim();
  if (!backupId) throw syncError("Sauvegarde à restaurer absente.");
  if (!isPrivateIpv4(targetTv)) throw syncError("TV cible invalide.");
  const backup = await restoreAccountBackup(account.id, app, backupId);
  store.sync.manualRestores = store.sync.manualRestores
    .filter((item) => item.accountId !== account.id || item.targetTv !== targetTv || item.status !== "pending");
  const request = {
    id: crypto.randomUUID(), accountId: account.id, app, backupId, targetTv,
    createdAt: Date.now(), status: "pending"
  };
  store.sync.manualRestores.push(request);
  await persistStore();
  return { request, backup };
}

async function runScheduledBackups() {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: BACKUP_TIME_ZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23"
  }).formatToParts(new Date()).reduce((values, part) => {
    if (part.type !== "literal") values[part.type] = part.value;
    return values;
  }, {});
  const day = `${parts.year}-${parts.month}-${parts.day}`;
  if (Number(parts.hour) !== 3 || Number(parts.minute) !== 0 || lastScheduledBackupDay === day) return;
  lastScheduledBackupDay = day;
  const policies = store.sync.backupPolicies.filter((item) => item.app === "jojohome" && item.enabled !== false);
  for (const policy of policies) {
    await createAccountBackup(policy.accountId, policy.app, "scheduled").catch((error) => {
      console.error(`Backup ${policy.app} failed for account ${policy.accountId}:`, error.message);
    });
  }
}

function syncWatchProgressSnapshot(accountId, profileId) {
  return store.sync.watchProgress.find((item) => item.accountId === accountId && item.profileId === profileId) || null;
}

function syncWatchProgressEntries(value) {
  if (!Array.isArray(value)) throw syncError("Liste de reprises invalide.");
  if (value.length > 10_000) throw syncError("Trop de reprises de lecture.");
  const now = Date.now();
  return value.map((item) => {
    const key = String(item.key || "").trim();
    const contentId = String(item.contentId || "").trim();
    const contentType = String(item.contentType || "").trim();
    const videoId = String(item.videoId || "").trim();
    const lastWatched = Number(item.lastWatched);
    if (!key || !contentId || !contentType || !Number.isFinite(lastWatched) || lastWatched <= 0) {
      throw syncError("Reprise de lecture invalide.");
    }
    const boundedText = (field, limit = 4096) => String(item[field] || "").slice(0, limit);
    const nullableInt = (field) => Number.isInteger(item[field]) ? item[field] : null;
    return {
      key, contentId, contentType, videoId,
      name: boundedText("name"), poster: boundedText("poster"), backdrop: boundedText("backdrop"), logo: boundedText("logo"),
      season: nullableInt("season"), episode: nullableInt("episode"), episodeTitle: boundedText("episodeTitle"),
      position: Math.max(0, Number(item.position) || 0), duration: Math.max(0, Number(item.duration) || 0),
      lastWatched, addonBaseUrl: boundedText("addonBaseUrl"), progressPercent: Number.isFinite(item.progressPercent) ? item.progressPercent : null,
      source: boundedText("source", 128), traktPlaybackId: nullableInt("traktPlaybackId"), traktMovieId: nullableInt("traktMovieId"),
      traktShowId: nullableInt("traktShowId"), traktEpisodeId: nullableInt("traktEpisodeId"), description: boundedText("description"), updatedAt: now
    };
  });
}

function syncWatchProgressKeys(value) {
  if (!Array.isArray(value) || value.length > 10_000) throw syncError("Suppressions de reprises invalides.");
  return [...new Set(value.map((key) => String(key || "").trim()).filter(Boolean))];
}

function syncWatchProgressDeletions(value) {
  if (!Array.isArray(value) || value.length > 10_000) throw syncError("Suppressions datées invalides.");
  const deletions = new Map();
  for (const item of value) {
    if (!item || typeof item.key !== "string" || !item.key.trim() ||
        !Number.isSafeInteger(item.deletedAt) || item.deletedAt <= 0) {
      throw syncError("Suppression datée invalide.");
    }
    const key = item.key.trim();
    deletions.set(key, Math.max(deletions.get(key) || 0, item.deletedAt));
  }
  return [...deletions].map(([key, deletedAt]) => ({ key, deletedAt }));
}

function syncWatchedItemSnapshot(accountId, profileId) {
  return store.sync.watchedItems.find((item) => item.accountId === accountId && item.profileId === profileId) || null;
}

function watchedItemKey(contentId, season, episode) {
  return `${String(contentId)}|${season == null ? "" : season}|${episode == null ? "" : episode}`;
}

function syncWatchedItemEntries(value) {
  if (!Array.isArray(value) || value.length > 20_000) throw syncError("Elements vus invalides.");
  return value.map((item) => {
    const contentId = String(item && item.contentId || "").trim();
    const contentType = String(item && item.contentType || "").trim();
    const title = String(item && item.title || "").trim();
    const watchedAt = Number(item && item.watchedAt || 0);
    if (!contentId || !contentType || !title || !Number.isFinite(watchedAt) || watchedAt <= 0) throw syncError("Un element vu est invalide.");
    const season = item.season == null ? null : Number(item.season);
    const episode = item.episode == null ? null : Number(item.episode);
    if ((season != null && !Number.isInteger(season)) || (episode != null && !Number.isInteger(episode))) throw syncError("La saison ou l'episode est invalide.");
    return { key: watchedItemKey(contentId, season, episode), contentId, contentType, title, season, episode, watchedAt };
  });
}

function syncWatchedItemKeys(value) {
  if (!Array.isArray(value) || value.length > 20_000) throw syncError("Suppressions d'elements vus invalides.");
  return value.map((item) => {
    const contentId = String(item && item.contentId || "").trim();
    if (!contentId) throw syncError("Cle d'un element vu invalide.");
    const season = item.season == null ? null : Number(item.season);
    const episode = item.episode == null ? null : Number(item.episode);
    return watchedItemKey(contentId, season, episode);
  });
}

function syncProfileBlobSnapshot(accountId, profileId, key) {
  return store.sync.profileBlobs.find((item) => item.accountId === accountId && item.profileId === profileId && item.key === key) || null;
}

function syncProfileBlobKey(value) {
  const key = String(value || "").trim();
  if (!/^[a-z0-9_-]{1,64}$/i.test(key)) throw syncError("Cle de synchronisation invalide.");
  return key;
}

function syncProfileAssetSnapshot(accountId, profileId, key) {
  return store.sync.profileAssets.find((item) => item.accountId === accountId && item.profileId === profileId && item.key === key) || null;
}

function syncProfileSecretSnapshot(accountId, profileId, key) {
  return store.sync.profileSecrets.find((item) => item.accountId === accountId && item.profileId === profileId && item.key === key) || null;
}

function syncAssetPath(accountId, profileId, key) {
  const safeAccount = crypto.createHash("sha256").update(String(accountId)).digest("hex").slice(0, 24);
  return path.join(SYNC_ASSET_DIR, `${safeAccount}-${profileId}-${key}`);
}

function encryptSyncSecret(value) {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-256-gcm", crypto.createHash("sha256").update(store.sync.tokenSecret).digest(), iv);
  const encrypted = Buffer.concat([cipher.update(JSON.stringify(value), "utf8"), cipher.final()]);
  return { iv: iv.toString("base64"), tag: cipher.getAuthTag().toString("base64"), data: encrypted.toString("base64") };
}

function decryptSyncSecret(value) {
  const decipher = crypto.createDecipheriv("aes-256-gcm", crypto.createHash("sha256").update(store.sync.tokenSecret).digest(), Buffer.from(value.iv, "base64"));
  decipher.setAuthTag(Buffer.from(value.tag, "base64"));
  return JSON.parse(Buffer.concat([decipher.update(Buffer.from(value.data, "base64")), decipher.final()]).toString("utf8"));
}

// IPTV clients exchange per-record revisions; a stale whole snapshot must not erase a newer avatar.
function mergeHomeIptvSnapshot(previous, incoming) {
  const current = previous == null ? { schema: 1, records: {} } : previous;
  for (const snapshot of [current, incoming]) {
    if (!snapshot || typeof snapshot !== "object" || Array.isArray(snapshot) ||
        (snapshot.schema !== undefined && snapshot.schema !== 1)) {
      throw syncError("Bibliotheque IPTV invalide.");
    }
    // Kotlin omits the default schema=1, empty records and null record values on the wire.
    const entries = snapshot.records === undefined ? {} : snapshot.records;
    if (!entries || typeof entries !== "object" || Array.isArray(entries)) throw syncError("Bibliotheque IPTV invalide.");
    for (const record of Object.values(entries)) {
      if (!record || !Number.isSafeInteger(record.changedAt) || record.changedAt < 0 || typeof record.writer !== "string" ||
          (record.value != null && typeof record.value !== "string")) throw syncError("Revision IPTV invalide.");
    }
  }
  const records = Object.assign(Object.create(null), current.records);
  for (const [key, record] of Object.entries(incoming.records || {})) {
    const existing = records[key];
    if (!existing) { records[key] = record; continue; }
    // The legacy account is permanent, matching the client's earliest-anchor rule.
    if (key === "legacyAccount" && existing.value != null && record.value != null) {
      if (record.changedAt < existing.changedAt || record.changedAt === existing.changedAt &&
          (record.writer < existing.writer || record.writer === existing.writer && record.value < existing.value)) records[key] = record;
      continue;
    }
    if (record.changedAt !== existing.changedAt) {
      if (record.changedAt > existing.changedAt) records[key] = record;
    } else if (record.writer !== existing.writer) {
      if (record.writer > existing.writer) records[key] = record;
    } else if (existing.value != null && (record.value == null || record.value > existing.value)) records[key] = record;
  }
  return { schema: 1, records };
}

// Free TV/OQEE credentials are deliberately kept in the encrypted NAS store.
// No read endpoint is exposed: clients can only set or clear a session, then
// request a server-side operation.
function freeTvSessionSnapshot(accountId, profileId) {
  return syncProfileSecretSnapshot(accountId, profileId, "free-tv-session");
}

function freeTvSessionPayload(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw syncError("Session Free TV invalide.");
  const accessToken = String(value.accessToken || "").trim();
  const oqeeProfile = String(value.profile || "").trim();
  if (accessToken.length < 24) throw syncError("Jeton Free TV invalide.");
  if (!/^[a-zA-Z0-9_-]{1,160}$/.test(oqeeProfile)) throw syncError("Profil Free TV invalide.");
  return { accessToken, profile: oqeeProfile };
}

function freeTvHeaders(session, rightsToken = null) {
  const headers = {
    authorization: `Bearer ${session.accessToken}`,
    "x-oqee-platform": "web",
    "x-oqee-customization": "0",
    accept: "application/json"
  };
  if (session.profile && session.profile !== "pending") headers["x-oqee-profile"] = session.profile;
  if (rightsToken) headers["x-fbx-rights-token"] = rightsToken;
  return headers;
}

function freeTvAuthHeaders() {
  return {
    accept: "application/json, text/plain, */*",
    "content-type": "application/json",
    origin: "https://tv.free.fr",
    referer: "https://tv.free.fr/",
    "x-oqee-customization": "0",
    "x-oqee-platform": "web"
  };
}

async function freeTvAuthJson(url, options = {}) {
  const response = await fetch(url, { ...options, signal: AbortSignal.timeout(15_000) });
  const raw = await response.text();
  let body = {};
  try { body = raw ? JSON.parse(raw) : {}; } catch { body = { message: raw.slice(0, 240) }; }
  if (!response.ok || body.success === false) {
    const message = body?.error?.msg || body?.error || body?.message || `Free TV HTTP ${response.status}.`;
    throw syncError(String(message), response.status === 401 || response.status === 403 ? 401 : 502);
  }
  return { body, response };
}

/**
 * Exchanges a Freebox login for an OQEE session. The password exists only for
 * this request and is never added to the NAS store, logs, or API response.
 */
async function freeTvFreeboxSession(login, password) {
  const init = await freeTvAuthJson("https://api.oqee.net/api/v2/user/oauth/init", {
    method: "POST",
    headers: freeTvAuthHeaders(),
    body: JSON.stringify({ provider: "free", platform: "web" })
  });
  const authorizationUrl = String(init.body?.result?.redirect_url || "");
  let authorization;
  try { authorization = new URL(authorizationUrl); } catch { throw syncError("Initialisation Free TV invalide.", 502); }
  const clientId = authorization.searchParams.get("client_id");
  const redirectUri = authorization.searchParams.get("redirect_uri");
  const state = authorization.searchParams.get("state");
  if (!clientId || !redirectUri || !state) throw syncError("Initialisation Free TV incomplète.", 502);

  const form = new URLSearchParams({
    login,
    pass: password,
    ok: "Se connecter",
    client_id: clientId,
    ressource: "",
    response_type: "code",
    redirect_uri: redirectUri,
    state
  });
  const authorizeResponse = await fetch("https://subscribe.free.fr/auth/auth.pl", {
    method: "POST",
    headers: {
      accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
      "content-type": "application/x-www-form-urlencoded",
      origin: "https://subscribe.free.fr",
      referer: "https://subscribe.free.fr/auth/auth.pl"
    },
    body: form,
    // Freebox returns the authorization result only after its redirect chain.
    // Following it matches the official browser flow without retaining cookies.
    redirect: "follow",
    signal: AbortSignal.timeout(15_000)
  });
  let exchangeToken = null;
  try {
    const finalUrl = new URL(authorizeResponse.url, "https://subscribe.free.fr");
    exchangeToken = finalUrl.searchParams.get("result");
    if (!exchangeToken) {
      const resultLocation = authorizeResponse.headers.get("location");
      exchangeToken = resultLocation ? new URL(resultLocation, "https://subscribe.free.fr").searchParams.get("result") : null;
    }
  } catch { /* handled below */ }
  if (!exchangeToken) throw syncError("Free a refusé la connexion. Utilise les identifiants de ton Espace Abonné Freebox, pas le compte local Freebox OS.", 401);

  const loginResult = await freeTvAuthJson("https://api.oqee.net/api/v5/user/login", {
    method: "POST",
    headers: freeTvAuthHeaders(),
    body: JSON.stringify({ type: "freeoa", token: exchangeToken })
  });
  const accessToken = String(loginResult.body?.result?.token || "").trim();
  if (accessToken.length < 24) throw syncError("Session Free TV absente après connexion.", 502);
  const profiles = await freeTvJson({ accessToken, profile: "pending" }, "/api/v2/user/profiles");
  const profile = String(profiles?.result?.[0]?.id || "").trim();
  return freeTvSessionPayload({ accessToken, profile });
}

async function freeTvJson(session, path, options = {}) {
  const response = await fetch(`https://api.oqee.net${path}`, {
    ...options,
    headers: { ...freeTvHeaders(session, options.rightsToken), ...(options.headers || {}) },
    signal: AbortSignal.timeout(15_000)
  });
  const raw = await response.text();
  let body;
  try { body = raw ? JSON.parse(raw) : {}; } catch { body = { message: raw }; }
  if (!response.ok) throw syncError(body.message || body.error || `Free TV HTTP ${response.status}.`, response.status === 401 ? 401 : 502);
  return body;
}

const FREEBOX_OS_API = "http://mafreebox.freebox.fr/api/v11";
const FREEBOX_OS_APP_ID = "fr.jojotv.companion";

function freeboxOsSecretSnapshot(accountId, profileId) {
  return syncProfileSecretSnapshot(accountId, profileId, "freebox-os-app-token");
}

async function freeboxOsJson(path, options = {}) {
  const response = await fetch(`${FREEBOX_OS_API}${path}`, {
    ...options,
    headers: { accept: "application/json", ...(options.headers || {}) },
    signal: AbortSignal.timeout(10_000)
  });
  const raw = await response.text();
  let body = {};
  try { body = raw ? JSON.parse(raw) : {}; } catch { body = { msg: raw.slice(0, 240) }; }
  if (!response.ok || body.success === false) throw syncError(String(body.msg || body.error_code || `Freebox OS HTTP ${response.status}.`), response.status === 401 || response.status === 403 ? 401 : 502);
  return body;
}

async function freeboxOsSession(secret) {
  const login = await freeboxOsJson("/login/");
  const challenge = String(login?.result?.challenge || "");
  if (!challenge) throw syncError("Défi Freebox OS indisponible.", 502);
  const password = crypto.createHmac("sha1", secret.appToken).update(challenge).digest("hex");
  const session = await freeboxOsJson("/login/session/", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ app_id: FREEBOX_OS_APP_ID, app_version: "1.0.0", password })
  });
  const token = String(session?.result?.session_token || "");
  if (!token) throw syncError("Session Freebox OS indisponible.", 502);
  return { token, permissions: session?.result?.permissions || {} };
}

async function freeboxOsPvrMedia(secret) {
  const session = await freeboxOsSession(secret);
  if (!session.permissions.pvr) throw syncError("Autorisation PVR absente dans Freebox OS.", 403);
  return freeboxOsJson("/pvr/media/", { headers: { "X-Fbx-App-Auth": session.token } });
}

async function freeboxOsPvrRecord(secret, details) {
  const session = await freeboxOsSession(secret);
  if (!session.permissions.pvr) throw syncError("Autorisation PVR absente dans Freebox OS.", 403);
  const headers = { "X-Fbx-App-Auth": session.token, "content-type": "application/json" };
  const channels = await freeboxOsJson("/tv/channels/", { headers });
  const wanted = String(details.channelName || "").trim().toLocaleLowerCase("fr-FR");
  const list = Object.values(channels?.result || {});
  const channel = list.find((item) => [item?.name, item?.short_name].some((name) => String(name || "").trim().toLocaleLowerCase("fr-FR") === wanted) && item?.available !== false && item?.has_service !== false);
  if (!channel?.uuid) throw syncError(`Chaîne locale Freebox introuvable : ${details.channelName}.`, 404);
  return freeboxOsJson("/pvr/programmed/", {
    method: "POST",
    headers,
    body: JSON.stringify({ channel_uuid: channel.uuid, channel_name: channel.name, channel_type: details.source, channel_quality: details.quality, media: details.media, path: "Enregistrements", name: details.name, broadcast_type: "tv", start: details.start, end: details.end, margin_before: details.marginBefore * 60, margin_after: details.marginAfter * 60 })
  });
}

function normalizeFreeboxThumbnailPath(value) {
  const trimmed = String(value || "").trim();
  if (!trimmed || trimmed.length > 4096 || trimmed.includes("\0")) {
    throw syncError("Chemin vidéo Freebox invalide.");
  }
  return trimmed.startsWith("/") ? trimmed : `/${trimmed}`;
}

function freeboxDownloadUrl(filePath) {
  const encodedPath = Buffer.from(filePath, "utf8").toString("base64");
  return `${FREEBOX_OS_API}/dl/${encodeURIComponent(encodedPath)}`;
}

async function probeFreeboxVideoDurationMs(downloadUrl, sessionToken) {
  const headers = `X-Fbx-App-Auth: ${sessionToken}\r\n`;
  try {
    const result = await execFileAsync("ffprobe", [
      "-v", "error", "-headers", headers,
      "-show_entries", "format=duration",
      "-of", "default=noprint_wrappers=1:nokey=1",
      downloadUrl
    ], { timeout: 30_000, maxBuffer: 1024 * 1024 });
    const seconds = Number(String(result.stdout || "").trim());
    return Number.isFinite(seconds) && seconds > 0 ? Math.round(seconds * 1000) : null;
  } catch (_) {
    return null;
  }
}

async function generateFreeboxThumbnailFrames(accountId, profileId, secret, details) {
  const filePath = normalizeFreeboxThumbnailPath(details.path);
  const requestedCount = Math.min(5, Math.max(1, Number(details.count) || 1));
  const fingerprint = crypto.createHash("sha256").update(JSON.stringify({
    version: FREEBOX_THUMBNAIL_VERSION,
    accountId,
    profileId,
    path: filePath,
    size: Number(details.size) || 0,
    modifiedMs: Number(details.modifiedMs) || 0
  })).digest("hex");
  const cacheDirectory = path.join(FREEBOX_THUMBNAIL_DIR, fingerprint);
  const jobKey = `${fingerprint}:${requestedCount}`;
  const currentJob = freeboxThumbnailJobs.get(jobKey);
  if (currentJob) return currentJob;

  const job = (async () => {
    await fsp.mkdir(cacheDirectory, { recursive: true, mode: 0o700 });
    const session = await freeboxOsSession(secret);
    const downloadUrl = freeboxDownloadUrl(filePath);
    const suppliedDuration = Number(details.durationMs);
    const durationMs = Number.isFinite(suppliedDuration) && suppliedDuration > 0
      ? suppliedDuration
      : await probeFreeboxVideoDurationMs(downloadUrl, session.token);
    const positionsMs = durationMs
      ? FREEBOX_THUMBNAIL_POSITIONS.map((fraction) => Math.max(1_000, Math.min(durationMs - 1_000, Math.round(durationMs * fraction))))
      : [60_000, 20_000, 180_000, 300_000, 480_000, 720_000];
    const frames = [];
    const headers = `X-Fbx-App-Auth: ${session.token}\r\n`;

    for (const positionMs of [...new Set(positionsMs)]) {
      if (frames.length >= requestedCount) break;
      const target = path.join(cacheDirectory, `frame-${positionMs}.jpg`);
      let usable = false;
      try {
        const stat = await fsp.stat(target);
        usable = stat.isFile() && stat.size >= 1024;
      } catch (_) { }
      if (!usable) {
        const temporary = `${target}.${crypto.randomUUID()}.tmp.jpg`;
        try {
          await execFileAsync("ffmpeg", [
            "-hide_banner", "-loglevel", "error",
            "-ss", (positionMs / 1000).toFixed(3),
            "-headers", headers, "-i", downloadUrl,
            "-map", "0:v:0", "-frames:v", "1",
            "-vf", "scale=720:-2:force_original_aspect_ratio=decrease",
            "-q:v", "3", "-y", temporary
          ], { timeout: 45_000, maxBuffer: 2 * 1024 * 1024 });
          const stat = await fsp.stat(temporary);
          if (stat.size < 1024) throw new Error("JPEG vide");
          await fsp.rename(temporary, target);
          await fsp.chmod(target, 0o600);
          usable = true;
        } catch (_) {
          await fsp.rm(temporary, { force: true }).catch(() => {});
        }
      }
      if (usable) {
        const jpeg = await fsp.readFile(target);
        frames.push({ positionMs, jpegBase64: jpeg.toString("base64") });
      }
    }
    if (!frames.length) throw syncError("FFmpeg n'a pu extraire aucune image de cette vidéo.", 502);
    return frames;
  })();
  freeboxThumbnailJobs.set(jobKey, job);
  try {
    return await job;
  } finally {
    freeboxThumbnailJobs.delete(jobKey);
  }
}

async function runFreeTvRecurringRecords() {
  const now = Math.floor(Date.now() / 1000);
  let changed = false;
  for (const rule of store.sync.freeTvRecurringRecords || []) {
    if (!rule.enabled || Number(rule.nextAt) > now + 7 * 86400) continue;
    try {
      const snapshot = freeTvSessionSnapshot(rule.accountId, rule.profileId);
      if (!snapshot) { rule.enabled = false; changed = true; continue; }
      const session = decryptSyncSecret(snapshot.value);
      const rightsToken = await freeTvRightsToken(session);
      await freeTvJson(session, "/api/v4/user/npvr/records", { method: "POST", rightsToken, headers: { "content-type": "application/json" }, body: JSON.stringify({ channel_id: rule.channelId, start: rule.nextAt, end: rule.nextAt + rule.durationSeconds, margin_before: rule.marginBefore * 60, margin_after: rule.marginAfter * 60 }) });
      rule.nextAt += 7 * 86400;
      changed = true;
    } catch (_) { }
  }
  if (changed) await persistStore();
}

async function freeTvRightsToken(session) {
  const rights = await freeTvJson(session, "/api/v3/user/rights_proxad");
  const tokenFrom = (value) => {
    if (!value || typeof value !== "object") return "";
    for (const [key, candidate] of Object.entries(value)) {
      if (["token", "rights_token", "rightsToken"].includes(key) && typeof candidate === "string" && candidate.trim()) return candidate.trim();
      const nested = tokenFrom(candidate);
      if (nested) return nested;
    }
    return "";
  };
  const token = tokenFrom(rights);
  if (!token) throw syncError("Droits Free TV indisponibles.", 502);
  return token;
}

function syncProfilePinSnapshot(accountId, profileId) {
  return store.sync.profilePins.find((item) => item.accountId === accountId && item.profileId === profileId) || null;
}

async function sendProvider(record) {
  const application = "JojoHome IPTV";
  record.status = "sending";
  record.message = `Envoi à ${application}…`;
  record.updatedAt = new Date().toISOString();
  await persistStore();
  try {
    targetFrom(record.target);
    if (record.action) throw new Error("Cette ancienne action n’est plus prise en charge.");
    const sent = await requestTv(record.target, "POST", "/api/providers", record.payload);
    if (sent.statusCode < 200 || sent.statusCode >= 300) throw new Error(sent.body.error || `${application} répond HTTP ${sent.statusCode}.`);
    record.remoteId = sent.body.id || null;
    record.status = sent.body.status || "pending";
    record.message = sent.body.message || `Demande envoyée à ${application}.`;
  } catch (error) {
    record.status = error.retryable ? "waiting_for_tv" : "error";
    record.message = error.retryable
      ? "TV indisponible. Nouvel essai automatique dans moins d’une minute."
      : (error.message || "Impossible d’envoyer le fournisseur.");
  }
  record.updatedAt = new Date().toISOString();
  await persistStore();
  return record;
}

async function retryWaitingProviders() {
  if (retryInProgress) return;
  retryInProgress = true;
  try {
    for (const record of store.history.filter((item) => item.status === "waiting_for_tv" && item.target.port === 8093 && !item.action)) {
      await sendProvider(record);
    }
  } finally {
    retryInProgress = false;
  }
}

async function refreshRecord(record) {
  if (record.target.port !== 8093 || record.action || !record.remoteId || FINAL_STATUSES.has(record.status)) return record;
  try {
    const result = await requestTv(record.target, "GET", `/api/status/${encodeURIComponent(record.remoteId)}`);
    if (result.statusCode >= 200 && result.statusCode < 300) {
      record.status = result.body.status || record.status;
      record.message = result.body.message || record.message;
      record.updatedAt = new Date().toISOString();
      await persistStore();
    }
  } catch (_) { }
  return record;
}

const UI_AVATAR_DIR = path.join(__dirname, "ui-avatars");
let avatarWriteQueue = Promise.resolve();

async function readAvatarCatalog() {
  const catalog = JSON.parse(await fsp.readFile(path.join(UI_AVATAR_DIR, "catalog.json"), "utf8"));
  if (catalog.version !== 1 || !Array.isArray(catalog.groups) || !Array.isArray(catalog.avatars)) {
    throw syncError("Catalogue d’avatars invalide.", 500);
  }
  return catalog;
}

async function companionAvatarCatalog() {
  const catalog = await readAvatarCatalog();
  const palette = JSON.parse(await fsp.readFile(path.join(UI_AVATAR_DIR, "avatar", "catalog.json"), "utf8"));
  return { version: catalog.version, groups: catalog.groups, avatars: catalog.avatars,
    deletedAvatars: catalog.deletedAvatars || [], colors: palette.avatars };
}

function avatarName(value) {
  if (typeof value !== "string" || !value.trim() || value.trim().length > 80 || /[\u0000-\u001f\u007f]/u.test(value)) {
    throw syncError("Saisis un nom d’avatar de 1 à 80 caractères.");
  }
  return value.trim();
}

function avatarPng(value) {
  if (typeof value !== "string" || !/^data:image\/png;base64,[A-Za-z0-9+/]+={0,2}$/.test(value)) {
    throw syncError("L’image doit être au format PNG.");
  }
  if (value.length > 3 * 1024 * 1024) throw syncError("Image trop volumineuse (2 Mo maximum).", 413);
  const data = Buffer.from(value.slice("data:image/png;base64,".length), "base64");
  if (data.length > 2 * 1024 * 1024) throw syncError("Image trop volumineuse (2 Mo maximum).", 413);
  if (data.length < 45 || !data.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])) ||
      data.toString("ascii", 12, 16) !== "IHDR" || data.readUInt32BE(16) < 1 || data.readUInt32BE(16) > 2048 ||
      data.readUInt32BE(20) < 1 || data.readUInt32BE(20) > 2048 || data.toString("ascii", data.length - 8, data.length - 4) !== "IEND") {
    throw syncError("Image PNG invalide ou dimensions supérieures à 2048 pixels.");
  }
  return data;
}

async function updateAvatarCatalog(change) {
  const operation = avatarWriteQueue.then(async () => {
    const catalog = await readAvatarCatalog();
    const createdImages = [];
    const temporary = path.join(UI_AVATAR_DIR, "catalog-" + crypto.randomUUID() + ".tmp");
    try {
      const result = await change(catalog, createdImages);
      await fsp.writeFile(temporary, JSON.stringify(catalog, null, 2) + "\n", { flag: "wx", mode: 0o600 });
      await fsp.rename(temporary, path.join(UI_AVATAR_DIR, "catalog.json"));
      return result;
    } catch (error) {
      await fsp.unlink(temporary).catch(() => {});
      await Promise.all(createdImages.map(file => fsp.unlink(file).catch(() => {})));
      throw error;
    }
  });
  avatarWriteQueue = operation.catch(() => {});
  return operation;
}

async function addCompanionAvatar(body) {
  return updateAvatarCatalog(async (catalog, createdImages) => {
    const name = avatarName(body.name);
    const group = body.group;
    if (!["avatar", "smileys", "famille", "animaux"].includes(group)) throw syncError("Groupe d’avatars inconnu.");
    let avatar;
    let image;
    if (group === "avatar") {
      const palette = JSON.parse(await fsp.readFile(path.join(UI_AVATAR_DIR, "avatar", "catalog.json"), "utf8"));
      const color = palette.avatars.find(item => item.id === body.colorId);
      if (!color) throw syncError("Choisis une couleur des thèmes JojoHome.");
      avatar = { id: color.id, group, name, color: color.color };
    } else if (group === "smileys") {
      const emoji = typeof body.emoji === "string" ? body.emoji.trim() : "";
      if (emoji.length > 32 || [...new Intl.Segmenter("fr", { granularity: "grapheme" }).segment(emoji)].length !== 1 ||
          !/[\p{Extended_Pictographic}\p{Regional_Indicator}\u20e3]/u.test(emoji)) {
        throw syncError("Saisis un seul smiley ou emoji.");
      }
      avatar = { id: "emoji:" + emoji, group, name, emoji };
    } else {
      image = avatarPng(body.image);
      const id = crypto.randomUUID();
      avatar = { id: "custom:" + id, group, name, image: "avatar-" + id + ".png" };
    }
    if (catalog.avatars.some(item => item.id === avatar.id)) throw syncError("Cet avatar existe déjà.", 409);
    if ((catalog.deletedAvatars || []).some(item => item.id === avatar.id)) {
      throw syncError("Cet avatar est dans la corbeille : restaure-le depuis cette page.", 409);
    }
    if (image) {
      const file = path.join(UI_AVATAR_DIR, avatar.image);
      await fsp.writeFile(file, image, { flag: "wx", mode: 0o600 });
      createdImages.push(file);
    }
    catalog.avatars.push(avatar);
    return avatar;
  });
}

async function changeCompanionAvatar(id, method, body) {
  return updateAvatarCatalog(catalog => {
    if (method === "RESTORE") {
      const index = (catalog.deletedAvatars || []).findIndex(item => item.id === id);
      if (index < 0) throw syncError("Avatar absent de la corbeille.", 404);
      if (catalog.avatars.some(item => item.id === id)) throw syncError("Cet avatar existe déjà.", 409);
      const { deletedAt, ...avatar } = catalog.deletedAvatars.splice(index, 1)[0];
      catalog.avatars.push(avatar);
      return avatar;
    }
    const index = catalog.avatars.findIndex(item => item.id === id);
    if (index < 0) throw syncError("Avatar introuvable.", 404);
    const avatar = catalog.avatars[index];
    if (method === "DELETE") {
      catalog.avatars.splice(index, 1);
      (catalog.deletedAvatars ||= []).push({ ...avatar, deletedAt: Date.now() });
      return avatar;
    }
    const name = avatarName(body.name);
    const group = body.group;
    // Image identifiers stay immutable, so TVs can keep their bitmap caches.
    if (avatar.image ? !["famille", "animaux"].includes(group) : group !== avatar.group) {
      throw syncError("Les images peuvent être classées dans Famille ou Animaux.");
    }
    catalog.avatars[index] = { ...avatar, name, group };
    return catalog.avatars[index];
  });
}

function companionAvatarPreview(avatar, initial = "A", ornamentColor = "") {
  const ornament = /^#[0-9a-f]{6}$/i.test(ornamentColor) ? ';--account-ornament:' + ornamentColor : "";
  const decoration = ornament ? ' accountAvatarOrnament' : "";
  if (avatar.image && /^[a-z0-9_-]+\.png$/.test(avatar.image)) {
    return '<img class="avatarPicture' + decoration + '" loading="lazy"' + (ornament ? ' style="' + ornament.slice(1) + '"' : '') + ' src="/api/ui-avatars/' + esc(avatar.image) + '" alt="' + esc(avatar.name) + '">';
  }
  if (avatar.emoji) return '<span class="avatarEmoji' + decoration + '"' + (ornament ? ' style="' + ornament.slice(1) + '"' : '') + ' aria-hidden="true">' + esc(avatar.emoji) + '</span>';
  const color = /^#[0-9a-f]{6}$/i.test(avatar.color) ? avatar.color : "#202F52";
  const channels = color.slice(1).match(/../g).map(hex => parseInt(hex, 16));
  const foreground = (channels[0] * 299 + channels[1] * 587 + channels[2] * 114) / 1000 >= 150 ? "#10192b" : "#ffffff";
  return '<span class="avatarInitial' + decoration + '" style="background:' + color + ';color:' + foreground + ornament + '" aria-label="Initiale ' + esc(initial) + '">' + esc(initial) + '</span>';
}

function companionAvatarClient() {
  const byId = id => document.getElementById(id);
  const section = byId("profiles");
  const status = byId("avatarStatus");
  const form = byId("avatarAddForm");
  const group = byId("avatarAddGroup");
  const files = byId("avatarFiles");
  const name = byId("avatarName");
  const add = byId("avatarAdd");
  let catalog = null, filter = "avatar", loading = null, adding = false;
  const preview = companionAvatarPreview;

  function render() {
    if (!catalog) return;
    const groups = [{ id: "all", name: "Tous" }, ...catalog.groups, { id: "trash", name: "Corbeille" }];
    byId("avatarGroups").innerHTML = groups.map(item => {
      const count = item.id === "trash" ? catalog.deletedAvatars.length : catalog.avatars.filter(avatar => item.id === "all" || avatar.group === item.id).length;
      return '<button type="button" data-avatar-group="' + esc(item.id) + '" aria-pressed="' + (filter === item.id) + '" class="' + (filter === item.id ? 'primary' : '') + '">' + esc(item.name) + ' <span class="avatarCount">' + count + '</span></button>';
    }).join("");
    byId("avatarGroups").querySelectorAll("button").forEach(button => button.onclick = () => { filter = button.dataset.avatarGroup; render(); });
    const query = byId("avatarSearch").value.trim().toLocaleLowerCase("fr");
    const list = (filter === "trash" ? catalog.deletedAvatars : catalog.avatars)
      .filter(avatar => (filter === "all" || filter === "trash" || avatar.group === filter) && avatar.name.toLocaleLowerCase("fr").includes(query));
    byId("avatarTotal").textContent = list.length + (list.length === 1 ? " avatar" : " avatars") + (filter === "trash" ? " dans la corbeille" : list.length === 1 ? " disponible" : " disponibles");
    byId("avatarGrid").innerHTML = list.length ? list.map(avatar => '<article class="avatarTile">' + preview(avatar) + '<b>' + esc(avatar.name) + '</b><span class="meta">' + esc(catalog.groups.find(item => item.id === avatar.group)?.name || avatar.group) + '</span><div class="actions">' +
      (filter === "trash" ? '<button type="button" data-avatar-restore="' + esc(avatar.id) + '" aria-label="Restaurer ' + esc(avatar.name) + '">Restaurer</button>' :
        '<button type="button" data-avatar-edit="' + esc(avatar.id) + '" aria-label="Modifier ' + esc(avatar.name) + '">Modifier</button><button type="button" class="danger" data-avatar-delete="' + esc(avatar.id) + '" aria-label="Supprimer ' + esc(avatar.name) + '">Supprimer</button>') + '</div></article>').join("") : '<p class="meta">Aucun avatar dans cette sélection.</p>';
    byId("avatarGrid").querySelectorAll("[data-avatar-edit]").forEach(button => button.onclick = () => edit(button.dataset.avatarEdit));
    byId("avatarGrid").querySelectorAll("[data-avatar-delete]").forEach(button => button.onclick = () => remove(button));
    byId("avatarGrid").querySelectorAll("[data-avatar-restore]").forEach(button => button.onclick = () => restore(button));
    const selectedColor = byId("avatarColor").value;
    byId("avatarColor").innerHTML = catalog.colors.map(color => '<option value="' + esc(color.id) + '">' + esc(color.name) + (catalog.avatars.some(avatar => avatar.id === color.id) ? ' — déjà disponible' : catalog.deletedAvatars.some(avatar => avatar.id === color.id) ? ' — dans la corbeille' : '') + '</option>').join("");
    if (catalog.colors.some(color => color.id === selectedColor)) byId("avatarColor").value = selectedColor;
    updateForm();
  }

  function updateForm() {
    const isImage = group.value === "famille" || group.value === "animaux";
    byId("avatarImageFields").hidden = !isImage;
    byId("avatarEmojiFields").hidden = group.value !== "smileys";
    byId("avatarColorFields").hidden = group.value !== "avatar";
    files.required = isImage;
    byId("avatarEmoji").required = group.value === "smileys";
    name.required = !isImage || files.files.length <= 1;
    byId("avatarNameHint").textContent = files.files.length > 1 && isImage ? "Pour plusieurs images, chaque nom de fichier devient le nom de l’avatar." : "Le nom apparaît dans le sélecteur d’avatars de la TV.";
    const color = catalog?.colors.find(item => item.id === byId("avatarColor").value);
    byId("avatarColorPreview").innerHTML = color ? preview(color) : "";
    add.disabled = group.value === "avatar" && (!color || catalog.avatars.concat(catalog.deletedAvatars).some(avatar => avatar.id === color.id));
    byId("avatarColorHint").textContent = add.disabled ? "Cette couleur existe déjà. Tu peux la modifier dans la galerie ou la restaurer depuis la corbeille." : "Couleurs des thèmes JojoHome. La TV affichera la première initiale de chaque compte.";
  }

  function loadCatalog(refresh = false) {
    if (byId("app").classList.contains("hidden")) return Promise.resolve();
    if (refresh && loading) return loading.then(() => loadCatalog());
    if (loading) return loading;
    status.textContent = "Chargement des avatars…";
    loading = secure("/api/companion/avatars").then(data => {
      catalog = data; render(); status.textContent = "Catalogue NAS à jour. Les avatars ajoutés seront disponibles dans le sélecteur de la TV.";
    }).catch(error => { status.textContent = error.message; }).finally(() => { loading = null; });
    return loading;
  }

  async function png(file) {
    if (!["image/png", "image/jpeg", "image/webp"].includes(file.type)) throw new Error("Choisis une image PNG, JPG ou WebP.");
    if (file.size > 20 * 1024 * 1024) throw new Error("Image trop volumineuse (20 Mo maximum avant réduction).");
    const image = await createImageBitmap(file);
    try {
      const canvas = document.createElement("canvas");
      canvas.width = 512; canvas.height = 512;
      const context = canvas.getContext("2d");
      const scale = Math.min(512 / image.width, 512 / image.height);
      const width = image.width * scale, height = image.height * scale;
      context.drawImage(image, (512 - width) / 2, (512 - height) / 2, width, height);
      return canvas.toDataURL("image/png");
    } finally { image.close(); }
  }

  form.onsubmit = async event => {
    event.preventDefault();
    if (adding) return;
    adding = true;
    form.querySelectorAll("input,select,button").forEach(input => { input.disabled = true; });
    let added = 0;
    try {
      const batch = ["famille", "animaux"].includes(group.value) ? [...files.files] : [null];
      if (!batch.length) throw new Error("Choisis au moins une image.");
      for (const file of batch) {
        status.textContent = "Ajout sur le NAS… " + (added + 1) + "/" + batch.length;
        const label = file && batch.length > 1 ? file.name.replace(/\.[^.]+$/, "").replace(/[_-]+/g, " ").slice(0, 80) : name.value.trim();
        await secure("/api/companion/avatars", { method: "POST", body: JSON.stringify({ name: label, group: group.value,
          image: file ? await png(file) : undefined, emoji: byId("avatarEmoji").value.trim(), colorId: byId("avatarColor").value }) });
        added++;
      }
      filter = group.value; files.value = ""; name.value = ""; byId("avatarEmoji").value = "";
      await loadCatalog(true); status.textContent = added + (added === 1 ? " avatar ajouté" : " avatars ajoutés") + " sur le NAS.";
    } catch (error) {
      await loadCatalog(true); status.textContent = (added ? added + " avatar(s) ajouté(s). " : "") + error.message;
    } finally { adding = false; form.querySelectorAll("input,select,button").forEach(input => { input.disabled = false; }); updateForm(); }
  };

  function remove(button) {
    const avatar = catalog.avatars.find(item => item.id === button.dataset.avatarDelete);
    const dialog = byId("avatarDeleteDialog"), confirm = byId("avatarDeleteConfirm");
    byId("avatarDeleteName").textContent = avatar.name;
    byId("avatarDeleteStatus").textContent = "";
    confirm.onclick = async () => {
      confirm.disabled = true;
      try { await secure("/api/companion/avatars/" + encodeURIComponent(avatar.id), { method: "DELETE" }); dialog.close(); await loadCatalog(true); status.textContent = "Avatar placé dans la corbeille."; }
      catch (error) { byId("avatarDeleteStatus").textContent = error.message; }
      finally { confirm.disabled = false; }
    };
    dialog.showModal(); byId("avatarDeleteCancel").focus();
  }

  async function restore(button) {
    button.disabled = true;
    try { await secure("/api/companion/avatars/" + encodeURIComponent(button.dataset.avatarRestore) + "/restore", { method: "POST" }); await loadCatalog(true); status.textContent = "Avatar restauré dans son groupe."; }
    catch (error) { status.textContent = error.message; button.disabled = false; }
  }

  function edit(id) {
    const avatar = catalog.avatars.find(item => item.id === id), dialog = byId("avatarEditDialog");
    byId("avatarEditPreview").innerHTML = preview(avatar);
    byId("avatarEditName").value = avatar.name;
    byId("avatarEditGroup").innerHTML = catalog.groups.filter(item => avatar.image ? ["famille", "animaux"].includes(item.id) : item.id === avatar.group)
      .map(item => '<option value="' + esc(item.id) + '">' + esc(item.name) + '</option>').join("");
    byId("avatarEditGroup").value = avatar.group; byId("avatarEditStatus").textContent = "";
    byId("avatarEditForm").onsubmit = async event => {
      event.preventDefault(); const save = byId("avatarEditSave"); save.disabled = true;
      try { await secure("/api/companion/avatars/" + encodeURIComponent(id), { method: "PATCH", body: JSON.stringify({ name: byId("avatarEditName").value.trim(), group: byId("avatarEditGroup").value }) }); dialog.close(); await loadCatalog(true); status.textContent = "Avatar modifié sur le NAS."; }
      catch (error) { byId("avatarEditStatus").textContent = error.message; }
      finally { save.disabled = false; }
    };
    dialog.showModal(); byId("avatarEditName").focus();
  }

  byId("avatarEditCancel").onclick = () => byId("avatarEditDialog").close();
  byId("avatarDeleteCancel").onclick = () => byId("avatarDeleteDialog").close();
  group.onchange = updateForm;
  byId("avatarColor").onchange = updateForm;
  files.onchange = () => { if (files.files.length === 1 && !name.value.trim()) name.value = files.files[0].name.replace(/\.[^.]+$/, "").replace(/[_-]+/g, " ").slice(0, 80); updateForm(); };
  byId("avatarSearch").oninput = render;
  document.querySelector('[data-view="profiles"]').addEventListener("click", () => loadCatalog());
  byId("refresh").addEventListener("click", () => { if (section.classList.contains("active")) loadCatalog(true); });
  new MutationObserver(() => { if (section.classList.contains("active")) loadCatalog(); }).observe(byId("app"), { attributes: true, attributeFilter: ["class"] });
  window.addEventListener("DOMContentLoaded", () => { if (section.classList.contains("active")) loadCatalog(); });
  updateForm();
}

function companionAccountClient() {
  const byId = id => document.getElementById(id), list = byId("profilesList");
  const status = byId("accountStatus");
  const editDialog = byId("accountEditDialog"), avatarDialog = byId("accountAvatarDialog"), deleteDialog = byId("accountDeleteDialog");
  const favoritesView = byId("accountFavoritesView"), favoritesGrid = byId("accountFavoritesGrid");
  const favoriteShortcuts = [...document.querySelectorAll("[data-favorite-shortcut]")], favoriteAccountSelect = byId("accountFavoritesAccount");
  let pickerAccount = null, pickerCatalog = null, pickerGroup = "avatar", pickerSaving = false;
  let favoriteAccount = null, favoriteItems = [], favoriteCategory = "all", favoriteLimit = 60, favoriteOrigin = null, lastFavoriteAccountId = null;
  let imageController = null, imageObserver = null, imageQueue = [], imageActive = 0, imageSession = 0;
  const imageCache = new Map(), categories = [{ id: "all", name: "Tous" }, { id: "live", name: "Chaînes" }, { id: "movies", name: "Films" }, { id: "series", name: "Séries" }];
  const initial = account => Array.from(account.name.trim())[0] || "C";
  function favoriteDuration(value) {
    const text = String(value || "").trim();
    if (!/^\d+(?:\s*(?:min(?:ute)?s?|m))?$/i.test(text)) return text;
    const minutes = parseInt(text, 10), hours = Math.floor(minutes / 60);
    return hours ? hours + "h" + String(minutes % 60).padStart(2, "0") : minutes + " min";
  }
  function message(value) { status.textContent = value; status.hidden = !value; }
  function focusAccount(id, action) {
    const button = [...list.querySelectorAll("[data-account-action]")].find(item => item.dataset.accountId === id && item.dataset.accountAction === action);
    (button || list.querySelector("button") || byId("refresh")).focus();
  }
  async function update(account, body) {
    await secure("/api/companion/accounts/" + encodeURIComponent(account.id), { method: "PATCH", body: JSON.stringify(body) });
    await load(); message("Compte enregistré. JojoHome et l’IPTV le reprendront à leur prochaine synchronisation.");
  }
  function stopImages() {
    imageSession++; imageController?.abort(); imageObserver?.disconnect(); imageQueue = []; imageActive = 0;
    for (const url of imageCache.values()) URL.revokeObjectURL(url);
    imageCache.clear();
  }
  function displayImage(container, url, title) {
    const image = document.createElement("img"); image.alt = title; image.src = url; image.decoding = "async";
    image.onerror = () => { if (container.isConnected) container.textContent = "Vignette indisponible"; };
    container.replaceChildren(image);
  }
  function loadImages() {
    while (imageActive < 4 && imageQueue.length) {
      const { container, item } = imageQueue.shift();
      if (!container.isConnected) continue;
      if (imageCache.has(item.id)) { displayImage(container, imageCache.get(item.id), item.title); continue; }
      imageActive++; const session = imageSession, signal = imageController.signal;
      (async () => {
        try {
          const response = await fetch(item.artwork, { headers: authHeaders(), signal });
          if (!response.ok) throw new Error("Vignette indisponible");
          const url = URL.createObjectURL(await response.blob());
          if (session !== imageSession) { URL.revokeObjectURL(url); return; }
          if (imageCache.has(item.id)) URL.revokeObjectURL(url); else imageCache.set(item.id, url);
          if (container.isConnected) displayImage(container, imageCache.get(item.id), item.title);
        } catch (_) { if (session === imageSession && container.isConnected) container.textContent = "Vignette indisponible"; }
        finally { if (session === imageSession) { imageActive--; loadImages(); } }
      })();
    }
  }
  function selectFavoriteShortcut(category) {
    favoriteShortcuts.forEach(button => {
      const active = button.dataset.favoriteShortcut === category;
      button.classList.toggle("active", active); button.setAttribute("aria-pressed", String(active));
    });
  }
  function renderFavorites() {
    if (!favoriteAccount) return;
    selectFavoriteShortcut(favoriteCategory);
    const belongs = (item, category) => category === "all" || ({ LIVE: "live", MOVIE: "movies", SERIES: "series", EPISODE: "series" })[item.type] === category;
    const groups = byId("accountFavoriteGroups");
    groups.innerHTML = categories.map(category => '<button type="button" data-favorite-category="' + category.id + '" aria-pressed="' + (category.id === favoriteCategory) + '" class="' + (category.id === favoriteCategory ? 'primary' : '') + '">' + category.name + ' (' + favoriteItems.filter(item => belongs(item, category.id)).length + ')</button>').join("");
    groups.querySelectorAll("button").forEach(button => button.onclick = () => {
      favoriteCategory = button.dataset.favoriteCategory; favoriteLimit = 60; renderFavorites(); groups.querySelector('[aria-pressed="true"]').focus();
    });
    const query = byId("accountFavoriteSearch").value.trim().toLocaleLowerCase("fr");
    const filtered = favoriteItems.filter(item => belongs(item, favoriteCategory) && [item.title, item.provider, item.category, item.genre, item.year].join(" ").toLocaleLowerCase("fr").includes(query));
    const visible = filtered.slice(0, favoriteLimit);
    byId("accountFavoriteTotal").textContent = filtered.length + (filtered.length === 1 ? " favori" : " favoris") + (visible.length < filtered.length ? " · " + visible.length + " affichés" : "");
    imageObserver?.disconnect(); imageQueue = [];
    favoritesGrid.innerHTML = visible.length ? visible.map(item => {
      const kind = ({ LIVE: "Chaîne", MOVIE: "Film", SERIES: "Série", EPISODE: "Épisode" })[item.type];
      const episode = item.type === "EPISODE" && item.episode ? (item.season ? "S" + String(item.season).padStart(2, "0") : "") + "E" + String(item.episode).padStart(2, "0") : "";
      const info = [item.year, item.genre, favoriteDuration(item.duration), item.rating ? "Note : " + item.rating : "", episode].filter(Boolean).join(" · ");
      const action = item.type === "SERIES" ? "Choisir un épisode" : "Lire";
      return '<article class="accountFavorite"><button type="button" class="favoriteArtwork' + (item.type === "LIVE" ? ' channelLogo' : '') + '" data-favorite-image="' + esc(item.id) + '" data-favorite-play="' + esc(item.id) + '" aria-label="' + esc(action + " : " + item.title) + '">' + (item.artwork ? 'Chargement…' : 'Sans vignette') + '</button><div class="favoriteInfo"><span class="favoriteKind">' + kind + '</span><h3 title="' + esc(item.title) + '">' + esc(item.title) + '</h3><p class="meta">' + esc([item.provider, item.category].filter(Boolean).join(" · ")) + '</p>' + (info ? '<p class="meta">' + esc(info) + '</p>' : '') +
        (item.description ? '<p class="favoriteDescription"><strong>Synopsis :</strong> ' + esc(item.description) + '</p>' : '') +
        (item.cast ? '<p class="favoriteCredits"><strong>Acteurs :</strong> ' + esc(item.cast) + '</p>' : '') +
        (item.director ? '<p class="favoriteCredits"><strong>Réalisateur :</strong> ' + esc(item.director) + '</p>' : '') +
        '<div class="actions"><button type="button" class="primary favoritePlay" data-favorite-play="' + esc(item.id) + '">' + (item.type === "SERIES" ? 'Choisir un épisode' : '▶ Lire') + '</button><button type="button" class="danger" data-favorite-delete="' + esc(item.id) + '" aria-label="Supprimer des favoris : ' + esc(item.title) + '">Supprimer</button></div></div></article>';
    }).join("") : '<p class="meta">' + (query ? 'Aucun favori ne correspond à la recherche.' : 'Aucun favori dans cette catégorie pour ce compte.') + '</p>';
    const more = byId("accountFavoriteMore"); more.hidden = visible.length >= filtered.length;
    more.onclick = () => {
      const previous = visible.length; favoriteLimit += 60; renderFavorites();
      const next = favoritesGrid.querySelectorAll("article")[previous];
      if (next) { next.tabIndex = -1; next.focus(); }
    };
    const items = new Map(visible.map(item => [item.id, item]));
    favoritesGrid.querySelectorAll("[data-favorite-play]").forEach(button => button.onclick = () => window.playCompanionFavorite(favoriteAccount, items.get(button.dataset.favoritePlay), button));
    favoritesGrid.querySelectorAll("[data-favorite-delete]").forEach(button => button.onclick = async () => {
      const item = items.get(button.dataset.favoriteDelete), account = favoriteAccount, session = imageSession;
      if (!item || !await window.confirmCompanionDeletion(item.title, "Retirer ce contenu des favoris du compte « " + account.name + " » ? Il restera disponible chez le fournisseur IPTV.")) return;
      button.disabled = true;
      try {
        await secure("/api/companion/accounts/" + encodeURIComponent(account.id) + "/favorites/" + encodeURIComponent(item.id) + "/delete", { method: "POST", body: JSON.stringify({ confirm: true }) });
        if (favoriteAccount?.id === account.id && session === imageSession) {
          favoriteItems = favoriteItems.filter(value => value.id !== item.id); renderFavorites();
          byId("accountFavoritesStatus").textContent = "Favori supprimé pour « " + account.name + " ».";
        }
        await load();
      } catch (error) { byId("accountFavoritesStatus").textContent = error.message; button.disabled = false; }
    });
    imageObserver = new IntersectionObserver(entries => {
      for (const entry of entries) if (entry.isIntersecting) {
        imageObserver.unobserve(entry.target); imageQueue.push({ container: entry.target, item: items.get(entry.target.dataset.favoriteImage) });
      }
      loadImages();
    }, { root: null, rootMargin: "150px" });
    favoritesGrid.querySelectorAll("[data-favorite-image]").forEach(container => {
      const item = items.get(container.dataset.favoriteImage);
      if (imageCache.has(item.id)) displayImage(container, imageCache.get(item.id), item.title);
      else if (item.artwork) imageObserver.observe(container);
    });
  }
  async function showFavorites(account, category, origin) {
    window.closeCompanionSeriesEpisodes?.();
    stopImages(); imageController = new AbortController(); const session = imageSession;
    favoriteAccount = account; favoriteItems = []; favoriteCategory = categories.some(item => item.id === category) ? category : "all";
    document.querySelectorAll(".view").forEach(view => view.classList.toggle("active", view === favoritesView));
    document.querySelectorAll(".navButton").forEach(button => button.classList.remove("active"));
    const title = favoriteCategory === "live" ? "Mes favoris TV" : favoriteCategory === "movies" ? "Mes favoris Films" : favoriteCategory === "series" ? "Mes favoris Séries" : "Mes favoris IPTV";
    byId("eyebrow").textContent = "IPTV · FAVORIS";
    byId("title").textContent = title;
    byId("subtitle").textContent = "Favoris synchronisés du compte " + account.name + ".";
    if (!favoriteShortcuts.includes(origin)) byId("accountFavoriteSearch").focus();
    lastFavoriteAccountId = account.id; selectFavoriteShortcut(favoriteCategory);
    const accounts = overview?.accounts || [];
    favoriteAccountSelect.innerHTML = accounts.map(item => '<option value="' + esc(item.id) + '">' + esc(item.name) + '</option>').join("");
    favoriteAccountSelect.value = account.id; byId("accountFavoritesAccountField").hidden = accounts.length < 2;
    favoriteLimit = 60; favoriteOrigin = origin;
    byId("accountFavoritesName").textContent = account.name; byId("accountFavoriteSearch").value = "";
    byId("accountFavoriteGroups").innerHTML = ""; byId("accountFavoriteTotal").textContent = ""; byId("accountFavoriteMore").hidden = true;
    favoritesGrid.textContent = "Chargement des favoris synchronisés…"; byId("accountFavoritesStatus").textContent = "";
    try {
      const result = await secure("/api/companion/accounts/" + encodeURIComponent(account.id) + "/favorites", { signal: imageController.signal });
      if (!favoritesView.classList.contains("active") || session !== imageSession) return;
      favoriteAccount = result.account; favoriteItems = result.favorites;
      byId("accountFavoritesName").textContent = favoriteAccount.name; renderFavorites();
    } catch (error) {
      if (favoritesView.classList.contains("active") && session === imageSession) { favoritesGrid.textContent = ""; byId("accountFavoritesStatus").textContent = error.message; }
    }
  }
  function edit(account) {
    const field = byId("accountEditName"), save = byId("accountEditSave");
    field.value = account.name; byId("accountEditStatus").textContent = "";
    byId("accountEditForm").onsubmit = async event => {
      event.preventDefault(); if (save.disabled) return; save.disabled = true;
      try { await update(account, { name: field.value }); if (editDialog.open) editDialog.close(); focusAccount(account.id, "edit"); }
      catch (error) { byId("accountEditStatus").textContent = error.message; }
      finally { save.disabled = false; }
    };
    editDialog.showModal(); field.focus(); field.select();
  }
  function renderOrnaments() {
    if (!pickerAccount) return;
    const colors = companionAccountOrnaments(), selected = pickerAccount.ornamentColor || "#D3A033";
    const choices = byId("accountOrnamentChoices");
    choices.innerHTML = colors.map(color => '<button type="button" class="accountOrnamentChoice" data-account-ornament="' + color.hex + '" style="--account-ornament:' + color.hex + '" aria-pressed="' + (selected === color.hex) + '"><span class="ornamentSwatch" aria-hidden="true"></span><span>' + esc(color.name) + '</span></button>').join("");
    byId("accountOrnamentPreview").innerHTML = companionAvatarPreview(pickerAccount.avatar || {}, initial(pickerAccount), selected);
    choices.querySelectorAll("button").forEach(button => button.onclick = async () => {
      if (pickerSaving || button.dataset.accountOrnament === selected) return;
      const account = pickerAccount, color = button.dataset.accountOrnament;
      pickerSaving = true; avatarDialog.querySelectorAll("button,input").forEach(input => { input.disabled = true; });
      byId("accountAvatarStatus").textContent = "Enregistrement de la couleur d’ornement…";
      try {
        await update(account, { ornamentColor: color });
        if (avatarDialog.open && pickerAccount?.id === account.id) {
          pickerAccount = overview?.accounts.find(item => item.id === account.id) || { ...account, ornamentColor: color };
          renderOrnaments(); renderPicker(); byId("accountAvatarStatus").textContent = "Couleur d’ornement enregistrée.";
        }
      } catch (error) {
        if (avatarDialog.open && pickerAccount?.id === account.id) byId("accountAvatarStatus").textContent = error.message;
      } finally {
        pickerSaving = false; avatarDialog.querySelectorAll("button,input").forEach(input => { input.disabled = false; });
        if (avatarDialog.open && pickerAccount?.id === account.id) choices.querySelector('[data-account-ornament="' + color + '"]')?.focus();
      }
    });
  }
  function renderPicker() {
    if (!pickerCatalog || !pickerAccount) return;
    renderOrnaments();
    const groups = byId("accountAvatarGroups");
    groups.innerHTML = pickerCatalog.groups.map(group => '<button type="button" data-account-avatar-group="' + esc(group.id) + '" aria-pressed="' + (group.id === pickerGroup) + '" class="' + (group.id === pickerGroup ? 'primary' : '') + '">' + esc(group.name) + '</button>').join("");
    groups.querySelectorAll("button").forEach(button => button.onclick = () => {
      if (pickerSaving) return;
      pickerGroup = button.dataset.accountAvatarGroup; renderPicker(); groups.querySelector('[aria-pressed="true"]').focus();
    });
    const query = byId("accountAvatarSearch").value.trim().toLocaleLowerCase("fr");
    const avatars = pickerCatalog.avatars.filter(avatar => avatar.group === pickerGroup && avatar.name.toLocaleLowerCase("fr").includes(query));
    const grid = byId("accountAvatarGrid");
    grid.innerHTML = avatars.length ? avatars.map(avatar => {
      const selected = avatar.id === pickerAccount.avatarId || avatar.emoji && avatar.emoji === pickerAccount.avatarId;
      return '<button type="button" class="accountAvatarChoice' + (selected ? ' primary' : '') + '" data-account-avatar="' + esc(avatar.id) + '" aria-pressed="' + Boolean(selected) + '" aria-label="' + esc(avatar.name) + '">' + companionAvatarPreview(avatar, initial(pickerAccount), pickerAccount.ornamentColor || "#D3A033") + '<span>' + esc(avatar.name) + '</span></button>';
    }).join("") : '<p class="meta">Aucun avatar dans ce groupe.</p>';
    grid.querySelectorAll("button").forEach(button => button.onclick = async () => {
      if (pickerSaving) return; pickerSaving = true;
      avatarDialog.querySelectorAll("button,input").forEach(input => { input.disabled = true; });
      byId("accountAvatarStatus").textContent = "Enregistrement de l’avatar…";
      try { await update(pickerAccount, { avatarId: button.dataset.accountAvatar }); if (avatarDialog.open) avatarDialog.close(); focusAccount(pickerAccount.id, "avatar"); }
      catch (error) { byId("accountAvatarStatus").textContent = error.message; }
      finally { pickerSaving = false; avatarDialog.querySelectorAll("button,input").forEach(input => { input.disabled = false; }); }
    });
  }
  async function chooseAvatar(account) {
    pickerAccount = account; pickerCatalog = null; byId("accountAvatarSearch").value = "";
    renderOrnaments();
    byId("accountAvatarName").textContent = account.name; byId("accountAvatarGroups").innerHTML = "";
    byId("accountAvatarGrid").textContent = "Chargement des avatars du NAS…"; byId("accountAvatarStatus").textContent = "";
    avatarDialog.showModal(); byId("accountAvatarCancel").focus();
    try {
      const catalog = await plain("/api/ui-avatars/catalog.json");
      if (!avatarDialog.open || pickerAccount.id !== account.id) return;
      pickerCatalog = catalog;
      pickerGroup = catalog.avatars.find(avatar => avatar.id === account.avatarId || avatar.emoji && avatar.emoji === account.avatarId)?.group || "avatar";
      renderPicker(); (byId("accountAvatarGrid").querySelector('[aria-pressed="true"]') || byId("accountAvatarGrid").querySelector("button") || byId("accountAvatarCancel")).focus();
    } catch (error) { byId("accountAvatarStatus").textContent = error.message; }
  }
  function remove(account) {
    byId("accountDeleteName").textContent = account.name; byId("accountDeleteStatus").textContent = "";
    const confirm = byId("accountDeleteConfirm");
    confirm.onclick = async () => {
      if (confirm.disabled) return; confirm.disabled = true;
      try {
        await secure("/api/companion/accounts/" + encodeURIComponent(account.id), { method: "DELETE", body: JSON.stringify({ confirm: true }) });
        if (deleteDialog.open) deleteDialog.close(); await load(); focusAccount("", "edit");
        message("Compte supprimé. JojoHome et l’IPTV le reprendront à leur prochaine synchronisation.");
      } catch (error) { byId("accountDeleteStatus").textContent = error.message; }
      finally { confirm.disabled = false; }
    };
    deleteDialog.showModal(); byId("accountDeleteCancel").focus();
  }
  list.onclick = event => {
    const button = event.target.closest("[data-account-action]");
    const account = button && overview?.accounts.find(item => item.id === button.dataset.accountId);
    if (!account) return;
    if (button.dataset.accountAction === "edit") edit(account);
    else if (button.dataset.accountAction === "avatar") chooseAvatar(account);
    else if (button.dataset.accountAction === "delete") remove(account);
    else if (button.dataset.accountAction === "favorites") showFavorites(account, button.dataset.accountCategory, button);
  };
  favoriteShortcuts.forEach(button => button.onclick = () => {
    const accounts = overview?.accounts || [];
    const account = accounts.find(item => item.id === lastFavoriteAccountId) || accounts[0];
    if (!account) {
      document.querySelector('[data-view="profiles"]').click(); return;
    }
    showFavorites(account, button.dataset.favoriteShortcut, button);
  });
  document.querySelectorAll(".navButton").forEach(button => button.addEventListener("click", () => {
    window.closeCompanionSeriesEpisodes?.(); stopImages(); selectFavoriteShortcut(null);
  }));
  favoriteAccountSelect.onchange = () => {
    const account = overview?.accounts.find(item => item.id === favoriteAccountSelect.value);
    if (account) showFavorites(account, favoriteCategory, favoriteOrigin);
  };
  byId("accountEditCancel").onclick = () => editDialog.close();
  byId("accountAvatarCancel").onclick = () => avatarDialog.close();
  byId("accountDeleteCancel").onclick = () => deleteDialog.close();
  byId("accountAvatarSearch").oninput = renderPicker;
  byId("accountFavoriteSearch").oninput = () => { favoriteLimit = 60; renderFavorites(); };
}

function companionIptvProvidersClient() {
  const byId = id => document.getElementById(id);
  const list = byId("iptvProvidersList"), status = byId("iptvProvidersStatus");
  if (!list || !status) return;
  let revision = 0, refreshTimer, importRevision = 0, importTimer;
  const types = { M3U: "M3U", XTREAM: "Xtream", STALKER: "Stalker" };
  const css = document.createElement("style");
  css.textContent = `.iptvProviderRow{display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap;padding:14px 16px;margin:10px 0;border:1px solid var(--line,#2a3b5b);border-radius:14px;background:#0b1426}.iptvProviderInfo{display:flex;align-items:center;gap:12px;min-width:180px}.iptvProviderActions{display:flex;gap:8px;flex-wrap:wrap}.iptvProviderActions button,.iptvAdminDialog button{width:auto;margin:0;padding:9px 13px}.iptvAdminDialog{width:min(980px,calc(100vw - 32px));max-height:90vh;overflow:auto;padding:22px;border:1px solid #344766;border-radius:18px;background:#111d33;color:#f5f7ff}.iptvAdminDialog::backdrop{background:#020611c9}.iptvAdminDialog h2{margin:0 0 8px}.iptvAdminDialog .iptvAdminHint{color:#a9bad6;line-height:1.45}.iptvAdminDialog label{display:block;margin:12px 0 6px;color:#c7d4ec}.iptvAdminDialog input:not([type=checkbox]),.iptvAdminDialog textarea,.iptvAdminDialog select{box-sizing:border-box;width:100%;margin:0;padding:10px 12px;border:1px solid #344766;border-radius:10px;background:#0a1324;color:#f5f7ff;font:inherit}.iptvAdminDialog input[type=checkbox]{width:20px;height:20px;accent-color:var(--yellow,#FFB309);vertical-align:middle}.iptvAdminGrid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px 14px}.iptvAdminActions{display:flex;justify-content:flex-end;gap:8px;flex-wrap:wrap;margin-top:16px}.iptvAdminStatus{min-height:24px;margin-top:10px;color:#a9bad6}.iptvAdminGroup,.iptvAdminChannel{display:grid;grid-template-columns:minmax(120px,1fr) minmax(140px,1.2fr) auto auto;align-items:center;gap:8px;padding:10px 0;border-bottom:1px solid #293a58}.iptvAdminGroupName{font-weight:700}.iptvAdminVisible{white-space:nowrap}.iptvAdminChannels{margin-top:22px;padding-top:14px;border-top:1px solid #344766}.iptvAdminChannel{grid-template-columns:minmax(150px,1fr) minmax(180px,1.4fr) auto}.iptvAdminSmall{padding:7px 10px!important}.iptvAdminClose{float:right}.iptvAdminDialog button.danger{background:#87353f;color:#fff}@media(max-width:650px){.iptvAdminGrid{grid-template-columns:1fr}.iptvAdminGroup{grid-template-columns:1fr 1fr}.iptvAdminChannel{grid-template-columns:1fr 1fr}.iptvAdminGroupName{grid-column:1/-1}}`;
  document.head.appendChild(css);
  const makeDialog = () => {
    const dialog = document.createElement("dialog");
    dialog.className = "iptvAdminDialog";
    document.body.appendChild(dialog);
    return dialog;
  };
  const editor = makeDialog(), manager = makeDialog(), dnsDialog = makeDialog();
  css.textContent += ".iptvDnsDialog input,.iptvDnsDialog select,.iptvPlaylistDns input,.iptvPlaylistDns select{margin-top:8px!important}.iptvDnsDialog input[readonly],.iptvPlaylistDns input[readonly]{color:#b7c4da}.iptvPlaylistDns{grid-column:1/-1;margin-top:14px;padding-top:12px;border-top:1px solid #344766}.iptvPlaylistDns [hidden]{display:none!important}";
  dnsDialog.classList.add("iptvDnsDialog");
  const targetQuery = () => "?tv=" + encodeURIComponent(byId("tvIp").value.trim()) + "&port=" + encodeURIComponent(byId("tvPort").value.trim());
  const catalogUrl = params => "/api/tv/catalog" + targetQuery() + "&" + new URLSearchParams(params).toString();
  const report = (node, message) => { if (node) node.textContent = message || ""; };
  const parseHeaders = value => {
    const headers = {};
    for (const line of value.split("\n").filter(value => value.trim())) {
      const index = line.indexOf(":");
      if (index < 1) throw new Error("Chaque header doit être au format Nom: valeur.");
      headers[line.slice(0, index).trim()] = line.slice(index + 1).trim();
    }
    return headers;
  };
  const field = (label, name, placeholder, type = "text") => '<label>' + esc(label) + '<input name="' + esc(name) + '" type="' + esc(type) + '" placeholder="' + esc(placeholder || "") + '" autocomplete="off"></label>';
  const playlistDnsMarkup = '<div class="iptvPlaylistDns"><label>DNS de cette playlist (facultatif)<select name="playlistDnsMode"><option value="tv">Utiliser le DNS par défaut de la TV</option></select></label><p class="iptvAdminHint" data-playlist-dns-note>Aucun DNS spécifique : cette playlist suivra le réglage de la TV.</p><div class="iptvAdminGrid" data-playlist-dns-addresses hidden>' + field("DNS principal", "playlistDnsPrimary", "Adresse IPv4 ou IPv6") + field("DNS secondaire (facultatif)", "playlistDnsSecondary", "Adresse IPv4 ou IPv6") + '</div></div>';
  const bindPlaylistDns = (root, data, config = null) => {
    const select = root.querySelector('[name="playlistDnsMode"]');
    const inputs = ["playlistDnsPrimary", "playlistDnsSecondary"].map(name => root.querySelector('[name="' + name + '"]'));
    select.replaceChildren();
    const presets = data?.presets || [];
    const options = [{ id: "tv", name: "Utiliser le DNS par défaut de la TV" }];
    if (data) options.push({ id: "automatic", name: "Automatique (réseau de la TV)" }, ...presets, { id: "custom", name: "Personnalisé" });
    options.forEach(value => { const option = document.createElement("option"); option.value = value.id; option.textContent = value.name; select.append(option); });
    select.value = config?.mode || "tv";
    inputs[0].value = config?.primary || ""; inputs[1].value = config?.secondary || "";
    select.disabled = !data;
    const changed = () => {
      const mode = select.value, preset = presets.find(value => value.id === mode);
      root.querySelector("[data-playlist-dns-addresses]").hidden = mode === "tv" || mode === "automatic";
      inputs.forEach((input, index) => {
        input.readOnly = mode !== "custom"; input.disabled = mode === "tv" || mode === "automatic";
        if (preset) input.value = index ? preset.secondary : preset.primary;
        else if (mode === "tv" || mode === "automatic") input.value = "";
      });
      inputs[0].required = mode === "custom";
      root.querySelector("[data-playlist-dns-note]").textContent = preset ? (preset.note || "Ce DNS sera utilisé uniquement pour cette playlist.")
        : mode === "tv" ? "Aucun DNS spécifique : cette playlist suivra le réglage de la TV."
        : mode === "automatic" ? "Cette playlist utilisera le DNS fourni par le réseau de la TV."
        : "Saisis une ou deux adresses IP DNS. Elles seront enregistrées avec cette playlist.";
    };
    select.onchange = changed; changed();
    return () => {
      if (select.value === "tv") return { dns: null, clearDns: true };
      if (select.disabled) throw new Error("Le réglage DNS de cette playlist est indisponible. Actualise la connexion à la TV.");
      const primary = inputs[0].value.trim(), secondary = inputs[1].value.trim();
      if (select.value === "custom" && !primary) throw new Error("Saisis le DNS principal de cette playlist.");
      return { dns: { mode: select.value, primary, secondary }, clearDns: false };
    };
  };
  const importButton = byId("sendProvider");
  let playlistDnsTarget = "";
  let loadPlaylistDns = () => {};
  if (importButton) {
    importButton.insertAdjacentHTML("beforebegin", playlistDnsMarkup);
    const root = importButton.previousElementSibling;
    window.iptvPlaylistDnsPayload = bindPlaylistDns(root, null);
    let dnsRevision = 0;
    loadPlaylistDns = async () => {
      const version = ++dnsRevision, tv = byId("tvIp").value.trim();
      playlistDnsTarget = tv ? tv + ":" + byId("tvPort").value.trim() : "";
      const mode = root.querySelector("select").value;
      const selected = mode === "tv" ? null : { mode, primary: root.querySelector('[name="playlistDnsPrimary"]').value,
        secondary: root.querySelector('[name="playlistDnsSecondary"]').value };
      root.querySelector("select").disabled = true;
      if (!tv) { root.querySelector("[data-playlist-dns-note]").textContent = "Sélectionne une TV pour choisir un DNS spécifique."; return; }
      try {
        const data = await secure("/api/tv/dns" + targetQuery());
        if (version !== dnsRevision) return;
        window.iptvPlaylistDnsPayload = bindPlaylistDns(root, data, selected);
      } catch (error) {
        if (version === dnsRevision) root.querySelector("[data-playlist-dns-note]").textContent = error.message;
      }
    };
    loadPlaylistDns();
  }
  const openDns = async () => {
    const tv = byId("tvIp").value.trim();
    if (!tv) { report(status, "Sélectionne une TV pour régler son DNS."); return; }
    const url = "/api/tv/dns" + targetQuery();
    const testUrl = "/api/tv/dns/test" + targetQuery();
    dnsDialog.setAttribute("aria-labelledby", "iptvDnsTitle");
    dnsDialog.innerHTML = '<button type="button" class="iptvAdminClose" aria-label="Fermer le réglage DNS">Fermer</button><h2 id="iptvDnsTitle">DNS par défaut de Jojo Home · ' + esc(tv) + '</h2><p class="iptvAdminHint">Ce réglage est propre à cette TV. Les playlists sans DNS spécifique l’utilisent pour le catalogue et la lecture. Un changement de DNS ne garantit pas la résolution d’un refus HTTP 403.</p><form><label>Fournisseur DNS<select name="mode"></select></label><p class="iptvAdminHint" data-dns-note></p><div class="iptvAdminGrid">' + field("DNS principal", "primary", "Adresse IPv4 ou IPv6") + field("DNS secondaire (facultatif)", "secondary", "Adresse IPv4 ou IPv6") + '</div>' + field("Serveur à tester", "host", "exemple.com") + '<div class="iptvAdminActions"><button type="button" data-dns-test>Tester depuis cette TV</button><button type="button" data-dns-reset>Rétablir Automatique</button><button type="submit" class="primary">Enregistrer sur cette TV</button></div></form><p class="iptvAdminStatus" role="status" aria-live="polite"></p>';
    const form = dnsDialog.querySelector("form"), feedback = dnsDialog.querySelector(".iptvAdminStatus"), note = dnsDialog.querySelector("[data-dns-note]");
    const mode = form.elements.mode, primary = form.elements.primary, secondary = form.elements.secondary;
    let presets = [], busy = false, loaded = false;
    const modeChanged = () => {
      const preset = presets.find(value => value.id === mode.value), custom = mode.value === "custom";
      primary.readOnly = secondary.readOnly = !custom;
      primary.required = custom;
      primary.disabled = secondary.disabled = busy || mode.value === "automatic";
      if (preset) { primary.value = preset.primary; secondary.value = preset.secondary; }
      else if (mode.value === "automatic") { primary.value = secondary.value = ""; }
      report(note, preset ? (preset.note || "Adresses du fournisseur DNS sélectionné.") : (mode.value === "automatic"
        ? "DNS fourni par le réseau de la TV. Les noms du réseau local utilisent toujours ce DNS."
        : "Saisis une ou deux adresses IP de serveur DNS."));
    };
    const display = data => {
      presets = data.presets || [];
      mode.innerHTML = [{ id: "automatic", name: "Automatique" }, ...presets, { id: "custom", name: "Personnalisé" }]
        .map(value => '<option value="' + esc(value.id) + '">' + esc(value.name) + '</option>').join("");
      mode.value = data.config.mode;
      primary.value = data.config.primary || ""; secondary.value = data.config.secondary || "";
      loaded = true;
      modeChanged();
    };
    const config = () => ({ mode: mode.value, primary: primary.value.trim(), secondary: secondary.value.trim() });
    const action = async operation => {
      if (busy) return;
      busy = true;
      form.querySelectorAll("input,select,button").forEach(input => { input.disabled = true; });
      report(feedback, "Connexion à la TV…");
      try { await operation(); }
      catch (error) { report(feedback, error.message || "La TV n’est plus disponible."); }
      finally {
        busy = false;
        form.querySelectorAll("input,select,button").forEach(input => { input.disabled = !loaded; });
        if (loaded) modeChanged();
        if (dnsDialog.open && feedback.isConnected) feedback.scrollIntoView({ block: "nearest" });
      }
    };
    dnsDialog.querySelector(".iptvAdminClose").onclick = () => dnsDialog.close();
    mode.onchange = () => { modeChanged(); report(feedback, "Choix de DNS modifié. Teste-le puis enregistre-le sur cette TV."); };
    primary.oninput = secondary.oninput = () => report(feedback, "Adresses DNS modifiées. Teste-les puis enregistre-les sur cette TV.");
    form.onsubmit = event => { event.preventDefault(); const body = config(); action(async () => {
      display(await secure(url, { method: "POST", body: JSON.stringify(body) }));
      report(feedback, "DNS enregistré pour les prochaines connexions de Jojo Home sur cette TV.");
    }); };
    dnsDialog.querySelector("[data-dns-reset]").onclick = () => action(async () => {
      display(await secure(url, { method: "POST", body: JSON.stringify({ mode: "automatic" }) }));
      report(feedback, "DNS Automatique rétabli sur cette TV.");
    });
    dnsDialog.querySelector("[data-dns-test]").onclick = () => {
      const body = { config: config(), host: form.elements.host.value.trim() };
      action(async () => {
        const result = await secure(testUrl, { method: "POST", body: JSON.stringify(body) });
        report(feedback, result.host + " → " + result.addresses.join(", ") + " · " + result.elapsedMs + " ms. "
          + (result.systemForLocalHost ? "DNS du réseau local utilisé. " : "") + "Test effectué sans enregistrer.");
      });
    };
    dnsDialog.showModal();
    await action(async () => { display(await secure(url)); report(feedback, "Configuration actuelle chargée depuis cette TV."); });
  };
  const checkbox = (label, name, checked) => '<label><input type="checkbox" name="' + esc(name) + '" ' + (checked ? "checked" : "") + '> ' + esc(label) + '</label>';
  let providers = [];
  const render = (values, emptyMessage) => {
    providers = Array.isArray(values) ? values : [];
    list.innerHTML = providers.length ? [...providers].sort((left, right) => left.name.localeCompare(right.name, "fr"))
      .map(provider => '<article class="iptvProviderRow"><div class="iptvProviderInfo"><b>' + esc(provider.name) + '</b><span class="iptvProviderType">' + esc(types[provider.type] || provider.type) + '</span></div><div class="iptvProviderActions"><button type="button" data-iptv-action="edit" data-id="' + esc(provider.id) + '">Modifier</button><button type="button" data-iptv-action="manage" data-id="' + esc(provider.id) + '">Groupes et chaînes</button><button type="button" class="danger" data-iptv-action="delete" data-id="' + esc(provider.id) + '">Supprimer</button></div></article>').join("") : emptyMessage;
  };
  const providerUrl = id => "/api/tv/providers/" + encodeURIComponent(id) + targetQuery();
  const openEditor = async provider => {
    editor.innerHTML = '<button type="button" class="iptvAdminClose" data-close>Fermer</button><h2>Modifier ' + esc(provider.name) + '</h2><p class="iptvAdminHint">Les champs sensibles ne sont jamais affichés. Laisse-les vides pour conserver la configuration actuelle.</p><div class="iptvAdminStatus" role="status"></div><div class="iptvAdminForm"></div><div class="iptvAdminActions"><button type="button" data-close>Annuler</button><button type="button" class="primary" data-save>Enregistrer les modifications</button></div>';
    const message = editor.querySelector(".iptvAdminStatus"), formBox = editor.querySelector(".iptvAdminForm");
    try {
      report(message, "Chargement du fournisseur…");
      const detail = await secure(providerUrl(provider.id));
      const type = String(detail.type || provider.type).toUpperCase();
      const fields = [field("Nom du fournisseur", "name", "Nom", "text")];
      if (type === "M3U") {
        fields.push(field("URL de la playlist M3U", "m3uUrl", "Vide = conserver l’adresse actuelle", "url"));
        fields.push(field("URL XMLTV", "epgUrl", "Vide = conserver l’adresse actuelle", "url"));
        if (detail.hasEpg) fields.push(checkbox("Effacer l’adresse XMLTV actuelle", "clearEpgUrl", false));
      } else if (type === "XTREAM") {
        fields.push(field("URL du serveur", "serverUrl", "Vide = conserver l’adresse actuelle", "url"));
        fields.push(field("Nom d’utilisateur", "username", "Vide = conserver l’utilisateur actuel"));
        fields.push(field("Mot de passe", "password", detail.hasPassword ? "Vide = conserver le mot de passe actuel" : "Mot de passe", "password"));
      } else {
        fields.push(field("URL du portail Stalker", "portalUrl", "Vide = conserver l’adresse actuelle", "url"));
        fields.push(field("Adresse MAC", "macAddress", "Vide = conserver l’adresse actuelle"));
        fields.push(field("Identifiant du terminal", "deviceId", "Vide = conserver l’identifiant actuel"));
      }
      fields.push('<label>Headers HTTP ' + (detail.hasHeaders ? '· configuration actuelle conservée' : '') + '<textarea name="headers" rows="3" placeholder="Vide = conserver · un par ligne : Nom: valeur"></textarea></label>');
      if (detail.hasHeaders) fields.push(checkbox("Effacer les headers HTTP actuels", "clearHeaders", false));
      fields.push(checkbox("Inclure les chaînes TV", "includeLive", detail.includeLive));
      fields.push(checkbox("Inclure les films et séries", "includeVod", detail.includeVod));
      fields.push(playlistDnsMarkup);
      formBox.innerHTML = '<form class="iptvAdminGrid">' + fields.join("") + '</form>';
      const form = formBox.querySelector("form");
      form.elements.name.value = detail.name;
      let dnsPayload;
      try { dnsPayload = bindPlaylistDns(form, await secure("/api/tv/dns" + targetQuery()), detail.dns); }
      catch (error) { bindPlaylistDns(form, null); form.querySelector("[data-playlist-dns-note]").textContent = error.message; }
      report(message, "Les valeurs laissées vides restent inchangées.");
      editor.showModal();
      editor.querySelectorAll("[data-close]").forEach(button => button.onclick = () => editor.close());
      editor.querySelector("[data-save]").onclick = async () => {
        const value = name => form.elements[name]?.value.trim() || "";
        const body = {
          type: type.toLowerCase(), name: value("name"), m3uUrl: value("m3uUrl"), serverUrl: value("serverUrl"),
          username: value("username"), password: form.elements.password?.value || "", portalUrl: value("portalUrl"),
          macAddress: value("macAddress"), deviceId: value("deviceId"), epgUrl: value("epgUrl"), headers: parseHeaders(value("headers")),
          clearEpgUrl: Boolean(form.elements.clearEpgUrl?.checked), clearHeaders: Boolean(form.elements.clearHeaders?.checked),
          includeLive: Boolean(form.elements.includeLive?.checked), includeVod: Boolean(form.elements.includeVod?.checked)
        };
        if (!body.includeLive && !body.includeVod) return report(message, "Active au moins les chaînes TV ou la VOD.");
        try {
          if (dnsPayload) Object.assign(body, dnsPayload());
          report(message, "Enregistrement sur la TV…");
          await secure(providerUrl(provider.id), { method: "POST", body: JSON.stringify(body) });
          editor.close();
          await window.loadCompanionProviders();
          report(status, "Fournisseur mis à jour sur la TV.");
        } catch (error) { report(message, error.message); }
      };
    } catch (error) {
      report(message, error.message);
      editor.showModal();
      editor.querySelectorAll("[data-close]").forEach(button => button.onclick = () => editor.close());
    }
  };
  const openManager = provider => {
    manager.innerHTML = '<button type="button" class="iptvAdminClose" data-close>Fermer</button><h2>Groupes et chaînes · ' + esc(provider.name) + '</h2><p class="iptvAdminHint">Tu peux renommer, masquer ou réordonner les éléments. Les groupes et chaînes restent ceux du fournisseur ; les masquer les retire de JojoHome.</p><label>Type de contenu<select id="iptvManageType"><option value="LIVE">Télévision</option><option value="MOVIE">Films</option><option value="SERIES">Séries</option></select></label><div class="iptvAdminActions"><button type="button" data-all-visible="true">Afficher tous les groupes</button><button type="button" data-all-visible="false">Masquer tous les groupes</button></div><div id="iptvManageStatus" class="iptvAdminStatus" role="status"></div><div id="iptvManageGroups"></div><div class="iptvAdminChannels"><h3 id="iptvManageChannelTitle">Chaînes</h3><div class="iptvAdminActions"><button type="button" data-channel-page-visible="true">Afficher les chaînes chargées</button><button type="button" data-channel-page-visible="false">Masquer les chaînes chargées</button></div><div id="iptvManageChannels" class="iptvAdminHint">Choisis un groupe.</div><button type="button" class="iptvAdminSmall" id="iptvManageMore" hidden>Charger la suite</button></div>';
    manager.showModal();
    manager.querySelectorAll("[data-close]").forEach(button => button.onclick = () => manager.close());
    const typeSelect = manager.querySelector("#iptvManageType"), message = manager.querySelector("#iptvManageStatus");
    const groupsBox = manager.querySelector("#iptvManageGroups"), channelsBox = manager.querySelector("#iptvManageChannels");
    const channelTitle = manager.querySelector("#iptvManageChannelTitle"), more = manager.querySelector("#iptvManageMore");
    let groups = [], selectedGroup = "", nextPage = null, channelRows = [];
    const readCatalog = async (type, groupId = "", page = 0) => secure(catalogUrl({ sourceId: provider.id, type, groupId, page }));
    const update = async body => secure("/api/tv/catalog" + targetQuery(), { method: "POST", body: JSON.stringify({ sourceId: provider.id, type: typeSelect.value, ...body }) });
    const renderGroups = () => {
      groupsBox.innerHTML = groups.length ? groups.map((group, index) => '<div class="iptvAdminGroup" data-id="' + esc(group.id) + '"><button type="button" class="iptvAdminGroupName" data-group-pick>' + esc(group.name) + '</button><label class="iptvAdminVisible"><input type="checkbox" data-group-visible ' + (group.visible ? 'checked' : '') + '> Visible</label><input data-group-name aria-label="Nom du groupe" value="' + esc(group.name) + '"><button type="button" class="iptvAdminSmall" data-group-save>Enregistrer</button><span><button type="button" class="iptvAdminSmall" data-group-move="up" ' + (index === 0 ? 'disabled' : '') + '>↑</button> <button type="button" class="iptvAdminSmall" data-group-move="down" ' + (index === groups.length - 1 ? 'disabled' : '') + '>↓</button></span></div>').join("") : '<p class="iptvAdminHint">Aucun groupe dans cette source pour ce type.</p>';
    };
    const loadGroups = async () => {
      selectedGroup = ""; channelRows = []; nextPage = null; channelTitle.textContent = "Chaînes"; channelsBox.textContent = "Choisis un groupe."; more.hidden = true;
      report(message, "Chargement des groupes…");
      try { groups = (await readCatalog(typeSelect.value)).groups || []; renderGroups(); report(message, groups.length + " groupes chargés."); }
      catch (error) { report(message, error.message); groupsBox.innerHTML = ""; }
    };
    const renderChannels = () => {
      channelsBox.innerHTML = channelRows.length ? channelRows.map(item => '<div class="iptvAdminChannel" data-id="' + esc(item.id) + '"><b>' + esc(item.name) + '</b><input data-channel-name aria-label="Nom de la chaîne" value="' + esc(item.name) + '"><label class="iptvAdminVisible"><input type="checkbox" data-channel-visible ' + (item.visible ? 'checked' : '') + '> Visible</label><div class="actions"><button type="button" data-channel-save>Enregistrer</button><button type="button" class="danger" data-channel-delete>Supprimer</button></div></div>').join("") : '<p class="iptvAdminHint">Aucun contenu dans ce groupe.</p>';
      more.hidden = nextPage === null;
    };
    const loadChannels = async (group, page = 0) => {
      selectedGroup = group.id;
      channelTitle.textContent = "Chaînes · " + group.name;
      if (page === 0) { channelRows = []; channelsBox.textContent = "Chargement des chaînes…"; }
      try {
        const data = await readCatalog(typeSelect.value, selectedGroup, page);
        const rows = (data.items || []).map(item => ({ ...item, _page: page }));
        channelRows = page === 0 ? rows : channelRows.concat(rows);
        nextPage = data.nextPage ?? null;
        renderChannels();
        report(message, channelRows.length + " chaîne(s) chargée(s) · les noms vides restaurent le nom du fournisseur.");
      } catch (error) { report(message, error.message); }
    };
    const saveGroupOrder = async () => {
      try { await update({ entity: "group", orderIds: groups.map(group => group.id) }); report(message, "Ordre des groupes enregistré."); }
      catch (error) { report(message, error.message); await loadGroups(); }
    };
    groupsBox.onclick = async event => {
      const row = event.target.closest(".iptvAdminGroup");
      if (!row) return;
      const group = groups.find(item => item.id === row.dataset.id);
      if (!group) return;
      try {
        if (event.target.closest("[data-group-pick]")) return loadChannels(group);
        if (event.target.closest("[data-group-save]")) {
          const wasSelected = selectedGroup === group.id;
          await update({ entity: "group", id: group.id, name: row.querySelector("[data-group-name]").value.trim(), visible: row.querySelector("[data-group-visible]").checked });
          report(message, "Groupe mis à jour.");
          await loadGroups();
          if (wasSelected) await loadChannels(groups.find(item => item.id === group.id) || group);
          return;
        }
        const move = event.target.closest("[data-group-move]")?.dataset.groupMove;
        if (move) {
          const from = groups.findIndex(item => item.id === group.id), to = from + (move === "up" ? -1 : 1);
          if (to < 0 || to >= groups.length) return;
          const reordered = [...groups]; reordered.splice(to, 0, reordered.splice(from, 1)[0]); groups = reordered; renderGroups(); await saveGroupOrder();
        }
      } catch (error) { report(message, error.message); }
    };
    groupsBox.onchange = async event => {
      if (!event.target.matches("[data-group-visible]")) return;
      const row = event.target.closest(".iptvAdminGroup"), group = groups.find(item => item.id === row?.dataset.id);
      if (!group) return;
      try { await update({ entity: "group", id: group.id, visible: event.target.checked }); group.visible = event.target.checked; report(message, "Visibilité du groupe enregistrée."); }
      catch (error) { event.target.checked = group.visible; report(message, error.message); }
    };
    channelsBox.onclick = async event => {
      const button = event.target.closest("[data-channel-save],[data-channel-delete]");
      if (!button) return;
      const row = button.closest(".iptvAdminChannel"), item = channelRows.find(value => value.id === row?.dataset.id);
      if (!item) return;
      try {
        if (button.matches("[data-channel-delete]")) {
          if (!await window.confirmCompanionDeletion(item.name, "Masquer ce contenu dans Jojo Home ? Il restera chez le fournisseur et pourra être réaffiché avec l’option « Visible ».")) return;
          button.disabled = true;
          await update({ entity: "channel", groupId: selectedGroup, page: item._page, id: item.id, visible: false });
          item.visible = false; renderChannels(); report(message, "Contenu supprimé de l’affichage de Jojo Home.");
          return;
        }
        await update({ entity: "channel", groupId: selectedGroup, page: item._page, id: item.id, name: row.querySelector("[data-channel-name]").value.trim(), visible: row.querySelector("[data-channel-visible]").checked });
        const currentGroup = groups.find(group => group.id === selectedGroup);
        if (currentGroup) await loadChannels(currentGroup, 0);
        report(message, "Contenu mis à jour.");
      } catch (error) { report(message, error.message); button.disabled = false; }
    };
    channelsBox.onchange = async event => {
      if (!event.target.matches("[data-channel-visible]")) return;
      const row = event.target.closest(".iptvAdminChannel"), item = channelRows.find(value => value.id === row?.dataset.id);
      if (!item) return;
      try { await update({ entity: "channel", groupId: selectedGroup, page: item._page, id: item.id, visible: event.target.checked }); item.visible = event.target.checked; report(message, "Visibilité de la chaîne enregistrée."); }
      catch (error) { event.target.checked = item.visible; report(message, error.message); }
    };
    more.onclick = () => { if (nextPage !== null) loadChannels(groups.find(group => group.id === selectedGroup), nextPage); };
    typeSelect.onchange = loadGroups;
    manager.querySelectorAll("[data-all-visible]").forEach(button => button.onclick = async () => {
      try { await update({ entity: "group", all: true, visible: button.dataset.allVisible === "true" }); await loadGroups(); }
      catch (error) { report(message, error.message); }
    });
    manager.querySelectorAll("[data-channel-page-visible]").forEach(button => button.onclick = async () => {
      if (!selectedGroup || !channelRows.length) return report(message, "Choisis d’abord un groupe avec des chaînes chargées.");
      const pages = [...new Set(channelRows.map(item => item._page))];
      try {
        for (const page of pages) await update({ entity: "channel", groupId: selectedGroup, page, all: true, visible: button.dataset.channelPageVisible === "true" });
        const group = groups.find(item => item.id === selectedGroup);
        const loadedPageCount = Math.max(...pages) + 1;
        if (group) {
          await loadChannels(group, 0);
          for (let index = 1; index < loadedPageCount && nextPage !== null; index++) await loadChannels(group, nextPage);
        }
        report(message, "Visibilité des chaînes chargées enregistrée.");
      } catch (error) { report(message, error.message); }
    });
    loadGroups();
  };
  list.onclick = event => {
    const button = event.target.closest("[data-iptv-action]");
    if (!button) return;
    const provider = providers.find(item => item.id === button.dataset.id);
    if (!provider) return;
    if (button.dataset.iptvAction === "edit") openEditor(provider);
    if (button.dataset.iptvAction === "manage") openManager(provider);
    if (button.dataset.iptvAction === "delete") {
      if (!confirm('Supprimer « ' + provider.name + ' » de cette TV ? Ses favoris et ses réglages IPTV locaux seront aussi supprimés.')) return;
      secure(providerUrl(provider.id), { method: "DELETE" }).then(async () => {
        report(status, "Fournisseur supprimé de la TV.");
        await window.loadCompanionProviders();
      }).catch(error => report(status, error.message));
    }
  };
  const refreshButton = byId("refreshIptvProviders");
  window.loadCompanionProviders = async () => {
    const version = ++revision, tv = byId("tvIp").value.trim(), port = byId("tvPort").value.trim();
    if (tv && playlistDnsTarget !== tv + ":" + port) loadPlaylistDns();
    const cached = overview?.providers || [];
    render(cached, tv ? "Recherche des fournisseurs sur la TV…" : "Aucun fournisseur synchronisé pour le moment.");
    if (!tv) {
      status.textContent = "Sélectionne une TV pour actualiser ses fournisseurs.";
      return;
    }
    refreshButton.disabled = true;
    refreshButton.textContent = "Actualisation…";
    status.textContent = "Connexion à la TV pour actualiser les fournisseurs…";
    try {
      const data = await secure("/api/tv/providers?tv=" + encodeURIComponent(tv) + "&port=" + encodeURIComponent(port));
      if (version !== revision) return;
      if (data.message) {
        if (!cached.length) render([], "TV indisponible : aucun fournisseur en cache sur le NAS.");
        status.textContent = cached.length
          ? "Liste en cache conservée. Vérifie que Jojo Home est actif sur la TV, puis actualise."
          : data.message;
      } else {
        providers = Array.isArray(data.providers) ? data.providers : [];
        render(data.providers, "Aucun fournisseur intégré sur cette TV.");
        status.textContent = "Liste actualisée depuis la TV sélectionnée.";
      }
    } catch (error) {
      if (version !== revision) return;
      if (!cached.length) render([], "Actualisation impossible : vérifie que la TV est joignable.");
      status.textContent = error.message;
    } finally {
      if (version === revision) {
        refreshButton.disabled = false;
        refreshButton.textContent = "Actualiser";
      }
    }
  };
  byId("refreshIptvProviders").onclick = () => window.loadCompanionProviders();
  byId("configureIptvDns").onclick = openDns;
  byId("tvIp").addEventListener("input", () => {
    revision++;
    clearTimeout(refreshTimer);
    refreshTimer = setTimeout(window.loadCompanionProviders, 300);
    loadPlaylistDns();
  });
  byId("tvPort").addEventListener("input", loadPlaylistDns);
  byId("testTv").addEventListener("click", loadPlaylistDns);
  byId("testTv").addEventListener("click", window.loadCompanionProviders);
  window.companionProviderSubmitted = record => {
    const version = ++importRevision;
    clearTimeout(importTimer);
    if (!record.id) return;
    const follow = async current => {
      if (version !== importRevision) return;
      byId("iptvStatus").textContent = current.message;
      if (current.status === "success") {
        await load();
        return;
      }
      if (["error", "rejected"].includes(current.status)) {
        loadProviderHistory();
        return;
      }
      importTimer = setTimeout(async () => {
        try { await follow(await plain("/api/status/" + encodeURIComponent(record.id))); }
        catch (error) { if (version === importRevision) byId("iptvStatus").textContent = error.message; }
      }, current.status === "waiting_for_tv" ? 10000 : 1500);
    };
    follow(record).catch(error => { if (version === importRevision) byId("iptvStatus").textContent = error.message; });
  };
}

function companionPlaybackClient() {
  const byId = id => document.getElementById(id), dialog = byId("companionPlayerDialog"), video = byId("companionPlayerVideo");
  const surface = byId("companionPlayerSurface");
  const status = byId("companionPlayerStatus"), favoriteListPane = byId("accountFavoriteListPane"), episodesPane = byId("accountFavoriteEpisodesPane");
  const seasons = byId("companionPlayerSeason"), episodeList = byId("accountFavoriteEpisodesList"), episodesStatus = byId("accountFavoriteEpisodesStatus");
  const tracks = byId("companionPlayerTracks"), audioSelect = byId("companionPlayerAudio"), subtitleSelect = byId("companionPlayerSubtitle"), modeSelect = byId("companionPlayerMode");
  const timeline = byId("companionPlayerTimeline"), seek = byId("companionPlayerSeek"), trackStatus = byId("companionPlayerTrackStatus");
  const libraries = new Map();
  let episodeArtworkObserver = null, episodeArtworkQueue = [], episodeArtworkActive = 0, episodeArtworkSession = 0;
  const episodeArtworkUrls = new Set();
  let account = null, favorite = null, origin = null, episodes = [], selected = null, session = null, engine = null, controller = null;
  let generation = 0, started = false, resumed = false, lastSave = 0;
  let liveReconnectTimer = null, liveReconnectAttempts = 0, liveStartedAt = 0;
  let media = null, relay = null, format = "", resumeTarget = 0, attemptOptions = null;
  const duration = () => relay && media?.duration > 0 ? media.duration : video.duration;
  const position = () => (relay?.position || 0) + video.currentTime;
  const endpoint = () => "/api/companion/accounts/" + encodeURIComponent(account.id) + "/favorites/" + encodeURIComponent(favorite.id);
  const resumeKey = () => "jojohome_companion_resume:" + JSON.stringify([overview?.account?.id || "", account?.id, selected?.id]);
  function savedPlayback() {
    try { const value = JSON.parse(localStorage.getItem(resumeKey()) || "null"); return Number.isFinite(value?.position) && value.position > 0 ? value : null; } catch (_) { return null; }
  }
  function savedPosition() { return savedPlayback()?.position || 0; }
  function savePosition(force = false) {
    if (!selected || selected.type === "LIVE" || !started || video.seeking || !resumed && resumeTarget > 0 || !Number.isFinite(duration()) || duration() <= 0 || !Number.isFinite(position()) || (!force && Date.now() - lastSave < 15_000)) return;
    try {
      const key = resumeKey();
      if (video.ended || position() >= duration() - 10) localStorage.removeItem(key);
      else localStorage.setItem(key, JSON.stringify({ position: position(), duration: duration(), updatedAt: Date.now(),
        options: relay ? { relay: true, compatible: relay.compatible, ...(relay.audio >= 0 ? { audio: relay.audio } : {}), subtitle: relay.subtitle } : null }));
      lastSave = Date.now();
    } catch (_) {}
  }
  function stopStream() {
    clearTimeout(liveReconnectTimer); liveReconnectTimer = null; liveStartedAt = 0;
    savePosition(true); generation++; controller?.abort(); controller = null;
    const previous = session; session = null;
    if (previous) fetch("/api/companion/playback/" + previous, { method: "DELETE", keepalive: true }).catch(() => {});
    if (engine) { const previousEngine = engine; engine = null; previousEngine.destroy(); }
    video.pause(); video.removeAttribute("src"); video.load(); selected = null; started = false; resumed = false; lastSave = 0;
    media = null; relay = null; format = ""; resumeTarget = 0; attemptOptions = null; tracks.hidden = true; timeline.hidden = true;
    byId("companionPlayerRestart").hidden = true;
  }
  function clearEpisodeArtwork() {
    episodeArtworkSession++; episodeArtworkObserver?.disconnect(); episodeArtworkObserver = null; episodeArtworkQueue = []; episodeArtworkActive = 0;
    for (const url of episodeArtworkUrls) URL.revokeObjectURL(url);
    episodeArtworkUrls.clear();
  }
  function loadEpisodeArtworkQueue() {
    while (episodeArtworkActive < 4 && episodeArtworkQueue.length) {
      const { image, item, version } = episodeArtworkQueue.shift();
      if (!image.isConnected) continue;
      episodeArtworkActive++;
      (async () => {
        try {
          const response = await fetch(item.artwork, { headers: authHeaders() });
          if (!response.ok) throw new Error("Vignette indisponible.");
          const url = URL.createObjectURL(await response.blob());
          if (version !== episodeArtworkSession || !image.isConnected) { URL.revokeObjectURL(url); return; }
          episodeArtworkUrls.add(url); image.src = url; image.hidden = false; image.nextElementSibling.hidden = true;
        } catch (_) {
          if (version === episodeArtworkSession && image.isConnected) { image.hidden = true; image.nextElementSibling.textContent = "Vignette indisponible"; }
        } finally {
          if (version === episodeArtworkSession) { episodeArtworkActive--; loadEpisodeArtworkQueue(); }
        }
      })();
    }
  }
  function loadLibrary(name, module = false) {
    if (libraries.has(name)) return libraries.get(name);
    const promise = new Promise((resolve, reject) => {
      const script = document.createElement("script"); script.src = "/api/companion/player-assets/" + name;
      if (module) script.type = "module";
      script.onload = resolve; script.onerror = () => { libraries.delete(name); script.remove(); reject(new Error("La bibliothèque du lecteur est indisponible sur le NAS.")); };
      document.head.appendChild(script);
    });
    libraries.set(name, promise); return promise;
  }
  function configurePlayer(item) {
    const live = item.type === "LIVE", tag = live ? "live-video-player" : "video-player";
    let player = byId("companionVideoJs");
    if (player.localName !== tag) {
      const replacement = document.createElement(tag), skin = document.createElement(live ? "live-video-skin" : "video-skin");
      replacement.id = player.id; skin.className = "companionVideoSkin";
      skin.appendChild(video); replacement.appendChild(skin); player.replaceWith(replacement); player = replacement;
    }
    player.setAttribute("content-title", byId("companionPlayerTitle").textContent);
  }
  function autoplay() {
    video.play()?.catch(error => { if (dialog.open && selected && error.name === "NotAllowedError") status.textContent = "Appuie sur Lecture dans le lecteur pour démarrer."; });
  }
  function playbackError(message, version) {
    if (!dialog.open || version !== generation) return;
    const item = selected, previousMedia = media, previousRelay = relay, previousFormat = format, previousTarget = resumeTarget, previousOptions = attemptOptions;
    stopStream(); selected = item; media = previousMedia; relay = previousRelay; format = previousFormat; resumeTarget = previousTarget; attemptOptions = previousOptions;
    surface.hidden = true;
    renderTracks(); status.textContent = message; byId("companionPlayerRetry").hidden = false;
  }
  function trackLabel(track, index) { return track.title || track.name || track.language || track.lang || "Piste " + (index + 1); }
  function renderTracks() {
    const hls = format === "hls" && engine && !relay;
    const audio = media ? media.audio : hls ? engine.audioTracks : Array.from(video.audioTracks || []);
    const subtitles = media ? media.subtitles : hls ? engine.subtitleTracks : Array.from(video.textTracks).filter(track => ["subtitles", "captions"].includes(track.kind));
    const currentOptions = relay || attemptOptions;
    const audioValue = currentOptions?.audio ?? (hls ? engine.audioTrack : audio.findIndex(track => track.enabled));
    const subtitleValue = currentOptions?.subtitle ?? (hls ? engine.subtitleTrack : subtitles.findIndex(track => track.mode === "showing"));
    audioSelect.innerHTML = audio.length ? audio.map((track, index) => '<option value="' + (media ? track.id : index) + '">' + esc(trackLabel(track, index)) + '</option>').join("") : '<option value="-1">Piste par défaut</option>';
    subtitleSelect.innerHTML = '<option value="-1">Désactivés</option>' + subtitles.map((track, index) => '<option value="' + (media ? track.id : index) + '">' + esc(trackLabel(track, index)) + '</option>').join("");
    audioSelect.value = String(audioValue >= 0 ? audioValue : media ? (audio.find(track => track.default) || audio[0])?.id ?? -1 : 0);
    subtitleSelect.value = String(subtitleValue ?? -1);
    audioSelect.disabled = audio.length < 2; subtitleSelect.disabled = !subtitles.length;
    tracks.hidden = !selected; modeSelect.value = currentOptions?.compatible ? "compatible" : "original";
  }
  function relayOptions(at = !resumed && resumeTarget > 0 ? resumeTarget : position()) {
    if (video.ended || media?.duration > 0 && at >= media.duration - 1) at = 0;
    return { relay: true, compatible: modeSelect.value === "compatible", position: selected?.type === "LIVE" ? 0 : at,
      ...(Number(audioSelect.value) >= 0 ? { audio: Number(audioSelect.value) } : {}), subtitle: Number(subtitleSelect.value) };
  }
  async function play(item, options = null, focusOrigin = null) {
    if (focusOrigin) origin = focusOrigin;
    const previousMedia = selected?.id === item.id ? media : null;
    stopStream(); selected = item; media = previousMedia; controller = new AbortController(); const version = generation;
    const saved = savedPlayback(); options = options || (saved?.options?.relay === true ? { ...saved.options, position: saved.position } : {});
    resumeTarget = options.position ?? (item.type === "LIVE" ? 0 : savedPosition());
    attemptOptions = options; renderTracks();
    surface.hidden = false; byId("companionPlayerRetry").hidden = true;
    byId("companionPlayerBack").hidden = favorite.type !== "SERIES";
    byId("companionPlayerTitle").textContent = item.type === "EPISODE" ? favorite.title + " · S" + item.season + " E" + item.episode + " · " + item.title : item.title;
    status.textContent = options.relay || options.compatible ? "Préparation de la vidéo sur le NAS…" : "Ouverture du flux…";
    trackStatus.textContent = options.relay || options.compatible ? options.compatible ? "Mode compatible · 1080p maximum. La conversion utilise le NAS." : "Qualité vidéo originale · pistes préparées sur le NAS." : "";
    byId("companionPlayerRetry").onclick = () => { liveReconnectAttempts = 0; play(item, options); };
    try {
      await loadLibrary("videojs-10.0.1/locales/fr.js", true);
      // The video bundle registers the i18n provider shared by the VOD and live skins.
      await loadLibrary("videojs-10.0.1/video.js", true);
      if (item.type === "LIVE") await loadLibrary("videojs-10.0.1/live-video.js", true);
      if (!dialog.open || version !== generation) return;
      configurePlayer(item);
      const playback = await secure(endpoint() + "/playback", { method: "POST", body: JSON.stringify({ ...(item.id === favorite.id ? {} : { episode: item.id }), ...options }), signal: controller.signal });
      if (!dialog.open || version !== generation) { fetch("/api/companion/playback/" + playback.session, { method: "DELETE" }).catch(() => {}); return; }
      session = playback.session;
      format = playback.format; relay = playback.relay || null; media = playback.media || null;
      if (relay) { resumed = true; resumeTarget = 0; byId("companionPlayerRestart").hidden = relay.position <= 0; }
      renderTracks();
      if (relay && item.type !== "LIVE" && media.duration > 0) { timeline.hidden = false; seek.max = media.duration; seek.value = relay.position; }
      trackStatus.textContent = relay?.compatible ? "Mode compatible · 1080p maximum. La conversion utilise le NAS." : relay ? "Qualité vidéo originale · pistes préparées sur le NAS." : "";
      if (playback.format === "hls") {
        await loadLibrary("hls-1.7.3.min.js"); if (version !== generation) return;
        if (window.Hls?.isSupported()) {
          engine = new window.Hls({ enableWorker: true, startPosition: relay ? 0 : resumeTarget || -1, ...(relay?.subtitle >= 0 && !relay.bitmap ? { subtitlePreference: { name: "Subtitles" } } : {}) });
          engine.on(window.Hls.Events.ERROR, (_, data) => { if (data.fatal) playbackError(data.type === "networkError" ? "Le flux HLS est indisponible ou a été interrompu. Réessaie la lecture." : "Ce flux ou ses codecs ne sont pas pris en charge par ce navigateur.", version); });
          for (const event of [window.Hls.Events.AUDIO_TRACKS_UPDATED, window.Hls.Events.AUDIO_TRACK_SWITCHED, window.Hls.Events.SUBTITLE_TRACKS_UPDATED, window.Hls.Events.SUBTITLE_TRACK_SWITCH]) engine.on(event, renderTracks);
          engine.on(window.Hls.Events.MANIFEST_PARSED, autoplay); engine.loadSource(playback.url); engine.attachMedia(video);
        } else if (video.canPlayType("application/vnd.apple.mpegurl")) { video.src = playback.url; autoplay(); }
        else throw new Error("Ce navigateur ne prend pas en charge la lecture HLS.");
      } else if (playback.format === "mpegts") {
        await loadLibrary("mpegts-1.8.2.min.js"); if (version !== generation) return;
        if (!window.mpegts?.isSupported()) throw new Error("Ce navigateur ne prend pas en charge les flux TV MPEG-TS.");
        window.mpegts.LoggingControl.enableAll = false;
        engine = window.mpegts.createPlayer({ type: "mpegts", isLive: playback.live, url: new URL(playback.url, location.href).href }, { enableWorker: true, liveBufferLatencyChasing: playback.live });
        engine.on(window.mpegts.Events.ERROR, () => playbackError("Ce flux ou ses codecs ne sont pas pris en charge, ou le fournisseur a interrompu la lecture.", version));
        engine.attachMediaElement(video); engine.load(); autoplay();
      } else { video.src = playback.url; autoplay(); }
      if (!relay && playback.format !== "hls") {
        secure("/api/companion/playback/" + session + "/media", { signal: controller.signal }).then(value => {
          if (version !== generation) return;
          media = value; renderTracks();
          trackStatus.textContent = value.video?.codec === "hevc" ? "Vidéo HEVC : si Edge ne montre pas l’image, choisis le mode compatible." : "";
        }).catch(() => { if (version === generation) trackStatus.textContent = "Les pistes intégrées nécessitent FFprobe sur le NAS."; });
      }
      video.focus();
    } catch (error) {
      playbackError(error.name === "TypeError" && !session ? options.relay || options.compatible
        ? "La connexion au NAS a été interrompue pendant la préparation de la vidéo. Réessaie la lecture."
        : "La connexion au NAS a été interrompue. Réessaie la lecture." : error.message, version);
    }
  }
  function renderEpisodes() {
    clearEpisodeArtwork();
    const version = episodeArtworkSession;
    const visible = episodes.filter(item => item.season === Number(seasons.value));
    episodeList.innerHTML = visible.map(item => {
      const number = "S" + String(item.season).padStart(2, "0") + " · Épisode " + item.episode;
      const thumbnail = item.artwork ? '<img data-episode-artwork alt="" loading="lazy" hidden><span class="episodeArtworkFallback">Chargement de la vignette…</span>' : '<span class="episodeArtworkFallback">Sans vignette</span>';
      return '<button type="button" class="companionEpisode" data-play-episode="' + esc(item.id) + '" aria-label="Lire ' + esc(number + " · " + item.title + (item.watched ? " · Déjà vu" : "")) + '"><span class="companionEpisodeArtwork">' + thumbnail +
        (item.watched ? '<span class="episodeWatchedBadge" title="Épisode vu" aria-hidden="true">✓</span>' : '') + '</span><span class="companionEpisodeInfo"><b>' + esc(number) + '</b><span title="' + esc(item.title) + '">' + esc(item.title) + '</span><span class="episodePlayLabel">▶ Lire</span></span></button>';
    }).join("");
    episodeList.querySelectorAll("button").forEach(button => button.onclick = () => {
      const item = visible.find(value => value.id === button.dataset.playEpisode);
      if (!item) return;
      dialog.showModal(); play(item, null, button);
    });
    episodeArtworkObserver = new IntersectionObserver(entries => {
      for (const entry of entries) if (entry.isIntersecting) {
        episodeArtworkObserver.unobserve(entry.target);
        const button = entry.target.closest("[data-play-episode]");
        const item = visible.find(value => value.id === button?.dataset.playEpisode);
        if (item?.artwork) episodeArtworkQueue.push({ image: entry.target, item, version });
      }
      loadEpisodeArtworkQueue();
    }, { root: null, rootMargin: "150px" });
    episodeList.querySelectorAll("[data-episode-artwork]").forEach(image => episodeArtworkObserver.observe(image));
  }
  function closeEpisodeBrowser() {
    if (dialog.open) dialog.close(); else stopStream();
    clearEpisodeArtwork(); episodesPane.hidden = true; favoriteListPane.hidden = false;
    episodeList.innerHTML = ""; seasons.innerHTML = ""; episodesStatus.textContent = "";
    account = null; favorite = null; origin = null; episodes = [];
  }
  window.closeCompanionSeriesEpisodes = closeEpisodeBrowser;
  window.playCompanionFavorite = async (value, item, button) => {
    closeEpisodeBrowser(); stopStream(); account = value; favorite = item; origin = button; episodes = [];
    liveReconnectAttempts = 0;
    byId("companionPlayerTitle").textContent = item.title;
    byId("companionPlayerInfo").textContent = value.name + " · " + item.provider;
    episodeList.innerHTML = ""; seasons.innerHTML = ""; surface.hidden = true;
    byId("companionPlayerBack").hidden = true; byId("companionPlayerRetry").hidden = true;
    if (item.type !== "SERIES") { dialog.showModal(); byId("companionPlayerClose").focus(); await play(item); return; }
    episodesPane.hidden = false; favoriteListPane.hidden = true;
    byId("accountFavoriteEpisodesTitle").textContent = item.title;
    episodesStatus.textContent = "Chargement des épisodes chez le fournisseur…";
    controller = new AbortController(); const version = generation;
    try {
      const result = await secure(endpoint() + "/episodes", { signal: controller.signal });
      if (episodesPane.hidden || version !== generation || favorite?.id !== item.id) return;
      episodes = result.episodes;
      seasons.innerHTML = [...new Set(episodes.map(episode => episode.season))].map(season => '<option value="' + season + '">Saison ' + season + '</option>').join("");
      episodesStatus.textContent = episodes.length ? result.watchedKnown ? "Les épisodes vus sont indiqués par une coche." : "État « vu » indisponible : aucun profil JojoHome au nom correspondant." : "Aucun épisode disponible chez le fournisseur.";
      renderEpisodes(); seasons.focus();
    } catch (error) { if (!episodesPane.hidden && version === generation) episodesStatus.textContent = error.message; }
  };
  window.stopCompanionPlayback = () => { if (dialog.open) dialog.close(); else stopStream(); };
  seasons.onchange = renderEpisodes;
  byId("accountFavoriteEpisodesBack").onclick = () => { const target = origin; closeEpisodeBrowser(); if (target?.isConnected) target.focus(); };
  byId("companionPlayerBack").onclick = () => dialog.close();
  byId("companionPlayerClose").onclick = () => dialog.close();
  byId("companionPlayerRestart").onclick = () => {
    try { localStorage.removeItem(resumeKey()); } catch (_) {}
    if (relay) { play(selected, relayOptions(0)); return; }
    resumeTarget = 0; resumed = true; video.currentTime = 0; byId("companionPlayerRestart").hidden = true; autoplay();
  };
  modeSelect.onchange = () => { if (selected) play(selected, modeSelect.value === "compatible" ? { ...relayOptions(!resumed && resumeTarget > 0 ? resumeTarget : position()), compatible: true } : { position: position() }); };
  audioSelect.onchange = () => {
    if (relay || media) play(selected, relayOptions());
    else if (format === "hls" && engine) engine.audioTrack = Number(audioSelect.value);
    else Array.from(video.audioTracks || []).forEach((track, index) => { track.enabled = index === Number(audioSelect.value); });
  };
  subtitleSelect.onchange = () => {
    if (relay || media) {
      if (media?.subtitles.find(track => track.id === Number(subtitleSelect.value) && ["hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle"].includes(track.codec))) modeSelect.value = "compatible";
      play(selected, relayOptions());
    } else if (format === "hls" && engine) { engine.subtitleTrack = Number(subtitleSelect.value); engine.subtitleDisplay = engine.subtitleTrack >= 0; }
    else Array.from(video.textTracks).filter(track => ["subtitles", "captions"].includes(track.kind)).forEach((track, index) => { track.mode = index === Number(subtitleSelect.value) ? "showing" : "disabled"; });
  };
  seek.onchange = () => { if (relay && selected) play(selected, relayOptions(Number(seek.value))); };
  video.textTracks.addEventListener("addtrack", renderTracks); video.textTracks.addEventListener("change", () => { if (!media && !(format === "hls" && engine)) renderTracks(); });
  video.audioTracks?.addEventListener("addtrack", renderTracks);
  dialog.addEventListener("close", () => { stopStream(); if (episodesPane.hidden) { account = null; favorite = null; episodes = []; } if (origin?.isConnected) origin.focus(); });
  for (const event of ["loadedmetadata", "durationchange", "canplay", "progress", "playing"]) video.addEventListener(event, restorePosition);
  function restorePosition() {
    if (!selected || selected.type === "LIVE" || resumed || !Number.isFinite(video.duration) || video.duration <= 0) return;
    if (!(resumeTarget > 0 && resumeTarget < video.duration - 10)) { resumed = true; return; }
    byId("companionPlayerRestart").hidden = false;
    if (!video.seekable.length && video.readyState < 2) return;
    try { video.currentTime = resumeTarget; resumed = true; } catch (_) {}
  }
  video.addEventListener("playing", () => { started = true; if (!liveStartedAt) liveStartedAt = Date.now(); status.textContent = selected?.type === "LIVE" ? "En direct" : "Lecture en cours"; });
  video.addEventListener("timeupdate", () => { savePosition(); if (relay) { seek.value = position(); byId("companionPlayerTime").textContent = Math.floor(position() / 60) + ":" + String(Math.floor(position() % 60)).padStart(2, "0") + " / " + Math.floor(duration() / 60) + ":" + String(Math.floor(duration() % 60)).padStart(2, "0"); } });
  video.addEventListener("pause", () => { savePosition(true); if (selected && started && !video.ended) status.textContent = "En pause"; });
  video.addEventListener("ended", () => {
    if (selected?.type === "LIVE") {
      if (liveStartedAt && Date.now() - liveStartedAt >= 60_000) liveReconnectAttempts = 0;
      if (liveReconnectAttempts >= 2) { playbackError("Le direct est interrompu. Réessaie la lecture.", generation); return; }
      const item = selected, version = generation, options = relay ? relayOptions(0) : {}; liveReconnectAttempts++;
      status.textContent = "Le direct a été interrompu. Reconnexion…";
      liveReconnectTimer = setTimeout(() => { liveReconnectTimer = null; if (dialog.open && version === generation) play(item, options); }, 1_000);
      return;
    }
    savePosition(true); if (selected) status.textContent = "Lecture terminée";
  });
  video.addEventListener("error", () => { if (selected && !engine) playbackError(video.error?.code === 2 ? "Le fournisseur a interrompu la lecture. Réessaie." : "Le format vidéo ou audio de ce contenu n’est pas pris en charge par ce navigateur.", generation); });
  window.addEventListener("pagehide", stopStream);
  new MutationObserver(() => { if (byId("app").classList.contains("hidden")) window.stopCompanionPlayback(); }).observe(byId("app"), { attributes: true, attributeFilter: ["class"] });
}

function companionMobileNavigationClient() {
  const toggle = document.getElementById("mobileNavToggle");
  const sidebar = toggle && toggle.closest(".sidebar");
  const nav = document.getElementById("companionNav");
  if (!toggle || !sidebar || !nav) return;
  const navButtons = Array.from(nav.querySelectorAll(".navButton"));
  const syncCurrent = () => navButtons.forEach(button => {
    if (button.classList.contains("active")) button.setAttribute("aria-current", "page");
    else button.removeAttribute("aria-current");
  });
  const setOpen = (open, restoreFocus = false) => {
    sidebar.classList.toggle("navOpen", open);
    toggle.setAttribute("aria-expanded", String(open));
    toggle.setAttribute("aria-label", open ? "Fermer le menu de navigation" : "Ouvrir le menu de navigation");
    if (restoreFocus) toggle.focus();
  };
  document.querySelector(".brand")?.addEventListener("click", event => {
    event.preventDefault();
    nav.querySelector('[data-view="profiles"]')?.click();
    window.scrollTo(0, 0);
  });
  toggle.addEventListener("click", () => setOpen(toggle.getAttribute("aria-expanded") !== "true"));
  nav.addEventListener("click", event => {
    const button = event.target instanceof Element ? event.target.closest("button") : null;
    if (!button) return;
    setOpen(false);
    window.requestAnimationFrame(() => {
      syncCurrent();
      if (window.matchMedia("(max-width: 767px)").matches) document.getElementById("title")?.focus();
    });
  });
  nav.addEventListener("focusout", event => {
    if (!sidebar.contains(event.relatedTarget)) setOpen(false);
  });
  document.addEventListener("pointerdown", event => {
    if (sidebar.classList.contains("navOpen") && !sidebar.contains(event.target)) setOpen(false);
  });
  document.addEventListener("keydown", event => {
    if (event.key === "Escape" && sidebar.classList.contains("navOpen")) {
      event.preventDefault();
      setOpen(false, true);
    }
  });
  window.addEventListener("resize", () => {
    if (!window.matchMedia("(max-width: 767px)").matches) setOpen(false);
    syncCurrent();
  });
  syncCurrent();
  document.addEventListener("DOMContentLoaded", syncCurrent, { once: true });
}

function companionVideosClient() {
  const byId = id => document.getElementById(id), section = byId("videos"), grid = byId("companionVideosGrid");
  if (!section || !grid) return;
  const esc = value => String(value ?? "").replace(/[&<>"']/g, value => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[value]);
  const dialog = byId("contentDeleteDialog"), message = byId("companionVideosStatus");
  window.confirmCompanionDeletion = (name, text) => new Promise(resolve => {
    byId("contentDeleteName").textContent = name; byId("contentDeleteMessage").textContent = text;
    dialog.returnValue = ""; dialog.addEventListener("close", () => resolve(dialog.returnValue === "delete"), { once: true });
    byId("contentDeleteCancel").onclick = () => dialog.close("cancel");
    byId("contentDeleteConfirm").onclick = () => dialog.close("delete");
    dialog.showModal(); byId("contentDeleteCancel").focus();
  });
  const tv = byId("videosTvIp"), port = byId("videosTvPort"), more = byId("companionVideosMore");
  tv.value = byId("tvIp").value; port.value = byId("tvPort").value;
  let rows = [], crumbs = [{ id: "", name: "Vidéos" }], nextPage = null, generation = 0, operation = null, controller, submitting = false, checking = false;
  const target = () => "?tv=" + encodeURIComponent(tv.value.trim()) + "&port=" + encodeURIComponent(port.value.trim());
  const status = text => { message.textContent = text; };
  const render = () => {
    grid.innerHTML = rows.length ? rows.map(item => '<article class="accountFavorite"><div class="favoriteArtwork videoThumbnail" data-video-image="' + esc(item.id) + '">' + (item.directory ? 'Dossier' : 'Sans vignette') + '</div><div class="favoriteInfo"><span class="favoriteKind">' + (item.directory ? 'Dossier' : 'Vidéo') + '</span><h3>' + esc(item.name) + '</h3>' + (item.size >= 0 ? '<p class="meta">' + esc(fmt(item.size)) + '</p>' : '') + '<div class="actions">' + (item.directory ? '<button type="button" data-video-open="' + esc(item.id) + '">Ouvrir</button>' : '') + '<button type="button" class="danger" data-video-delete="' + esc(item.id) + '" aria-label="Supprimer : ' + esc(item.name) + '">Supprimer</button></div></div></article>').join("") : '<p class="meta">Ce dossier ne contient aucune vidéo ni sous-dossier.</p>';
    byId("companionVideosCrumbs").innerHTML = crumbs.map((item, index) => '<button type="button" data-video-crumb="' + index + '"' + (index === crumbs.length - 1 ? ' aria-current="page"' : '') + '>' + esc(item.name) + '</button>').join("");
    more.hidden = nextPage === null;
  };
  async function loadFolder(folder = crumbs.at(-1).id, page = 0) {
    const current = ++generation, selectedTarget = target();
    controller?.abort(); controller = new AbortController();
    status("Chargement du dossier Vidéos…");
    if (!page) { rows = []; grid.textContent = "Chargement…"; more.hidden = true; }
    try {
      const data = await secure("/api/tv/videos" + selectedTarget + "&" + new URLSearchParams({ folder, page }), { signal: controller.signal });
      if (current !== generation) return;
      rows = page ? rows.concat(data.items || []) : data.items || []; nextPage = data.nextPage ?? null;
      render(); status(data.total + " élément(s) · " + rows.length + " affiché(s).");
      const images = Array.from(grid.querySelectorAll("[data-video-image]"));
      // Only read existing thumbnails, with at most two TV/NAS requests at a time.
      let cursor = 0;
      const loadImages = async () => {
        while (cursor < images.length && current === generation) {
          const node = images[cursor++];
          try {
            const image = await secure("/api/tv/videos/artwork" + selectedTarget + "&id=" + encodeURIComponent(node.dataset.videoImage), { signal: controller.signal });
            if (current !== generation || !image.image) continue;
            const picture = document.createElement("img"); picture.alt = ""; picture.loading = "lazy"; picture.src = image.image; node.replaceChildren(picture);
          } catch (_) { }
        }
      };
      loadImages(); loadImages();
    } catch (error) { if (current === generation && error.name !== "AbortError") { grid.textContent = ""; status(error.message); } }
  }
  async function checkDeletion() {
    if (!operation || checking) return;
    checking = true;
    const pending = operation; byId("companionVideosCheck").hidden = true;
    try {
      for (;;) {
        const data = await secure("/api/tv/videos/status" + pending.target + "&id=" + encodeURIComponent(pending.id));
        if (data.status === "error" || data.status === "success" || data.status === "not_found") {
          operation = null; tv.disabled = port.disabled = false;
          const text = data.status === "not_found" ? "Le suivi de la suppression a expiré. Vérifie la liste des vidéos avant de réessayer." : data.message;
          await loadFolder(); status(text);
          return;
        }
        status(data.message || "Suppression en cours…");
        await new Promise(resolve => setTimeout(resolve, 1000));
      }
    } catch (error) {
      status(error.message + " Vérifie le résultat avant de relancer une suppression.");
      byId("companionVideosCheck").hidden = false;
    } finally {
      checking = false;
    }
  }
  grid.onclick = async event => {
    const open = event.target.closest("[data-video-open]"), remove = event.target.closest("[data-video-delete]");
    if (open) { const item = rows.find(value => value.id === open.dataset.videoOpen); if (item) { crumbs.push({ id: item.id, name: item.name }); loadFolder(item.id); } return; }
    if (!remove || operation || submitting) return;
    const item = rows.find(value => value.id === remove.dataset.videoDelete), selectedTarget = target();
    if (!item || !await window.confirmCompanionDeletion(item.name, item.directory ? "Effacer définitivement ce dossier et tout son contenu du serveur Freebox/NAS ? Les fichiers et leurs reprises seront supprimés." : "Effacer définitivement cette vidéo du serveur Freebox/NAS ? Le fichier et sa reprise seront supprimés.")) return;
    submitting = true; remove.disabled = true; tv.disabled = port.disabled = true; status("Demande de suppression…");
    try {
      const result = await secure("/api/tv/videos/delete" + selectedTarget, { method: "POST", body: JSON.stringify({ id: item.id, confirm: true }) });
      operation = { id: result.id, target: selectedTarget }; tv.disabled = port.disabled = true; await checkDeletion();
    } catch (error) { status(error.message); remove.disabled = false; tv.disabled = port.disabled = false; }
    finally { submitting = false; }
  };
  byId("companionVideosCrumbs").onclick = event => {
    const button = event.target.closest("[data-video-crumb]"); if (!button) return;
    crumbs = crumbs.slice(0, Number(button.dataset.videoCrumb) + 1); loadFolder();
  };
  more.onclick = () => { if (nextPage !== null) loadFolder(crumbs.at(-1).id, nextPage); };
  byId("companionVideosRefresh").onclick = () => operation ? checkDeletion() : loadFolder();
  byId("companionVideosCheck").onclick = checkDeletion;
  [tv, port].forEach(input => input.onchange = () => {
    byId("tvIp").value = tv.value; byId("tvPort").value = port.value;
    byId(input === tv ? "tvIp" : "tvPort").dispatchEvent(new Event("change"));
    crumbs = [{ id: "", name: "Vidéos" }]; loadFolder("");
  });
  document.querySelector('[data-view="videos"]').addEventListener("click", () => {
    if (!operation && !submitting && (tv.value !== byId("tvIp").value || port.value !== byId("tvPort").value)) {
      tv.value = byId("tvIp").value; port.value = byId("tvPort").value; crumbs = [{ id: "", name: "Vidéos" }];
    }
    loadFolder();
  });
}

function companionAvatarDashboard(html) {
  const favoriteNav = '<div class="iptvFavoriteShortcuts" role="group" aria-label="Mes favoris IPTV">' + [
    ["live", "TV", '<span class="navIcon" aria-hidden="true" style="background-color:currentColor;-webkit-mask:url(/api/ui-icons/home_category_live_tv_v1.png) center/contain no-repeat;mask:url(/api/ui-icons/home_category_live_tv_v1.png) center/contain no-repeat"></span>'],
    ["movies", "Films", '<path fill-rule="evenodd" d="M6,23H44V39c0,3.3 -2.7,6 -6,6H12c-3.3,0 -6,-2.7 -6,-6Z M8,25H15L12,30H8Z M18,25H25L22,30H15Z M28,25H35L32,30H25Z M38,25H42V30H35Z"/><path fill-rule="evenodd" d="M10,11H40c2.2,0 4,1.8 4,4v4c0,2.2 -1.8,4 -4,4H10c-2.2,0 -4,-1.8 -4,-4v-4c0,-2.2 1.8,-4 4,-4Z M9,14H14L18,20H9Z M18,14H24L28,20H22Z M28,14H34L38,20H32Z M38,14H40c0.6,0 1,0.4 1,1v5Z" transform="rotate(-17 6 23)"/>'],
    ["series", "Séries", '<path d="M15.5,5h17c1.4,0 2.5,1.1 2.5,2.5v2H13v-2C13,6.1 14.1,5 15.5,5Z"/><path d="M12,10.5h24c1.7,0 3,1.3 3,3V15H9v-1.5c0,-1.7 1.3,-3 3,-3Z"/><path fill-rule="evenodd" d="M9.5,16h29c2.2,0 4,1.8 4,4v19c0,2.2 -1.8,4 -4,4h-29c-2.2,0 -4,-1.8 -4,-4V20c0,-2.2 1.8,-4 4,-4Z M20.5,26c0,-0.6 0.6,-1 1.1,-0.7l6.4,4.1c0.5,0.3 0.5,1.1 0,1.4l-6.4,4.1c-0.5,0.3 -1.1,-0.1 -1.1,-0.7Z"/>']
  ].map(([category, label, icon]) => '<button type="button" class="favoriteShortcut" data-favorite-shortcut="' + category + '" aria-pressed="false">' + (category === "live" ? icon : '<svg class="navIcon" viewBox="0 0 48 48" aria-hidden="true" style="fill:currentColor;stroke:none">' + icon + '</svg>') + ' Mes favoris ' + label + '</button>').join("") + '</div>';
  const section = `<div class="card avatarManagement"><div class="eyebrow">Catalogue partagé</div><h2>Gestion des avatars</h2><p class="meta">Ajoute et organise les avatars disponibles pour les comptes JojoHome.</p>
    <div id="avatarStatus" class="status" role="status" aria-live="polite">Choisis un groupe pour gérer les avatars du NAS.</div>
    <div class="grid avatarManager"><div class="card avatarLibrary"><h2>Catalogue d’avatars</h2>
      <div id="avatarGroups" class="tabs" aria-label="Groupes d’avatars"></div>
      <label for="avatarSearch">Rechercher un avatar</label><input id="avatarSearch" type="search" placeholder="Nom de l’avatar">
      <p id="avatarTotal" class="meta"></p><div id="avatarGrid" class="avatarGallery"></div>
    </div><div class="card avatarAddCard"><h2>Ajouter un avatar</h2><p class="meta">Les avatars restent sur le NAS, hors de l’APK.</p>
      <form id="avatarAddForm"><label for="avatarAddGroup">Groupe</label><select id="avatarAddGroup"><option value="famille">Famille</option><option value="animaux">Animaux</option><option value="smileys">Smileys</option><option value="avatar">Avatar — initiale et couleur</option></select>
      <label for="avatarName">Nom de l’avatar</label><input id="avatarName" maxlength="80" placeholder="Nom de l’avatar"><p id="avatarNameHint" class="meta"></p>
      <div id="avatarImageFields"><label for="avatarFiles">Images PNG, JPG ou WebP</label><input id="avatarFiles" type="file" accept="image/png,image/jpeg,image/webp" multiple><p class="meta">Une ou plusieurs images, réduites à 512 × 512 pixels avant l’envoi.</p></div>
      <div id="avatarEmojiFields" hidden><label for="avatarEmoji">Smiley / emoji</label><input id="avatarEmoji" maxlength="32" placeholder="Ex. : 😊"></div>
      <div id="avatarColorFields" hidden><label for="avatarColor">Couleur du thème</label><select id="avatarColor"></select><div id="avatarColorPreview" class="avatarColorPreview"></div><p id="avatarColorHint" class="meta"></p></div>
      <button id="avatarAdd" class="primary" type="submit">Ajouter sur le NAS</button></form>
    </div></div>
    <dialog id="avatarEditDialog" class="card avatarEdit" aria-labelledby="avatarEditTitle"><form id="avatarEditForm"><h2 id="avatarEditTitle">Modifier l’avatar</h2><div id="avatarEditPreview" class="avatarColorPreview"></div>
      <label for="avatarEditName">Nom</label><input id="avatarEditName" maxlength="80" required><label for="avatarEditGroup">Groupe</label><select id="avatarEditGroup"></select>
      <div class="actions"><button id="avatarEditSave" class="primary" type="submit">Enregistrer</button><button id="avatarEditCancel" type="button">Annuler</button></div><div id="avatarEditStatus" class="status" role="status"></div>
    </form></dialog>
    <dialog id="avatarDeleteDialog" class="card avatarEdit" aria-labelledby="avatarDeleteTitle"><h2 id="avatarDeleteTitle">Supprimer l’avatar ?</h2><p><b id="avatarDeleteName"></b> sera retiré des choix de la TV. Tu pourras le restaurer depuis la corbeille.</p>
      <div class="actions"><button id="avatarDeleteConfirm" class="danger" type="button">Mettre à la corbeille</button><button id="avatarDeleteCancel" type="button">Annuler</button></div><div id="avatarDeleteStatus" class="status" role="status"></div>
    </dialog></div>`;
  const favoritesSection = `<section id="accountFavoritesView" class="view accountFavorites"><div class="card">
    <h2>Favoris de <span id="accountFavoritesName"></span></h2><p class="meta">Chaînes, films et séries synchronisés depuis JojoHome.</p>
    <div id="accountFavoritesAccountField" hidden><label for="accountFavoritesAccount">Compte</label><select id="accountFavoritesAccount"></select></div>
    <div id="accountFavoriteListPane"><div id="accountFavoriteGroups" class="actions" role="group" aria-label="Types de favoris"></div><label for="accountFavoriteSearch">Rechercher dans les favoris</label><input id="accountFavoriteSearch" type="search" placeholder="Titre, fournisseur, catégorie…">
      <p id="accountFavoriteTotal" class="meta" role="status" aria-live="polite"></p><div id="accountFavoritesGrid" class="accountFavoritesGrid"></div><div class="actions"><button id="accountFavoriteMore" type="button" hidden>Afficher plus</button></div><div id="accountFavoritesStatus" class="status" role="status"></div>
    </div>
    <section id="accountFavoriteEpisodesPane" hidden><div class="favoriteHeader"><div><h2 id="accountFavoriteEpisodesTitle"></h2><p class="meta">Choisis une saison puis un épisode.</p></div><button id="accountFavoriteEpisodesBack" type="button">Retour aux favoris</button></div>
      <label for="companionPlayerSeason">Saison</label><select id="companionPlayerSeason"></select><div id="accountFavoriteEpisodesList" class="accountFavoriteEpisodes"></div><p id="accountFavoriteEpisodesStatus" class="status" role="status" aria-live="polite"></p>
    </section>
  </div></section>`;
  const videosNav = '<button class="navButton" data-view="videos"><svg class="navIcon" viewBox="0 0 24 24" aria-hidden="true"><path d="M4 5h16v14H4z M8 5v14 M16 5v14 M4 9h4 M4 15h4 M16 9h4 M16 15h4"/></svg> Vidéos</button>';
  const videosSection = `<section id="videos" class="view"><div class="card"><div class="eyebrow">JOJO HOME · FREEBOX / NAS</div><h2>Dossier Vidéos</h2><div class="row"><label>Adresse de la TV<input id="videosTvIp" placeholder="192.168.1.163"></label><label>Port Companion<input id="videosTvPort" type="number" min="1" max="65535" value="8093"></label></div><div class="actions"><button id="companionVideosRefresh" type="button">Actualiser</button><button id="companionVideosCheck" type="button" hidden>Vérifier la suppression</button></div><nav id="companionVideosCrumbs" class="actions" aria-label="Chemin du dossier Vidéos"></nav><p id="companionVideosStatus" class="status" role="status" aria-live="polite"></p><div id="companionVideosGrid" class="accountFavoritesGrid"></div><button id="companionVideosMore" type="button" hidden>Afficher plus</button></div></section>`;
  const accountDialogs = `<dialog id="contentDeleteDialog" class="card avatarEdit" aria-labelledby="contentDeleteTitle"><h2 id="contentDeleteTitle">Confirmer la suppression</h2><p id="contentDeleteName"></p><p id="contentDeleteMessage"></p><div class="actions"><button id="contentDeleteCancel" type="button">Annuler</button><button id="contentDeleteConfirm" class="danger" type="button">Supprimer</button></div></dialog><dialog id="accountEditDialog" class="card avatarEdit" aria-labelledby="accountEditTitle"><form id="accountEditForm"><h2 id="accountEditTitle">Éditer le compte</h2>
    <label for="accountEditName">Nom du compte</label><input id="accountEditName" maxlength="40" required autocomplete="off">
    <div class="actions"><button id="accountEditSave" class="primary" type="submit">Enregistrer</button><button id="accountEditCancel" type="button">Annuler</button></div><div id="accountEditStatus" class="status" role="status"></div>
  </form></dialog>
  <dialog id="accountAvatarDialog" class="card avatarEdit accountAvatarPicker" aria-labelledby="accountAvatarTitle"><h2 id="accountAvatarTitle">Choisir un avatar</h2><p id="accountAvatarName" class="meta"></p>
    <div id="accountOrnamentPreview" class="accountOrnamentPreview" aria-hidden="true"></div>
    <label id="accountOrnamentLabel">Couleur d’ornement</label><div id="accountOrnamentChoices" class="actions" role="group" aria-labelledby="accountOrnamentLabel"></div>
    <div id="accountAvatarGroups" class="actions" role="group" aria-label="Groupes d’avatars"></div><label for="accountAvatarSearch">Rechercher un avatar</label><input id="accountAvatarSearch" type="search">
    <div id="accountAvatarGrid" class="accountAvatarGrid"></div><div id="accountAvatarStatus" class="status" role="status"></div><div class="actions"><button id="accountAvatarCancel" type="button">Annuler</button></div>
  </dialog>
  <dialog id="accountDeleteDialog" class="card avatarEdit" aria-labelledby="accountDeleteTitle"><h2 id="accountDeleteTitle">Supprimer le compte ?</h2>
    <p>Supprimer le compte « <b id="accountDeleteName"></b> » ? Ses favoris et ses réglages seront supprimés de JojoHome et du module IPTV.</p>
    <div class="actions"><button id="accountDeleteCancel" type="button">Annuler</button><button id="accountDeleteConfirm" class="danger" type="button">Supprimer</button></div><div id="accountDeleteStatus" class="status" role="status"></div>
  </dialog>
  <dialog id="companionPlayerDialog" class="card avatarEdit companionPlayer" aria-labelledby="companionPlayerTitle"><div class="favoriteHeader"><div><p class="playerBrand">Jojo Home · Lecteur</p><h2 id="companionPlayerTitle"></h2><p id="companionPlayerInfo" class="meta"></p></div><button id="companionPlayerClose" type="button">Fermer le lecteur</button></div>
    <media-i18n id="companionPlayerSurface" lang="fr" hidden><video-player id="companionVideoJs"><video-skin class="companionVideoSkin"><video id="companionPlayerVideo" playsinline preload="metadata" tabindex="0" aria-label="Lecteur JojoTV"></video></video-skin></video-player></media-i18n>
    <div id="companionPlayerTracks" class="companionPlayerTracks" hidden><label>Lecture<select id="companionPlayerMode"><option value="original">Qualité originale</option><option value="compatible">Mode compatible · 1080p maximum</option></select></label><label>Piste audio<select id="companionPlayerAudio"></select></label><label>Sous-titres<select id="companionPlayerSubtitle"></select></label><p id="companionPlayerTrackStatus" class="meta"></p></div>
    <div id="companionPlayerTimeline" hidden><label for="companionPlayerSeek">Position dans la vidéo</label><input id="companionPlayerSeek" type="range" min="0" step="1"><output id="companionPlayerTime"></output></div>
    <p id="companionPlayerStatus" class="status" role="status" aria-live="polite"></p><div class="actions"><button id="companionPlayerBack" type="button" hidden>Retour aux épisodes</button><button id="companionPlayerRestart" type="button" hidden>Lire depuis le début</button><button id="companionPlayerRetry" class="primary" type="button" hidden>Réessayer</button></div>
    <p class="meta playerResumeNote">La reprise des films et épisodes est mémorisée dans ce navigateur, pour ce compte.</p>
  </dialog>`;
  const styles = `<style>
    .danger{background:#b42332!important;color:#fff!important;border-color:#d75363!important}.danger:hover,.danger:focus-visible{background:#941d29!important;color:#fff!important}.danger:focus-visible{outline:3px solid #fff;outline-offset:3px}.videoThumbnail{aspect-ratio:2/3;overflow:hidden}.videoThumbnail img{width:100%;height:100%;object-fit:cover}#companionVideosCrumbs{margin-top:16px}.iptvAdminChannel .actions{flex-wrap:wrap}.brand{color:var(--text);text-decoration:none}.brand:focus-visible{outline:3px solid var(--yellow);outline-offset:3px;border-radius:6px;box-shadow:0 0 0 6px #FFB30933}
    .avatarManagement{margin-top:16px}
    .accountFavorites .accountFavorite{display:flex;flex-direction:column}.accountFavorites .favoriteInfo{display:flex;flex:1;flex-direction:column}.accountFavorites .favoritePlay{margin-top:auto}
    .accountFavoriteEpisodes{display:grid;gap:10px;margin-top:16px}.companionEpisode{display:grid;width:100%;grid-template-columns:minmax(150px,220px) minmax(0,1fr);align-items:center;gap:16px;text-align:left;padding:10px}.companionEpisodeArtwork{position:relative;display:grid;place-items:center;aspect-ratio:16/9;overflow:hidden;border-radius:8px;background:#151f33;color:var(--muted);font-size:13px;text-align:center}.companionEpisodeArtwork img{width:100%;height:100%;object-fit:cover}.episodeWatchedBadge{position:absolute;top:8px;right:8px;display:grid;place-items:center;width:28px;height:28px;border-radius:50%;background:#5bbb78;color:#0b1322;font-weight:900}.companionEpisodeInfo{display:flex;flex-direction:column;gap:8px;min-width:0}.companionEpisodeInfo span{overflow-wrap:anywhere}.episodePlayLabel{margin-top:6px;color:var(--cyan)}@media(max-width:650px){.companionEpisode{grid-template-columns:minmax(110px,32%) minmax(0,1fr);gap:12px}}
    .iptvFavoriteShortcuts{display:grid;gap:4px;margin:-4px 0 6px 22px}.nav .favoriteShortcut{padding:10px 12px;font-size:13px}.nav .favoriteShortcut.active{background:var(--yellow);color:#1a1500}.nav .favoriteShortcut:focus-visible{outline:3px solid var(--yellow);outline-offset:2px}@media(max-width:760px){.iptvFavoriteShortcuts{display:flex;flex-wrap:wrap;margin:0}}
    .companionPlayerTracks{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin-top:14px}.companionPlayerTracks label{margin:0}.companionPlayerTracks p{grid-column:1/-1;margin:0}.companionPlayerTracks select{width:100%}#companionPlayerTimeline{margin-top:12px}#companionPlayerSeek{width:100%;accent-color:var(--yellow)}@media(max-width:700px){.companionPlayerTracks{grid-template-columns:1fr}}
    .avatarManager{grid-template-columns:minmax(0,1fr) 300px;align-items:start}.avatarAddCard{position:sticky;top:20px}.avatarGallery{display:grid;grid-template-columns:repeat(auto-fill,minmax(140px,1fr));gap:12px}.avatarTile{display:flex;align-items:center;flex-direction:column;gap:9px;min-width:0;padding:16px 10px;background:#0b1322;border:1px solid var(--line);border-radius:14px;text-align:center}.avatarTile b{overflow-wrap:anywhere}.avatarTile .actions{margin-top:auto;justify-content:center;gap:6px;padding-top:6px}.avatarTile button{padding:8px;font-size:12px}.avatarPicture,.avatarEmoji,.avatarInitial{width:76px;height:76px;flex:none}.avatarPicture{object-fit:contain;border-radius:12px;background:#151f33}.avatarEmoji,.avatarInitial{display:inline-grid;place-items:center}.avatarEmoji{font-size:52px}.avatarInitial{border-radius:50%;border:1px solid #ffffff48;font-size:30px;font-weight:700}.avatarCount{opacity:.75;font-size:12px}.avatarColorPreview{margin-top:16px;display:flex;justify-content:center}.avatarAddCard button[type=submit]{width:100%;margin-top:18px}.avatarAddCard [hidden]{display:none!important}.avatarEdit{color:var(--text);width:min(420px,calc(100% - 32px));max-height:90vh;overflow:auto}.avatarEdit::backdrop{background:#000a}.avatarTile button:hover{filter:brightness(1.15)}#avatars input:focus-visible,#avatars select:focus-visible,#avatars button:focus-visible{outline:3px solid var(--yellow);outline-offset:3px}
    @media(max-width:1000px){.avatarManager{grid-template-columns:1fr}.avatarAddCard{position:static;grid-row:1}}
    @media(max-width:760px){.sidebar{flex-wrap:wrap}.nav{width:100%;flex-wrap:wrap}.avatarGallery{grid-template-columns:repeat(auto-fill,minmax(130px,1fr))}}
    .profile-head{gap:9px}.profile-head .avatarPicture,.profile-head .avatarEmoji,.profile-head .avatarInitial{width:34px;height:34px}.profile-head .avatarPicture{border-radius:50%}.profile-head .avatarInitial{font-size:16px}.profile-head .avatarEmoji{font-size:26px}
    .accountAvatarOrnament{border-radius:50%;box-shadow:0 0 0 2px var(--account-ornament),0 0 14px var(--account-ornament)}.accountOrnamentPreview{display:flex;justify-content:center;padding:12px}.accountOrnamentPreview .avatarPicture,.accountOrnamentPreview .avatarEmoji,.accountOrnamentPreview .avatarInitial{width:76px;height:76px;border-radius:50%}.accountOrnamentPreview .avatarEmoji{font-size:48px}.accountOrnamentPreview .avatarInitial{font-size:34px}.accountOrnamentChoice{display:flex;align-items:center;gap:9px;background:#182235;color:#f4f7ff;border:1px solid #46516a}.accountOrnamentChoice[aria-pressed="true"]{border-color:var(--account-ornament);box-shadow:0 0 0 2px #f4f7ff}.ornamentSwatch{display:block;width:20px;height:20px;flex:none;border-radius:50%;background:var(--account-ornament);border:1px solid #ffffff80}.accountAvatarPicker button:focus-visible,.accountAvatarPicker input:focus-visible{outline:3px solid var(--yellow);outline-offset:3px}
    .accountActions{margin-top:16px;gap:6px}.accountActions button{padding:8px 10px;font-size:12px}.accountAvatarPicker{width:min(760px,calc(100% - 32px))}.accountAvatarGrid{display:grid;grid-template-columns:repeat(auto-fill,minmax(96px,1fr));gap:10px;margin-top:16px}.accountAvatarChoice{display:flex;flex-direction:column;align-items:center;gap:8px;padding:12px 6px;min-width:0}.accountAvatarChoice span:last-child{font-size:12px;overflow-wrap:anywhere}.accountAvatarChoice .avatarPicture,.accountAvatarChoice .avatarEmoji,.accountAvatarChoice .avatarInitial{width:56px;height:56px}.accountAvatarChoice .avatarEmoji{font-size:38px}.accountAvatarChoice .avatarInitial{font-size:26px}
    .accountFavoriteMetrics button{font-weight:400;font-size:13px;min-width:0;padding:10px 4px}.accountFavoriteMetrics button b{display:block;font-size:18px;color:var(--text)}.accountFavoriteMetrics button:hover{background:#26364f}.accountFavoriteMetrics button:focus-visible,.accountFavorites button:focus-visible,.accountFavorites input:focus-visible{outline:3px solid var(--yellow);outline-offset:3px}.accountFavorites{width:100%}.accountFavoritesGrid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:16px;margin-top:16px}.accountFavorite{background:#0b1322;border:1px solid var(--line);border-radius:14px;overflow:hidden;min-width:0}.favoriteArtwork{aspect-ratio:2/3;background:#151f33;display:grid;place-items:center;color:var(--muted);text-align:center;font-size:13px}.favoriteArtwork img{width:100%;height:100%;object-fit:cover;min-height:0}.favoriteArtwork.channelLogo{aspect-ratio:2/3;padding:16px}.favoriteArtwork.channelLogo img{object-fit:contain}.favoriteInfo{padding:12px}.favoriteKind{font-size:11px;color:var(--cyan);text-transform:uppercase;letter-spacing:.06em}.favoriteInfo h3{font-size:16px;height:2.4em;line-height:1.2;margin:8px 0;overflow-wrap:anywhere;overflow:hidden;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical}.favoriteInfo p{font-size:12px;line-height:1.5;margin:8px 0}.favoriteDescription{color:var(--muted);white-space:pre-line;overflow-wrap:anywhere}.favoriteCredits{overflow-wrap:anywhere}.favoriteDescription strong,.favoriteCredits strong{color:var(--text);font-weight:600}.accountFavorites [hidden]{display:none!important}
    .accountFavorite:focus-visible{outline:3px solid var(--yellow);outline-offset:3px}.favoriteArtwork{width:100%;padding:0;border-radius:0;font-weight:400;min-width:0;overflow:hidden}.accountFavorites .favoriteArtwork:focus-visible{outline-offset:-3px}
    .favoritePlay{width:100%;margin-top:12px}.companionPlayer{width:min(1100px,calc(100% - 32px))}.companionPlayer[open]{display:flex;flex-direction:column}.companionPlayer h2{margin:8px 0;overflow-wrap:anywhere}.playerBrand{color:var(--yellow);font-weight:700;margin:0}#companionPlayerSurface{display:block;width:100%;flex:none;aspect-ratio:16/9;margin-top:16px}.companionVideoSkin{display:block;width:100%;height:100%;--media-accent-color:var(--yellow);--media-accent-text-color:#111;--media-border-radius:12px;--media-object-fit:contain;--media-object-position:center;--media-font-family:inherit}.companionPlayer video{display:block;width:100%;height:100%;min-height:0;object-fit:contain;background:#000;border-radius:12px;margin:0}.companionPlayer .favoriteHeader,.companionPlayer .status,.companionPlayer .actions,.playerResumeNote{flex:none}.companionPlayer [hidden]{display:none!important}.companionPlayer button:focus-visible,.companionPlayer select:focus-visible,.companionPlayer video:focus-visible{outline:3px solid var(--yellow);outline-offset:3px}.companionEpisodes{display:grid;gap:8px;margin-top:16px;max-height:45vh;overflow:auto;padding:4px}.companionEpisode{display:flex;align-items:center;gap:16px;text-align:left}.companionEpisode b{flex:none}.companionEpisode span{overflow-wrap:anywhere}.companionEpisode:hover,.companionEpisode:focus-visible{background:var(--yellow);color:#111}.playerResumeNote{font-size:12px}
    .companionPlayer video::-webkit-media-text-track-container{z-index:1;scale:.98;translate:0 var(--media-caption-track-y,0);transition:translate var(--media-caption-track-duration,0) ease-out;transition-delay:var(--media-caption-track-delay,0);font-family:inherit}
    .accountFavorites .companionEpisode{display:grid;grid-template-columns:minmax(150px,220px) minmax(0,1fr)}@media(max-width:650px){.accountFavorites .companionEpisode{grid-template-columns:minmax(110px,32%) minmax(0,1fr)}}

    .mobileNavToggle{display:none}
    @media(min-width:768px) and (max-width:1100px){.accountFavoritesGrid{grid-template-columns:repeat(3,minmax(0,1fr))}}
    @media(max-width:767px){
      html,body{min-height:100%;overflow-x:hidden}
      body{min-height:100dvh}
      .app{display:block;min-height:100dvh;min-width:0}
      .sidebar{position:sticky;top:0;z-index:100;display:flex;flex-wrap:nowrap;align-items:center;justify-content:space-between;gap:12px;padding:calc(9px + env(safe-area-inset-top,0px)) calc(12px + env(safe-area-inset-right,0px)) 9px calc(12px + env(safe-area-inset-left,0px));border-right:0;border-bottom:1px solid var(--line)}
      .brand{min-width:0;flex:1 1 auto;gap:4px;padding:0;overflow:hidden;font-size:clamp(16px,4.6vw,18px);white-space:nowrap}
      .brand span{white-space:nowrap}
      .mobileNavToggle{display:inline-flex;flex:0 0 44px;align-items:center;justify-content:center;width:44px;min-width:44px;min-height:44px;padding:10px;border:1px solid var(--line);border-radius:12px;background:var(--panel2);color:var(--text)}
      .mobileNavToggle svg{display:block;width:22px;height:22px;fill:none;stroke:currentColor;stroke-width:2;stroke-linecap:round}
      .nav{display:none;position:absolute;top:calc(100% + 6px);left:calc(12px + env(safe-area-inset-left,0px));right:calc(12px + env(safe-area-inset-right,0px));z-index:101;width:auto;grid-template-columns:minmax(0,1fr);gap:3px;margin:0;padding:8px;border:1px solid var(--line);border-radius:16px;background:#0a1020;box-shadow:0 18px 40px #0009;max-height:calc(100vh - 84px - env(safe-area-inset-bottom,0px));max-height:calc(100dvh - 84px - env(safe-area-inset-top,0px) - env(safe-area-inset-bottom,0px));overflow-x:hidden;overflow-y:auto;overscroll-behavior:contain}
      .sidebar.navOpen .nav{display:grid}
      .nav button,.nav .favoriteShortcut{display:flex;width:100%;min-width:0;min-height:44px;align-items:center;justify-content:flex-start;gap:10px;padding:10px 12px;text-align:left;font-size:15px;line-height:1.3;white-space:normal;overflow-wrap:anywhere}
      .iptvFavoriteShortcuts{display:grid;width:100%;grid-template-columns:minmax(0,1fr);gap:3px;margin:0}
      .nav .favoriteShortcut{font-size:14px}
      .account{display:none}
      .main{width:100%;min-width:0;padding:18px calc(14px + env(safe-area-inset-right,0px)) calc(36px + env(safe-area-inset-bottom,0px)) calc(14px + env(safe-area-inset-left,0px))}
      .top{align-items:flex-start;gap:10px}
      .top>div{flex:1 1 140px;min-width:0}
      #refresh{flex:0 0 auto;min-height:44px;margin-top:2px;padding:10px 13px}
      h1{font-size:clamp(26px,7vw,32px);line-height:1.12;overflow-wrap:anywhere}
      h2{font-size:19px;line-height:1.3;overflow-wrap:anywhere}
      p{font-size:15px;line-height:1.5;overflow-wrap:anywhere}
      .view,.view.active,.grid,.row,.profiles,.avatarManager,.card,.profile{min-width:0}
      .grid,.row,.profiles,.avatarManager{grid-template-columns:minmax(0,1fr);gap:12px}
      .grid{margin-top:16px}
      .wide,#free-tv .grid .wide{grid-column:auto}
      .card{width:100%;padding:16px;border-radius:16px}
      .profile{padding:14px}
      .profile-head{min-width:0;gap:9px;overflow-wrap:anywhere}
      .profile-head>span{min-width:0;overflow-wrap:anywhere}
      .metrics{grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}
      .metric{min-width:0;min-height:62px;padding:10px 6px;white-space:normal;overflow-wrap:anywhere}
      .actions{gap:8px;align-items:stretch}
      .actions button,.tabs button,.accountActions button{min-width:0;min-height:44px;white-space:normal;overflow-wrap:anywhere}
      .accountActions{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}
      .accountActions button{padding:10px 8px}
      button{max-width:100%}
      input,select,textarea{width:100%;min-width:0;max-width:100%;min-height:44px}
      input[type=file]{font-size:13px}
      .item{min-width:0;align-items:flex-start;flex-wrap:wrap;overflow-wrap:anywhere}
      .status{min-width:0;overflow-wrap:anywhere}
      .item>div{min-width:0;max-width:100%;overflow-wrap:anywhere}
      .avatarManager{align-items:stretch}
      .avatarAddCard{position:static;top:auto;grid-row:auto}
      .avatarGallery{grid-template-columns:repeat(auto-fill,minmax(min(100%,84px),1fr));gap:8px}
      .accountFavoritesGrid{grid-template-columns:repeat(auto-fill,minmax(min(100%,150px),1fr));gap:10px}
      .accountFavorites .companionEpisode{grid-template-columns:minmax(0,1fr);gap:10px}
      .avatarEdit,.companionPlayer{width:calc(100vw - 24px);max-width:calc(100vw - 24px);max-height:calc(100dvh - 24px);margin:auto;padding:16px;overflow:auto}
    }
    @media(max-width:380px){
      .sidebar{gap:8px;padding-right:calc(8px + env(safe-area-inset-right,0px));padding-left:calc(8px + env(safe-area-inset-left,0px))}
      .brand{font-size:16px}
      .main{padding-right:calc(12px + env(safe-area-inset-right,0px));padding-left:calc(12px + env(safe-area-inset-left,0px))}
      .card{padding:14px}
    }
  </style>`;
  return html.replaceAll("Jojo Home Companion", "JojoTV Companion")
    .replace('<div class="brand">Jojo Home <span style="color:var(--yellow)">Companion</span></div>', '<a class="brand" href="#profiles" aria-label="JojoTV Companion · Comptes"><img src="/api/ui-avatars/jojohome-favicon.png?v=4" alt="" aria-hidden="true" style="width:34px;height:34px;object-fit:contain;flex:0 0 34px">JojoTV <span style="color:var(--yellow)">Companion</span></a>')
    .replace('<button class="navButton" data-view="free-tv">', videosNav + favoriteNav + '<button class="navButton" data-view="free-tv">')
    .replace('<div id="accountStatus" class="status" role="status" hidden></div></div></section>', '<div id="accountStatus" class="status" role="status" hidden></div></div>' + section + '</section>')
    .replace('</main>', favoritesSection + videosSection + '</main>')
    .replace('const titles={iptv:', "const titles={videos:['Vidéos','Parcours et gestion du dossier Vidéos de Jojo Home.'],iptv:")
    .replace('</head>', '<link rel="icon" type="image/png" sizes="128x128" href="/api/ui-avatars/jojohome-favicon.png?v=4">' +
      '<link rel="stylesheet" href="/api/companion/player-assets/videojs-10.0.1/global.css">' + styles + '</head>')
    .replace('history.replaceState(null,', 'window.history.replaceState(null,')
    .replace('<script>', '<script>' + companionAccountOrnaments.toString() + '\n' + companionAvatarPreview.toString() + '</script><script>')
    .replace('</body>', accountDialogs + '<script>(' + companionAvatarClient.toString() + ')();</script><script>(' + companionPlaybackClient.toString() + ')();</script><script>(' + companionAccountClient.toString() + ')();</script><script>(' + companionVideosClient.toString() + ')();</script><script>(' + companionMobileNavigationClient.toString() + ')();</script></body>');
}

function page() {
  return companionAvatarDashboard(companionDashboardPage()
    .replace('</body>', `<script>window.addEventListener('DOMContentLoaded',()=>{
      const query=new URLSearchParams(location.search);
      document.getElementById('tvPort').value='8093';
      if(query.get('tv'))document.getElementById('tvIp').value=query.get('tv');
    });</script></body>`)
    .replace('<input id="m3uUrl" placeholder="https://…/playlist.m3u">', '<input id="m3uUrl" placeholder="https://…/playlist.m3u"><label>ou choisir un fichier M3U sur cet ordinateur</label><input id="m3uFile" type="file" accept=".m3u,.m3u8,audio/x-mpegurl,application/x-mpegurl,text/plain"><div id="m3uFileHint" class="meta">Le fichier sera copié sur le NAS.</div>')
    .replace('<div class="brand"><i>J</i> Jojo Home <span style="color:var(--yellow)">Companion</span></div>', '<div class="brand">Jojo Home <span style="color:var(--yellow)">Companion</span></div>')
    .replace('<nav class="nav"><button class="navButton active" data-view="iptv">▣ &nbsp; IPTV</button><button class="navButton" data-view="maintenance">▣ &nbsp; Maintenance</button><button class="navButton" data-view="profiles">● &nbsp; Comptes</button></nav>', '<nav class="nav"><button class="navButton active" data-view="profiles">● &nbsp; Comptes</button><button class="navButton" data-view="iptv">▣ &nbsp; IPTV</button><button class="navButton" data-view="free-tv">◉ &nbsp; Free TV</button><button class="navButton" data-view="maintenance">▣ &nbsp; Maintenance</button></nav>')
    .replace('<nav class="nav">', '<button type="button" id="mobileNavToggle" class="mobileNavToggle" aria-controls="companionNav" aria-expanded="false" aria-label="Ouvrir le menu de navigation"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16M4 12h16M4 17h16" /></svg></button><nav id="companionNav" class="nav" aria-label="Navigation principale">')
    .replace('<h1 id="title">IPTV</h1><p id="subtitle">Ajoute et pilote les fournisseurs de ta TV Jojo Home.</p>', '<h1 id="title" tabindex="-1">Comptes</h1><p id="subtitle">Comptes JojoHome et favoris IPTV synchronisés.</p>')
    .replace('<section id="iptv" class="view active">', '<section id="iptv" class="view">')
    .replace('<section id="profiles" class="view">', '<section id="profiles" class="view active">')
    .replace('<meta name="viewport" content="width=device-width,initial-scale=1">', '<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">')
    .replace('</section><section id="maintenance" class="view">', '</section><section id="free-tv" class="view"><div class="grid"><div class="card wide"><div class="eyebrow">Enregistrements Free TV</div><h2>Associer Free TV</h2><p>Saisis les identifiants de ton Espace Abonné Freebox (identifiant souvent du type fbx…). Ils sont différents du compte administrateur local Freebox OS. Le mot de passe ne sera pas conservé : le NAS stocke uniquement la session chiffrée.</p><label>Profil Jojo Home</label><input id="freeTvProfileId" inputmode="numeric" value="1"><div id="freeTvStatus" class="status">Prêt.</div><label>Identifiant Espace Abonné Freebox</label><input id="freeTvLogin" autocomplete="username" placeholder="ex. fbx12345678"><label>Mot de passe Espace Abonné Freebox</label><input id="freeTvPassword" type="password" autocomplete="current-password"><div class="actions"><button id="associateFreeTv" class="primary">Associer Free TV</button><button id="disconnectFreeTv" class="danger">Dissocier</button></div></div><div class="card wide"><div class="eyebrow">Ma liste</div><h2>Enregistrements Free TV</h2><p>Les programmés, en cours et terminés restent gérés par Free TV. La suppression est définitive chez Free.</p><div class="actions"><button id="refreshFreeTvRecords">Actualiser les enregistrements</button></div><div id="freeTvRecords" class="list meta">Ouvre cet onglet pour charger tes enregistrements.</div></div></div></section><section id="maintenance" class="view">')
    .replace("const titles={iptv:['IPTV','Ajoute et pilote les fournisseurs de ta TV Jojo Home.'],maintenance:['Maintenance','Sauvegarde et restauration manuelle de l’état NAS.'],profiles:['Comptes','Comptes JojoHome et favoris IPTV synchronisés.']};", "const titles={iptv:['IPTV','Ajoute et pilote les fournisseurs de ta TV Jojo Home.'],'free-tv':['Free TV','Associe Free TV et gère les enregistrements depuis le NAS.'],maintenance:['Maintenance','Sauvegarde et restauration manuelle de l’état NAS.'],profiles:['Comptes','Comptes JojoHome et favoris IPTV synchronisés.']};")
    .replace('▣ &nbsp; IPTV', '<svg class="navIcon" viewBox="0 0 48 48" aria-hidden="true"><path d="M24,20a4,4 0,1 0,0 8a4,4 0,1 0,0 -8" fill="currentColor" stroke="none"/><path d="M17,17.5C12.8,21.4 12.8,26.6 17,30.5M31,17.5C35.2,21.4 35.2,26.6 31,30.5M11,11.5C3.4,18.6 3.4,29.4 11,36.5M37,11.5C44.6,18.6 44.6,29.4 37,36.5" fill="none" stroke="currentColor" stroke-width="3.2" stroke-linecap="round"/></svg> IPTV')
    .replace('▣ &nbsp; Maintenance', '<svg class="navIcon" viewBox="0 0 24 24" aria-hidden="true"><path d="M14.7 6.3a5 5 0 0 0-6.6 6.6L3 18l3 3 5.1-5.1a5 5 0 0 0 6.6-6.6l-3.1 3.1-2.5-2.5z"/></svg> Maintenance')
    .replace('● &nbsp; Comptes', '<svg class="navIcon" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="8" r="3.5"/><path d="M4.5 20c.8-3.6 3.3-5.5 7.5-5.5s6.7 1.9 7.5 5.5"/></svg> Comptes')
    .replace('<h2>TV cible</h2>', '<h2>TV cible</h2><label>TV connue</label><select id="knownTv"><option value="">Saisie manuelle d’une adresse IP</option></select>')
    .replace('<div id="connection" class="status">Sélectionne une TV.</div>', '<div id="connection" class="status">Sélectionne une TV.</div><div class="iptvProviders"><div class="iptvProvidersHeader"><h2>Mes fournisseurs IPTV</h2><div class="actions"><button id="configureIptvDns" type="button">DNS par défaut de cette TV</button><button id="refreshIptvProviders" type="button" aria-label="Actualiser les fournisseurs">Actualiser</button></div></div><p id="iptvProvidersStatus" class="meta" role="status" aria-live="polite"></p><div id="iptvProvidersList" class="list">Chargement…</div></div>')
    .replace('renderTargets();loadProviderHistory()', 'renderTargets();loadCompanionProviders();loadProviderHistory()')
    .replace('</head>', '<style>.iptvProviders{margin-top:24px;padding-top:20px;border-top:1px solid var(--line)}.iptvProvidersHeader{display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:8px}.iptvProvidersHeader h2{margin:0}.iptvProviders .list{margin-top:12px}.iptvProviders .item{gap:12px}.iptvProviders .item b{overflow-wrap:anywhere}.iptvProviderType{color:var(--cyan);font-size:12px;flex:none}</style></head>')
    .replace('</head>', '<style>.brand{padding-left:0;gap:4px;font-size:18px}.nav button{display:flex;align-items:center;gap:12px}.navIcon{width:20px;height:20px;flex:0 0 20px;fill:none;stroke:currentColor;stroke-width:2.3;stroke-linecap:round;stroke-linejoin:round}button:focus-visible,input:focus-visible,select:focus-visible{outline:3px solid #FFB309;outline-offset:3px;box-shadow:0 0 0 6px #FFB30933}#free-tv .grid{align-items:start}#free-tv .card.wide{grid-column:auto}#free-tv .card{min-width:0}@media(max-width:760px){.sidebar{display:grid;grid-template-columns:minmax(0,1fr);align-items:stretch;gap:10px;padding:10px 14px 12px;border-right:0;border-bottom:1px solid var(--line)}.brand{padding:0}.nav{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:5px}.nav button{min-width:0;display:flex;align-items:center;gap:6px;padding:8px 6px;font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.main{padding:18px 14px 48px}.top{flex-wrap:wrap;align-items:center;gap:8px}.grid,.row{grid-template-columns:minmax(0,1fr)}.wide{grid-column:auto}.profiles{grid-template-columns:minmax(0,1fr)}.card{padding:16px;border-radius:16px}}@media(max-width:500px){.sidebar{padding-left:12px;padding-right:12px}.nav{grid-template-columns:repeat(2,minmax(0,1fr))}.nav button{padding:8px 9px}.main{padding:16px 12px 40px}.top{align-items:flex-start}.top>div{flex:1 1 160px;min-width:0}#refresh{flex:0 0 auto;margin-top:2px}h1{font-size:28px}h2{font-size:19px}.card{padding:14px}.metrics{grid-template-columns:repeat(2,minmax(0,1fr))}}</style></head>')
    .replace('</body>', `<script>window.addEventListener('DOMContentLoaded',()=>{const section=document.getElementById('free-tv');if(!section)return;const guide=document.getElementById('freeTvGuide'),channel=document.getElementById('freeTvChannel'),legacy=[...section.querySelectorAll('.card.wide')].find(card=>card.querySelector('#manualChannelId'));legacy?.remove();if(!guide||!channel)return;const card=guide.closest('.card'),manual=document.createElement('div');manual.innerHTML='<hr><div class="eyebrow">Créneau manuel</div><h3>Ou saisir un horaire précis</h3><p>Utilise la chaîne déjà choisie ci-dessus. Durée maximale : 4 heures.</p><label>Date et heure de début</label><input id="mergedManualStart" type="datetime-local"><label>Date et heure de fin</label><input id="mergedManualEnd" type="datetime-local"><label>Marge avant (minutes)</label><input id="mergedManualBefore" value="0" inputmode="numeric"><label>Marge après (minutes)</label><input id="mergedManualAfter" value="0" inputmode="numeric"><div class="actions"><button id="createMergedManualFreeTv" class="primary">Programmer ce créneau</button></div><div id="mergedManualFreeTvStatus" class="status">Renseigne le créneau puis programme l’enregistrement.</div>';card.append(manual);const start=manual.querySelector('#mergedManualStart'),end=manual.querySelector('#mergedManualEnd'),status=manual.querySelector('#mergedManualFreeTvStatus'),fmt=d=>new Date(d.getTime()-d.getTimezoneOffset()*60000).toISOString().slice(0,16),profile=()=>document.getElementById('freeTvProfileId')?.value.trim()||'1',base=()=>'/api/sync/profiles/'+encodeURIComponent(profile())+'/free-tv';const now=new Date();now.setMinutes(now.getMinutes()-now.getMinutes()%5,0,0);start.value=fmt(now);end.value=fmt(new Date(now.getTime()+3600000));manual.querySelector('#createMergedManualFreeTv').onclick=async()=>{const channelId=Number(channel.value),begin=Math.floor(new Date(start.value).getTime()/1000),finish=Math.floor(new Date(end.value).getTime()/1000),before=Number(manual.querySelector('#mergedManualBefore').value||0),after=Number(manual.querySelector('#mergedManualAfter').value||0);if(!channelId||!begin||!finish){status.textContent='Renseigne la chaîne, le début et la fin.';return}status.textContent='Programmation en cours…';try{await secure(base()+'/manual-records',{method:'POST',body:JSON.stringify({channelId,start:begin,end:finish,marginBeforeMinutes:before,marginAfterMinutes:after})});status.textContent='Enregistrement programmé dans Free TV.'}catch(error){status.textContent=error.message}}});</script></body>`)
    .replace('</body>', `<script>window.addEventListener('DOMContentLoaded',()=>{const id=document.getElementById('freeTvProfileId'),login=document.getElementById('freeTvLogin'),password=document.getElementById('freeTvPassword'),status=document.getElementById('freeTvStatus'),connect=document.getElementById('associateFreeTv'),disconnect=document.getElementById('disconnectFreeTv');if(!id||!login||!password||!status||!connect||!disconnect)return;id.previousElementSibling?.style.setProperty('display','none');id.type='hidden';const fields=[login.previousElementSibling,login,password.previousElementSibling,password],path=()=>'/api/sync/profiles/'+encodeURIComponent(id.value.trim()||'1')+'/free-tv';let connected=false;const render=()=>{fields.forEach(field=>{if(field)field.style.display=connected?'none':''});disconnect.style.display=connected?'':'none';connect.style.display=connected?'none':'';connect.textContent='Connecter Free TV'};const refresh=async()=>{try{const data=await secure(path()+'/status');connected=Boolean(data.connected);render()}catch(error){connected=false;render()}};connect.addEventListener('click',()=>setTimeout(refresh,3500));disconnect.addEventListener('click',()=>setTimeout(refresh,500));id.addEventListener('change',refresh);refresh()});</script></body>`)
    .replace('</body>', `<script>window.addEventListener('DOMContentLoaded',()=>{const section=document.getElementById('free-tv'),guide=document.getElementById('freeTvGuide'),channel=document.getElementById('freeTvChannel'),date=document.getElementById('freeTvGuideDate'),button=document.getElementById('loadFreeTvGuide');if(!section||!guide||!channel||!date||!button)return;const sourceCard=guide.closest('.card'),panel=document.createElement('div');panel.className='card';panel.innerHTML='<div class="eyebrow">Guide de la journée</div><h2>Programmes</h2><p>Choisis une chaîne à gauche.</p>';panel.append(guide);section.querySelector('.grid').append(panel);button.style.display='none';const load=()=>{if(channel.value&&date.value)button.click()};channel.addEventListener('change',load);date.addEventListener('change',load);const observer=new MutationObserver(()=>{if(channel.options.length){observer.disconnect();load()}});observer.observe(channel,{childList:true})});</script></body>`)
    .replace('</body>', `<script>window.addEventListener('DOMContentLoaded',()=>{const box=document.getElementById('mergedManualFreeTvStatus'),start=document.getElementById('mergedManualStart'),end=document.getElementById('mergedManualEnd'),channel=document.getElementById('freeTvChannel'),button=document.getElementById('createMergedManualFreeTv');if(!box||!start||!end||!channel||!button)return;const manual=button.closest('div').parentElement,extra=document.createElement('div');extra.innerHTML='<label>Nom du programme</label><input id="mergedManualTitle" placeholder="Nom personnalisé (facultatif)"><label>Répétition (jours)</label><div class="row" id="mergedManualDays"><label><input type="checkbox" value="1"> Lundi</label><label><input type="checkbox" value="2"> Mardi</label><label><input type="checkbox" value="3"> Mercredi</label><label><input type="checkbox" value="4"> Jeudi</label><label><input type="checkbox" value="5"> Vendredi</label><label><input type="checkbox" value="6"> Samedi</label><label><input type="checkbox" value="0"> Dimanche</label></div>';button.closest('.actions').before(extra);button.onclick=async()=>{const begin=Math.floor(new Date(start.value).getTime()/1000),finish=Math.floor(new Date(end.value).getTime()/1000),before=Number(document.getElementById('mergedManualBefore').value||0),after=Number(document.getElementById('mergedManualAfter').value||0),days=[...document.querySelectorAll('#mergedManualDays input:checked')].map(input=>Number(input.value)),title=document.getElementById('mergedManualTitle').value.trim(),profile=document.getElementById('freeTvProfileId').value||'1';if(!channel.value||!begin||!finish){box.textContent='Renseigne la chaîne, le début et la fin.';return}button.disabled=true;box.textContent=days.length?'Programmation récurrente…':'Programmation en cours…';try{await secure('/api/sync/profiles/'+encodeURIComponent(profile)+'/free-tv/manual-records',{method:'POST',body:JSON.stringify({channelId:Number(channel.value),start:begin,end:finish,marginBeforeMinutes:before,marginAfterMinutes:after,title,recurrenceDays:days})});box.textContent=days.length?'Répétition programmée.':'Enregistrement programmé dans Free TV.'}catch(error){box.textContent=error.message}finally{button.disabled=false}}});</script></body>`)
    .replace('</body>', `<script>
      (()=>{const byId=id=>document.getElementById(id);const move=(items,current,step)=>{const index=items.indexOf(current);items[(index+step+items.length)%items.length].focus()};[['.navButton'],['.type']].forEach(([selector])=>{const items=[...document.querySelectorAll(selector)];items.forEach(item=>item.addEventListener('keydown',event=>{if(event.key==='ArrowDown'||event.key==='ArrowRight'){event.preventDefault();move(items,item,1)}if(event.key==='ArrowUp'||event.key==='ArrowLeft'){event.preventDefault();move(items,item,-1)}if(event.key==='Home'){event.preventDefault();items[0].focus()}if(event.key==='End'){event.preventDefault();items.at(-1).focus()}}))});const file=byId('m3uFile'),hint=byId('m3uFileHint'),send=byId('sendProvider');if(file){file.addEventListener('change',()=>{hint.textContent=file.files[0]?file.files[0].name+' · '+Math.ceil(file.files[0].size/1024)+' Ko à envoyer sur le NAS.':'Le fichier sera copié sur le NAS.'})}const submit=async()=>{let m3uUrl=byId('m3uUrl').value.trim();try{send.disabled=true;const dnsPayload=window.iptvPlaylistDnsPayload?window.iptvPlaylistDnsPayload():{};if(byId('type').value==='m3u'&&file&&file.files[0]){byId('iptvStatus').textContent='Copie du fichier M3U sur le NAS…';const selected=file.files[0];const response=await fetch('/api/m3u/upload?name='+encodeURIComponent(selected.name),{method:'POST',headers:{Authorization:'Bearer '+sessionStorage.getItem('jojotv_companion_token'),'content-type':selected.type||'audio/x-mpegurl'},body:selected});const uploaded=await response.json().catch(()=>({}));if(!response.ok)throw new Error(uploaded.error||'Import du fichier M3U impossible');m3uUrl=uploaded.url;hint.textContent=selected.name+' est prêt sur le NAS.'}const body={tv:byId('tvIp').value.trim(),port:byId('tvPort').value.trim(),type:byId('type').value,name:byId('providerName').value.trim(),m3uUrl,serverUrl:byId('serverUrl').value.trim(),username:byId('username').value.trim(),password:byId('providerPassword').value,portalUrl:byId('portalUrl').value.trim(),macAddress:byId('macAddress').value.trim(),...dnsPayload};const response=await fetch('/api/providers',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(body)});const data=await response.json().catch(()=>({}));if(!response.ok)throw new Error(data.error||'Envoi IPTV impossible');byId('iptvStatus').textContent=data.message}catch(error){byId('iptvStatus').textContent=error.message}finally{send.disabled=false}};send.onclick=submit;byId('providerName').addEventListener('keydown',event=>{if(event.key==='Enter'){event.preventDefault();submit()}})})();
    </script></body>`)
    .replace('</body>', `<script>(()=>{const section=document.getElementById('free-tv'),profile=()=>document.getElementById('freeTvProfileId')?.value.trim()||'1';if(!section)return;const grid=section.querySelector('.grid'),card=document.createElement('div');card.className='card wide';card.innerHTML='<div class="eyebrow">Programmation manuelle</div><h2>Créer un enregistrement manuel</h2><p>Pour une chaîne ou un créneau qui n’apparaît pas encore dans le guide. Durée maximale : 4 heures.</p><label>Chaîne Free TV</label><select id="manualChannelId"><option>Chargement des chaînes…</option></select><label>Date et heure de début</label><input id="manualStart" type="datetime-local"><label>Date et heure de fin</label><input id="manualEnd" type="datetime-local"><label>Marge avant (minutes)</label><input id="manualBefore" value="0" inputmode="numeric"><label>Marge après (minutes)</label><input id="manualAfter" value="0" inputmode="numeric"><div class="actions"><button id="createManualFreeTv" class="primary">Programmer l’enregistrement</button></div><div id="manualFreeTvStatus" class="status">Renseigne la chaîne et le créneau, puis programme l’enregistrement.</div>';grid.append(card);const start=card.querySelector('#manualStart'),end=card.querySelector('#manualEnd'),status=card.querySelector('#manualFreeTvStatus'),select=card.querySelector('#manualChannelId'),base=()=>'/api/sync/profiles/'+encodeURIComponent(profile())+'/free-tv',channels=value=>Object.values(value?.result?.channels||{}).filter(c=>c?.id&&c?.name&&!c?.adult&&c?.npvr_allowed!==false).map(c=>({id:c.id,name:c.name})).sort((a,b)=>a.name.localeCompare(b.name,'fr')),fmt=d=>new Date(d.getTime()-d.getTimezoneOffset()*60000).toISOString().slice(0,16);const loadChannels=async()=>{try{const data=await secure(base()+'/guide/channels'),list=channels(data.channels);select.replaceChildren();list.forEach(c=>{const o=document.createElement('option');o.value=c.id;o.textContent=c.name;select.append(o)});if(!select.options.length)select.innerHTML='<option value="536">TF1</option>'}catch(e){select.innerHTML='<option value="536">TF1</option>';status.textContent=e.message}};const now=new Date();now.setMinutes(now.getMinutes()-now.getMinutes()%5,0,0);start.value=fmt(now);end.value=fmt(new Date(now.getTime()+3600000));card.querySelector('#createManualFreeTv').onclick=async()=>{const channelId=Number(select.value),begin=Math.floor(new Date(start.value).getTime()/1000),finish=Math.floor(new Date(end.value).getTime()/1000),before=Number(card.querySelector('#manualBefore').value||0),after=Number(card.querySelector('#manualAfter').value||0);if(!channelId||!begin||!finish){status.textContent='Renseigne la chaîne, le début et la fin.';return}status.textContent='Programmation en cours…';try{await secure(base()+'/manual-records',{method:'POST',body:JSON.stringify({channelId,start:begin,end:finish,marginBeforeMinutes:before,marginAfterMinutes:after})});status.textContent='Enregistrement programmé dans Free TV.'}catch(e){status.textContent=e.message}};loadChannels()})();</script></body>`)
    .replace('</body>', `<script>(()=>{const section=document.getElementById('free-tv');const profile=()=>document.getElementById('freeTvProfileId')?.value.trim()||'1';if(!section)return;const grid=section.querySelector('.grid'),card=document.createElement('div');card.className='card wide';card.innerHTML='<div class="eyebrow">Guide TV</div><h2>Programmer un enregistrement</h2><p>Choisis une chaîne et une date pour afficher toute la journée.</p><label>Date du guide</label><input id="freeTvGuideDate" type="date"><label>Chaîne Free TV</label><select id="freeTvChannel"><option>Chargement des chaînes…</option></select><div class="actions"><button id="loadFreeTvGuide">Afficher le guide</button></div><div id="freeTvGuide" class="list meta">Sélectionne une chaîne et une date.</div>';grid.append(card);const select=card.querySelector('#freeTvChannel'),date=card.querySelector('#freeTvGuideDate'),guide=card.querySelector('#freeTvGuide'),base=()=>'/api/sync/profiles/'+encodeURIComponent(profile())+'/free-tv',order=['TF1','France 2','France 3','France 4','France 5','M6','Arte','C8','W9','TMC','TFX','NRJ 12','LCP','Franceinfo','BFM TV','CNews','LCI'],channels=value=>Object.values(value?.result?.channels||{}).filter(c=>c?.id&&c?.name&&!c?.adult&&c?.npvr_allowed!==false).map(c=>({id:c.id,name:c.name})).sort((a,b)=>{const ai=order.indexOf(a.name),bi=order.indexOf(b.name);return(ai<0?9999:ai)-(bi<0?9999:bi)||a.name.localeCompare(b.name,'fr')}),programs=value=>(Array.isArray(value)?value:[]).map(p=>p?.live||p).filter(p=>p?.id&&p?.title);date.value=new Date().toLocaleDateString('en-CA');const loadChannels=async()=>{try{const data=await secure(base()+'/guide/channels'),list=channels(data.channels);select.replaceChildren();list.forEach(c=>{const o=document.createElement('option');o.value=c.id;o.textContent=c.name;select.append(o)});if(!select.options.length)select.innerHTML='<option value="536">TF1</option>'}catch(e){select.innerHTML='<option value="536">TF1</option>';guide.textContent=e.message}};card.querySelector('#loadFreeTvGuide').onclick=async()=>{guide.textContent='Chargement de la journée…';try{const at=Math.floor(new Date(date.value+'T00:00:00').getTime()/1000),data=await secure(base()+'/guide/programs?channel='+encodeURIComponent(select.value)+'&at='+at),list=programs(data.programs);guide.replaceChildren();if(!list.length){guide.textContent='Aucun programme pour cette journée.';return}list.forEach(program=>{const id=program.id;const row=document.createElement('div');row.className='item';const text=document.createElement('div');text.innerHTML='<b>'+String(program.title)+'</b><div class="meta">'+new Date(Number(program.start)*1000).toLocaleString('fr-FR',{hour:'2-digit',minute:'2-digit'})+' – '+new Date(Number(program.end)*1000).toLocaleString('fr-FR',{hour:'2-digit',minute:'2-digit'})+'</div>';row.append(text);const b=document.createElement('button');b.className='primary';b.textContent='Enregistrer';b.onclick=async()=>{b.disabled=true;try{await secure(base()+'/records',{method:'POST',body:JSON.stringify({diffusionId:id})});b.textContent='Programmé'}catch(e){guide.textContent=e.message;b.disabled=false}};row.append(b);guide.append(row)})}catch(e){guide.textContent=e.message}};loadChannels()})();</script></body>`)
    .replace('</body>', `<script>window.addEventListener('DOMContentLoaded',()=>{const guide=document.getElementById('freeTvGuide'),date=document.getElementById('freeTvGuideDate'),channel=document.getElementById('freeTvChannel');if(!guide||!date||!channel)return;const localDate=d=>{const z=n=>String(n).padStart(2,'0');return d.getFullYear()+'-'+z(d.getMonth()+1)+'-'+z(d.getDate())},readEnd=row=>{const times=(row.querySelector('.meta')?.textContent||'').match(/(\\d{2}):(\\d{2}).*?(\\d{2}):(\\d{2})/);if(!times||!date.value)return null;const start=new Date(date.value+'T'+times[1]+':'+times[2]+':00'),end=new Date(date.value+'T'+times[3]+':'+times[4]+':00');if(end<=start)end.setDate(end.getDate()+1);return end},setHeading=()=>{const heading=guide.closest('.card')?.querySelector('p');if(!heading||!date.value)return;heading.textContent='Guide du '+new Date(date.value+'T12:00:00').toLocaleDateString('fr-FR',{weekday:'long',day:'numeric',month:'long',year:'numeric'})+'.'},refresh=()=>{setHeading();const now=new Date(),today=localDate(now);guide.querySelectorAll('.item').forEach(row=>{const button=row.querySelector('button.primary');if(!button||button.textContent!=='Enregistrer')return;const end=readEnd(row);if(date.value<today||(date.value===today&&end&&end<=now)){button.disabled=true;button.textContent='Terminé';button.title='Ce programme est déjà terminé.';button.style.opacity='.42';button.style.cursor='not-allowed'}})};const close=()=>document.getElementById('freeTvRecordModal')?.remove(),open=row=>{close();const modal=document.createElement('div');modal.id='freeTvRecordModal';modal.style.cssText='position:fixed;inset:0;z-index:1000;background:#000a;display:grid;place-items:center;padding:20px';const box=document.createElement('div');box.className='card';box.style.cssText='width:min(520px,100%);max-height:90vh;overflow:auto';const title=row.querySelector('b')?.textContent||'Programme';box.innerHTML='<div class="eyebrow">Enregistrement Free TV</div><h2>'+title+'</h2><p>'+((row.querySelector('.meta')?.textContent)||'')+'</p><label>Flux</label><select id="freeTvRecordSource"><option value="cloud">Cloud Free TV</option><option disabled>Fibre — enregistrement local Freebox</option><option disabled>TNT — enregistrement local Freebox</option></select><p class="meta">Fibre et TNT nécessitent l’autorisation séparée de Freebox OS ; ils ne sont pas envoyés comme un faux réglage à Free TV.</p><label>Marge avant (minutes)</label><input id="freeTvRecordBefore" type="number" min="0" max="60" value="0"><label>Marge après (minutes)</label><input id="freeTvRecordAfter" type="number" min="0" max="60" value="0"><div class="actions"><button id="freeTvRecordConfirm" class="primary">Confirmer l’enregistrement</button><button id="freeTvRecordCancel">Annuler</button></div><div id="freeTvRecordStatus" class="status"></div>';modal.append(box);document.body.append(modal);modal.addEventListener('click',event=>{if(event.target===modal)close()});box.querySelector('#freeTvRecordCancel').onclick=close;box.querySelector('#freeTvRecordConfirm').onclick=async()=>{const confirm=box.querySelector('#freeTvRecordConfirm'),status=box.querySelector('#freeTvRecordStatus'),meta=row.querySelector('.meta')?.textContent||'',times=meta.match(/(\\d{2}):(\\d{2})/),label=row.querySelector('b')?.textContent||'';if(!times){status.textContent='Programme introuvable.';return}confirm.disabled=true;status.textContent='Programmation en cours…';try{const at=Math.floor(new Date(date.value+'T00:00:00').getTime()/1000),profile=document.getElementById('freeTvProfileId')?.value.trim()||'1',data=await secure('/api/sync/profiles/'+encodeURIComponent(profile)+'/free-tv/guide/programs?channel='+encodeURIComponent(channel.value)+'&at='+at),program=(Array.isArray(data.programs)?data.programs:[]).map(p=>p.live||p).find(p=>String(p.title)===label&&new Date(Number(p.start)*1000).getHours()===Number(times[1])&&new Date(Number(p.start)*1000).getMinutes()===Number(times[2]));if(!program)throw new Error('Programme introuvable dans le guide.');await secure('/api/sync/profiles/'+encodeURIComponent(profile)+'/free-tv/records',{method:'POST',body:JSON.stringify({diffusionId:Number(program.id),marginBeforeMinutes:Number(box.querySelector('#freeTvRecordBefore').value||0),marginAfterMinutes:Number(box.querySelector('#freeTvRecordAfter').value||0)})});row.querySelector('button.primary').textContent='Programmé';row.querySelector('button.primary').disabled=true;close()}catch(error){status.textContent=error.message;confirm.disabled=false}}};guide.addEventListener('click',event=>{const button=event.target.closest('.item button.primary');if(!button||button.disabled)return;event.preventDefault();event.stopImmediatePropagation();open(button.closest('.item'))},true);new MutationObserver(refresh).observe(guide,{childList:true,subtree:true});date.addEventListener('change',refresh);refresh()});</script></body>`)
    .replace('</body>', `<script>window.addEventListener('DOMContentLoaded',()=>{const section=document.getElementById('free-tv'),profile=()=>document.getElementById('freeTvProfileId')?.value.trim()||'1';if(!section)return;const card=document.createElement('div');card.className='card';card.innerHTML='<div class="eyebrow">Enregistrement local</div><h2>Freebox OS</h2><p>Utilise le disque de la Freebox ou une clé USB, avec le flux Fibre ou TNT.</p><div class="actions"><button id="pairFreeboxOs" class="primary">Autoriser sur la Freebox</button></div><div id="freeboxOsStatus" class="status">Non autorisé.</div><div id="freeboxOsOptions" class="hidden"><label>Sources du flux</label><div class="row"><label><input type="checkbox" checked> Fibre</label><label><input type="checkbox"> TNT</label></div><label>Qualité vidéo</label><select><option>Automatique</option><option>4K</option><option>HD+</option><option>HD</option><option>Normale</option><option>Bas débit</option></select><label>Destination</label><select id="freeboxOsMedia"><option>Chargement des supports…</option></select></div>';section.querySelector('.grid').append(card);const status=card.querySelector('#freeboxOsStatus'),pair=card.querySelector('#pairFreeboxOs'),options=card.querySelector('#freeboxOsOptions'),path=suffix=>'/api/sync/profiles/'+encodeURIComponent(profile())+'/freebox-os/'+suffix;let timer;const loadMedia=async()=>{try{const data=await secure(path('media')),media=Array.isArray(data.media)?data.media:[],select=card.querySelector('#freeboxOsMedia');select.replaceChildren();media.forEach(item=>{const option=document.createElement('option');option.value=item.id||item.path||item.name||'';option.textContent=item.label||item.name||item.path||'Support Freebox';select.append(option)});if(!select.options.length)select.innerHTML='<option>Disque Freebox (aucun support détecté)</option>'}catch(error){status.textContent=error.message}};const refresh=async()=>{try{const data=await secure(path('status'));if(data.state==='granted'){status.textContent='Freebox OS autorisé. Les supports de stockage sont chargés.';pair.style.display='none';options.classList.remove('hidden');clearInterval(timer);loadMedia()}else if(data.state==='pending'){status.textContent='Valide maintenant la demande « Jojo Home Companion » sur l’écran de ta Freebox.';pair.disabled=true;timer=timer||setInterval(refresh,2000)}else if(data.state==='denied'||data.state==='timeout'){status.textContent='Autorisation refusée ou expirée. Relance la demande.';pair.disabled=false;clearInterval(timer);timer=null}else{status.textContent='Autorise Jojo Home Companion sur l’écran de ta Freebox.';pair.disabled=false}}catch(error){status.textContent=error.message;pair.disabled=false}};pair.onclick=async()=>{pair.disabled=true;status.textContent='Demande envoyée à la Freebox…';try{await secure(path('authorize'),{method:'POST'});await refresh()}catch(error){status.textContent=error.message;pair.disabled=false}};refresh()});</script></body>`)
    .replace('</body>', `<script>window.addEventListener('DOMContentLoaded',()=>{const guide=document.getElementById('freeTvGuide'),local=[...document.querySelectorAll('#free-tv .card')].find(card=>card.querySelector('#freeboxOsOptions'));if(!guide||!local)return;const target=guide.closest('.card'),options=local.querySelector('#freeboxOsOptions'),status=local.querySelector('#freeboxOsStatus'),pair=local.querySelector('#pairFreeboxOs'),quality=options.querySelector('select:not(#freeboxOsMedia)'),destination=options.querySelector('#freeboxOsMedia'),fib=options.querySelectorAll('input[type=checkbox]')[0],tnt=options.querySelectorAll('input[type=checkbox]')[1],cloud=document.createElement('input'),cloudLabel=document.createElement('label'),sources=options.querySelector('.row');local.className='localRecordSettings';local.style.cssText='margin-top:24px;padding-top:18px;border-top:1px solid #2d3a52';local.querySelector('.eyebrow').textContent='Paramètres d’enregistrement';local.querySelector('h2').textContent='Flux, qualité et destination';local.querySelector('p').textContent='Choisis un flux. Le Cloud Free TV ne nécessite ni qualité ni support local.';cloud.type='checkbox';cloud.checked=true;cloud.id='recordSourceCloud';cloudLabel.htmlFor=cloud.id;cloudLabel.append(cloud,' Cloud Free TV');fib.checked=false;fib.id='recordSourceFibre';fib.parentElement.htmlFor=fib.id;tnt.id='recordSourceTnt';tnt.parentElement.htmlFor=tnt.id;sources.prepend(cloudLabel);target.append(local);const setDisabled=(element,disabled)=>{element.disabled=disabled;element.style.opacity=disabled?'.45':'';element.title=disabled?'Indisponible pour le Cloud Free TV.':''},sync=changed=>{if(changed===cloud&&cloud.checked){fib.checked=false;tnt.checked=false}else if(changed&&(changed===fib||changed===tnt)&&changed.checked){cloud.checked=false;const other=changed===fib?tnt:fib;other.checked=false}if(!cloud.checked&&!fib.checked&&!tnt.checked)cloud.checked=true;const isCloud=cloud.checked;setDisabled(quality,isCloud);setDisabled(destination,isCloud);window.jojoTvRecordingSource=isCloud?'cloud':fib.checked?'iptv':'dvb'};[cloud,fib,tnt].forEach(input=>input.addEventListener('change',()=>sync(input)));sync();const loadMedia=async()=>{try{const profile=document.getElementById('freeTvProfileId')?.value.trim()||'1',data=await secure('/api/sync/profiles/'+encodeURIComponent(profile)+'/freebox-os/media'),media=Array.isArray(data.media)?data.media:[];destination.replaceChildren();media.forEach(item=>{const option=document.createElement('option');option.value=item.media||'';const free=Number(item.free_bytes||0),total=Number(item.total_bytes||0),size=free&&total?' · '+Math.round(free/1073741824)+' Go libres':' ';option.textContent=(item.media||'Support Freebox')+size;destination.append(option)});if(!destination.options.length)destination.innerHTML='<option>Disque Freebox</option>'}catch(error){status.textContent=error.message}};const watch=()=>{if(!options.classList.contains('hidden'))loadMedia();else setTimeout(watch,1500)};watch();if(pair&&pair.style.display==='none')loadMedia()});</script></body>`)
    .replace('</body>', `<script>window.addEventListener('DOMContentLoaded',()=>{const guide=document.getElementById('freeTvGuide'),date=document.getElementById('freeTvGuideDate'),channel=document.getElementById('freeTvChannel'),quality=document.querySelector('.localRecordSettings select:not(#freeboxOsMedia)'),media=document.getElementById('freeboxOsMedia');if(!guide||!date||!channel)return;const bind=modal=>{if(modal.dataset.localBound||window.jojoTvRecordingSource==='cloud')return;modal.dataset.localBound='1';const select=modal.querySelector('#freeTvRecordSource'),confirm=modal.querySelector('#freeTvRecordConfirm'),status=modal.querySelector('#freeTvRecordStatus'),rowTitle=modal.querySelector('h2')?.textContent||'';if(select)select.innerHTML='<option>'+((window.jojoTvRecordingSource==='dvb')?'TNT — Freebox OS':'Fibre — Freebox OS')+'</option>';confirm.onclick=async()=>{const meta=modal.querySelector('p')?.textContent||'',times=meta.match(/(\\d{2}):(\\d{2})/);if(!times){status.textContent='Programme local introuvable.';return}confirm.disabled=true;status.textContent='Programmation locale Freebox…';try{const at=Math.floor(new Date(date.value+'T00:00:00').getTime()/1000),profile=document.getElementById('freeTvProfileId')?.value.trim()||'1',data=await secure('/api/sync/profiles/'+encodeURIComponent(profile)+'/free-tv/guide/programs?channel='+encodeURIComponent(channel.value)+'&at='+at),program=(Array.isArray(data.programs)?data.programs:[]).map(p=>p.live||p).find(p=>String(p.title)===rowTitle&&new Date(Number(p.start)*1000).getHours()===Number(times[1])&&new Date(Number(p.start)*1000).getMinutes()===Number(times[2]));if(!program)throw new Error('Programme introuvable dans le guide.');await secure('/api/sync/profiles/'+encodeURIComponent(profile)+'/freebox-os/records',{method:'POST',body:JSON.stringify({channelName:channel.options[channel.selectedIndex]?.textContent.trim()||'',name:rowTitle,start:Number(program.start),end:Number(program.end),source:window.jojoTvRecordingSource,quality:quality?.value||'Automatique',media:media?.value||'',marginBeforeMinutes:Number(modal.querySelector('#freeTvRecordBefore').value||0),marginAfterMinutes:Number(modal.querySelector('#freeTvRecordAfter').value||0)})});const row=[...guide.querySelectorAll('.item')].find(item=>item.querySelector('b')?.textContent===rowTitle);if(row){const button=row.querySelector('button.primary');button.textContent='Programmé Freebox';button.disabled=true}modal.remove()}catch(error){status.textContent=error.message;confirm.disabled=false}}};new MutationObserver(()=>document.querySelectorAll('#freeTvRecordModal').forEach(bind)).observe(document.body,{childList:true,subtree:true})});</script></body>`)
    .replace('</body>', `<script>window.addEventListener('DOMContentLoaded',()=>{const guide=document.getElementById('freeTvGuide'),date=document.getElementById('freeTvGuideDate'),channel=document.getElementById('freeTvChannel'),local=document.querySelector('.localRecordSettings');if(!guide||!date||!channel||!local)return;const target=guide.closest('.card'),manual=target.querySelector('hr'),cloud=document.getElementById('recordSourceCloud'),fibre=document.getElementById('recordSourceFibre'),tnt=document.getElementById('recordSourceTnt'),quality=local.querySelector('select:not(#freeboxOsMedia)'),media=document.getElementById('freeboxOsMedia');if(manual)target.insertBefore(local,manual);const wanted=new Set([cloud].filter(Boolean).filter(input=>input.checked).map(input=>input.id));const render=()=>{[cloud,fibre,tnt].forEach(input=>{if(input)input.checked=wanted.has(input.id)});if(!wanted.size&&cloud){wanted.add(cloud.id);cloud.checked=true}const hasLocal=wanted.has(fibre?.id)||wanted.has(tnt?.id);[quality,media].forEach(input=>{if(!input)return;input.disabled=!hasLocal;input.style.opacity=hasLocal?'':'.45';input.title=hasLocal?'':'Disponibles uniquement pour Fibre ou TNT.'});window.jojoTvRecordingSources=[wanted.has(cloud?.id)&&'cloud',wanted.has(fibre?.id)&&'iptv',wanted.has(tnt?.id)&&'dvb'].filter(Boolean)};[cloud,fibre,tnt].forEach(input=>input?.addEventListener('change',event=>{if(event.target.checked)wanted.add(event.target.id);else wanted.delete(event.target.id);setTimeout(render,0)},true));render();const bind=modal=>{if(modal.dataset.multiBound)return;modal.dataset.multiBound='1';const confirm=modal.querySelector('#freeTvRecordConfirm'),status=modal.querySelector('#freeTvRecordStatus'),source=modal.querySelector('#freeTvRecordSource'),title=modal.querySelector('h2')?.textContent||'';if(source)source.innerHTML='<option>'+window.jojoTvRecordingSources.map(value=>value==='cloud'?'Cloud Free TV':value==='iptv'?'Fibre Freebox':'TNT Freebox').join(' + ')+'</option>';confirm.onclick=async()=>{const meta=modal.querySelector('p')?.textContent||'',times=meta.match(/(\\d{2}):(\\d{2})/);if(!times){status.textContent='Programme introuvable.';return}confirm.disabled=true;status.textContent='Programmation en cours…';try{const at=Math.floor(new Date(date.value+'T00:00:00').getTime()/1000),profile=document.getElementById('freeTvProfileId')?.value.trim()||'1',base='/api/sync/profiles/'+encodeURIComponent(profile),data=await secure(base+'/free-tv/guide/programs?channel='+encodeURIComponent(channel.value)+'&at='+at),program=(Array.isArray(data.programs)?data.programs:[]).map(p=>p.live||p).find(p=>String(p.title)===title&&new Date(Number(p.start)*1000).getHours()===Number(times[1])&&new Date(Number(p.start)*1000).getMinutes()===Number(times[2]));if(!program)throw new Error('Programme introuvable dans le guide.');const before=Number(modal.querySelector('#freeTvRecordBefore').value||0),after=Number(modal.querySelector('#freeTvRecordAfter').value||0),jobs=window.jojoTvRecordingSources.map(value=>value==='cloud'?secure(base+'/free-tv/records',{method:'POST',body:JSON.stringify({diffusionId:Number(program.id),marginBeforeMinutes:before,marginAfterMinutes:after})}):secure(base+'/freebox-os/records',{method:'POST',body:JSON.stringify({channelName:channel.options[channel.selectedIndex]?.textContent.trim()||'',name:title,start:Number(program.start),end:Number(program.end),source:value,quality:quality?.value||'Automatique',media:media?.value||'',marginBeforeMinutes:before,marginAfterMinutes:after})}));await Promise.all(jobs);const row=[...guide.querySelectorAll('.item')].find(item=>item.querySelector('b')?.textContent===title);if(row){const button=row.querySelector('button.primary');button.textContent='Programmé';button.disabled=true}modal.remove()}catch(error){status.textContent=error.message;confirm.disabled=false}}};new MutationObserver(()=>document.querySelectorAll('#freeTvRecordModal').forEach(bind)).observe(document.body,{childList:true,subtree:true})});</script></body>`)
    .replace('</body>', `<script>window.addEventListener('DOMContentLoaded',()=>{const settings=document.querySelector('.localRecordSettings'),formCard=document.getElementById('freeTvGuideDate')?.closest('.card');if(!settings||!formCard)return;const divider=formCard.querySelector('hr');if(divider)formCard.insertBefore(settings,divider);else formCard.append(settings)});</script></body>`)
    .replace('</body>', `<script>window.addEventListener('DOMContentLoaded',()=>{const settings=document.querySelector('.localRecordSettings'),formCard=document.getElementById('freeTvGuideDate')?.closest('.card');if(settings&&formCard){settings.style.cssText='margin:14px 0 0;padding:0;border:0';settings.querySelector('.eyebrow')?.remove();settings.querySelector('h2')?.remove();settings.querySelector('p')?.remove();const sourceLabel=settings.querySelector('#recordSourceCloud')?.closest('.row')?.previousElementSibling;if(sourceLabel)sourceLabel.textContent='Enregistrer sur';const divider=formCard.querySelector('hr');if(divider)formCard.insertBefore(settings,divider);else formCard.append(settings)}});</script></body>`)
    .replace('</body>', `<script>window.addEventListener('DOMContentLoaded',()=>{const buttons=[...document.querySelectorAll('.navButton')],show=view=>{const button=buttons.find(item=>item.dataset.view===view);if(!button)return;buttons.forEach(item=>item.classList.toggle('active',item===button));document.querySelectorAll('.view').forEach(item=>item.classList.toggle('active',item.id===view));if(typeof titles!=='undefined'&&titles[view]){$('title').textContent=titles[view][0];$('subtitle').textContent=titles[view][1]}};const remembered=decodeURIComponent(location.hash.slice(1)),preferred=remembered||'profiles';show(preferred==='avatars'?'profiles':preferred);buttons.forEach(button=>button.addEventListener('click',()=>{const view=button.dataset.view;localStorage.setItem('jojotv_companion_active_view',view);history.replaceState(null,'','#'+encodeURIComponent(view))},true))});</script></body>`)
    .replace('</body>', `<script>
      (()=>{
        const knownTv=document.getElementById('knownTv');
        const restoreTarget=document.getElementById('restoreTarget');
        const ip=document.getElementById('tvIp');
        if(!knownTv||!restoreTarget||!ip)return;
        const refreshKnownTvs=()=>{
          const selected=ip.value.trim();
          const options=[...restoreTarget.options]
            .filter(option=>option.value)
            .map(option=>({ip:option.value,label:option.textContent}));
          knownTv.innerHTML='<option value="">Saisie manuelle d’une adresse IP</option>'+options.map(option=>'<option value="'+option.ip+'">'+option.label+'</option>').join('');
          knownTv.value=options.some(option=>option.ip===selected)?selected:'';
          knownTv.disabled=options.length===0;
        };
        knownTv.addEventListener('change',()=>{if(knownTv.value){ip.value=knownTv.value;ip.dispatchEvent(new Event('input',{bubbles:true}));}});
        ip.addEventListener('input',()=>{if(knownTv.value!==ip.value.trim())knownTv.value='';});
        new MutationObserver(refreshKnownTvs).observe(restoreTarget,{childList:true});
        refreshKnownTvs();
      })();
    </script></body>`)
    .replace("byId('iptvStatus').textContent=data.message}catch(error)", "byId('iptvStatus').textContent=data.message;window.companionProviderSubmitted(data)}catch(error)")
    .replace('</body>', '<script>(' + companionIptvProvidersClient.toString() + ')();</script></body>')
    .replace('</body>', `<script>(()=>{const id=document.getElementById('freeTvProfileId'),login=document.getElementById('freeTvLogin'),password=document.getElementById('freeTvPassword'),status=document.getElementById('freeTvStatus'),connect=document.getElementById('associateFreeTv'),disconnect=document.getElementById('disconnectFreeTv'),records=document.getElementById('freeTvRecords'),refreshRecords=document.getElementById('refreshFreeTvRecords');if(!id||!status||!connect||!disconnect)return;const path=()=>'/api/sync/profiles/'+encodeURIComponent(id.value.trim()||'1')+'/free-tv';const request=async(suffix,options={})=>secure(path()+suffix,options);const esc=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));const loadRecords=async()=>{if(!records)return;records.textContent='Chargement…';try{const data=await request('/records');const source=data.records||{};const list=Array.isArray(source)?source:(source.result||source.items||source.records||[]);if(!Array.isArray(list)||!list.length){records.textContent='Aucun enregistrement Free TV.';return}records.innerHTML=list.map(item=>{const record=item.record||item;const recordId=record.id||record.record_id||record.uuid||'';const title=record.title||record.name||record.program_title||'Enregistrement Free TV';const subtitle=[record.channel_name||record.channel?.name,record.start_at||record.start||record.date].filter(Boolean).join(' · ');return '<div class="item"><div><b>'+esc(title)+'</b><div class="meta">'+esc(subtitle||'Détails disponibles dans Free TV')+'</div></div>'+(recordId?'<button class="danger" data-free-tv-delete="'+esc(recordId)+'">Supprimer</button>':'')+'</div>'}).join('');records.querySelectorAll('[data-free-tv-delete]').forEach(button=>button.addEventListener('click',async()=>{if(!confirm('Supprimer cet enregistrement Free TV ?'))return;button.disabled=true;try{await request('/records/'+encodeURIComponent(button.dataset.freeTvDelete),{method:'DELETE'});await loadRecords()}catch(e){status.textContent=e.message}finally{button.disabled=false}}))}catch(e){records.textContent=e.message}};const refresh=async()=>{try{const data=await request('/status');status.textContent=data.connected?'Free TV est associé à ce profil.':'Free TV n’est pas encore associé.';if(data.connected)loadRecords()}catch(e){status.textContent=e.message}};id.addEventListener('change',refresh);refreshRecords?.addEventListener('click',loadRecords);connect.addEventListener('click',async()=>{if(!login.value.trim()||!password.value){status.textContent='Saisis tes identifiants Freebox.';return}connect.disabled=true;status.textContent='Connexion à Free TV…';try{await request('/authorize/freebox',{method:'POST',body:JSON.stringify({login:login.value.trim(),password:password.value})});password.value='';await refresh()}catch(e){password.value='';status.textContent=e.message}finally{connect.disabled=false}});disconnect.addEventListener('click',async()=>{disconnect.disabled=true;try{await request('/session',{method:'DELETE'});if(records)records.textContent='Free TV est dissocié.';await refresh()}catch(e){status.textContent=e.message}finally{disconnect.disabled=false}});refresh()})();</script></body>`));
}

function companionDashboardPage() {
  return `<!doctype html><html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Jojo Home Companion</title><style>
  :root{color-scheme:dark;font-family:Inter,ui-sans-serif,system-ui,sans-serif;--bg:#070b16;--panel:#11192b;--panel2:#171f33;--line:#2a3753;--text:#f4f7ff;--muted:#9baac4;--yellow:#FFB309;--cyan:#12d9ee}*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at 70% -20%,#19375c 0,transparent 40%),var(--bg);color:var(--text);min-height:100vh}.app{min-height:100vh;display:grid;grid-template-columns:250px 1fr}.sidebar{padding:26px 16px;border-right:1px solid #24304a;background:#0a1020}.brand{display:flex;gap:10px;align-items:center;padding:0 10px 25px;font-weight:900;font-size:20px}.brand i{display:grid;place-items:center;width:34px;height:34px;border-radius:10px;background:var(--yellow);color:#111;font-style:normal}.nav{display:grid;gap:7px}.nav button{background:transparent;color:#cfd9ef;border:0;border-radius:12px;text-align:left;padding:13px 14px;font:700 15px inherit;cursor:pointer}.nav button.active,.nav button:hover{background:var(--yellow);color:#161100}.account{position:fixed;bottom:18px;left:16px;width:218px;padding:12px;color:var(--muted);font-size:12px;border-top:1px solid var(--line)}.main{max-width:1180px;width:100%;margin:0 auto;padding:36px 34px 70px}.top{display:flex;justify-content:space-between;gap:20px;align-items:start}.eyebrow{color:var(--cyan);font-weight:800;font-size:12px;letter-spacing:.12em;text-transform:uppercase}h1{font-size:32px;margin:4px 0 7px}h2{font-size:21px;margin:0 0 7px}p{color:var(--muted);line-height:1.55}.view{display:none}.view.active{display:block}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px;margin-top:22px}.card{padding:20px;border:1px solid var(--line);background:linear-gradient(145deg,#151f34,#0f1728);border-radius:18px;box-shadow:0 16px 36px #0003}.wide{grid-column:1/-1}.row{display:grid;grid-template-columns:1fr 1fr;gap:12px}.tabs,.actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:15px}button,input,select{font:inherit}button{border:0;border-radius:10px;padding:10px 13px;background:#293750;color:#f4f7ff;font-weight:750;cursor:pointer}button.primary{background:var(--yellow);color:#1a1500}button.danger{background:#a92d3d}button:disabled{opacity:.55;cursor:wait}input,select{width:100%;color:#fff;background:#0a1120;border:1px solid #34425e;border-radius:10px;padding:11px}label{display:block;color:#bfcae0;font-size:13px;font-weight:700;margin:14px 0 6px}.status{margin-top:14px;min-height:24px;padding:10px 12px;border-radius:10px;background:#0b1324;color:#cdd8ee;white-space:pre-wrap}.hidden{display:none!important}.profiles{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:12px;margin-top:16px}.profile{border:1px solid #33415e;border-radius:15px;padding:15px;background:#0b1322}.avatar{width:34px;height:34px;border-radius:50%;display:inline-grid;place-items:center;color:#fff;font-weight:900;margin-right:9px}.profile-head{display:flex;align-items:center;font-weight:800}.metrics{display:grid;grid-template-columns:repeat(4,1fr);gap:7px;margin-top:15px}.metric{background:#151f33;border-radius:9px;padding:8px;text-align:center;font-size:12px;color:var(--muted)}.metric b{display:block;color:#fff;font-size:16px}.list{display:grid;gap:9px;margin-top:14px}.item{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:12px;border:1px solid #2d3a55;border-radius:12px;background:#0c1424}.meta{color:var(--muted);font-size:12px}.login{display:grid;place-items:center;min-height:100vh;padding:20px}.login .card{width:min(420px,100%)}@media(max-width:760px){.app{grid-template-columns:1fr}.sidebar{display:flex;align-items:center;gap:10px;padding:12px;border-right:0;border-bottom:1px solid var(--line)}.brand{padding:0}.nav{display:flex;grid-template-columns:none}.nav button{padding:9px}.account{display:none}.main{padding:25px 16px}.grid,.row{grid-template-columns:1fr}.wide{grid-column:auto}}
  </style></head><body><section id="authLoading" class="login"><div class="card"><div class="eyebrow">NAS privé</div><h1>Jojo Home Companion</h1><p>Restauration de ta session…</p></div></section><section id="login" class="login hidden"><div class="card"><div class="eyebrow">NAS privé</div><h1>Jojo Home Companion</h1><p>Connecte-toi pour accéder aux données de ton compte et à la maintenance.</p><label>E-mail NAS</label><input id="email" type="email" autocomplete="email"><label>Mot de passe NAS</label><input id="password" type="password" autocomplete="current-password"><button id="loginButton" class="primary" style="width:100%;margin-top:18px">Ouvrir Companion</button><div id="loginStatus" class="status"></div></div></section><div id="app" class="app hidden"><aside class="sidebar"><div class="brand"><i>J</i> Jojo Home <span style="color:var(--yellow)">Companion</span></div><nav class="nav"><button class="navButton active" data-view="iptv">▣ &nbsp; IPTV</button><button class="navButton" data-view="maintenance">▣ &nbsp; Maintenance</button><button class="navButton" data-view="profiles">● &nbsp; Comptes</button></nav><div id="account" class="account"></div></aside><main class="main"><header class="top"><div><div id="eyebrow" class="eyebrow">Tableau de bord NAS</div><h1 id="title">IPTV</h1><p id="subtitle">Ajoute et pilote les fournisseurs de ta TV Jojo Home.</p></div><button id="refresh">Actualiser</button></header><section id="iptv" class="view active"><div class="grid"><div class="card"><h2>TV cible</h2><label>Adresse IP</label><input id="tvIp" placeholder="192.168.1.150"><input id="tvPort" type="hidden" value="8093"><div class="actions"><button id="testTv">Tester Jojo Home</button></div><div id="connection" class="status">Sélectionne une TV.</div></div><div class="card"><h2>Nouveau fournisseur</h2><div class="tabs"><button class="type active" data-type="m3u">M3U</button><button class="type" data-type="xtream">Xtream</button><button class="type" data-type="stalker">Stalker</button></div><input id="type" type="hidden" value="m3u"><label>Nom</label><input id="providerName" placeholder="Mon fournisseur"><section data-section="m3u"><label>URL playlist M3U</label><input id="m3uUrl" placeholder="https://…/playlist.m3u"></section><section class="hidden" data-section="xtream"><label>URL serveur</label><input id="serverUrl" placeholder="http://serveur:8080"><div class="row"><div><label>Identifiant</label><input id="username"></div><div><label>Mot de passe</label><input id="providerPassword" type="password"></div></div></section><section class="hidden" data-section="stalker"><label>URL portail</label><input id="portalUrl"><label>Adresse MAC</label><input id="macAddress" value="00:1A:79:"></section><button id="sendProvider" class="primary" style="margin-top:18px;width:100%">Envoyer à Jojo Home</button><div id="iptvStatus" class="status">Prêt.</div></div><div class="card wide"><h2>Historique Companion</h2><div id="history" class="list meta">Chargement…</div></div></div></section><section id="maintenance" class="view"><div class="grid"><div class="card"><div class="eyebrow">Sauvegarde manuelle</div><h2>Créer un snapshot NAS</h2><p>Le snapshot est pris immédiatement et active la rotation quotidienne de 03:00. Les 7 dernières versions sont conservées.</p><div class="actions"><button class="primary backup" data-app="jojohome">Sauvegarder Jojo Home</button></div><div id="maintenanceStatus" class="status">Aucune opération en cours.</div></div><div class="card"><div class="eyebrow">Restauration manuelle</div><h2>TV prioritaire</h2><p>Une restauration remplace l'état NAS du compte. Elle est appliquée uniquement après confirmation et la TV choisie le recharge à son prochain lancement.</p><label>TV à restaurer en premier</label><select id="restoreTarget"></select><label>Ou saisir une IP</label><input id="restoreIp" placeholder="192.168.1.150"></div><div class="card wide"><h2>Sauvegardes disponibles</h2><div id="backups" class="list meta">Chargement…</div></div></div></section><section id="profiles" class="view"><div class="card"><div class="eyebrow">État synchronisé</div><h2>Comptes JojoHome et favoris IPTV</h2><p>Clique sur les compteurs pour voir les favoris et leurs vignettes. Modifie le nom ou l’avatar d’un compte, ou supprime-le. Les changements sont partagés avec JojoHome et le module IPTV.</p><div id="profilesList" class="profiles"></div><div id="accountStatus" class="status" role="status" hidden></div></div></section></main></div><script>
  const $=id=>document.getElementById(id),tokenKey='jojotv_companion_token';let token=sessionStorage.getItem(tokenKey)||'',overview=null;const titles={iptv:['IPTV','Ajoute et pilote les fournisseurs de ta TV Jojo Home.'],maintenance:['Maintenance','Sauvegarde et restauration manuelle de l’état NAS.'],profiles:['Comptes','Comptes JojoHome et favoris IPTV synchronisés.']};
  function authHeaders(json=false){return Object.assign({Authorization:'Bearer '+token},json?{'content-type':'application/json'}:{})}async function secure(path,options={}){const response=await fetch(path,Object.assign({},options,{headers:Object.assign({},authHeaders(Boolean(options.body)),options.headers||{})}));const data=await response.json().catch(()=>({}));if(!response.ok)throw new Error(data.error||'Erreur NAS');return data}async function plain(path,options={}){const response=await fetch(path,options);const data=await response.json().catch(()=>({}));if(!response.ok)throw new Error(data.error||'Erreur Companion');return data}function esc(s){return String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}function fmt(bytes){return bytes<1048576?Math.max(1,Math.round(bytes/1024))+' Ko':(bytes/1048576).toFixed(1)+' Mo'}function date(ms){return new Date(ms).toLocaleString('fr-FR')}
  async function load(){overview=await secure('/api/companion/overview');$('account').textContent=overview.account.email+' · NAS connecté';renderAccounts();renderBackups();renderTargets();loadProviderHistory()}function renderAccounts(){
    const list=$('profilesList'),accounts=overview.accounts||[];
    list.innerHTML=accounts.length?accounts.map(p=>'<article class="profile"><div class="profile-head">'+companionAvatarPreview(p.avatar||{},Array.from(p.name.trim())[0]||'C',p.ornamentColor||'#D3A033')+'<span>'+esc(p.name)+'</span></div><div class="metrics accountFavoriteMetrics">'+[['all','Total',p.iptvFavorites],['live','Chaînes',p.liveFavorites],['movies','Films',p.movieFavorites],['series','Séries',p.seriesFavorites]].map(([category,label,count])=>'<button type="button" class="metric" data-account-action="favorites" data-account-id="'+esc(p.id)+'" data-account-category="'+category+'" aria-label="Voir les favoris '+label+' de '+esc(p.name)+'"><b>'+count+'</b>'+label+'</button>').join('')+'</div><div class="actions accountActions"><button type="button" data-account-action="edit" data-account-id="'+esc(p.id)+'" aria-label="Éditer le compte '+esc(p.name)+'">Éditer</button><button type="button" data-account-action="avatar" data-account-id="'+esc(p.id)+'" aria-label="Choisir un avatar pour '+esc(p.name)+'">Choisir un avatar</button><button type="button" class="danger" data-account-action="delete" data-account-id="'+esc(p.id)+'" aria-label="Supprimer le compte '+esc(p.name)+'">Supprimer</button></div></article>').join(''):'Aucun compte JojoHome synchronisé pour le moment. Ouvre JojoHome sur une TV connectée au même compte NAS.';
  }function renderTargets(){const target=$('restoreTarget');target.innerHTML='<option value="">Choisir un appareil connu</option>'+overview.devices.map(d=>'<option value="'+esc(d.ip)+'">'+esc(d.name)+' · vu le '+esc(date(d.lastSeenAt))+'</option>').join('');if(overview.devices[0]&&!$('tvIp').value)$('tvIp').value=overview.devices[0].ip}function renderBackups(){const box=$('backups');box.innerHTML=overview.backups.length?overview.backups.map(b=>'<div class="item"><div><b>'+esc('Jojo Home')+'</b><div class="meta">'+esc(date(b.createdAt))+' · '+esc(fmt(b.size))+' · '+esc(b.reason)+'</div></div><button class="danger" data-restore="'+esc(b.id)+'" data-app="'+esc(b.app)+'">Restaurer…</button></div>').join(''):'Aucune sauvegarde. Crée d’abord un snapshot manuel.';box.querySelectorAll('[data-restore]').forEach(button=>button.onclick=()=>restore(button.dataset.app,button.dataset.restore))}async function restore(app,id){const target=$('restoreIp').value.trim()||$('restoreTarget').value;if(!target)return $('maintenanceStatus').textContent='Choisis d’abord la TV à restaurer en priorité.';if(!confirm('Confirmer la restauration manuelle de '+('Jojo Home')+' sur le NAS ?'))return;$('maintenanceStatus').textContent='Restauration NAS…';try{const data=await secure('/api/companion/restores',{method:'POST',body:JSON.stringify({app,backupId:id,targetTv:target,confirm:true})});$('maintenanceStatus').textContent=data.message;await load()}catch(error){$('maintenanceStatus').textContent=error.message}}async function loadProviderHistory(){try{const data=await plain('/api/history');$('history').innerHTML=data.history.length?data.history.map(x=>'<div class="item"><div><b>'+esc(x.provider.name)+'</b><div class="meta">'+esc(x.status)+' — '+esc(x.message||'')+'</div></div></div>').join(''):'Aucun envoi pour le moment.'}catch(error){$('history').textContent=error.message}}
  document.querySelectorAll('.navButton').forEach(b=>b.onclick=()=>{document.querySelectorAll('.navButton').forEach(x=>x.classList.toggle('active',x===b));document.querySelectorAll('.view').forEach(x=>x.classList.toggle('active',x.id===b.dataset.view));$('title').textContent=titles[b.dataset.view][0];$('subtitle').textContent=titles[b.dataset.view][1]});document.querySelectorAll('.type').forEach(b=>b.onclick=()=>{const type=b.dataset.type;$('type').value=type;document.querySelectorAll('.type').forEach(x=>x.classList.toggle('active',x===b));document.querySelectorAll('[data-section]').forEach(x=>x.classList.toggle('hidden',x.dataset.section!==type))});$('refresh').onclick=()=>load().catch(e=>alert(e.message));$('testTv').onclick=async()=>{try{$('connection').textContent=(await plain('/api/test?tv='+encodeURIComponent($('tvIp').value.trim())+'&port='+encodeURIComponent($('tvPort').value.trim()))).message}catch(e){$('connection').textContent=e.message}};$('sendProvider').onclick=async()=>{const body={tv:$('tvIp').value.trim(),port:$('tvPort').value.trim(),type:$('type').value,name:$('providerName').value.trim(),m3uUrl:$('m3uUrl').value.trim(),serverUrl:$('serverUrl').value.trim(),username:$('username').value.trim(),password:$('providerPassword').value,portalUrl:$('portalUrl').value.trim(),macAddress:$('macAddress').value.trim()};try{Object.assign(body,window.iptvPlaylistDnsPayload?.()||{});$('iptvStatus').textContent=(await plain('/api/providers',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(body)})).message;loadProviderHistory()}catch(e){$('iptvStatus').textContent=e.message}};document.querySelectorAll('.backup').forEach(b=>b.onclick=async()=>{try{$('maintenanceStatus').textContent='Sauvegarde en cours…';const data=await secure('/api/companion/backups',{method:'POST',body:JSON.stringify({app:b.dataset.app})});$('maintenanceStatus').textContent='Sauvegarde créée : '+fmt(data.backup.size);await load()}catch(e){$('maintenanceStatus').textContent=e.message}});$('loginButton').onclick=async()=>{try{$('loginStatus').textContent='Connexion…';const response=await fetch('/api/sync/auth/login',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({email:$('email').value.trim(),password:$('password').value})});const data=await response.json();if(!response.ok)throw new Error(data.error||'Connexion impossible');token=data.token;sessionStorage.setItem(tokenKey,token);await load();$('login').classList.add('hidden');$('app').classList.remove('hidden')}catch(e){$('loginStatus').textContent=e.message}};if(token){load().then(()=>{$('authLoading').classList.add('hidden');$('app').classList.remove('hidden')}).catch(()=>{sessionStorage.removeItem(tokenKey);token='';$('authLoading').classList.add('hidden');$('login').classList.remove('hidden')})}else{$('authLoading').classList.add('hidden');$('login').classList.remove('hidden')}
  </script></body></html>`;
}

async function route(request, response) {
  const url = new URL(request.url, `http://${request.headers.host || "localhost"}`);
  try {
    if (request.method === "GET" && (url.pathname === "/" || url.pathname === "/jojotv")) return text(response, 200, page(), "text/html; charset=utf-8");
    if (request.method === "GET" && url.pathname === "/health") return json(response, 200, { status: "ok" });
    if (request.method === "GET" && url.pathname.startsWith("/api/ui-avatars/")) {
      const name = url.pathname.slice("/api/ui-avatars/".length);
      if (name !== "catalog.json" && !/^[a-z0-9_-]+\.png$/.test(name)) return json(response, 404, { error: "Avatar introuvable." });
      let data = await fsp.readFile(path.join(UI_AVATAR_DIR, name)).catch(() => null);
      if (!data) return json(response, 404, { error: "Avatar introuvable." });
      if (name === "catalog.json") {
        const catalog = JSON.parse(data.toString("utf8"));
        if (catalog.deletedAvatars) {
          const { deletedAvatars, ...visible } = catalog;
          data = Buffer.from(JSON.stringify(visible));
        }
      }
      response.writeHead(200, { "content-type": name === "catalog.json" ? "application/json; charset=utf-8" : "image/png",
        "content-length": data.length, "cache-control": name === "catalog.json" ? "no-cache" : "public, max-age=86400" });
      response.end(data);
      return;
    }
    if (request.method === "GET" && [
      "/api/ui-icons/home_category_live_tv_v1.png",
      "/api/ui-icons/home_category_film_reel_v1.png",
      "/api/ui-icons/iptv_app_v1.png"
    ].includes(url.pathname)) {
      const file = path.join(DATA_DIR, "ui-icons", path.basename(url.pathname));
      const data = await fsp.readFile(file).catch(() => null);
      if (!data) return json(response, 404, { error: "Icone introuvable." });
      response.writeHead(200, { "content-type": "image/png", "content-length": data.length, "cache-control": "public, max-age=86400" });
      response.end(data);
      return;
    }
    const pairingPageMatch = url.pathname.match(/^\/jojotv\/pair\/([a-f0-9-]+)$/i);
    if (request.method === "GET" && pairingPageMatch) {
      pairingForApproval(pairingPageMatch[1], url.searchParams.get("code"));
      return text(response, 200, pairingPage(pairingPageMatch[1], url.searchParams.get("code")), "text/html; charset=utf-8");
    }
    if (request.method === "POST" && url.pathname === "/api/sync/pairings") {
      if (pruneExpiredPairings()) await persistStore();
      const body = await readJson(request);
      const id = crypto.randomUUID();
      const deviceSecret = pairingSecret();
      const approvalCode = pairingSecret();
      const expiresAt = Date.now() + PAIRING_TTL_MS;
      const deviceName = String(body.deviceName || "TV Jojo Home").trim().slice(0, 120) || "TV Jojo Home";
      store.sync.pairings.push({
        id,
        deviceSecretHash: pairingSecretDigest(deviceSecret),
        approvalCodeHash: pairingSecretDigest(approvalCode),
        deviceName,
        expiresAt,
        approvedAccountId: null,
        approvedAt: null
      });
      await persistStore();
      const origin = `http://${request.headers.host || `localhost:${PORT}`}`;
      return json(response, 201, {
        id,
        deviceSecret,
        qrUrl: `${origin}/jojotv/pair/${id}?code=${encodeURIComponent(approvalCode)}`,
        expiresAt,
        pollIntervalSeconds: PAIRING_POLL_INTERVAL_SECONDS
      });
    }
    const pairingStatusMatch = url.pathname.match(/^\/api\/sync\/pairings\/([a-f0-9-]+)$/i);
    if (request.method === "GET" && pairingStatusMatch) {
      const pairing = pairingForDevice(pairingStatusMatch[1], url.searchParams.get("deviceSecret"));
      if (!pairing.approvedAccountId) {
        return json(response, 200, { status: "pending", expiresAt: pairing.expiresAt, pollIntervalSeconds: PAIRING_POLL_INTERVAL_SECONDS });
      }
      const account = store.sync.accounts.find((item) => item.id === pairing.approvedAccountId);
      if (!account) throw syncError("Compte NAS d'appairage introuvable.", 404);
      return json(response, 200, { status: "approved", token: syncTokenFor(account), account: syncAccountPublic(account), expiresAt: pairing.expiresAt });
    }
    const pairingApprovalMatch = url.pathname.match(/^\/api\/sync\/pairings\/([a-f0-9-]+)\/approve$/i);
    if (request.method === "POST" && pairingApprovalMatch) {
      const body = await readJson(request);
      const pairing = pairingForApproval(pairingApprovalMatch[1], body.approvalCode);
      const email = normaliseSyncEmail(body.email);
      const account = store.sync.accounts.find((item) => item.email === email);
      if (!account || !passwordMatches(body.password, account.passwordHash)) throw syncError("E-mail ou mot de passe NAS incorrect.", 401);
      pairing.approvedAccountId = account.id;
      pairing.approvedAt = Date.now();
      await persistStore();
      return json(response, 200, { approved: true, deviceName: pairing.deviceName });
    }
    if (request.method === "POST" && url.pathname === "/api/sync/auth/register") {
      const body = await readJson(request);
      const email = normaliseSyncEmail(body.email);
      const password = String(body.password || "");
      if (password.length < 8) throw syncError("Le mot de passe NAS doit contenir au moins 8 caractères.");
      const existing = store.sync.accounts.find((account) => account.email === email);
      if (existing && !existing.passwordHash) {
        existing.passwordHash = passwordHash(password);
        existing.passwordCreatedAt = new Date().toISOString();
        await persistStore();
        return json(response, 200, { token: syncTokenFor(existing), account: syncAccountPublic(existing), claimed: true });
      }
      if (existing) throw syncError("Cette adresse e-mail possède déjà un compte NAS.", 409);
      const account = { id: crypto.randomUUID(), email, passwordHash: passwordHash(password), createdAt: new Date().toISOString() };
      store.sync.accounts.push(account);
      await persistStore();
      return json(response, 201, { token: syncTokenFor(account), account: syncAccountPublic(account) });
    }
    if (request.method === "POST" && url.pathname === "/api/sync/auth/login") {
      const body = await readJson(request);
      const email = normaliseSyncEmail(body.email);
      const account = store.sync.accounts.find((item) => item.email === email);
      if (!account || !passwordMatches(body.password, account.passwordHash)) throw syncError("E-mail ou mot de passe NAS incorrect.", 401);
      return json(response, 200, { token: syncTokenFor(account), account: syncAccountPublic(account) });
    }
    if (request.method === "POST" && url.pathname === "/api/sync/auth/bootstrap-nuvio") {
      const body = await readJson(request);
      const legacyNuvioUserId = String(body.nuvioUserId || "").trim();
      if (legacyNuvioUserId.length < 8 || legacyNuvioUserId.length > 200) throw syncError("Identifiant de compte Jojo Home invalide.");
      const email = normaliseSyncEmail(body.email);
      let account = store.sync.accounts.find((item) => item.legacyNuvioUserId === legacyNuvioUserId);
      if (!account) {
        const existingEmail = store.sync.accounts.find((item) => item.email === email);
        if (existingEmail) throw syncError("Cette adresse e-mail NAS est déjà associée à un autre compte.", 409);
        account = {
          id: crypto.randomUUID(),
          email,
          legacyNuvioUserId,
          passwordHash: null,
          createdAt: new Date().toISOString()
        };
        store.sync.accounts.push(account);
        await persistStore();
      }
      return json(response, 200, { token: syncTokenFor(account), account: syncAccountPublic(account), migratedFromNuvio: true });
    }
    if (request.method === "GET" && url.pathname === "/api/sync/account") {
      return json(response, 200, { account: syncAccountPublic(syncAccountFromRequest(request)) });
    }
    if (request.method === "GET" && url.pathname === "/api/companion/overview") {
      return json(response, 200, await companionOverview(syncAccountFromRequest(request)));
    }
    if (request.method === "GET" && url.pathname.startsWith("/api/companion/player-assets/")) {
      const name = url.pathname.slice("/api/companion/player-assets/".length);
      if (!["hls-1.7.3.min.js", "mpegts-1.8.2.min.js"].includes(name) && !/^videojs-10\.0\.1\/(?:[a-zA-Z0-9_-]+\/)*[a-zA-Z0-9_-]+\.(?:js|css)$/.test(name)) return json(response, 404, { error: "Bibliothèque introuvable." });
      const data = await fsp.readFile(path.join(__dirname, "web-player", name)).catch(() => null);
      if (!data) return json(response, 404, { error: "La bibliothèque du lecteur est absente du NAS." });
      response.writeHead(200, { "content-type": name.endsWith(".css") ? "text/css; charset=utf-8" : "application/javascript; charset=utf-8", "content-length": data.length, "cache-control": "public, max-age=31536000, immutable", "x-content-type-options": "nosniff" });
      return response.end(data);
    }
    const playbackStreamMatch = url.pathname.match(/^\/api\/companion\/playback\/([a-f0-9]{64})(?:\/(media|[a-f0-9]{64}))?$/);
    if (playbackStreamMatch) {
      // A random capability grants access only to this resolved content, never to NAS account data.
      const session = companionPlaybackSessions.get(playbackStreamMatch[1]);
      if (!session || session.expiresAt < Date.now()) { closeCompanionPlayback(playbackStreamMatch[1]); return json(response, 410, { error: "Cette session de lecture a expiré. Relance le contenu." }); }
      if (request.method === "DELETE" && !playbackStreamMatch[2]) { closeCompanionPlayback(session.id); return json(response, 200, { closed: true }); }
      if (request.method === "GET" && playbackStreamMatch[2] === "media") return json(response, 200, await companionPlaybackMedia(session));
      if (!["GET", "HEAD"].includes(request.method) || !playbackStreamMatch[2]) return json(response, 405, { error: "Méthode non autorisée." });
      return await proxyCompanionPlayback(request, response, session, playbackStreamMatch[2], url);
    }
    const favoriteEpisodeArtworkMatch = url.pathname.match(/^\/api\/companion\/accounts\/([^/]+)\/favorites\/([^/]+)\/episodes\/([^/]+)\/artwork$/);
    if (favoriteEpisodeArtworkMatch) {
      const owner = syncAccountFromRequest(request);
      if (request.method !== "GET") return json(response, 405, { error: "Méthode non autorisée." });
      const { account, items } = await companionHomeAccountFavorites(owner.id, decodeURIComponent(favoriteEpisodeArtworkMatch[1]));
      const favorite = items.find(({ item }) => item.id === decodeURIComponent(favoriteEpisodeArtworkMatch[2]));
      if (!favorite || favorite.item.type !== "SERIES") return json(response, 404, { error: "Série introuvable pour ce compte." });
      const episodes = await companionCachedPlaybackEpisodes(owner.id, account.id, favorite);
      const episode = episodes.find(item => item.id === decodeURIComponent(favoriteEpisodeArtworkMatch[3]));
      if (!episode || !companionFavoriteArtwork(episode, favorite.source)) return json(response, 404, { error: "Vignette indisponible." });
      const image = await companionFavoriteImage({ item: episode, source: favorite.source });
      response.writeHead(200, { "content-type": image.type, "content-length": image.data.length, "cache-control": "private, max-age=300", "x-content-type-options": "nosniff" });
      return response.end(image.data);
    }
    const favoritePlaybackMatch = url.pathname.match(/^\/api\/companion\/accounts\/([^/]+)\/favorites\/([^/]+)\/(episodes|playback)$/);
    if (favoritePlaybackMatch) {
      const owner = syncAccountFromRequest(request);
      const { account, items, legacy } = await companionHomeAccountFavorites(owner.id, decodeURIComponent(favoritePlaybackMatch[1]));
      let favorite = items.find(({ item }) => item.id === decodeURIComponent(favoritePlaybackMatch[2]));
      if (!favorite) return json(response, 404, { error: "Favori introuvable pour ce compte." });
      if (request.method === "GET" && favoritePlaybackMatch[3] === "episodes") {
        if (favorite.item.type !== "SERIES") return json(response, 400, { error: "Ce favori n’est pas une série." });
        const episodes = await companionCachedPlaybackEpisodes(owner.id, account.id, favorite);
        const profiles = companionProfilesFor(owner.id).filter(profile => companionProfileNameKey(profile.name) === companionProfileNameKey(account.name));
        const profileId = profiles.length === 1 ? profiles[0].profileId : null;
        const contentId = companionPlaybackContentId(favorite.item.id, account.id, legacy);
        const progress = syncWatchProgressSnapshot(owner.id, profileId)?.entries || [];
        const watchedEpisodes = new Set(progress.filter(item => item.contentId === contentId && item.contentType === "series" && item.season != null && item.episode != null &&
          (Number.isFinite(item.progressPercent) ? item.progressPercent / 100 : item.duration > 0 ? item.position / item.duration : 0) >= 0.93)
          .map(item => item.season + ":" + item.episode));
        const artworkBase = "/api/companion/accounts/" + encodeURIComponent(account.id) + "/favorites/" + encodeURIComponent(favorite.item.id) + "/episodes/";
        return json(response, 200, { watchedKnown: profileId !== null, episodes: episodes.map(item => ({
          id: item.id, type: "EPISODE", title: item.title, season: item.season, episode: item.episode,
          artwork: companionFavoriteArtwork(item, favorite.source) ? artworkBase + encodeURIComponent(item.id) + "/artwork" : "",
          watched: watchedEpisodes.has(item.season + ":" + item.episode)
        })) });
      }
      if (request.method !== "POST" || favoritePlaybackMatch[3] !== "playback") return json(response, 405, { error: "Méthode non autorisée." });
      const body = await readJson(request);
      if (favorite.item.type === "SERIES") {
        const episode = (await companionCachedPlaybackEpisodes(owner.id, account.id, favorite)).find(item => item.id === body.episode);
        if (!episode) return json(response, 404, { error: "Choisis un épisode disponible pour cette série." });
        favorite = { item: episode, source: favorite.source };
      } else if (body.episode) return json(response, 400, { error: "Ce favori n’est pas une série." });
      const playback = await createCompanionPlayback(owner.id, favorite, { port: request.socket.localPort, relay: body.relay === true, compatible: body.compatible === true, position: body.position, audio: body.audio, subtitle: body.subtitle, isCancelled: () => request.aborted || response.destroyed });
      if (request.aborted || response.destroyed) { closeCompanionPlayback(playback.session); return; }
      return json(response, 200, playback);
    }
    const companionFavoriteDeleteMatch = url.pathname.match(/^\/api\/companion\/accounts\/([^/]+)\/favorites\/([^/]+)\/delete$/);
    if (companionFavoriteDeleteMatch && request.method === "POST") {
      const owner = syncAccountFromRequest(request);
      const result = await removeCompanionHomeFavorite(owner.id, decodeURIComponent(companionFavoriteDeleteMatch[1]), decodeURIComponent(companionFavoriteDeleteMatch[2]), await readJson(request));
      return json(response, 200, result);
    }
    const companionFavoritesMatch = url.pathname.match(/^\/api\/companion\/accounts\/([^/]+)\/favorites(?:\/([^/]+)\/artwork)?$/);
    if (companionFavoritesMatch) {
      const owner = syncAccountFromRequest(request);
      if (request.method !== "GET") return json(response, 405, { error: "Méthode non autorisée." });
      const { account, items } = await companionHomeAccountFavorites(owner.id, decodeURIComponent(companionFavoritesMatch[1]));
      if (companionFavoritesMatch[2]) {
        const favorite = items.find(({ item }) => item.id === decodeURIComponent(companionFavoritesMatch[2]));
        if (!favorite) return json(response, 404, { error: "Favori introuvable." });
        const image = await companionFavoriteImage(favorite);
        response.writeHead(200, { "content-type": image.type, "content-length": image.data.length, "cache-control": "private, max-age=300", "x-content-type-options": "nosniff" });
        return response.end(image.data);
      }
      const favorites = items.map(item => companionFavoriteDetails(item, account.id)).sort((left, right) => left.title.localeCompare(right.title, "fr", { numeric: true, sensitivity: "base" }) || left.id.localeCompare(right.id));
      return json(response, 200, { account, favorites });
    }
    const companionAccountMatch = url.pathname.match(/^\/api\/companion\/accounts\/([^/]+)$/);
    if (companionAccountMatch) {
      const account = syncAccountFromRequest(request);
      if (!["PATCH", "DELETE"].includes(request.method)) return json(response, 405, { error: "Méthode non autorisée." });
      const result = await changeCompanionHomeAccount(account.id, decodeURIComponent(companionAccountMatch[1]), request.method, await readJson(request));
      return json(response, 200, result);
    }
    if (url.pathname === "/api/companion/avatars") {
      syncAccountFromRequest(request);
      if (request.method === "GET") return json(response, 200, await companionAvatarCatalog());
      if (request.method === "POST") return json(response, 201, { avatar: await addCompanionAvatar(await readJson(request)) });
      return json(response, 405, { error: "Méthode non autorisée." });
    }
    const companionAvatarMatch = url.pathname.match(/^\/api\/companion\/avatars\/([^/]+)(\/restore)?$/);
    if (companionAvatarMatch) {
      syncAccountFromRequest(request);
      const method = companionAvatarMatch[2] && request.method === "POST" ? "RESTORE" : request.method;
      if (companionAvatarMatch[2] ? method !== "RESTORE" : !["PATCH", "DELETE"].includes(method)) {
        return json(response, 405, { error: "Méthode non autorisée." });
      }
      const id = decodeURIComponent(companionAvatarMatch[1]);
      const body = method === "PATCH" ? await readJson(request) : {};
      return json(response, 200, { avatar: await changeCompanionAvatar(id, method, body) });
    }
    if (request.method === "POST" && url.pathname === "/api/companion/backups") {
      const account = syncAccountFromRequest(request);
      const body = await readJson(request);
      const app = syncBackupApp(body.app);
      const index = store.sync.backupPolicies.findIndex((item) => item.accountId === account.id && item.app === app);
      const policy = { accountId: account.id, app, enabled: true, hour: 3, minute: 0, updatedAt: Date.now() };
      if (index >= 0) store.sync.backupPolicies[index] = policy;
      else store.sync.backupPolicies.push(policy);
      await persistStore();
      const backup = await createAccountBackup(account.id, app, "manual");
      return json(response, 201, { backup: { id: backup.id, app, createdAt: backup.createdAt, size: backup.size, reason: backup.reason } });
    }
    if (request.method === "POST" && url.pathname === "/api/companion/restores") {
      const account = syncAccountFromRequest(request);
      const { request: restoreRequest, backup } = await requestManualRestore(account, await readJson(request));
      return json(response, 200, {
        restored: true,
        backup: { id: backup.id, app: restoreRequest.app, createdAt: backup.createdAt, size: backup.size },
        targetTv: restoreRequest.targetTv,
        message: "Restauration manuelle enregistrée. La TV choisie rechargera l'état NAS à son prochain lancement."
      });
    }
    const syncBackupMatch = url.pathname.match(/^\/api\/sync\/backups\/(jojotv|jojohome)$/);
    if (syncBackupMatch) {
      const account = syncAccountFromRequest(request);
      const app = syncBackupApp(syncBackupMatch[1]);
      if (request.method === "GET") {
        const backups = store.sync.backups
          .filter((item) => item.accountId === account.id && item.app === app)
          .sort((left, right) => right.createdAt - left.createdAt)
          .map(({ id, createdAt, size, reason }) => ({ id, createdAt, size, reason }));
        return json(response, 200, { app, retention: BACKUP_RETENTION, backups });
      }
      if (request.method === "POST") {
        const index = store.sync.backupPolicies.findIndex((item) => item.accountId === account.id && item.app === app);
        const policy = { accountId: account.id, app, enabled: true, hour: 3, minute: 0, updatedAt: Date.now() };
        if (index >= 0) store.sync.backupPolicies[index] = policy;
        else store.sync.backupPolicies.push(policy);
        await persistStore();
        const backup = await createAccountBackup(account.id, app, "manual");
        return json(response, 201, { backup: { id: backup.id, createdAt: backup.createdAt, size: backup.size, reason: backup.reason } });
      }
    }
    const syncBackupRestoreMatch = url.pathname.match(/^\/api\/sync\/backups\/(jojotv|jojohome)\/([^/]+)\/restore$/);
    if (syncBackupRestoreMatch && request.method === "POST") {
      const account = syncAccountFromRequest(request);
      const app = syncBackupApp(syncBackupRestoreMatch[1]);
      const backup = await restoreAccountBackup(account.id, app, decodeURIComponent(syncBackupRestoreMatch[2]));
      return json(response, 200, { restored: true, backup: { id: backup.id, createdAt: backup.createdAt, size: backup.size } });
    }
    const syncProfilePinMatch = url.pathname.match(/^\/api\/sync\/profiles\/(\d+)\/pin$/);
    if (syncProfilePinMatch) {
      const account = syncAccountFromRequest(request);
      const profileId = syncProfileId(syncProfilePinMatch[1]);
      const current = syncProfilePinSnapshot(account.id, profileId);
      if (request.method === "GET") return json(response, 200, { enabled: Boolean(current) });
      if (request.method === "PUT") {
        const body = await readJson(request);
        const pin = String(body.pin || "");
        const currentPin = String(body.currentPin || "");
        if (!/^\d{4,12}$/.test(pin)) throw syncError("Le code PIN doit comporter de 4 a 12 chiffres.");
        if (current && !passwordMatches(currentPin, current.hash)) throw syncError("Le code PIN actuel est requis.", 403);
        const replacement = { accountId: account.id, profileId, hash: passwordHash(pin), updatedAt: Date.now() };
        const index = store.sync.profilePins.findIndex((item) => item.accountId === account.id && item.profileId === profileId);
        if (index >= 0) store.sync.profilePins[index] = replacement;
        else store.sync.profilePins.push(replacement);
        await persistStore();
        return json(response, 200, { enabled: true });
      }
      if (request.method === "POST") {
        const body = await readJson(request);
        const unlocked = Boolean(current && passwordMatches(String(body.pin || ""), current.hash));
        return json(response, 200, { unlocked, retryAfterSeconds: 0 });
      }
      if (request.method === "DELETE") {
        const body = await readJson(request);
        if (current && !passwordMatches(String(body.currentPin || ""), current.hash)) throw syncError("Le code PIN actuel est requis.", 403);
        store.sync.profilePins = store.sync.profilePins.filter((item) => item.accountId !== account.id || item.profileId !== profileId);
        await persistStore();
        return json(response, 200, { enabled: false });
      }
    }
    if (request.method === "GET" && url.pathname === "/api/sync/profile-pins") {
      const account = syncAccountFromRequest(request);
      return json(response, 200, { pins: store.sync.profilePins.filter((item) => item.accountId === account.id).map((item) => ({ profileId: item.profileId, enabled: true })) });
    }
    if (request.method === "GET" && url.pathname === "/api/sync/changes") {
      const account = syncAccountFromRequest(request);
      const since = Math.max(0, Number(url.searchParams.get("since")) || 0);
      const requestedProfileId = Number(url.searchParams.get("profileId")) || 0;
      const visibleEvents = store.sync.events
        .filter((item) => item.accountId === account.id && item.revision > since)
        .filter((item) => requestedProfileId <= 0 || item.profileId === requestedProfileId);
      const changes = visibleEvents
        .slice(0, 1_000)
        .map(({ accountId, ...item }) => item);
      const retainedEvents = store.sync.events
        .filter((item) => item.accountId === account.id)
        .filter((item) => requestedProfileId <= 0 || item.profileId === requestedProfileId);
      const deliveredRevision = visibleEvents.length > changes.length && changes.length
        ? changes[changes.length - 1].revision
        : store.sync.nextRevision;
      return json(response, 200, {
        revision: deliveredRevision,
        oldestAvailableRevision: retainedEvents.length ? retainedEvents[0].revision : store.sync.nextRevision,
        changes
      });
    }
    if (request.method === "GET" && url.pathname === "/api/sync/status") {
      const account = syncAccountFromRequest(request);
      const accountEvents = store.sync.events.filter((item) => item.accountId === account.id);
      const lastByDevice = {};
      accountEvents.forEach((item) => {
        if (!item.deviceId) return;
        const previous = lastByDevice[item.deviceId];
        if (!previous || item.revision > previous.revision) lastByDevice[item.deviceId] = {
          revision: item.revision, updatedAt: item.updatedAt, domain: item.domain, profileId: item.profileId
        };
      });
      return json(response, 200, {
        revision: store.sync.nextRevision,
        events: accountEvents.length,
        devices: lastByDevice,
        tombstones: {
          favorites: store.sync.favorites.filter((item) => item.accountId === account.id).reduce((sum, item) => sum + (item.tombstones || []).length, 0),
          watchProgress: store.sync.watchProgress.filter((item) => item.accountId === account.id).reduce((sum, item) => sum + (item.tombstones || []).length, 0)
        }
      });
    }
    if (request.method === "GET" && url.pathname === "/api/sync/profiles") {
      const account = syncAccountFromRequest(request);
      // Profiles exist independently of watch history. Build the registry from
      // every synchronized domain so a new/empty profile stays discoverable.
      const profileDomains = [store.sync.watchProgress, store.sync.favorites, store.sync.settings,
        store.sync.watchedItems, store.sync.profileBlobs, store.sync.profilePins];
      const profileDirectoryIds = store.sync.profileBlobs
        .filter((item) => item.accountId === account.id && item.key === "profiles")
        .flatMap((item) => {
          try {
            const values = JSON.parse(String(item.value?.json || "[]"));
            return Array.isArray(values) ? values.map((profile) => Number(profile?.id) || 0) : [];
          } catch (_) { return []; }
        });
      const profileIds = [...new Set(profileDomains.flat()
        .filter((item) => item.accountId === account.id)
        .map((item) => item.profileId)
        .concat(profileDirectoryIds)
        .filter((value) => Number.isInteger(value) && value > 0))]
        .sort((left, right) => left - right);
      return json(response, 200, { profiles: profileIds.map((profileId) => ({ profileId })), revision: store.sync.nextRevision });
    }
    const syncProfileMatch = url.pathname.match(/^\/api\/sync\/profiles\/(\d+)$/);
    if (syncProfileMatch && request.method === "DELETE") {
      const account = syncAccountFromRequest(request);
      const profileId = syncProfileId(syncProfileMatch[1]);
      store.sync.favorites = store.sync.favorites.filter((item) => item.accountId !== account.id || item.profileId !== profileId);
      store.sync.settings = store.sync.settings.filter((item) => item.accountId !== account.id || item.profileId !== profileId);
      store.sync.watchProgress = store.sync.watchProgress.filter((item) => item.accountId !== account.id || item.profileId !== profileId);
      store.sync.watchedItems = store.sync.watchedItems.filter((item) => item.accountId !== account.id || item.profileId !== profileId);
      store.sync.profileBlobs = store.sync.profileBlobs.filter((item) => item.accountId !== account.id || item.profileId !== profileId);
      const assets = store.sync.profileAssets.filter((item) => item.accountId === account.id && item.profileId === profileId);
      await Promise.all(assets.map((item) => fsp.unlink(syncAssetPath(account.id, profileId, item.key)).catch(() => {})));
      store.sync.profileAssets = store.sync.profileAssets.filter((item) => item.accountId !== account.id || item.profileId !== profileId);
      store.sync.profileSecrets = store.sync.profileSecrets.filter((item) => item.accountId !== account.id || item.profileId !== profileId);
      store.sync.profilePins = store.sync.profilePins.filter((item) => item.accountId !== account.id || item.profileId !== profileId);
      const revision = appendSyncEvent(account.id, profileId, "profiles", "delete", [String(profileId)], Date.now(), requestDeviceId(request));
      await persistStore();
      return json(response, 200, { deleted: true, revision });
    }
    const syncFavoritesMatch = url.pathname.match(/^\/api\/sync\/profiles\/(\d+)\/iptv-favorites$/);
    if (syncFavoritesMatch) {
      const account = syncAccountFromRequest(request);
      const profileId = syncProfileId(syncFavoritesMatch[1]);
      if (request.method === "GET") {
        const snapshot = syncFavoriteSnapshot(account.id, profileId);
        return json(response, 200, {
          favorites: snapshot?.favorites || [],
          tombstones: snapshot?.tombstones || [],
          updatedAt: snapshot?.updatedAt || 0
        });
      }
      if (request.method === "PUT") {
        const body = await readJson(request);
        const favorites = syncFavoritesPayload(body.favorites);
        const existingSnapshot = syncFavoriteSnapshot(account.id, profileId);
        const existingFavorites = new Map((existingSnapshot?.favorites || []).map((item) => [syncFavoriteKey(item), item]));
        const tombstones = new Map((existingSnapshot?.tombstones || []).map((item) => [String(item.key || ""), Number(item.deletedAt) || 0]));
        favorites.forEach((item) => {
          const key = syncFavoriteKey(item);
          const deletedAt = tombstones.get(key) || 0;
          const existing = existingFavorites.get(key);
          // A client may only add/update an item that is newer than its deletion.
          // PUT is deliberately a merge, never a replacement: a TV whose catalogue
          // is still loading must not erase favourites known by another TV.
          if (item.updatedAt > deletedAt && (!existing || item.updatedAt >= existing.updatedAt)) {
            existingFavorites.set(key, item);
            tombstones.delete(key);
          }
        });
        const mergedFavorites = [...existingFavorites.values()];
        const mergedTombstones = [...tombstones.entries()].map(([key, deletedAt]) => ({ key, deletedAt }));
        if (existingSnapshot &&
            syncValueEqual(existingSnapshot.favorites || [], mergedFavorites) &&
            syncValueEqual(existingSnapshot.tombstones || [], mergedTombstones)) {
          const revision = store.sync.nextRevision;
          const updatedAt = existingSnapshot.updatedAt;
          await writeQueue;
          if (persistedSyncRevision < revision) await persistStore();
          return json(response, 200, { count: mergedFavorites.length, updatedAt, revision, unchanged: true });
        }
        const snapshot = {
          accountId: account.id,
          profileId,
          favorites: mergedFavorites,
          tombstones: mergedTombstones,
          updatedAt: Date.now()
        };
        const index = store.sync.favorites.findIndex((item) => item.accountId === account.id && item.profileId === profileId);
        if (index >= 0) store.sync.favorites[index] = snapshot;
        else store.sync.favorites.push(snapshot);
        const revision = appendSyncEvent(account.id, profileId, "iptv_favorites", "upsert", favorites.map(syncFavoriteKey), snapshot.updatedAt, requestDeviceId(request));
        await persistStore();
        return json(response, 200, { count: snapshot.favorites.length, updatedAt: snapshot.updatedAt, revision });
      }
      if (request.method === "DELETE") {
        const body = await readJson(request);
        const keys = syncFavoriteKeys(body.keys);
        const existingSnapshot = syncFavoriteSnapshot(account.id, profileId) || {
          accountId: account.id, profileId, favorites: [], tombstones: [], updatedAt: 0
        };
        const deletedAt = Date.now();
        const entries = new Map((existingSnapshot.favorites || []).map((item) => [syncFavoriteKey(item), item]));
        const tombstones = new Map((existingSnapshot.tombstones || []).map((item) => [String(item.key || ""), Number(item.deletedAt) || 0]));
        keys.forEach((key) => {
          entries.delete(key);
          tombstones.set(key, deletedAt);
        });
        const snapshot = {
          ...existingSnapshot,
          favorites: [...entries.values()],
          tombstones: [...tombstones.entries()].map(([key, timestamp]) => ({ key, deletedAt: timestamp })),
          updatedAt: deletedAt
        };
        const index = store.sync.favorites.findIndex((item) => item.accountId === account.id && item.profileId === profileId);
        if (index >= 0) store.sync.favorites[index] = snapshot;
        else store.sync.favorites.push(snapshot);
        const revision = appendSyncEvent(account.id, profileId, "iptv_favorites", "delete", keys, snapshot.updatedAt, requestDeviceId(request));
        await persistStore();
        return json(response, 200, { deleted: keys.length, updatedAt: snapshot.updatedAt, revision });
      }
    }
    const syncSettingsMatch = url.pathname.match(/^\/api\/sync\/profiles\/(\d+)\/settings$/);
    if (syncSettingsMatch) {
      const account = syncAccountFromRequest(request);
      const profileId = syncProfileId(syncSettingsMatch[1]);
      if (request.method === "GET") {
        const snapshot = syncSettingsSnapshot(account.id, profileId);
        return json(response, 200, { settings: snapshot?.settings || null, updatedAt: snapshot?.updatedAt || 0 });
      }
      if (request.method === "PUT") {
        const body = await readJson(request);
        const settings = syncSettingsPayload(body.settings);
        const existingSnapshot = syncSettingsSnapshot(account.id, profileId);
        if (existingSnapshot && syncValueEqual(existingSnapshot.settings, settings)) {
          const revision = store.sync.nextRevision;
          const updatedAt = existingSnapshot.updatedAt;
          await writeQueue;
          if (persistedSyncRevision < revision) await persistStore();
          return json(response, 200, { updatedAt, revision, unchanged: true });
        }
        const snapshot = { accountId: account.id, profileId, settings, updatedAt: Date.now() };
        const index = store.sync.settings.findIndex((item) => item.accountId === account.id && item.profileId === profileId);
        if (index >= 0) store.sync.settings[index] = snapshot;
        else store.sync.settings.push(snapshot);
        const revision = appendSyncEvent(account.id, profileId, "profile_settings", "upsert", [String(profileId)], snapshot.updatedAt, requestDeviceId(request));
        await persistStore();
        return json(response, 200, { updatedAt: snapshot.updatedAt, revision });
      }
    }
    const syncWatchProgressMatch = url.pathname.match(/^\/api\/sync\/profiles\/(\d+)\/watch-progress(\/conditional)?$/);
    if (syncWatchProgressMatch) {
      const account = syncAccountFromRequest(request);
      const profileId = syncProfileId(syncWatchProgressMatch[1]);
      const conditional = !!syncWatchProgressMatch[2];
      if (conditional && request.method !== "DELETE") throw syncError("Méthode non autorisée.", 405);
      let snapshot = syncWatchProgressSnapshot(account.id, profileId);
      if (!snapshot) {
        snapshot = { accountId: account.id, profileId, entries: [], tombstones: [], updatedAt: 0 };
        store.sync.watchProgress.push(snapshot);
      }
      if (!Array.isArray(snapshot.entries)) snapshot.entries = [];
      if (!Array.isArray(snapshot.tombstones)) snapshot.tombstones = [];
      if (conditional) {
        const body = await readJson(request);
        const deletions = syncWatchProgressDeletions(body.deletions);
        const entries = new Map(snapshot.entries.map((item) => [item.key, item]));
        const tombstones = new Map(snapshot.tombstones.map((item) => [item.key, item.deletedAt || 0]));
        const changedKeys = [];
        let preserved = 0;
        // No await between comparison and mutation: a newer checkpoint from
        // another client cannot slip between this guard and the deletion.
        for (const { key, deletedAt } of deletions) {
          const current = entries.get(key);
          if (current && current.lastWatched > deletedAt) { preserved++; continue; }
          const previous = tombstones.get(key) || 0;
          if (current || deletedAt > previous) {
            entries.delete(key);
            tombstones.set(key, Math.max(previous, deletedAt));
            changedKeys.push(key);
          }
        }
        if (!changedKeys.length) {
          const revision = store.sync.nextRevision;
          const updatedAt = snapshot.updatedAt || 0;
          await writeQueue;
          if (persistedSyncRevision < revision) await persistStore({ retry: true });
          return json(response, 200, { deleted: 0, preserved, updatedAt, revision, unchanged: true });
        }
        snapshot.entries = [...entries.values()];
        snapshot.tombstones = [...tombstones].map(([key, deletedAt]) => ({ key, deletedAt }));
        const updatedAt = Date.now();
        snapshot.updatedAt = updatedAt;
        const revision = appendSyncEvent(account.id, profileId, "watch_progress", "delete", changedKeys, updatedAt, requestDeviceId(request), true);
        await persistStore({ snapshot, keys: changedKeys });
        return json(response, 200, { deleted: changedKeys.length, preserved, updatedAt, revision });
      }
      if (request.method === "GET") {
        return json(response, 200, { entries: snapshot.entries, tombstones: snapshot.tombstones, updatedAt: snapshot.updatedAt || 0 });
      }
      if (request.method === "PUT") {
        const body = await readJson(request);
        const incoming = syncWatchProgressEntries(body.entries);
        const entries = new Map(snapshot.entries.map((item) => [item.key, item]));
        const tombstones = new Map(snapshot.tombstones.map((item) => [item.key, item.deletedAt || 0]));
        incoming.forEach((item) => {
          const deletedAt = tombstones.get(item.key) || 0;
          const existing = entries.get(item.key);
          if (item.lastWatched > deletedAt && (!existing || item.lastWatched >= existing.lastWatched)) {
            entries.set(item.key, item);
            tombstones.delete(item.key);
          }
        });
        const mergedEntries = [...entries.values()];
        const mergedTombstones = [...tombstones.entries()].map(([key, deletedAt]) => ({ key, deletedAt }));
        if (syncValueEqual(snapshot.entries, mergedEntries) && syncValueEqual(snapshot.tombstones, mergedTombstones)) {
          const revision = store.sync.nextRevision;
          const updatedAt = snapshot.updatedAt || 0;
          await writeQueue;
          if (persistedSyncRevision < revision) await persistStore({ retry: true });
          return json(response, 200, { count: mergedEntries.length, updatedAt, revision, unchanged: true });
        }
        snapshot.entries = mergedEntries;
        snapshot.tombstones = mergedTombstones;
        snapshot.updatedAt = Date.now();
        const keys = incoming.map(item => item.key);
        const revision = appendSyncEvent(account.id, profileId, "watch_progress", "upsert", keys, snapshot.updatedAt, requestDeviceId(request), true);
        await persistStore({ snapshot, keys });
        return json(response, 200, { count: snapshot.entries.length, updatedAt: snapshot.updatedAt, revision });
      }
      if (request.method === "DELETE") {
        const body = await readJson(request);
        const keys = syncWatchProgressKeys(body.keys);
        const deletedAt = Date.now();
        const entries = new Map(snapshot.entries.map((item) => [item.key, item]));
        const tombstones = new Map(snapshot.tombstones.map((item) => [item.key, item.deletedAt || 0]));
        keys.forEach((key) => {
          entries.delete(key);
          tombstones.set(key, deletedAt);
        });
        snapshot.entries = [...entries.values()];
        snapshot.tombstones = [...tombstones.entries()].map(([key, timestamp]) => ({ key, deletedAt: timestamp }));
        snapshot.updatedAt = deletedAt;
        const revision = appendSyncEvent(account.id, profileId, "watch_progress", "delete", keys, snapshot.updatedAt, requestDeviceId(request), true);
        await persistStore({ snapshot, keys });
        return json(response, 200, { deleted: keys.length, updatedAt: snapshot.updatedAt, revision });
      }
    }
    const syncWatchedItemsMatch = url.pathname.match(/^\/api\/sync\/profiles\/(\d+)\/watched-items$/);
    if (syncWatchedItemsMatch) {
      const account = syncAccountFromRequest(request);
      const profileId = syncProfileId(syncWatchedItemsMatch[1]);
      let snapshot = syncWatchedItemSnapshot(account.id, profileId);
      if (!snapshot) {
        snapshot = { accountId: account.id, profileId, entries: [], tombstones: [], updatedAt: 0 };
        store.sync.watchedItems.push(snapshot);
      }
      if (!Array.isArray(snapshot.entries)) snapshot.entries = [];
      if (!Array.isArray(snapshot.tombstones)) snapshot.tombstones = [];
      if (request.method === "GET") {
        return json(response, 200, { entries: snapshot.entries, tombstones: snapshot.tombstones, updatedAt: snapshot.updatedAt || 0 });
      }
      if (request.method === "PUT") {
        const body = await readJson(request);
        const incoming = syncWatchedItemEntries(body.entries);
        const entries = new Map(snapshot.entries.map((item) => [item.key, item]));
        const tombstones = new Map(snapshot.tombstones.map((item) => [item.key, item.deletedAt || 0]));
        incoming.forEach((item) => {
          const deletedAt = tombstones.get(item.key) || 0;
          const existing = entries.get(item.key);
          if (item.watchedAt > deletedAt && (!existing || item.watchedAt >= existing.watchedAt)) {
            entries.set(item.key, item);
            tombstones.delete(item.key);
          }
        });
        snapshot.entries = [...entries.values()];
        snapshot.tombstones = [...tombstones.entries()].map(([key, deletedAt]) => ({ key, deletedAt }));
        snapshot.updatedAt = Date.now();
        const revision = appendSyncEvent(account.id, profileId, "watched_items", "upsert", incoming.map((item) => item.key), snapshot.updatedAt, requestDeviceId(request));
        await persistStore();
        return json(response, 200, { count: snapshot.entries.length, updatedAt: snapshot.updatedAt, revision });
      }
      if (request.method === "DELETE") {
        const body = await readJson(request);
        const keys = syncWatchedItemKeys(body.keys);
        const deletedAt = Date.now();
        const entries = new Map(snapshot.entries.map((item) => [item.key, item]));
        const tombstones = new Map(snapshot.tombstones.map((item) => [item.key, item.deletedAt || 0]));
        keys.forEach((key) => { entries.delete(key); tombstones.set(key, deletedAt); });
        snapshot.entries = [...entries.values()];
        snapshot.tombstones = [...tombstones.entries()].map(([key, timestamp]) => ({ key, deletedAt: timestamp }));
        snapshot.updatedAt = deletedAt;
        const revision = appendSyncEvent(account.id, profileId, "watched_items", "delete", keys.map((key) => JSON.stringify(key)), snapshot.updatedAt, requestDeviceId(request));
        await persistStore();
        return json(response, 200, { deleted: keys.length, updatedAt: snapshot.updatedAt, revision });
      }
    }
    const syncAssetMatch = url.pathname.match(/^\/api\/sync\/profiles\/(\d+)\/assets\/([a-z0-9_-]{1,64})$/i);
    if (syncAssetMatch) {
      const account = syncAccountFromRequest(request);
      const profileId = syncProfileId(syncAssetMatch[1]);
      const key = syncProfileBlobKey(syncAssetMatch[2]);
      const assetPath = syncAssetPath(account.id, profileId, key);
      if (request.method === "GET") {
        const snapshot = syncProfileAssetSnapshot(account.id, profileId, key);
        if (!snapshot) return json(response, 404, { error: "Fichier synchronise introuvable." });
        const data = await fsp.readFile(assetPath).catch(() => null);
        if (!data) return json(response, 404, { error: "Fichier synchronise absent." });
        response.writeHead(200, { "content-type": snapshot.contentType || "application/octet-stream", "content-length": data.length, "cache-control": "no-store" });
        return response.end(data);
      }
      if (request.method === "PUT") {
        const data = await readBuffer(request, MAX_SYNC_ASSET_BYTES);
        if (!data.length) throw syncError("Fichier synchronise vide.");
        const contentType = String(request.headers["content-type"] || "application/octet-stream").slice(0, 120);
        await fsp.writeFile(assetPath, data, { mode: 0o600 });
        const snapshot = { accountId: account.id, profileId, key, contentType, size: data.length, updatedAt: Date.now() };
        const index = store.sync.profileAssets.findIndex((item) => item.accountId === account.id && item.profileId === profileId && item.key === key);
        if (index >= 0) store.sync.profileAssets[index] = snapshot;
        else store.sync.profileAssets.push(snapshot);
        await persistStore();
        return json(response, 200, { size: snapshot.size, updatedAt: snapshot.updatedAt });
      }
    }
    const syncSecretMatch = url.pathname.match(/^\/api\/sync\/profiles\/(\d+)\/secrets\/([a-z0-9_-]{1,64})$/i);
    if (syncSecretMatch) {
      const account = syncAccountFromRequest(request);
      const profileId = syncProfileId(syncSecretMatch[1]);
      const key = syncProfileBlobKey(syncSecretMatch[2]);
      if (key === "free-tv-session") throw syncError("Cette session est utilisable uniquement par le Companion NAS.", 403);
      if (request.method === "GET") {
        const snapshot = syncProfileSecretSnapshot(account.id, profileId, key);
        return json(response, 200, { value: snapshot ? decryptSyncSecret(snapshot.value) : null, updatedAt: snapshot ? snapshot.updatedAt : 0 });
      }
      if (request.method === "PUT") {
        const body = await readJson(request);
        if (!Object.prototype.hasOwnProperty.call(body, "value")) throw syncError("Valeur securisee absente.");
        const previous = key === "jojo-home-iptv" ? syncProfileSecretSnapshot(account.id, profileId, key) : null;
        const value = key === "jojo-home-iptv" ? mergeHomeIptvSnapshot(previous ? decryptSyncSecret(previous.value) : null, body.value) : body.value;
        const snapshot = { accountId: account.id, profileId, key, value: encryptSyncSecret(value), updatedAt: Date.now() };
        const index = store.sync.profileSecrets.findIndex((item) => item.accountId === account.id && item.profileId === profileId && item.key === key);
        if (index >= 0) store.sync.profileSecrets[index] = snapshot;
        else store.sync.profileSecrets.push(snapshot);
        await persistStore();
        return json(response, 200, key === "jojo-home-iptv" ? { value, updatedAt: snapshot.updatedAt } : { updatedAt: snapshot.updatedAt });
      }
    }
    const freeboxPhotoMatch = url.pathname.match(/^\/api\/sync\/profiles\/(\d+)\/freebox-os\/photo-preview$/);
    if (freeboxPhotoMatch && request.method === "GET") {
      const account = syncAccountFromRequest(request);
      const profileId = syncProfileId(freeboxPhotoMatch[1]);
      const requestedMax = Number(url.searchParams.get("max") || 320);
      const maxDimension = Number.isInteger(requestedMax) ? Math.max(256, Math.min(requestedMax, 1920)) : 320;
      const preview = await freeboxPhotoPreview(account.id, profileId, url.searchParams.get("path"), maxDimension);
      response.writeHead(200, {
        "content-type": "image/jpeg", "content-length": preview.length, "cache-control": "private, no-store"
      });
      response.end(preview);
      return;
    }
    const freeboxThumbnailMatch = url.pathname.match(/^\/api\/sync\/profiles\/(\d+)\/freebox-os\/thumbnails$/);
    if (freeboxThumbnailMatch && request.method === "POST") {
      const account = syncAccountFromRequest(request);
      const profileId = syncProfileId(freeboxThumbnailMatch[1]);
      const snapshot = freeboxOsSecretSnapshot(account.id, profileId);
      if (!snapshot) throw syncError("Le Companion NAS n'est pas autorisé dans Freebox OS.", 409);
      const body = await readJson(request);
      const frames = await generateFreeboxThumbnailFrames(
        account.id,
        profileId,
        decryptSyncSecret(snapshot.value),
        body
      );
      return json(response, 200, { frames });
    }
    const freeboxOsMatch = url.pathname.match(/^\/api\/sync\/profiles\/(\d+)\/freebox-os\/(authorize|status|media|records)$/);
    if (freeboxOsMatch) {
      const account = syncAccountFromRequest(request);
      const profileId = syncProfileId(freeboxOsMatch[1]);
      const action = freeboxOsMatch[2];
      if (action === "authorize" && request.method === "POST") {
        const authorization = await freeboxOsJson("/login/authorize/", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ app_id: FREEBOX_OS_APP_ID, app_name: "JojoTV Companion", app_version: "1.0.0", device_name: "NAS Jojo Home" })
        });
        const appToken = String(authorization?.result?.app_token || "");
        const trackId = Number(authorization?.result?.track_id);
        if (!appToken || !Number.isInteger(trackId)) throw syncError("Autorisation Freebox OS invalide.", 502);
        const snapshot = { accountId: account.id, profileId, key: "freebox-os-app-token", value: encryptSyncSecret({ appToken, trackId }), updatedAt: Date.now() };
        const index = store.sync.profileSecrets.findIndex((item) => item.accountId === account.id && item.profileId === profileId && item.key === snapshot.key);
        if (index >= 0) store.sync.profileSecrets[index] = snapshot;
        else store.sync.profileSecrets.push(snapshot);
        await persistStore();
        return json(response, 202, { state: "pending" });
      }
      const snapshot = freeboxOsSecretSnapshot(account.id, profileId);
      if (!snapshot) return json(response, 200, { state: "unpaired", media: [] });
      const secret = decryptSyncSecret(snapshot.value);
      if (action === "status" && request.method === "GET") {
        const tracked = await freeboxOsJson(`/login/authorize/${encodeURIComponent(secret.trackId)}`);
        return json(response, 200, { state: String(tracked?.result?.status || "unknown") });
      }
      if (action === "media" && request.method === "GET") {
        const media = await freeboxOsPvrMedia(secret);
        return json(response, 200, { media: media?.result || [] });
      }
      if (action === "records" && request.method === "POST") {
        const body = await readJson(request);
        const channelName = String(body.channelName || "").trim().slice(0, 160);
        const name = String(body.name || channelName).trim().slice(0, 160);
        const start = Number(body.start), end = Number(body.end);
        const source = ["iptv", "dvb"].includes(body.source) ? body.source : "";
        const quality = { "Automatique": "auto", "4K": "hd", "HD+": "hd", "HD": "hd", "Normale": "sd", "Bas débit": "ld", "3D": "3d" }[String(body.quality || "")] || "auto";
        const media = String(body.media || "").trim().slice(0, 160);
        const marginBefore = Number(body.marginBeforeMinutes || 0), marginAfter = Number(body.marginAfterMinutes || 0);
        const now = Math.floor(Date.now() / 1000);
        if (!channelName || !name || !source || !media || !Number.isInteger(start) || !Number.isInteger(end) || start < now - 30 || end <= start || !Number.isInteger(marginBefore) || !Number.isInteger(marginAfter) || marginBefore < 0 || marginAfter < 0 || marginBefore > 60 || marginAfter > 60) throw syncError("Programmation locale Freebox invalide.");
        const record = await freeboxOsPvrRecord(secret, { channelName, name, start, end, source, quality, media, marginBefore, marginAfter });
        return json(response, 201, { record: record?.result || record });
      }
    }
    const freeTvSessionMatch = url.pathname.match(/^\/api\/sync\/profiles\/(\d+)\/free-tv\/session$/);
    if (freeTvSessionMatch) {
      const account = syncAccountFromRequest(request);
      const profileId = syncProfileId(freeTvSessionMatch[1]);
      if (request.method === "PUT") {
        const body = await readJson(request);
        const session = freeTvSessionPayload(body);
        const snapshot = { accountId: account.id, profileId, key: "free-tv-session", value: encryptSyncSecret(session), updatedAt: Date.now() };
        const index = store.sync.profileSecrets.findIndex((item) => item.accountId === account.id && item.profileId === profileId && item.key === snapshot.key);
        if (index >= 0) store.sync.profileSecrets[index] = snapshot;
        else store.sync.profileSecrets.push(snapshot);
        await persistStore();
        return json(response, 200, { connected: true, updatedAt: snapshot.updatedAt });
      }
      if (request.method === "DELETE") {
        store.sync.profileSecrets = store.sync.profileSecrets.filter((item) => !(item.accountId === account.id && item.profileId === profileId && item.key === "free-tv-session"));
        await persistStore();
        return json(response, 200, { connected: false });
      }
    }
    const freeTvAuthorizeMatch = url.pathname.match(/^\/api\/sync\/profiles\/(\d+)\/free-tv\/authorize\/freebox$/);
    if (freeTvAuthorizeMatch && request.method === "POST") {
      const account = syncAccountFromRequest(request);
      const profileId = syncProfileId(freeTvAuthorizeMatch[1]);
      const body = await readJson(request);
      const login = String(body.login || "").trim();
      const password = String(body.password || "");
      if (!login || login.length > 160 || !password || password.length > 512) {
        throw syncError("Identifiants Freebox invalides.");
      }
      const session = await freeTvFreeboxSession(login, password);
      const snapshot = { accountId: account.id, profileId, key: "free-tv-session", value: encryptSyncSecret(session), updatedAt: Date.now() };
      const index = store.sync.profileSecrets.findIndex((item) => item.accountId === account.id && item.profileId === profileId && item.key === snapshot.key);
      if (index >= 0) store.sync.profileSecrets[index] = snapshot;
      else store.sync.profileSecrets.push(snapshot);
      await persistStore();
      return json(response, 200, { connected: true, profile: session.profile, updatedAt: snapshot.updatedAt });
    }
    const freeTvStatusMatch = url.pathname.match(/^\/api\/sync\/profiles\/(\d+)\/free-tv\/status$/);
    if (freeTvStatusMatch && request.method === "GET") {
      const account = syncAccountFromRequest(request);
      const profileId = syncProfileId(freeTvStatusMatch[1]);
      const snapshot = freeTvSessionSnapshot(account.id, profileId);
      if (!snapshot) return json(response, 200, { connected: false });
      const session = decryptSyncSecret(snapshot.value);
      const quota = await freeTvJson(session, "/api/v1/user/npvr/quota");
      return json(response, 200, { connected: true, profile: session.profile, quota, updatedAt: snapshot.updatedAt });
    }
    const freeTvRecordsMatch = url.pathname.match(/^\/api\/sync\/profiles\/(\d+)\/free-tv\/records(?:\/([a-zA-Z0-9_-]{1,160}))?$/);
    if (freeTvRecordsMatch) {
      const account = syncAccountFromRequest(request);
      const profileId = syncProfileId(freeTvRecordsMatch[1]);
      const snapshot = freeTvSessionSnapshot(account.id, profileId);
      if (!snapshot) throw syncError("Session Free TV absente.", 409);
      const session = decryptSyncSecret(snapshot.value);
      if (request.method === "GET" && !freeTvRecordsMatch[2]) {
        const rightsToken = await freeTvRightsToken(session);
        const payload = await freeTvJson(session, "/api/v4/user/npvr/records", { rightsToken });
        return json(response, 200, { records: payload?.result?.records || payload?.result || payload?.records || [] });
      }
      if (request.method === "POST" && !freeTvRecordsMatch[2]) {
        const body = await readJson(request);
        const diffusionId = Number(body.diffusionId);
        const marginBeforeMinutes = Number(body.marginBeforeMinutes ?? body.marginBefore ?? 0);
        const marginAfterMinutes = Number(body.marginAfterMinutes ?? body.marginAfter ?? 0);
        if (!Number.isInteger(diffusionId) || diffusionId <= 0) throw syncError("Programme Free TV invalide.");
        if (!Number.isInteger(marginBeforeMinutes) || !Number.isInteger(marginAfterMinutes) || marginBeforeMinutes < 0 || marginAfterMinutes < 0 || marginBeforeMinutes > 60 || marginAfterMinutes > 60) throw syncError("Marges Free TV invalides.");
        const rightsToken = await freeTvRightsToken(session);
        const record = await freeTvJson(session, "/api/v4/user/npvr/records", {
          method: "POST",
          rightsToken,
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ diffusion_id: diffusionId, margin_before: marginBeforeMinutes * 60, margin_after: marginAfterMinutes * 60 })
        });
        return json(response, 201, { record });
      }
      if (request.method === "DELETE" && freeTvRecordsMatch[2]) {
        await freeTvJson(session, `/api/v4/user/npvr/records/${encodeURIComponent(freeTvRecordsMatch[2])}`, { method: "DELETE" });
        return json(response, 200, { deleted: true });
      }
    }
    const freeTvGuideMatch = url.pathname.match(/^\/api\/sync\/profiles\/(\d+)\/free-tv\/guide(?:\/(channels|programs))?$/);
    if (freeTvGuideMatch && request.method === "GET") {
      const account = syncAccountFromRequest(request);
      const profileId = syncProfileId(freeTvGuideMatch[1]);
      const snapshot = freeTvSessionSnapshot(account.id, profileId);
      if (!snapshot) throw syncError("Session Free TV absente.", 409);
      const session = decryptSyncSecret(snapshot.value);
      if (freeTvGuideMatch[2] === "channels") {
        return json(response, 200, { channels: await freeTvJson(session, "/api/v6/service_plan") });
      }
      if (freeTvGuideMatch[2] === "programs") {
        const channelId = Number(url.searchParams.get("channel"));
        const at = Number(url.searchParams.get("at") || Math.floor(Date.now() / 1000));
        if (!Number.isInteger(channelId) || channelId <= 0 || !Number.isInteger(at) || at <= 0) throw syncError("Guide Free TV invalide.");
        const dayStart = Math.floor(at / 3600) * 3600;
        const slots = await Promise.all(Array.from({ length: 24 }, (_, hour) => freeTvJson(session, `/api/v1/epg/all/${dayStart + hour * 3600}`)));
        const programs = new Map();
        slots.flatMap((epg) => epg?.result?.entries?.[String(channelId)] || []).forEach((entry) => {
          const live = entry?.live || entry;
          if (live?.id) programs.set(String(live.id), entry);
        });
        return json(response, 200, { programs: [...programs.values()].sort((left, right) => Number((left.live || left).start) - Number((right.live || right).start)) });
      }
    }
    const freeTvManualMatch = url.pathname.match(/^\/api\/sync\/profiles\/(\d+)\/free-tv\/manual-records$/);
    if (freeTvManualMatch && request.method === "POST") {
      const account = syncAccountFromRequest(request);
      const profileId = syncProfileId(freeTvManualMatch[1]);
      const snapshot = freeTvSessionSnapshot(account.id, profileId);
      if (!snapshot) throw syncError("Session Free TV absente.", 409);
      const body = await readJson(request);
      const channelId = Number(body.channelId);
      const start = Number(body.start);
      const end = Number(body.end);
      const marginBeforeMinutes = Number(body.marginBeforeMinutes || 0);
      const marginAfterMinutes = Number(body.marginAfterMinutes || 0);
      const recurrenceDays = [...new Set((Array.isArray(body.recurrenceDays) ? body.recurrenceDays : []).map(Number).filter((day) => Number.isInteger(day) && day >= 0 && day <= 6))];
      const title = String(body.title || "").trim().slice(0, 160);
      const now = Math.floor(Date.now() / 1000);
      if (!Number.isInteger(channelId) || channelId <= 0 || !Number.isInteger(start) || !Number.isInteger(end) || start < now - 30 || end <= start || end - start > 14_400) throw syncError("Créneau d’enregistrement manuel invalide.");
      if (!Number.isInteger(marginBeforeMinutes) || !Number.isInteger(marginAfterMinutes) || marginBeforeMinutes < 0 || marginAfterMinutes < 0 || marginBeforeMinutes > 60 || marginAfterMinutes > 60) throw syncError("Marges Free TV invalides.");
      if (recurrenceDays.length) {
        const durationSeconds = end - start;
        recurrenceDays.forEach((day) => {
          let nextAt = start;
          while (new Date(nextAt * 1000).getDay() !== day || nextAt < now + 60) nextAt += 86400;
          store.sync.freeTvRecurringRecords.push({ id: crypto.randomUUID(), accountId: account.id, profileId, channelId, title, nextAt, durationSeconds, marginBefore: marginBeforeMinutes, marginAfter: marginAfterMinutes, enabled: true, createdAt: Date.now() });
        });
        await persistStore();
        await runFreeTvRecurringRecords();
        return json(response, 201, { recurring: true, days: recurrenceDays.length });
      }
      const session = decryptSyncSecret(snapshot.value);
      const rightsToken = await freeTvRightsToken(session);
      const record = await freeTvJson(session, "/api/v4/user/npvr/records", { method: "POST", rightsToken, headers: { "content-type": "application/json" }, body: JSON.stringify({ channel_id: channelId, start, end, margin_before: marginBeforeMinutes * 60, margin_after: marginAfterMinutes * 60 }) });
      return json(response, 201, { record });
    }
    const syncBlobMatch = url.pathname.match(/^\/api\/sync\/profiles\/(\d+)\/blobs\/([a-z0-9_-]{1,64})$/i);
    if (syncBlobMatch) {
      const account = syncAccountFromRequest(request);
      const profileId = syncProfileId(syncBlobMatch[1]);
      const key = syncProfileBlobKey(syncBlobMatch[2]);
      if (request.method === "GET") {
        const snapshot = syncProfileBlobSnapshot(account.id, profileId, key);
        return json(response, 200, { value: snapshot ? snapshot.value : null, updatedAt: snapshot ? snapshot.updatedAt : 0 });
      }
      if (request.method === "PUT") {
        const body = await readJson(request);
        if (!Object.prototype.hasOwnProperty.call(body, "value")) throw syncError("Valeur de synchronisation absente.");
        const existingSnapshot = syncProfileBlobSnapshot(account.id, profileId, key);
        if (existingSnapshot && syncValueEqual(existingSnapshot.value, body.value)) {
          const revision = store.sync.nextRevision;
          const updatedAt = existingSnapshot.updatedAt;
          await writeQueue;
          if (persistedSyncRevision < revision) await persistStore();
          return json(response, 200, { updatedAt, revision, unchanged: true });
        }
        const snapshot = { accountId: account.id, profileId, key, value: body.value, updatedAt: Date.now() };
        const index = store.sync.profileBlobs.findIndex((item) => item.accountId === account.id && item.profileId === profileId && item.key === key);
        if (index >= 0) store.sync.profileBlobs[index] = snapshot;
        else store.sync.profileBlobs.push(snapshot);
        const revision = appendSyncEvent(account.id, profileId, key === "profiles" ? "profiles" : "profile_blobs", "upsert", [key], snapshot.updatedAt, requestDeviceId(request));
        await persistStore();
        return json(response, 200, { updatedAt: snapshot.updatedAt, revision });
      }
    }
    if (request.method === "POST" && url.pathname === "/api/heic/preview") {
      const requestedMax = Number(url.searchParams.get("max") || 1280);
      const maxDimension = Number.isInteger(requestedMax) ? Math.max(256, Math.min(requestedMax, 1920)) : 1280;
      const preview = await heicPreview(request, maxDimension);
      response.writeHead(200, {
        "content-type": "image/jpeg",
        "content-length": preview.length,
        "cache-control": "no-store"
      });
      response.end(preview);
      return;
    }
    if (request.method === "POST" && url.pathname === "/api/m3u/upload") {
      syncAccountFromRequest(request);
      const data = await readBuffer(request, MAX_LOCAL_M3U_BYTES);
      if (!data.length) throw syncError("Fichier M3U vide.");
      const sourceName = String(url.searchParams.get("name") || "playlist.m3u").replace(/[^a-z0-9._ -]/gi, "_").slice(0, 120) || "playlist.m3u";
      const id = crypto.randomUUID();
      const fileName = `${id}.m3u`;
      const target = path.join(LOCAL_M3U_DIR, fileName);
      await fsp.writeFile(target, data, { mode: 0o600 });
      const origin = `http://${request.headers.host || `localhost:${PORT}`}`;
      return json(response, 201, { id, name: sourceName, size: data.length, url: `${origin}/api/local-m3u/${fileName}` });
    }
    const localM3uMatch = url.pathname.match(/^\/api\/local-m3u\/([a-f0-9-]+\.m3u)$/i);
    if (request.method === "GET" && localM3uMatch) {
      const file = path.join(LOCAL_M3U_DIR, localM3uMatch[1]);
      const data = await fsp.readFile(file).catch(() => null);
      if (!data) return json(response, 404, { error: "Fichier M3U introuvable." });
      response.writeHead(200, { "content-type": "audio/x-mpegurl; charset=utf-8", "content-length": data.length, "cache-control": "no-store" });
      response.end(data);
      return;
    }
    if (request.method === "GET" && url.pathname === "/api/history") {
      return json(response, 200, { history: store.history.filter((item) => item.target.port === 8093 && !item.action).slice(0, 20).map(publicRecord) });
    }
    if (request.method === "GET" && url.pathname === "/api/player/providers") {
      return json(response, 200, { providers: playerProviderRecords() });
    }
    if (request.method === "GET" && url.pathname === "/api/player/items") {
      const record = playerRecord(String(url.searchParams.get("provider") || ""));
      const section = String(url.searchParams.get("section") || "live");
      const items = await playerItems(record, section);
      return json(response, 200, { items });
    }
    if (request.method === "GET" && url.pathname === "/api/player/episodes") {
      const record = playerRecord(String(url.searchParams.get("provider") || ""));
      if (record.payload.type !== "xtream") return json(response, 400, { error: "Les séries sont disponibles avec un fournisseur Xtream." });
      return json(response, 200, { items: await xtreamEpisodes(record.payload, String(url.searchParams.get("series") || "")) });
    }
    if (request.method === "GET" && url.pathname === "/api/player/stream") {
      const record = playerRecord(String(url.searchParams.get("provider") || ""));
      const section = String(url.searchParams.get("section") || "live");
      const itemId = String(url.searchParams.get("item") || "");
      const item = (await playerItems(record, section)).find((candidate) => candidate.id === itemId);
      if (!item) return json(response, 404, { error: "Contenu lecteur introuvable." });
      const streamUrl = record.payload.type === "m3u" ? item.url : xtreamStreamUrl(record.payload, section, item);
      const upstream = await fetchRemote(streamUrl);
      response.writeHead(200, {
        "content-type": upstream.headers.get("content-type") || "video/mp2t",
        "cache-control": "no-store"
      });
      Readable.fromWeb(upstream.body).pipe(response);
      return;
    }
    if (request.method === "GET" && url.pathname === "/api/manual-restores/pending") {
      const tv = String(url.searchParams.get("tv") || "").trim();
      if (!isPrivateIpv4(tv)) return json(response, 400, { error: "Adresse TV locale invalide." });
      const request = store.sync.manualRestores.find((item) => item.targetTv === tv && item.status === "pending");
      return json(response, 200, { restore: request ? { id: request.id, app: request.app, backupId: request.backupId } : null });
    }
    const manualRestoreAckMatch = url.pathname.match(/^\/api\/manual-restores\/([\w-]+)\/ack$/);
    if (request.method === "POST" && manualRestoreAckMatch) {
      const restore = store.sync.manualRestores.find((item) => item.id === manualRestoreAckMatch[1]);
      if (!restore) return json(response, 404, { error: "Restauration manuelle introuvable." });
      restore.status = "acknowledged";
      restore.acknowledgedAt = Date.now();
      await persistStore();
      return json(response, 200, { acknowledged: true });
    }
    const resendMatch = url.pathname.match(/^\/api\/history\/([\w-]+)\/resend$/);
    if (request.method === "POST" && resendMatch) {
      const prior = store.history.find((item) => item.id === resendMatch[1] && item.target.port === 8093 && !item.action);
      if (!prior) return json(response, 404, { error: "Historique introuvable." });
      const record = { ...prior, id: crypto.randomUUID(), createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(), remoteId: null, status: "pending", message: "Renvoi en attente." };
      store.history.unshift(record); store.history = store.history.slice(0, 50); await sendProvider(record);
      return json(response, record.status === "error" ? 502 : 202, publicRecord(record));
    }
    // These POST routes already validate their target from the JSON body below.
    const target = request.method === "POST" && url.pathname === "/api/providers"
      ? null : targetFrom(Object.fromEntries(url.searchParams));
    if (["/api/tv/videos", "/api/tv/videos/artwork", "/api/tv/videos/status", "/api/tv/videos/delete"].includes(url.pathname)) {
      syncAccountFromRequest(request);
      if (url.pathname.endsWith("/delete") ? request.method !== "POST" : request.method !== "GET")
        return json(response, 405, { error: "Méthode non autorisée." });
      const query = new URLSearchParams();
      for (const key of ["folder", "id", "page"]) {
        const value = url.searchParams.get(key); if (value !== null) query.set(key, value);
      }
      let route = "/api/videos" + url.pathname.slice("/api/tv/videos".length) + "?" + query;
      if (url.pathname.endsWith("/status")) {
        const id = url.searchParams.get("id") || "";
        if (!/^[a-fA-F0-9-]{36}$/.test(id)) throw syncError("Suppression invalide.");
        route = "/api/status/" + encodeURIComponent(id);
      }
      const body = request.method === "POST" ? await readJson(request) : undefined;
      if (body && (body.confirm !== true || typeof body.id !== "string" || !/^[a-fA-F0-9-]{36}$/.test(body.id)))
        throw syncError("Confirme la suppression d’un élément valide.");
      const result = await requestTv(target, request.method, route, body, 60000);
      if (result.statusCode === 404) return json(response, 404, { error: "Cette TV nécessite une version de Jojo Home qui prend en charge le dossier Vidéos dans Companion." });
      if (result.statusCode < 200 || result.statusCode >= 300) return json(response, result.statusCode, { error: result.body.error || result.body.message || "Dossier Vidéos indisponible." });
      if (url.pathname.endsWith("/artwork") && result.body.url) {
        const image = await companionFavoriteImage({ item: { artwork: result.body.url }, source: {} });
        return json(response, 200, { image: "data:" + image.type + ";base64," + image.data.toString("base64") });
      }
      return json(response, result.statusCode, result.body);
    }
    if ((url.pathname === "/api/tv/dns" && ["GET", "POST"].includes(request.method)) ||
        (url.pathname === "/api/tv/dns/test" && request.method === "POST")) {
      syncAccountFromRequest(request);
      const body = request.method === "POST" ? await readJson(request) : undefined;
      const route = url.pathname.endsWith("/test") ? "/api/dns/test" : "/api/dns";
      const result = await requestTv(target, request.method, route, body);
      if (result.statusCode === 404) return json(response, 404, { error: "Cette TV nécessite une version de Jojo Home qui prend en charge le réglage DNS." });
      return json(response, result.statusCode, result.body);
    }
    if (request.method === "GET" && url.pathname === "/api/tv/providers") {
      syncAccountFromRequest(request);
      try {
        const result = await requestTv(target, "GET", "/api/providers");
        if (result.statusCode < 200 || result.statusCode >= 300) throw new Error(result.body.error || `Jojo Home répond HTTP ${result.statusCode}.`);
        return json(response, 200, { providers: Array.isArray(result.body) ? result.body : [] });
      } catch (_) {
        return json(response, 200, {
          providers: [],
          message: "TV indisponible : vérifie que Jojo Home est actif sur cette TV et que son adresse est correcte."
        });
      }
    }
    const iptvProviderMatch = url.pathname.match(/^\/api\/tv\/providers\/([^/]+)$/);
    if (iptvProviderMatch && ["GET", "POST", "DELETE"].includes(request.method)) {
      syncAccountFromRequest(request);
      const providerId = decodeURIComponent(iptvProviderMatch[1]);
      if (!providerId || providerId.length > 200 || providerId.includes("/")) throw syncError("Fournisseur IPTV invalide.");
      const body = request.method === "POST" ? await readJson(request) : undefined;
      const result = await requestTv(target, request.method, `/api/providers/${encodeURIComponent(providerId)}`, body);
      if (result.statusCode < 200 || result.statusCode >= 300) {
        return json(response, result.statusCode, { error: result.body.error || result.body.message || "Modification du fournisseur impossible." });
      }
      return json(response, result.statusCode, result.body);
    }
    if (url.pathname === "/api/tv/catalog" && ["GET", "POST"].includes(request.method)) {
      syncAccountFromRequest(request);
      if (request.method === "GET") {
        const query = new URLSearchParams();
        for (const key of ["sourceId", "type", "groupId", "page"]) {
          const value = url.searchParams.get(key);
          if (value !== null) query.set(key, value);
        }
        const result = await requestTv(target, "GET", `/api/catalog?${query.toString()}`);
        if (result.statusCode < 200 || result.statusCode >= 300) {
          return json(response, result.statusCode, { error: result.body.error || result.body.message || "Catalogue IPTV indisponible." });
        }
        return json(response, 200, result.body);
      }
      const body = await readJson(request);
      const result = await requestTv(target, "POST", "/api/catalog", body);
      if (result.statusCode < 200 || result.statusCode >= 300) {
        return json(response, result.statusCode, { error: result.body.error || result.body.message || "Modification du catalogue impossible." });
      }
      return json(response, result.statusCode, result.body);
    }
    if (request.method === "GET" && url.pathname === "/api/test") return json(response, 200, await testTv(target));
    if (request.method === "POST" && url.pathname === "/api/providers") {
      const body = await readJson(request); const record = { id: crypto.randomUUID(), createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(), target: targetFrom(body), payload: providerPayload(body), remoteId: null, status: "pending", message: "En attente." };
      store.history.unshift(record); store.history = store.history.slice(0, 50); await sendProvider(record); return json(response, record.status === "error" ? 502 : 202, publicRecord(record));
    }
    const statusMatch = url.pathname.match(/^\/api\/status\/([\w-]+)$/);
    if (request.method === "GET" && statusMatch) { const record = store.history.find((item) => item.id === statusMatch[1] && item.target.port === 8093 && !item.action); if (!record) return json(response, 404, { error: "Envoi introuvable." }); await refreshRecord(record); return json(response, 200, publicRecord(record)); }
    return json(response, 404, { error: "Route introuvable." });
  } catch (error) { return json(response, error.statusCode || 400, { error: error.message || "Requête invalide." }); }
}

initialiseStore().then(() => {
  const server = http.createServer(route).listen(PORT, "0.0.0.0", () => console.log(`JojoTV Companion écoute le port ${PORT}.`));
  const shutdown = () => server.close(() => {
    persistStore().then(() => { syncSqliteStore?.close(); process.exit(0); }, error => {
      console.error("Checkpoint d'arrêt :", error.name);
      process.exit(1);
    });
  });
  process.once("SIGTERM", shutdown);
  process.once("SIGINT", shutdown);
  setInterval(retryWaitingProviders, RETRY_INTERVAL_MS).unref();
  retryWaitingProviders();
  setInterval(runScheduledBackups, 60_000).unref();
  runScheduledBackups();
  setInterval(runFreeTvRecurringRecords, 300_000).unref();
  runFreeTvRecurringRecords();
  if (JOJO_HOME_GUARD_TARGET) {
    createStartupGuardian({ target: JOJO_HOME_GUARD_TARGET, intervalMs: JOJO_HOME_GUARD_INTERVAL_MS, launchDelayMs: JOJO_HOME_GUARD_LAUNCH_DELAY_MS }).start();
    console.log(`Gardien de démarrage Jojo Home activé pour ${JOJO_HOME_GUARD_TARGET}.`);
  }
}).catch((error) => { console.error(error); process.exit(1); });

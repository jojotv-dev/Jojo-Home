"use strict";

const path = require("node:path");

/**
 * Transactional mirror for synchronization state and its revision journal.
 * Legacy stores without node:sqlite keep the atomic JSON fallback. Once
 * incremental checkpoints are enabled, SQLite and its WAL are required;
 * complete JSON exports may lag behind the last acknowledged checkpoint.
 */
function createSyncSqliteStore(dataDirectory) {
  let DatabaseSync;
  try {
    ({ DatabaseSync } = require("node:sqlite"));
  } catch (_) {
    return null;
  }
  const database = new DatabaseSync(path.join(dataDirectory, "sync.db"));
  database.exec(`
    PRAGMA journal_mode=WAL;
    PRAGMA synchronous=FULL;
    PRAGMA foreign_keys=ON;
    CREATE TABLE IF NOT EXISTS sync_snapshot (
      id INTEGER PRIMARY KEY CHECK (id = 1),
      revision INTEGER NOT NULL,
      updated_at INTEGER NOT NULL,
      payload TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS sync_events (
      revision INTEGER PRIMARY KEY,
      account_id TEXT NOT NULL,
      profile_id INTEGER NOT NULL,
      domain TEXT NOT NULL,
      operation TEXT NOT NULL,
      keys_json TEXT NOT NULL,
      updated_at INTEGER NOT NULL,
      device_id TEXT NOT NULL DEFAULT ''
    );
    CREATE INDEX IF NOT EXISTS sync_events_account_revision
      ON sync_events(account_id, revision);
    CREATE INDEX IF NOT EXISTS sync_events_account_profile_revision
      ON sync_events(account_id, profile_id, revision);
    CREATE TABLE IF NOT EXISTS sync_progress_deltas (
      revision INTEGER PRIMARY KEY,
      payload TEXT NOT NULL
    );
  `);

  const writeSnapshot = database.prepare(`
    INSERT INTO sync_snapshot(id, revision, updated_at, payload)
    VALUES(1, ?, ?, ?)
    ON CONFLICT(id) DO UPDATE SET
      revision=excluded.revision,
      updated_at=excluded.updated_at,
      payload=excluded.payload
  `);
  const writeEvent = database.prepare(`
    INSERT INTO sync_events
      (revision, account_id, profile_id, domain, operation, keys_json, updated_at, device_id)
    VALUES(?, ?, ?, ?, ?, ?, ?, ?)
  `);
  const importEvent = database.prepare(`
    INSERT INTO sync_events
      (revision, account_id, profile_id, domain, operation, keys_json, updated_at, device_id)
    VALUES(?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(revision) DO NOTHING
  `);
  const readDelta = database.prepare("SELECT payload FROM sync_progress_deltas WHERE revision=?");
  const writeDelta = database.prepare("INSERT INTO sync_progress_deltas(revision,payload) VALUES(?,?)");
  const latestDelta = database.prepare("SELECT COALESCE(MAX(revision),0) AS revision FROM sync_progress_deltas");
  const readEvent = database.prepare("SELECT * FROM sync_events WHERE revision=?");

  return {
    latestRevision() {
      return Number(database.prepare("SELECT COALESCE(MAX(revision), 0) AS revision FROM sync_events").get().revision);
    },
    loadSnapshot() {
      const row = database.prepare("SELECT revision, updated_at, payload FROM sync_snapshot WHERE id=1").get();
      const deltas = database.prepare("SELECT revision,payload FROM sync_progress_deltas ORDER BY revision").all();
      if (!row) {
        if (deltas.length) throw new Error("Checkpoints sans snapshot de référence.");
        return null;
      }
      let value;
      try { value = JSON.parse(row.payload); }
      catch (error) {
        if (deltas.length) throw error;
        return null;
      }
      let revision = Number(row.revision);
      let updatedAt = Number(row.updated_at);
      for (const delta of deltas) {
        if (delta.revision <= row.revision) continue;
        const change = JSON.parse(delta.payload);
        if (!Array.isArray(value.watchProgress)) value.watchProgress = [];
        let snapshot = value.watchProgress.find(item => item.accountId === change.accountId && item.profileId === change.profileId);
        if (!snapshot) {
          snapshot = { accountId: change.accountId, profileId: change.profileId, entries: [], tombstones: [] };
          value.watchProgress.push(snapshot);
        }
        const entries = new Map((snapshot.entries || []).map(item => [item.key, item]));
        const tombstones = new Map((snapshot.tombstones || []).map(item => [item.key, item]));
        const changedEntries = new Map(change.entries.map(item => [item.key, item]));
        const changedTombstones = new Map(change.tombstones.map(item => [item.key, item]));
        for (const key of change.keys) {
          if (changedEntries.has(key)) entries.set(key, changedEntries.get(key));
          else entries.delete(key);
          if (changedTombstones.has(key)) tombstones.set(key, changedTombstones.get(key));
          else tombstones.delete(key);
        }
        snapshot.entries = [...entries.values()];
        snapshot.tombstones = [...tombstones.values()];
        snapshot.updatedAt = change.updatedAt;
        if (!Array.isArray(value.events)) value.events = [];
        if (!value.events.some(item => item.revision === change.revision)) value.events.push(change.event);
        revision = change.revision;
        updatedAt = change.updatedAt;
      }
      value.nextRevision = Math.max(Number(value.nextRevision) || 0, revision);
      return { revision, updatedAt, value };
    },
    saveSnapshot(sync) {
      // Keep large, infrequently changed values ahead of the changing history.
      // A stable UTF-8 byte length lets SQLite reuse unchanged overflow pages.
      // Trailing JSON whitespace also remains readable by older Companions.
      const { watchProgress, watchedItems, events, nextRevision, ...otherFields } = sync;
      let payload = JSON.stringify({ ...otherFields, watchProgress, watchedItems, events, nextRevision });
      const blockSize = 64 * 1024;
      const bytes = Buffer.byteLength(payload);
      if (bytes >= blockSize) payload += " ".repeat((blockSize - bytes % blockSize) % blockSize);
      database.exec("BEGIN IMMEDIATE");
      try {
        if (latestDelta.get().revision > sync.nextRevision) throw new Error("Snapshot antérieur aux checkpoints durables.");
        writeSnapshot.run(Number(sync.nextRevision) || 0, Date.now(), payload);
        for (const event of sync.events || []) {
          importEvent.run(event.revision, event.accountId, event.profileId, event.domain,
            event.operation, JSON.stringify(event.keys || []), event.updatedAt, event.deviceId || "");
        }
        database.prepare("DELETE FROM sync_progress_deltas WHERE revision<=?").run(sync.nextRevision);
        database.exec("COMMIT");
      } catch (error) {
        database.exec("ROLLBACK");
        throw error;
      }
    },
    saveProgress(changes) {
      database.exec("BEGIN IMMEDIATE");
      try {
        const base = database.prepare("SELECT revision FROM sync_snapshot WHERE id=1").get();
        if (!base) throw new Error("Snapshot initial requis avant les checkpoints.");
        for (const change of changes) {
          const event = change.event;
          if (!Number.isSafeInteger(change.revision) || change.revision <= 0 ||
              !event || event.revision !== change.revision || event.domain !== "watch_progress" ||
              event.accountId !== change.accountId || event.profileId !== change.profileId ||
              !Array.isArray(change.keys) || !Array.isArray(change.entries) || !Array.isArray(change.tombstones)) {
            throw new Error("Checkpoint incohérent.");
          }
          if (change.revision <= base.revision) continue;
          const payload = JSON.stringify(change);
          const existing = readDelta.get(change.revision);
          if (existing && existing.payload !== payload) throw new Error("Conflit de révision du checkpoint.");
          const stored = readEvent.get(event.revision);
          if (stored) {
            if (stored.account_id !== event.accountId || stored.profile_id !== event.profileId ||
                stored.domain !== event.domain || stored.operation !== event.operation ||
                stored.keys_json !== JSON.stringify(event.keys || []) || stored.updated_at !== event.updatedAt ||
                stored.device_id !== (event.deviceId || "")) throw new Error("Conflit de révision de l'événement.");
          } else {
            writeEvent.run(event.revision, event.accountId, event.profileId, event.domain,
              event.operation, JSON.stringify(event.keys || []), event.updatedAt, event.deviceId || "");
          }
          if (!existing) writeDelta.run(change.revision, payload);
        }
        database.exec("COMMIT");
      } catch (error) {
        database.exec("ROLLBACK");
        throw error;
      }
    },
    appendEvent(event) {
      writeEvent.run(event.revision, event.accountId, event.profileId, event.domain,
        event.operation, JSON.stringify(event.keys || []), event.updatedAt, event.deviceId || "");
    },
    importEvents(events) {
      database.exec("BEGIN IMMEDIATE");
      try {
        for (const event of events || []) {
          importEvent.run(event.revision, event.accountId, event.profileId, event.domain,
            event.operation, JSON.stringify(event.keys || []), event.updatedAt, event.deviceId || "");
        }
        database.exec("COMMIT");
      } catch (error) {
        database.exec("ROLLBACK");
        throw error;
      }
    },
    compact(cutoff, keepLatest) {
      database.prepare(`DELETE FROM sync_events
        WHERE updated_at < ? AND revision < (SELECT MAX(revision) - ? FROM sync_events)`)
        .run(cutoff, keepLatest);
      database.exec("PRAGMA wal_checkpoint(PASSIVE)");
    },
    close() { database.close(); }
  };
}

module.exports = { createSyncSqliteStore };

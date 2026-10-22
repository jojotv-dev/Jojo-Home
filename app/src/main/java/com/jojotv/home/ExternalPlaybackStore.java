package com.jojotv.home;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/** Per-content records use the existing Home NAS revision/merge protocol. */
public final class ExternalPlaybackStore {
    public static final String PREFIX = "external_playback_";
    public static final String CONTENT_PREFIX = "home-external:";
    static final String LAST_HOME_PLAYBACK_KEY = "nas_home_last_playback_key";
    static final String LAST_HOME_PLAYBACK_AT = "nas_home_last_playback_at";
    static final String LAST_HOME_PLAYBACK_ORDERED_AT = "nas_home_last_playback_ordered_at";
    private ExternalPlaybackStore() {}

    public interface ProviderJsonCallback { void onResult(JSONObject value); }

    public static String id(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static String favoriteId(String source, String category, String providerId) {
        if (source == null || source.isEmpty() || !categoryValid(category)
                || providerId == null || providerId.isEmpty()) return "";
        return id("tivimate-stalker\n" + source + "\n" + category + "\n" + providerId);
    }

    static String stalkerEpisodeId(String source, String seriesId, String episodeId) {
        if (source == null || source.isEmpty() || seriesId == null || seriesId.isEmpty()
                || episodeId == null || episodeId.isEmpty()) return "";
        return id("tivimate-stalker-episode\n" + source + "\n" + seriesId + "\n" + episodeId);
    }

    /** Provider episode numbers can restart at one for every season. */
    static String stalkerEpisodeId(String source, String seriesId, int season, String episodeId) {
        if (source == null || source.isEmpty() || seriesId == null || seriesId.isEmpty()
                || episodeId == null || episodeId.isEmpty()) return "";
        return id("tivimate-stalker-episode\n" + source + "\n" + seriesId + "\n"
                + Math.max(0, season) + "\n" + episodeId);
    }

    static String expectedStorageId(JSONObject item) {
        int season = item.optInt("season", 0);
        String episodeId = season > 0
                ? stalkerEpisodeId(item.optString("stalkerSource"),
                        item.optString("stalkerSeriesId"), season, item.optString("stalkerEpisodeId"))
                : stalkerEpisodeId(item.optString("stalkerSource"),
                        item.optString("stalkerSeriesId"), item.optString("stalkerEpisodeId"));
        if (!episodeId.isEmpty()) return episodeId;
        if ("tivimate-stalker".equals(item.optString("favoriteSource"))) {
            String favoriteId = favoriteId(item.optString("stalkerSource"),
                    item.optString("category"), item.optString("stalkerId"));
            if (!favoriteId.isEmpty()) return favoriteId;
        }
        return id(item.optString("url"));
    }

    public static boolean categoryValid(String value) {
        return "movies".equals(value) || "series".equals(value) || "live".equals(value);
    }

    private static boolean storageCategoryValid(String value) {
        return categoryValid(value) || "unknown".equals(value);
    }

    public static boolean supportedUrl(String value) {
        if (value == null || value.isEmpty() || value.length() > 16384) return false;
        try {
            java.net.URI uri = new java.net.URI(value);
            // HTTP and local-network sources are intentional player inputs.
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && !uri.getHost().isEmpty();
        } catch (java.net.URISyntaxException ignored) { return false; }
    }

    public static JSONObject get(Context context, String id) {
        if (id != null && id.startsWith("iptv:")) {
            return com.jojotv.home.iptv.IptvStore.homeFavorite(context, id);
        }
        try {
            // Display reads need the content and deletion mask, not the NAS
            // revision history (which can be large for legacy records).
            String stored = records(context).getString(id, null);
            JSONObject item;
            boolean deleted;
            if (stored != null) {
                JSONObject revision = new JSONObject(stored);
                item = new JSONObject(revision.optString("value", "{}"));
                deleted = revision.optBoolean("deleted");
            } else {
                item = new JSONObject(prefs(context).getString(PREFIX + id, "{}"));
                deleted = removals(context).getBoolean(id, false);
            }
            if (deleted) item.put("deleted", true).put("started", false).put("position", 0L);
            return item;
        }
        catch (Exception ignored) { return new JSONObject(); }
    }

    /** Resolves metadata from the provider without delaying playback startup. */
    public static void enrichProviderMetadata(Context context, String id, Runnable completion) {
        if (id != null && id.startsWith("iptv:")) {
            JSONObject item = get(context, id);
            if (!"movies".equals(item.optString("category"))
                    || HomeMediaRules.providerRuntimeMinutes(item.optString("providerRuntime", "")) > 0
                    && !HomeMediaRules.mediaMetadataText(item.optString("genre")).isEmpty()
                    && !HomeMediaRules.mediaMetadataText(item.optString("description")).isEmpty()
                    && !HomeMediaRules.mediaMetadataText(item.optString("cast")).isEmpty()
                    && !HomeMediaRules.mediaMetadataText(item.optString("director")).isEmpty()) {
                if (completion != null) completion.run();
                return;
            }
            new Thread(() -> {
                try { com.jojotv.home.iptv.IptvStore.enrichHomeMovieRuntime(context.getApplicationContext(), id); }
                catch (Exception ignored) { }
                finally {
                    if (completion != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(completion);
                }
            }, "JojoHomeIptvMovieMetadata").start();
            return;
        }
        TiviMateFavoriteImporter.enrichPlaybackMetadata(context, id, completion);
    }

    /** Known provider identities can load episodes without waiting for metadata enrichment. */
    public static void loadProviderSeriesDetails(Context context, String id, ProviderJsonCallback callback) {
        loadProviderSeriesDetails(context, id, callback, null);
    }

    static void loadProviderSeriesDetails(Context context, String id, ProviderJsonCallback callback,
                                          Runnable metadataReady) {
        if (context == null || id == null || id.isEmpty()) {
            if (callback != null) callback.onResult(new JSONObject());
            return;
        }
        JSONObject known = get(context, id);
        if (id.startsWith("iptv:")) {
            new Thread(() -> {
                JSONObject result;
                try { result = com.jojotv.home.iptv.IptvStore.homeSeriesDetails(context.getApplicationContext(), id); }
                catch (Exception ignored) { result = new JSONObject(); }
                final JSONObject details = result;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    if (callback != null) callback.onResult(details);
                    if (metadataReady != null) metadataReady.run();
                });
            }, "JojoHomeIptvSeries").start();
            return;
        }
        if (!known.optString("stalkerSource").isEmpty() && !known.optString("stalkerId").isEmpty()) {
            TiviMateFavoriteImporter.loadSeriesDetails(context, known,
                    value -> { if (callback != null) callback.onResult(value); });
            enrichProviderMetadata(context, id, () -> {
                if (metadataReady != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(metadataReady);
            });
            return;
        }
        enrichProviderMetadata(context, id, () -> {
            JSONObject item = get(context, id);
            TiviMateFavoriteImporter.loadSeriesDetails(context, item,
                    value -> { if (callback != null) callback.onResult(value); });
        });
    }

    /** Refreshes one provider episode link and returns the updated stored record. */
    public static void refreshProviderPlaybackLink(Context context, String id, ProviderJsonCallback callback) {
        if (context == null || id == null || id.isEmpty()) {
            if (callback != null) callback.onResult(new JSONObject());
            return;
        }
        TiviMateFavoriteImporter.refreshPlaybackLink(context, get(context, id),
                value -> { if (callback != null) callback.onResult(value); });
    }

    // Legacy local masks are read only until a versioned record supersedes them.
    private static SharedPreferences removals(Context context) {
        return context.getSharedPreferences("external_playback_removals", Context.MODE_PRIVATE);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("home_settings", Context.MODE_PRIVATE);
    }

    private static SharedPreferences records(Context context) {
        return context.getSharedPreferences("external_playback_records", Context.MODE_PRIVATE);
    }

    /** Ordering shared with the existing NAS envelope (time, then device ID). */
    public static int compareRevisions(long firstTime, String firstWriter, long secondTime, String secondWriter) {
        int time = Long.compare(firstTime, secondTime);
        if (time != 0) return time;
        return (firstWriter == null ? "" : firstWriter).compareTo(secondWriter == null ? "" : secondWriter);
    }

    private static JSONObject localRevision(Context context, String id) throws org.json.JSONException {
        String stored = records(context).getString(id, null);
        if (stored != null) return new JSONObject(stored);
        JSONObject item = new JSONObject(prefs(context).getString(PREFIX + id, "{}"));
        boolean removed = removals(context).getBoolean(id, false);
        if (removed) item.put("deleted", true).put("started", false).put("position", 0L);
        JSONObject changes = new JSONObject(prefs(context).getString("nas_setting_changes", "{}"));
        JSONObject previous = changes.optJSONObject(PREFIX + id);
        JSONObject revision = new JSONObject().put("type", "string").put("value", item.toString())
                .put("changedAt", Math.max(item.optLong("updatedAt"), previous == null ? 0L : previous.optLong("changedAt")))
                .put("writer", previous == null ? "" : previous.optString("writer", ""));
        // Keep orphaned legacy masks exportable without inventing a new deletion time.
        if (removed && !prefs(context).contains(PREFIX + id)) revision.put("deleted", true);
        return revision;
    }

    /** One atomic per-content value; generic Home snapshots cannot overwrite it. */
    private static void writeRevision(Context context, String id, JSONObject revision) throws org.json.JSONException {
        records(context).edit().putString(id, revision.toString()).apply();
        SharedPreferences.Editor mirror = prefs(context).edit();
        if (revision.optBoolean("deleted")) mirror.remove(PREFIX + id);
        else mirror.putString(PREFIX + id, revision.getString("value"));
        mirror.apply();
        removals(context).edit().remove(id).apply();
    }

    public static JSONObject versionedRecords(Context context) throws org.json.JSONException {
        java.util.Set<String> ids = new java.util.HashSet<>(records(context).getAll().keySet());
        for (Map.Entry<String, ?> removal : removals(context).getAll().entrySet()) {
            if (Boolean.TRUE.equals(removal.getValue()) && removal.getKey().matches("[a-f0-9]{64}")) ids.add(removal.getKey());
        }
        for (String key : prefs(context).getAll().keySet()) {
            if (key.startsWith(PREFIX) && key.substring(PREFIX.length()).matches("[a-f0-9]{64}")) ids.add(key.substring(PREFIX.length()));
        }
        JSONObject result = new JSONObject();
        for (String id : ids) result.put(PREFIX + id, localRevision(context, id));
        return result;
    }

    /** Called by the NAS worker, never while holding a UI-thread/network lock. */
    public static boolean mergeRemote(Context context, JSONObject values) throws org.json.JSONException {
        if (values == null) return false;
        boolean changed = false;
        java.util.Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.startsWith(PREFIX)) continue;
            String id = key.substring(PREFIX.length());
            if (!id.matches("[a-f0-9]{64}")) continue;
            JSONObject incoming = values.optJSONObject(key);
            if (incoming == null) continue;
            if (!incoming.optBoolean("deleted")) {
                if (!"string".equals(incoming.optString("type"))) continue;
                JSONObject item = new JSONObject(incoming.optString("value", "{}"));
                if (!id.equals(item.optString("id")) || !supportedUrl(item.optString("url"))
                        || !id.equals(expectedStorageId(item)) || !storageCategoryValid(item.optString("category"))) continue;
            }
            synchronized (ExternalPlaybackStore.class) {
                JSONObject current = localRevision(context, id);
                JSONObject currentItem = get(context, id);
                JSONObject incomingItem = !incoming.optBoolean("deleted")
                        ? new JSONObject(incoming.optString("value", "{}")) : null;
                // Provider/favorite metadata refreshes may omit playback
                // fields and default started=false. Never hide an existing
                // resume during that metadata-only synchronization. An
                // explicit removal includes position=0 and is left intact.
                if (incomingItem != null && currentItem.optBoolean("started")
                        && !incomingItem.optBoolean("started")
                        && !incomingItem.has("position")) {
                    incomingItem.put("started", true)
                            .put("position", currentItem.optLong("position", 0L));
                    if (!incomingItem.has("duration") && currentItem.has("duration")) {
                        incomingItem.put("duration", currentItem.optLong("duration", 0L));
                    }
                    incoming = new JSONObject(incoming.toString())
                            .put("value", incomingItem.toString());
                }
                boolean known = records(context).contains(id) || prefs(context).contains(key)
                        || removals(context).getBoolean(id, false);
                if (known && compareRevisions(incoming.optLong("changedAt"), incoming.optString("writer", ""),
                        current.optLong("changedAt"), current.optString("writer", "")) <= 0) continue;
                writeRevision(context, id, incoming);
                changed = true;
                android.util.Log.i("JojoHomeResume", "REMOTE_REVISION_APPLIED at=" + android.os.SystemClock.elapsedRealtime()
                        + " id=" + id + " changedAt=" + incoming.optLong("changedAt"));
            }
        }
        return changed;
    }

    public static synchronized void save(Context context, JSONObject item) {
        save(context, item, false);
    }

    /** Only a new explicit playback handoff may restore a removed item. */
    public static synchronized void saveForPlayback(Context context, JSONObject item) {
        save(context, item, true);
    }

    private static void save(Context context, JSONObject item, boolean explicitPlayback) {
        String id = item.optString("id");
        if (!id.matches("[a-f0-9]{64}") || !storageCategoryValid(item.optString("category"))) return;
        try {
            JSONObject current = get(context, id);
            if (!item.has("lastPlayedAt") && current.has("lastPlayedAt")) {
                item.put("lastPlayedAt", current.optLong("lastPlayedAt"));
            }
            // A favorite's position is its arrival position in the folder,
            // not its last metadata/progress update time. Keep it immutable so
            // artwork, synopsis and playback refreshes cannot reorder the rail.
            long favoriteOrder = current.optLong("favoriteOrder", 0L);
            if (favoriteOrder <= 0L) favoriteOrder = current.optLong("updatedAt", 0L);
            if (favoriteOrder <= 0L && (item.optBoolean("favorite")
                    || "tivimate-stalker".equals(item.optString("favoriteSource")))) {
                favoriteOrder = System.currentTimeMillis();
            }
            if (favoriteOrder > 0L) item.put("favoriteOrder", favoriteOrder);
            // A metadata/favorite refresh can omit the playback position and
            // recreate the item with started=false. Preserve an existing
            // resume in that case; explicit removals carry position=0.
            if (!explicitPlayback && current.optBoolean("started")
                    && !item.optBoolean("started") && !item.has("position")) {
                item.put("started", true)
                        .put("position", current.optLong("position", 0L));
                if (!item.has("duration") && current.has("duration")) {
                    item.put("duration", current.optLong("duration", 0L));
                }
            }
            // An import may have been prepared before a newer deletion/restart.
            // Keep its metadata, not its obsolete playback state.
            if (!explicitPlayback && item.optLong("updatedAt") < current.optLong("updatedAt")) {
                for (String field : new String[]{"deleted", "started", "position", "duration"}) {
                    if (current.has(field)) item.put(field, current.get(field));
                }
            }
            if (explicitPlayback) {
                item.put("deleted", false);
            } else if (current.optBoolean("deleted")) {
                item.put("deleted", true).put("started", false).put("position", 0L);
            }
            SharedPreferences prefs = prefs(context);
            JSONObject previous = localRevision(context, id);
            long now = Math.max(System.currentTimeMillis(), previous.optLong("changedAt") + 1);
            item.put("updatedAt", now);
            String value = item.toString();
            String writer = prefs.getString("nas_device_id", "");
            if (writer == null || writer.isEmpty()) writer = java.util.UUID.randomUUID().toString();
            JSONObject revision = new JSONObject().put("type", "string").put("value", value)
                    .put("changedAt", now).put("writer", writer);
            writeRevision(context, id, revision);
            android.util.Log.i("JojoHomeResume", "EXTERNAL_REVISION_SAVED at=" + android.os.SystemClock.elapsedRealtime()
                    + " id=" + id + " changedAt=" + now + " deleted=" + item.optBoolean("deleted")
                    + " explicitPlayback=" + explicitPlayback);
            prefs.edit().putString("nas_device_id", writer)
                    .putLong("nas_home_updated_at", now).putBoolean("nas_home_pending", true).apply();
        } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
    }

    public static List<JSONObject> list(Context context, String category) {
        List<JSONObject> result = listAll(context, category, true);
        // Keep the TiviMate identity contract, then append the local IPTV favorites.
        // Do not mix in legacy or other external-playback records that happen
        // to carry favorite=true; those records have a different identity
        // contract and can point at an unrelated catalogue item.
        result.removeIf(item -> !"tivimate-stalker".equals(item.optString("favoriteSource")));
        result.addAll(com.jojotv.home.iptv.IptvStore.homeFavorites(context, category));
        return result;
    }

    public static List<JSONObject> listAll(Context context, String category, boolean favoritesOnly) {
        List<JSONObject> result = new ArrayList<>();
        java.util.Set<String> keys = new java.util.HashSet<>(prefs(context).getAll().keySet());
        for (String id : records(context).getAll().keySet()) keys.add(PREFIX + id);
        for (String key : keys) {
            if (!key.startsWith(PREFIX) || !key.substring(PREFIX.length()).matches("[a-f0-9]{64}")) continue;
            try {
                JSONObject item = get(context, key.substring(PREFIX.length()));
                String expectedId = expectedStorageId(item);
                if (category.equals(item.optString("category")) && visibleInRow(item.optBoolean("started"), item.optBoolean("favorite"), item.optBoolean("deleted"), favoritesOnly) && supportedUrl(item.optString("url"))
                        && key.equals(PREFIX + expectedId)
                        && item.optString("id").equals(expectedId)) result.add(item);
                } catch (Exception ignored) {}
        }
        if ("live".equals(category) && result.size() > 1) {
            java.util.LinkedHashMap<String, JSONObject> unique = new java.util.LinkedHashMap<>();
            for (JSONObject item : result) {
                String key = liveIdentityKey(item);
                JSONObject previous = unique.get(key);
                if (previous == null || livePlaybackRank(item) > livePlaybackRank(previous)) {
                    unique.put(key, item);
                }
            }
            result = new ArrayList<>(unique.values());
        }
        if (favoritesOnly) {
            result.sort((a, b) -> {
                long left = a.optLong("favoriteOrder", a.optLong("updatedAt", Long.MAX_VALUE));
                long right = b.optLong("favoriteOrder", b.optLong("updatedAt", Long.MAX_VALUE));
                if (left != right) return Long.compare(left, right);
                return a.optString("id").compareTo(b.optString("id"));
            });
        } else {
            result.sort((a, b) -> Long.compare(b.optLong("updatedAt"), a.optLong("updatedAt")));
        }
        return result;
    }

    private static String liveTitleKey(String title) {
        if (title == null) return "";
        return java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static String liveIdentityKey(JSONObject item) {
        if (item == null) return "live-title|";
        String source = item.optString("stalkerSource", "").trim();
        String providerId = item.optString("stalkerId", "").trim();
        if (!source.isEmpty() && !providerId.isEmpty()) {
            return "live-provider|" + source + "|" + providerId;
        }
        return "live-title|" + liveTitleKey(item.optString("providerTitle",
                item.optString("title", "")));
    }

    private static int livePlaybackRank(JSONObject item) {
        int rank = item.optBoolean("favorite") ? 2 : 0;
        if (!item.optString("stalkerSource").isEmpty()) rank++;
        return rank * 1_000_000 + (int) Math.min(item.optLong("updatedAt"), 999_999L);
    }

    /** Serialize deletion with progress writes: an old player cannot resurrect it. */
    public static synchronized boolean markDeleted(Context context, String id) {
        JSONObject item = get(context, id);
        if (!item.has("url") || !id.equals(item.optString("id"))
                || !storageCategoryValid(item.optString("category"))) return false;
        try {
            item.put("position", 0L).put("started", false).put("deleted", true);
            save(context, item);
            return get(context, id).optBoolean("deleted");
        } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
    }

    /** Remove only the playback resume; a favorite remains in its Home rail. */
    public static synchronized boolean removeFromContinueWatching(Context context, String id) {
        return removeFromContinueWatching(context, id, false);
    }

    /** An explicit reset also clears the watched state, unlike automatic completion cleanup. */
    public static synchronized boolean markUnwatched(Context context, String id, int profileId) {
        if (id != null && id.startsWith("iptv:")) {
            return com.jojotv.home.iptv.IptvStore.markHomeUnwatched(context, id, profileId);
        }
        if (profileId != HomeWatchProgressBridge.currentProfileId(context)) return false;
        return removeFromContinueWatching(context, id, true);
    }

    private static boolean removeFromContinueWatching(Context context, String id, boolean unwatched) {
        JSONObject item = get(context, id);
        if (!item.has("url") || !id.equals(item.optString("id"))
                || !storageCategoryValid(item.optString("category"))) return false;
        List<JSONObject> recordsToRemove = new ArrayList<>();
        String seriesKey = "series".equals(item.optString("category"))
                ? continueWatchingKey(item) : "";
        if (!seriesKey.isEmpty()) {
            for (JSONObject candidate : listAll(context, "series", false)) {
                if (sameContinueWatchingItem(item, candidate)) recordsToRemove.add(candidate);
            }
        }
        if (recordsToRemove.isEmpty()) recordsToRemove.add(item);
        boolean removed = true;
        for (JSONObject candidate : recordsToRemove) {
            if (!removeSingleContinueWatchingRecord(context, candidate, unwatched)) removed = false;
        }
        return removed;
    }

    private static boolean removeSingleContinueWatchingRecord(Context context, JSONObject item, boolean unwatched) {
        String id = item.optString("id");
        if (!item.has("url") || !id.matches("[a-f0-9]{64}") || !id.equals(expectedStorageId(item))
                || !storageCategoryValid(item.optString("category"))) return false;
        if (!item.optBoolean("favorite") && !unwatched) return markDeleted(context, id);
        try {
            boolean favorite = item.optBoolean("favorite");
            item.put("position", 0L).put("duration", 0L).put("started", false).put("deleted", !favorite);
            if (unwatched) item.put("watched", false);
            save(context, item);
            JSONObject saved = get(context, id);
            return saved.optBoolean("favorite") == favorite && !saved.optBoolean("started")
                    && saved.optBoolean("deleted") == !favorite && (!unwatched || !saved.optBoolean("watched"));
        } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
    }

    /** Remove the same IPTV item from both Continue Watching and its favorite folder. */
    public static synchronized boolean removeFromHomeLists(Context context, String id) {
        if (id != null && id.startsWith("iptv:")) {
            return com.jojotv.home.iptv.IptvStore.removeHomeFavorite(context, id);
        }
        JSONObject item = get(context, id);
        if (!item.has("url") || !id.equals(item.optString("id"))
                || !storageCategoryValid(item.optString("category"))) return false;
        try {
            item.put("favorite", false)
                    .put("position", 0L)
                    .put("duration", 0L)
                    .put("started", false)
                    .put("deleted", false);
            save(context, item);
            JSONObject saved = get(context, id);
            return !saved.optBoolean("favorite") && !saved.optBoolean("started")
                    && !saved.optBoolean("deleted");
        } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
    }

    /** Removing a series favorite also hides its episode resumes while retaining their positions. */
    public static synchronized boolean removeSeriesFavoriteFromHomeLists(Context context, String id) {
        JSONObject favorite = get(context, id);
        String source = favorite.optString("stalkerSource", "").trim();
        String seriesId = favorite.optString("stalkerId", "").trim();
        List<JSONObject> episodes = "series".equals(favorite.optString("category"))
                && !source.isEmpty() && !seriesId.isEmpty()
                ? listAll(context, "series", false) : new ArrayList<>();
        if (!removeFromHomeLists(context, id)) return false;
        for (JSONObject episode : episodes) {
            if (!source.equals(episode.optString("stalkerSource"))
                    || !seriesId.equals(episode.optString("stalkerSeriesId"))) continue;
            try {
                episode.put("started", false);
                save(context, episode);
            } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
        }
        return true;
    }

    public static synchronized void markStarted(Context context, String id) {
        JSONObject item = get(context, id);
        if (!item.has("url") || item.optBoolean("deleted")) return;
        clearHomeResumeDismissal(context, item);
        rememberLastPlayback(context, "external-playback|" + id);
        try {
            // Preserve an existing resume while ExoPlayer applies its initial seek.
            item.put("started", true).put("lastPlayedAt", System.currentTimeMillis());
            item.put("progressProfileId", HomeWatchProgressBridge.currentProfileId(context));
            save(context, item);
        } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
    }

    public static synchronized void progress(Context context, String id, long position, long duration) {
        JSONObject item = get(context, id);
        if (!item.has("url") || item.optBoolean("deleted")) return;
        rememberLastPlayback(context, "external-playback|" + id);
        try {
            boolean live = "live".equals(item.optString("category"));
            long savedPosition = live ? 0L : Math.max(0L, position);
            long savedDuration = live ? 0L : Math.max(0L, duration);
            boolean episodeWatched = !live && ("series".equals(item.optString("category"))
                    || item.optInt("episode") > 0) && savedDuration > 0L
                    && (float) savedPosition / savedDuration
                    >= com.nuvio.tv.domain.model.WatchProgress.EPISODE_COMPLETED_THRESHOLD;
            // A paused/background session must not keep winning NAS conflicts.
            if (item.optBoolean("started") && item.optLong("position") == savedPosition
                    && item.optLong("duration") == savedDuration
                    && (!episodeWatched || item.optBoolean("watched"))) return;
            item.put("position", savedPosition);
            item.put("duration", savedDuration);
            if (episodeWatched) item.put("watched", true);
            item.put("started", true);
            item.put("lastPlayedAt", System.currentTimeMillis());
            item.put("progressProfileId", HomeWatchProgressBridge.currentProfileId(context));
            save(context, item);
        } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
    }

    static void rememberLastPlayback(Context context, String key) {
        prefs(context).edit().putString(LAST_HOME_PLAYBACK_KEY, key)
                .putLong(LAST_HOME_PLAYBACK_AT, System.currentTimeMillis()).apply();
    }

    public static boolean visibleInRow(boolean started, boolean favorite, boolean deleted, boolean favoritesOnly) {
        return !deleted && (favoritesOnly ? favorite : started);
    }

    /** Stable Home suppression identity: every resumed episode of a series shares one Hero. */
    public static String continueWatchingDismissalKey(JSONObject item) {
        if (item == null) return "";
        String id = item.optString("id", "");
        if ("series".equals(item.optString("category"))) {
            String seriesKey = continueWatchingKey(item);
            if (!seriesKey.isEmpty()) return "external-series-resume|" + seriesKey;
        }
        return id.isEmpty() ? "" : "external-playback|" + id;
    }

    private static void clearHomeResumeDismissal(Context context, JSONObject item) {
        SharedPreferences preferences = prefs(context);
        Set<String> hidden = new HashSet<>(preferences.getStringSet(
                "jojotv_home_hidden_resume_keys", java.util.Collections.emptySet()));
        boolean changed = hidden.remove("external-playback|" + item.optString("id", ""));
        String dismissalKey = continueWatchingDismissalKey(item);
        if (!dismissalKey.isEmpty()) changed |= hidden.remove(dismissalKey);
        String prefix = HomeWatchProgressBridge.RESTORATION_PREFIX + HomeWatchProgressBridge.currentProfileId(context) + "_";
        long now = System.currentTimeMillis();
        SharedPreferences.Editor editor = preferences.edit()
                .putLong(prefix + "external-playback|" + item.optString("id", ""), now);
        if (!dismissalKey.isEmpty()) editor.putLong(prefix + dismissalKey, now);
        if (changed) editor.putStringSet("jojotv_home_hidden_resume_keys", hidden)
                .remove("continue_watching_home_cache");
        editor.apply();
    }

    /** Stable logical identity for Continue Watching, independent of rotating IPTV URLs. */
    static String continueWatchingKey(JSONObject item) {
        if (item == null) return "";
        String category = item.optString("category");
        boolean series = "series".equals(category);
        if (series && "jojo-iptv".equals(item.optString("favoriteSource"))
                && item.optString("id").startsWith("iptv:")) return "native-series|" + item.optString("id");
        // A series favorite and a resume opened from its episode list have
        // different item IDs. They represent one Home resume card, keyed by
        // the provider's parent series ID so replaying from either route
        // updates that same logical entry instead of adding a duplicate.
        String providerItemId = series
                ? seriesProviderId(item)
                : item.optString("stalkerEpisodeId", item.optString("stalkerId", ""));
        return continueWatchingKey(category, looksLikeLiveChannel(item),
                item.optString("id"), item.optString("providerTitle", item.optString("title", "")),
                item.optString("year", ""), item.optString("stalkerSource", ""),
                providerItemId, series ? 0 : item.optInt("season", 0),
                series ? 0 : item.optInt("episode", 0));
    }

    /** Compare playback routes without changing persisted dismissal/insertion keys. */
    static boolean sameContinueWatchingItem(JSONObject left, JSONObject right) {
        if (left == null || right == null) return false;
        boolean series = "series".equals(left.optString("category"))
                && "series".equals(right.optString("category"));
        String leftAccount = left.optString("stalkerProviderAccount", "").trim();
        String rightAccount = right.optString("stalkerProviderAccount", "").trim();
        if (series && !leftAccount.isEmpty() && !rightAccount.isEmpty()
                && !leftAccount.equals(rightAccount)) return false;
        String leftKey = continueWatchingKey(left);
        if (!leftKey.isEmpty() && leftKey.equals(continueWatchingKey(right))) return true;
        if (!series) return false;
        if ("jojo-iptv".equals(left.optString("favoriteSource"))
                || "jojo-iptv".equals(right.optString("favoriteSource"))) return false;
        String leftSource = left.optString("stalkerSource", "").trim();
        String rightSource = right.optString("stalkerSource", "").trim();
        boolean sameProvider = !leftSource.isEmpty() && leftSource.equals(rightSource)
                || !leftAccount.isEmpty() && leftAccount.equals(rightAccount)
                || !leftSource.isEmpty() && leftSource.equals(rightAccount)
                || !rightSource.isEmpty() && rightSource.equals(leftAccount);
        String leftSeries = seriesProviderId(left), rightSeries = seriesProviderId(right);
        if (!leftSeries.isEmpty() && !rightSeries.isEmpty()) {
            return sameProvider && leftSeries.equals(rightSeries);
        }
        if (!leftSource.isEmpty() && !rightSource.isEmpty() && !sameProvider) return false;
        String leftUrl = left.optString("url", "");
        if (supportedUrl(leftUrl) && leftUrl.equals(right.optString("url", ""))
                && left.optInt("season", 0) == right.optInt("season", 0)
                && left.optInt("episode", 0) == right.optInt("episode", 0)) return true;
        // With no catalogue identity on either record, retain the existing
        // title/year fallback but remove the episode suffix added by the player.
        return leftSeries.isEmpty() && rightSeries.isEmpty()
                && seriesTitleResumeKey(left).equals(seriesTitleResumeKey(right));
    }

    private static String seriesProviderId(JSONObject item) {
        String id = item.optString("stalkerSeriesId", "").trim();
        return id.isEmpty() ? item.optString("stalkerId", "").trim() : id;
    }

    private static String seriesTitleResumeKey(JSONObject item) {
        String title = item.optString("providerTitle", "").trim();
        if (title.isEmpty()) title = item.optString("title", "").trim();
        title = title.replaceFirst("(?i)\\s*(?:[-–—|:]\\s*)?(?:[\\[(]\\s*)?s\\s*\\d{1,2}\\s*e\\s*\\d{1,3}(?:\\s*[\\])])?\\s*$", "").trim();
        return continueWatchingKey("series", false, item.optString("id"), title,
                item.optString("year", ""), "", "", 0, 0);
    }

    static String continueWatchingKey(String category, boolean looksLive, String id, String rawTitle, String rawYear,
                                      String providerSource, String providerId, int season, int episode) {
        if ("live".equals(category) || looksLive) {
            JSONObject live = new JSONObject();
            try {
                live.put("category", "live").put("id", id == null ? "" : id)
                        .put("title", rawTitle == null ? "" : rawTitle)
                        .put("stalkerSource", providerSource == null ? "" : providerSource)
                        .put("stalkerId", providerId == null ? "" : providerId);
            } catch (org.json.JSONException ignored) { }
            return liveIdentityKey(live);
        }
        if (providerSource != null && !providerSource.isEmpty() && providerId != null && !providerId.isEmpty()) {
            return "vod-provider|" + providerSource + "|" + category + "|" + providerId
                    + "|" + Math.max(0, season) + "|" + Math.max(0, episode);
        }
        String title = rawTitle == null ? "" : rawTitle;
        title = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
        if (title.isEmpty()) return "vod-id|" + (id == null ? "" : id);
        String year = (rawYear == null ? "" : rawYear).replaceAll("[^0-9]", "");
        if (year.length() > 4) year = year.substring(0, 4);
        return "vod|" + title + "|" + year;
    }

    /** Prefer the latest playback, using provider metadata only as a deterministic tie-breaker. */
    static boolean preferForContinueWatching(JSONObject candidate, JSONObject current) {
        long candidatePlayback = candidate == null ? 0L : candidate.optLong("lastPlayedAt");
        long currentPlayback = current == null ? 0L : current.optLong("lastPlayedAt");
        if (candidatePlayback != currentPlayback) return candidatePlayback > currentPlayback;
        long candidateTime = candidate == null ? 0L : candidate.optLong("updatedAt");
        long currentTime = current == null ? 0L : current.optLong("updatedAt");
        int candidateMetadata = providerMetadataScore(candidate);
        int currentMetadata = providerMetadataScore(current);
        return preferForContinueWatching(candidateTime, candidateMetadata,
                candidate == null ? "" : candidate.optString("id"),
                currentTime, currentMetadata, current == null ? "" : current.optString("id"));
    }

    static boolean preferForContinueWatching(long candidateTime, int candidateMetadata, String candidateId,
                                               long currentTime, int currentMetadata, String currentId) {
        if (candidateTime != currentTime) return candidateTime > currentTime;
        if (candidateMetadata != currentMetadata) return candidateMetadata > currentMetadata;
        return candidateId != null && currentId != null && candidateId.compareTo(currentId) > 0;
    }

    private static int providerMetadataScore(JSONObject item) {
        if (item == null) return 0;
        int score = 0;
        for (String field : new String[]{"providerTitle", "originalTitle", "year", "description", "descr", "overview", "artwork", "season", "episode", "episodeTitle"}) {
            if (item.has(field) && !item.optString(field, "").isEmpty()) score++;
        }
        return score;
    }

    public static String playerContentType(String category) {
        return "live".equals(category) ? "iptv" : "home-external";
    }

    public static String playerDisplayTitle(String rawTitle, int storedSeason, int storedEpisode) {
        if (rawTitle == null || rawTitle.trim().isEmpty()) return "Vidéo";
        String title = rawTitle.replace('_', ' ').replaceAll("\\s+", " ").trim();
        int season = Math.max(0, storedSeason);
        int episode = Math.max(0, storedEpisode);
        java.util.regex.Matcher episodeCode = java.util.regex.Pattern
                .compile("(?i)(?:^|\\b)s\\s*(\\d{1,2})\\s*e\\s*(\\d{1,3})(?:\\b|$)")
                .matcher(title);
        if (episodeCode.find()) {
            if (season <= 0) season = Integer.parseInt(episodeCode.group(1));
            if (episode <= 0) episode = Integer.parseInt(episodeCode.group(2));
        }
        String previous;
        do {
            previous = title;
            title = title.replaceFirst("(?i)\\s*(?:[-–—|:]\\s*)?(?:[\\[(]\\s*)?s\\s*\\d{1,2}\\s*e\\s*\\d{1,3}(?:\\s*[\\])])?\\s*$", "");
            title = title.replaceFirst("(?i)\\s*[\\[(][^\\])]*(?:4k|8k|hdr|uhd|fhd|2160p|1080p|720p|hevc|h\\.?264|h\\.?265|dolby|atmos|web[- ]?dl|bluray|multi|vostfr|vf)[^\\])]*[\\])]\\s*$", "");
            title = title.replaceFirst("\\s*[\\[(]\\s*(?:19|20)\\d{2}\\s*[\\])]\\s*$", "");
            title = title.replaceAll("\\s+", " ").trim();
        } while (!title.equals(previous));
        if (title.isEmpty()) title = "Vidéo";
        return season > 0 && episode > 0
                ? title + String.format(Locale.ROOT, " S%02dE%02d", season, episode)
                : title;
    }

    private static boolean looksLikeLiveChannel(JSONObject item) {
        String title = item.optString("title", "").trim();
        return title.matches("(?i)^[a-z]{2}\\s*\\|\\s*.+");
    }

    public static Intent playerIntent(Context context, JSONObject item) {
        boolean live = "live".equals(item.optString("category")) || looksLikeLiveChannel(item);
        String title = item.optString("title", "Vidéo");
        if (!live) title = playerDisplayTitle(title, item.optInt("season", 0), item.optInt("episode", 0));
        String description = "";
        for (String field : new String[]{"description", "overview", "synopsis", "plot", "desc"}) {
            description = HomeMediaRules.mediaMetadataText(item.optString(field, ""));
            if (!description.isEmpty()) break;
        }
        Intent intent = new Intent(context, NuvioPlayerActivity.class)
                .putExtra("streamUrl", item.optString("url"))
                .putExtra("title", title)
                .putExtra(NuvioPlayerActivity.EXTRA_DESCRIPTION, description)
                .putExtra(NuvioPlayerActivity.EXTRA_MEDIA_PRESENTATION, playerPresentation(item).toString())
                .putExtra("contentId", CONTENT_PREFIX + item.optString("id"))
                .putExtra("contentType", live ? "iptv" : playerContentType(item.optString("category")))
                .putExtra(NuvioPlayerActivity.EXTRA_PLAYER_ACCENT,
                        MainActivity.playerAccentColor(context))
                .putExtra("startFromBeginning", live);
        // Keep the selected provider episode identity explicit in the player
        // route. Parsing SxxExx back out of a display title allowed stale
        // provider metadata to turn S01 into S02 after a link refresh.
        if (!live) {
            int season = item.optInt("season", 0);
            int episode = item.optInt("episode", 0);
            if (season > 0) intent.putExtra("season", season);
            if (episode > 0) intent.putExtra("episode", episode);
            String episodeTitle = item.optString("episodeTitle", "").trim();
            if (!episodeTitle.isEmpty()) intent.putExtra("episodeTitle", episodeTitle);
        }
        return intent;
    }

    public static JSONObject playerPresentation(JSONObject item) {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        for (String key : new String[]{"tmdbId", "mediaType", "description", "overview", "plot", "synopsis",
                "year", "releaseYear", "releaseDate", "genres", "genre", "cast", "actors", "castDetails",
                "director", "runtimeMinutes", "rating", "status", "titleLogo", "backdrop", "artwork"}) {
            Object value = item.opt(key);
            if (value != null && value != JSONObject.NULL
                    && !HomeMediaRules.mediaMetadataText(String.valueOf(value)).isEmpty()) values.put(key, value);
        }
        values.put("runtimeMinutes", HomeMediaRules.externalRuntimeMinutes(item.optLong("duration", 0L),
                item.optInt("runtimeMinutes", 0), item.optString("providerRuntime", "")));
        return new JSONObject(values);
    }

    /** Remove an IPTV favorite without deleting its playback history. */
    public static synchronized boolean removeFavorite(Context context, String id) {
        JSONObject item = get(context, id);
        if (!item.has("url") || !id.equals(item.optString("id"))
                || !storageCategoryValid(item.optString("category"))) return false;
        try {
            item.put("favorite", false);
            save(context, item);
            return !get(context, id).optBoolean("favorite");
        } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
    }

    /** Keeps the watched marker when a completed favorite leaves Continue Watching. */
    public static synchronized void markWatched(Context context, String id) {
        JSONObject item = get(context, id);
        if (!item.has("url") || !id.equals(item.optString("id"))) return;
        try {
            item.put("watched", true);
            save(context, item);
        } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
    }
}

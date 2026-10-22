package com.jojotv.home;

import android.content.Context;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Resolves external TiviMate playback links and provider metadata for Stalker portals. */
final class TiviMateFavoriteImporter {
    private static final String PORTAL = "tivimate_stalker_portal";
    private static final String MAC = "tivimate_stalker_mac";
    private static final String PROVIDER_PORTAL_PREFIX = "tivimate_stalker_provider_portal_";
    private static final String PROVIDER_MAC_PREFIX = "tivimate_stalker_provider_mac_";
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final ExecutorService PLAYBACK_WORK = Executors.newSingleThreadExecutor();
    private static final ExecutorService SERIES_WORK = Executors.newSingleThreadExecutor();

    private TiviMateFavoriteImporter() {}

    interface JsonCallback { void onResult(JSONObject value); }

    private static final class StalkerProvider {
        final String portal;
        final String mac;
        final String source;
        final String account;

        StalkerProvider(String portal, String mac) {
            this.portal = portal;
            this.mac = mac;
            this.source = providerSourceId(portal);
            this.account = providerAccountId(portal, mac);
        }
    }

    /** Refreshes a Stalker create_link off the UI thread immediately before playback. */
    static void refreshPlaybackLink(Context context, JSONObject requested, JsonCallback callback) {
        if (context == null || requested == null) {
            postResult(callback, new JSONObject());
            return;
        }
        Context app = context.getApplicationContext();
        JSONObject snapshot;
        try { snapshot = new JSONObject(requested.toString()); }
        catch (Exception ignored) { snapshot = requested; }
        String source = snapshot.optString("stalkerSource");
        String command = snapshot.optString("stalkerCmd");
        if (source.isEmpty() || command.isEmpty()) {
            postResult(callback, "tivimate-stalker".equals(snapshot.optString("favoriteSource"))
                    ? new JSONObject() : snapshot);
            return;
        }
        JSONObject finalSnapshot = snapshot;
        PLAYBACK_WORK.execute(() -> {
            JSONObject result = finalSnapshot;
            long startedAt = android.os.SystemClock.elapsedRealtime();
            try {
                StalkerProvider provider = providerForSource(app, source,
                        finalSnapshot.optString("stalkerProviderAccount"));
                if (provider == null) throw new IllegalStateException("local provider unavailable");
                String portal = provider.portal;
                String mac = provider.mac;
                String[] session = openProviderSessionForPlayback(portal, mac,
                        finalSnapshot.optString("stalkerEndpoint"));
                if (session == null) throw new IllegalStateException("handshake unavailable");
                String category = finalSnapshot.optString("category");
                String query = "type=" + ("live".equals(category) ? "itv" : "vod")
                        + "&action=create_link&cmd=" + enc(command);
                if ("series".equals(category)) {
                    String episodeId = finalSnapshot.optString("stalkerEpisodeId");
                    if (episodeId.isEmpty()) throw new IllegalStateException("episode unavailable");
                    query += "&series=" + enc(episodeId) + "&force_ch_link_check=0";
                }
                JSONObject link = request(session[0], mac, query + "&JsHttpRequest=1-xml", session[1],
                        2500, 4000).optJSONObject("js");
                String stream = streamCommand(link == null ? "" : link.optString("cmd"));
                if (!ExternalPlaybackStore.supportedUrl(stream)) throw new IllegalStateException("link unavailable");
                 JSONObject latest = ExternalPlaybackStore.get(app, finalSnapshot.optString("id"));
                 if (!latest.has("id")) latest = new JSONObject(finalSnapshot.toString());
                 // The clicked episode record is the authoritative selection.
                 // A cached playback record can contain older provider metadata;
                 // carrying that metadata back into the player is what produced
                 // a folder S01 tile with a player title rendered as S02.
                 for (String field : new String[]{"category", "title", "providerTitle",
                         "stalkerSeriesId", "stalkerEpisodeId", "stalkerCmd",
                         "stalkerProviderAccount", "season", "episode", "episodeTitle"}) {
                     if (finalSnapshot.has(field)) latest.put(field, finalSnapshot.get(field));
                 }
                 latest.put("url", stream).put("deleted", false);
                ExternalPlaybackStore.saveForPlayback(app, latest);
                result = ExternalPlaybackStore.get(app, latest.optString("id"));
                android.util.Log.i("TiviMatePlayback", "PLAYBACK_LINK_READY elapsedMs="
                        + (android.os.SystemClock.elapsedRealtime() - startedAt)
                        + " category=" + category);
            } catch (Exception ignored) {
                result = new JSONObject();
                android.util.Log.i("TiviMatePlayback", "PLAYBACK_LINK_FALLBACK elapsedMs="
                        + (android.os.SystemClock.elapsedRealtime() - startedAt));
            }
            postResult(callback, result);
        });
    }

    /** Loads provider-authoritative seasons and episodes for one imported series favorite. */
    static void loadSeriesDetails(Context context, JSONObject seriesFavorite, JsonCallback callback) {
        if (context == null || seriesFavorite == null) {
            postResult(callback, new JSONObject());
            return;
        }
        Context app = context.getApplicationContext();
        JSONObject parsedSourceItem;
        try { parsedSourceItem = new JSONObject(seriesFavorite.toString()); }
        catch (Exception ignored) { parsedSourceItem = seriesFavorite; }
        final JSONObject sourceItem = parsedSourceItem;
        SERIES_WORK.execute(() -> {
            JSONObject result = new JSONObject();
            try {
                String source = sourceItem.optString("stalkerSource");
                String seriesId = sourceItem.optString("stalkerId");
                StalkerProvider provider = providerForSource(app, source,
                        sourceItem.optString("stalkerProviderAccount"));
                if (provider == null || source.isEmpty() || seriesId.isEmpty()) throw new IllegalStateException("local provider unavailable");
                String portal = provider.portal;
                String mac = provider.mac;
                String[] session = openProviderSession(portal, mac);
                if (session == null) throw new IllegalStateException("handshake unavailable");
                JSONObject response = request(session[0], mac,
                        "type=series&action=get_ordered_list&movie_id=" + enc(seriesId)
                                + "&p=1&JsHttpRequest=1-xml", session[1]);
                JSONArray providerSeasons = providerData(response);
                JSONArray seasons = new JSONArray();
                if (providerSeasons != null) for (int seasonIndex = 0; seasonIndex < providerSeasons.length(); seasonIndex++) {
                    JSONObject providerSeason = providerSeasons.optJSONObject(seasonIndex);
                    if (providerSeason == null) continue;
                    String command = providerSeason.optString("cmd");
                    JSONArray providerEpisodes = providerSeason.optJSONArray("series");
                    if (command.isEmpty() || providerEpisodes == null || providerEpisodes.length() == 0) continue;
                    Integer parsedSeason = parseFirstInteger(providerText(providerSeason,
                            "season", "season_number", "seasonNumber", "season_num", "name", "title"));
                    int seasonNumber = parsedSeason == null || parsedSeason <= 0 ? seasonIndex + 1 : parsedSeason;
                    JSONObject season = new JSONObject().put("number", seasonNumber).put("episodes", new JSONArray());
                    JSONArray episodes = season.getJSONArray("episodes");
                    for (int episodeIndex = 0; episodeIndex < providerEpisodes.length(); episodeIndex++) {
                        JSONObject providerEpisode = providerEpisodes.optJSONObject(episodeIndex);
                        String episodeId = providerEpisode == null
                                ? providerEpisodes.optString(episodeIndex, "")
                                : providerText(providerEpisode, "id", "episode_id", "episodeId", "series_id", "seriesId");
                        if (episodeId.isEmpty()) continue;
                        Integer parsedEpisode = providerEpisode == null ? null : parseFirstInteger(providerText(providerEpisode,
                                "episode", "episode_number", "episodeNumber", "episode_num", "ep", "name", "title"));
                        int episodeNumber = parsedEpisode == null || parsedEpisode <= 0 ? episodeIndex + 1 : parsedEpisode;
                        String storageId = ExternalPlaybackStore.stalkerEpisodeId(
                                source, seriesId, seasonNumber, episodeId);
                        JSONObject previous = ExternalPlaybackStore.get(app, storageId);
                        JSONObject episode = new JSONObject(sourceItem.toString());
                        episode.remove("favoriteSource");
                        episode.remove("url");
                        episode.put("id", storageId)
                                .put("favorite", false).put("started", false).put("deleted", false)
                                .put("stalkerSeriesId", seriesId).put("stalkerEpisodeId", episodeId)
                                .put("stalkerCmd", command).put("season", seasonNumber).put("episode", episodeNumber);
                        if (ExternalPlaybackStore.supportedUrl(previous.optString("url"))) {
                            episode.put("url", previous.optString("url"))
                                    .put("position", previous.optLong("position"))
                                    .put("duration", previous.optLong("duration"))
                                    .put("started", previous.optBoolean("started"))
                                    .put("watched", previous.optBoolean("watched"));
                        }
                        copyProviderMetadata(episode, sourceItem, providerSeason, providerEpisode,
                                sourceItem.optString("title"));
                        copyEpisodeDuration(episode, providerEpisode, previous);
                        episode.put("title", sourceItem.optString("title"))
                                .put("providerTitle", sourceItem.optString("providerTitle", sourceItem.optString("title")))
                                .put("season", seasonNumber).put("episode", episodeNumber);
                        String episodeTitle = providerText(providerEpisode,
                                "episode_title", "episodeTitle", "episode_name", "episodeName", "title", "name");
                        if (!episodeTitle.isEmpty()) episode.put("episodeTitle", episodeTitle);
                        normalizeProviderArtwork(episode, session[0]);
                        // The player resolves a selected episode by this stable
                        // record ID. Persist it before exposing it to the panel;
                        // otherwise refreshProviderPlaybackLink() receives an
                        // empty record and reports an unavailable IPTV link.
                        ExternalPlaybackStore.saveForPlayback(app, episode);
                        episodes.put(episode);
                    }
                    if (episodes.length() > 0) seasons.put(season);
                }
                result.put("seasons", seasons);
                android.util.Log.i("TiviMatePlayback", "SERIES_DETAILS_READY seasons=" + seasons.length());
            } catch (Exception ignored) {
                android.util.Log.i("TiviMatePlayback", "SERIES_DETAILS_UNAVAILABLE");
            }
            postResult(callback, result);
        });
    }

    private static void postResult(JsonCallback callback, JSONObject value) {
        if (callback == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> callback.onResult(value));
    }

    /** Completes a TiviMate playback record with the metadata exposed by its Stalker provider. */
    static void enrichPlaybackMetadata(Context context, String playbackId) {
        enrichPlaybackMetadata(context, playbackId, null);
    }

    /** Notifies the visible Home hero when the asynchronous provider lookup has finished. */
    static void enrichPlaybackMetadata(Context context, String playbackId, Runnable completion) {
        if (context == null || playbackId == null || playbackId.isEmpty()) {
            if (completion != null) completion.run();
            return;
        }
        Context app = context.getApplicationContext();
        WORK.execute(() -> {
            try {
                JSONObject current = ExternalPlaybackStore.get(app, playbackId);
                if (current.optBoolean("deleted") || !current.has("url")) return;
                if ("live".equals(current.optString("category"))
                        || current.optString("title", "").matches("(?i)^[a-z]{2}\\s*\\|\\s*.+")) return;
                String playbackUrl = current.optString("url", "");
                String source = current.optString("stalkerSource", "");
                StalkerProvider provider = providerForSource(app, source,
                        current.optString("stalkerProviderAccount"));
                String portal = provider == null ? "" : provider.portal;
                String mac = provider == null ? "" : provider.mac;
                if (portal.isEmpty() && source.isEmpty()) {
                    portal = app.getSharedPreferences("home_settings", 0).getString(PORTAL, "");
                    mac = app.getSharedPreferences("home_settings", 0).getString(MAC, "");
                }
                if (portal.isEmpty()) portal = stalkerPortalFromPlaybackUrl(playbackUrl);
                if (mac.isEmpty()) mac = stalkerMacFromPlaybackUrl(playbackUrl);
                if (portal.isEmpty() || mac.isEmpty()) return;
                if (!source.isEmpty() && !source.equals(providerAccountId(portal, mac))
                        && !source.equals(providerSourceId(portal))) return;
                rememberStalkerProvider(app, portal, mac);
                String resolvedSource = source.isEmpty() ? providerSourceId(portal) : source;
                String rawTitle = current.optString("providerTitle", current.optString("title", ""));
                String searchTitle = providerSearchTitle(rawTitle);
                if (searchTitle.isEmpty()) return;

                String endpoint = "";
                String token = "";
                for (String candidate : stalkerEndpoints(portal, playbackUrl)) {
                    try {
                        JSONObject handshake = request(candidate, mac,
                                "type=stb&action=handshake&token=&JsHttpRequest=1-xml", null).optJSONObject("js");
                        String candidateToken = handshake == null ? "" : handshake.optString("token", "");
                        if (!candidateToken.isEmpty()) {
                            endpoint = candidate;
                            token = candidateToken;
                            break;
                        }
                    } catch (Exception ignored) { }
                }
                if (token.isEmpty()) return;

                java.util.regex.Matcher episodeCode = java.util.regex.Pattern
                        .compile("(?i)(?:^|\\b)s\\s*(\\d{1,2})\\s*e\\s*(\\d{1,3})(?:\\b|$)")
                        .matcher(rawTitle);
                Integer wantedSeason = null;
                Integer wantedEpisode = null;
                if (episodeCode.find()) {
                    wantedSeason = Integer.valueOf(episodeCode.group(1));
                    wantedEpisode = Integer.valueOf(episodeCode.group(2));
                }
                if (wantedSeason == null && current.optInt("season", 0) > 0) wantedSeason = current.optInt("season");
                if (wantedEpisode == null && current.optInt("episode", 0) > 0) wantedEpisode = current.optInt("episode");
                boolean seriesHint = "series".equals(current.optString("category")) || wantedEpisode != null;
                JSONObject match = null;
                JSONObject season = null;
                JSONObject episode = null;
                String resolvedCategory = current.optString("category", "unknown");

                if (seriesHint) {
                    JSONObject list = request(endpoint, mac,
                            "type=series&action=get_ordered_list&search=" + enc(searchTitle) + "&p=1&JsHttpRequest=1-xml", token);
                    match = exact(list.optJSONObject("js") == null ? null : list.optJSONObject("js").optJSONArray("data"), rawTitle);
                    if (match != null) {
                        resolvedCategory = "series";
                        JSONObject seasons = request(endpoint, mac,
                                "type=series&action=get_ordered_list&movie_id=" + enc(match.optString("id")) + "&p=1&JsHttpRequest=1-xml", token);
                        JSONArray seasonItems = seasons.optJSONObject("js") == null ? null : seasons.optJSONObject("js").optJSONArray("data");
                        season = selectSeason(seasonItems, wantedSeason);
                        episode = selectEpisode(season, wantedEpisode);
                    }
                } else {
                    JSONObject list = request(endpoint, mac,
                            "type=vod&action=get_ordered_list&search=" + enc(searchTitle) + "&p=1&JsHttpRequest=1-xml", token);
                    match = exact(list.optJSONObject("js") == null ? null : list.optJSONObject("js").optJSONArray("data"), rawTitle);
                    if (match != null) resolvedCategory = "movies";
                }
                if (match == null) return;

                // Re-read after the network requests so a newer playback
                // position cannot be overwritten by the metadata response.
                JSONObject enriched = ExternalPlaybackStore.get(app, playbackId);
                if (enriched.optBoolean("deleted") || !enriched.has("url")) return;
                copyProviderMetadata(enriched, match, season, episode, rawTitle);
                normalizeProviderArtwork(enriched, portal);
                enriched.put("category", resolvedCategory)
                        .put("stalkerSource", resolvedSource)
                        .put("stalkerProviderAccount", providerAccountId(portal, mac))
                        .put("stalkerId", match.optString("id"))
                        .put("stalkerEndpoint", endpoint);
                if ("series".equals(resolvedCategory) && season != null && episode != null) {
                    enriched.put("stalkerCmd", season.optString("cmd"))
                            .put("stalkerEpisodeId", providerText(episode,
                                    "id", "episode_id", "episodeId", "series_id", "seriesId"));
                } else if (!match.optString("cmd").isEmpty()) {
                    enriched.put("stalkerCmd", match.optString("cmd"));
                }
                // When the imported title carries an explicit SxxExx code, it
                // identifies the clicked episode more reliably than a provider
                // metadata field parsed from a free-form name/title.
                if (wantedSeason != null && wantedSeason > 0) enriched.put("season", wantedSeason);
                if (wantedEpisode != null && wantedEpisode > 0) enriched.put("episode", wantedEpisode);
                ExternalPlaybackStore.save(app, enriched);
                android.util.Log.i("TiviMatePlayback", "Playback provider metadata saved");
            } catch (Exception ignored) {
                // Provider metadata is optional: playback and its existing
                // Continue Watching record remain untouched on lookup failure.
                android.util.Log.i("TiviMatePlayback", "Playback provider metadata unavailable");
            } finally {
                if (completion != null) completion.run();
            }
        });
    }

    private static JSONObject selectSeason(JSONArray seasons, Integer wantedSeason) {
        if (seasons == null || seasons.length() == 0) return null;
        if (wantedSeason != null) {
            for (int index = 0; index < seasons.length(); index++) {
                JSONObject candidate = seasons.optJSONObject(index);
                if (candidate == null) continue;
                Integer number = parseFirstInteger(providerText(candidate,
                        "season", "season_number", "seasonNumber", "season_num", "name", "title"));
                if (wantedSeason.equals(number)) return candidate;
            }
            int index = wantedSeason - 1;
            if (index >= 0 && index < seasons.length()) return seasons.optJSONObject(index);
        }
        return seasons.optJSONObject(0);
    }

    private static JSONObject selectEpisode(JSONObject season, Integer wantedEpisode) {
        if (season == null) return null;
        JSONArray episodes = season.optJSONArray("series");
        if (episodes == null || episodes.length() == 0) return null;
        if (wantedEpisode != null) {
            for (int index = 0; index < episodes.length(); index++) {
                JSONObject candidate = episodes.optJSONObject(index);
                if (candidate == null) continue;
                Integer number = parseFirstInteger(providerText(candidate,
                        "episode", "episode_number", "episodeNumber", "episode_num", "ep", "name", "title"));
                if (wantedEpisode.equals(number)) return candidate;
            }
            int index = wantedEpisode - 1;
            if (index >= 0 && index < episodes.length()) return episodes.optJSONObject(index);
        }
        return episodes.optJSONObject(0);
    }

    static String providerSourceId(String portal) {
        String normalized = portal == null ? "" : portal.trim();
        try {
            URL url = new URL(normalized);
            String path = url.getPath() == null ? "" : url.getPath()
                    .replaceFirst("(?i)/(?:c/?|portal\\.php|server/load\\.php)$", "")
                    .replaceAll("/+$", "");
            normalized = url.getProtocol().toLowerCase(Locale.ROOT) + "://"
                    + url.getHost().toLowerCase(Locale.ROOT)
                    + (url.getPort() < 0 ? "" : ":" + url.getPort()) + path;
        } catch (Exception ignored) {
            normalized = normalized.replaceAll("/+$", "").toLowerCase(Locale.ROOT);
        }
        return normalized.isEmpty() ? "" : ExternalPlaybackStore.id("tivimate-stalker-provider\n" + normalized);
    }

    private static String providerAccountId(String portal, String mac) {
        String source = providerSourceId(portal);
        String normalizedMac = mac == null ? "" : mac.trim().toLowerCase(Locale.ROOT);
        return source.isEmpty() || normalizedMac.isEmpty() ? ""
                : ExternalPlaybackStore.id("tivimate-stalker-account\n" + source + "\n" + normalizedMac);
    }

    private static void rememberStalkerProvider(Context context, String portal, String mac) {
        if (context == null || portal == null || portal.trim().isEmpty()
                || mac == null || mac.trim().isEmpty()) return;
        String source = providerAccountId(portal, mac);
        if (source.isEmpty()) return;
        context.getSharedPreferences("home_settings", 0).edit()
                .putString(PROVIDER_PORTAL_PREFIX + source, portal)
                .putString(PROVIDER_MAC_PREFIX + source, mac)
                .apply();
    }

    private static List<StalkerProvider> stalkerProviders(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences("home_settings", 0);
        LinkedHashMap<String, StalkerProvider> providers = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(PROVIDER_PORTAL_PREFIX) || !(entry.getValue() instanceof String)) continue;
            String account = key.substring(PROVIDER_PORTAL_PREFIX.length());
            String portal = (String) entry.getValue();
            String mac = prefs.getString(PROVIDER_MAC_PREFIX + account, "");
            if (portal.isEmpty() || mac.isEmpty() || !account.equals(providerAccountId(portal, mac))) continue;
            providers.put(account, new StalkerProvider(portal, mac));
        }
        String currentPortal = prefs.getString(PORTAL, "");
        String currentMac = prefs.getString(MAC, "");
        if (!currentPortal.isEmpty() && !currentMac.isEmpty()) {
            StalkerProvider current = new StalkerProvider(currentPortal, currentMac);
            String account = providerAccountId(currentPortal, currentMac);
            if (!account.isEmpty()) providers.put(account, current);
        }
        return new ArrayList<>(providers.values());
    }

    private static StalkerProvider providerForSource(Context context, String source, String account) {
        String requestedSource = source == null ? "" : source;
        String requestedAccount = account == null ? "" : account;
        if (requestedSource.isEmpty() && requestedAccount.isEmpty()) return null;
        if (!requestedAccount.isEmpty()) {
            for (StalkerProvider provider : stalkerProviders(context)) {
                if (requestedAccount.equals(provider.account)
                        && (requestedSource.isEmpty() || requestedSource.equals(provider.source)
                        || requestedSource.equals(provider.account))) {
                    return provider;
                }
            }
            return null;
        }
        StalkerProvider match = null;
        for (StalkerProvider provider : stalkerProviders(context)) {
            if (!requestedSource.equals(provider.source) && !requestedSource.equals(provider.account)) continue;
            if (match != null && !match.mac.equalsIgnoreCase(provider.mac)) return null;
            match = provider;
        }
        return match;
    }

    static String streamCommand(String command) {
        String value = command == null ? "" : command.trim();
        return value.startsWith("ffmpeg ") ? value.substring(7).trim() : value;
    }

    private static JSONArray providerData(JSONObject response) {
        JSONObject js = response == null ? null : response.optJSONObject("js");
        return js == null ? null : js.optJSONArray("data");
    }

    private static String[] openProviderSession(String portal, String mac) {
        for (String endpoint : stalkerEndpoints(portal, "")) {
            try {
                JSONObject handshake = request(endpoint, mac,
                        "type=stb&action=handshake&token=&JsHttpRequest=1-xml", null).optJSONObject("js");
                String token = handshake == null ? "" : handshake.optString("token", "");
                if (!token.isEmpty()) return new String[]{endpoint, token};
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static String[] openProviderSessionForPlayback(String portal, String mac, String endpointHint) {
        LinkedHashSet<String> endpoints = new LinkedHashSet<>();
        if (ExternalPlaybackStore.supportedUrl(endpointHint)) endpoints.add(endpointHint);
        endpoints.addAll(stalkerEndpoints(portal, ""));
        int attempts = 0;
        for (String endpoint : endpoints) {
            if (attempts++ >= 2) break;
            try {
                JSONObject handshake = request(endpoint, mac,
                        "type=stb&action=handshake&token=&JsHttpRequest=1-xml", null,
                        2500, 4000).optJSONObject("js");
                String token = handshake == null ? "" : handshake.optString("token", "");
                if (!token.isEmpty()) return new String[]{endpoint, token};
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static JSONObject request(String endpoint, String mac, String query, String token) throws Exception {
        return request(endpoint, mac, query, token, 10000, 15000);
    }

    private static JSONObject request(String endpoint, String mac, String query, String token,
                                      int connectTimeoutMs, int readTimeoutMs) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint + "?" + query).openConnection();
        connection.setInstanceFollowRedirects(false);
        try {
        connection.setConnectTimeout(connectTimeoutMs); connection.setReadTimeout(readTimeoutMs); connection.setRequestProperty("Cookie", "mac=" + enc(mac) + "; stb_lang=fr; timezone=Europe%2FParis");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (QtEmbedded; U; Linux; C)"); connection.setRequestProperty("X-User-Agent", "Model: MAG250; Link: WiFi");
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"))) {
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                if (body.length() + count > 32 * 1024 * 1024) throw new java.io.IOException("Catalogue too large");
                body.append(buffer, 0, count);
            }
        }
        String payload = body.toString().trim();
        if (!payload.startsWith("{")) {
            int start = payload.indexOf('(');
            int end = payload.lastIndexOf(')');
            if (start >= 0 && end > start) payload = payload.substring(start + 1, end).trim();
        }
        return new JSONObject(payload);
        } finally { connection.disconnect(); }
    }

    static String stalkerMacFromPlaybackUrl(String playbackUrl) {
        return queryParameter(playbackUrl, "mac");
    }

    static String stalkerPortalFromPlaybackUrl(String playbackUrl) {
        try {
            URL url = new URL(playbackUrl);
            return new URL(url.getProtocol(), url.getHost(), url.getPort(), "/").toString();
        } catch (Exception ignored) { return ""; }
    }

    static List<String> stalkerEndpoints(String portal, String playbackUrl) {
        LinkedHashSet<String> endpoints = new LinkedHashSet<>();
        try {
            URL playback = new URL(playbackUrl);
            URL root = new URL(playback.getProtocol(), playback.getHost(), playback.getPort(), "/");
            endpoints.add(new URL(root, "server/load.php").toString());
            endpoints.add(new URL(root, "portal.php").toString());
        } catch (Exception ignored) { }
        try {
            URL base = new URL(portal.endsWith("/") ? portal : portal + "/");
            endpoints.add(new URL(base, base.getPath().endsWith("/c/") ? "../portal.php" : "portal.php").toString());
            endpoints.add(new URL(base, base.getPath().endsWith("/c/") ? "../server/load.php" : "server/load.php").toString());
        } catch (Exception ignored) { }
        return new ArrayList<>(endpoints);
    }

    private static String queryParameter(String rawUrl, String wanted) {
        try {
            String query = new URL(rawUrl).getQuery();
            if (query == null) return "";
            for (String pair : query.split("&")) {
                String[] parts = pair.split("=", 2);
                if (URLDecoder.decode(parts[0], "UTF-8").equals(wanted)) {
                    return parts.length > 1 ? URLDecoder.decode(parts[1], "UTF-8") : "";
                }
            }
        } catch (Exception ignored) { }
        return "";
    }

    private static void copyProviderMetadata(JSONObject target, JSONObject match, JSONObject season, JSONObject episode, String fallbackTitle) throws org.json.JSONException {
        String providerTitle = firstNonEmpty(providerText(match, "name", "title", "movie_name", "series_name"), fallbackTitle);
        if (!providerTitle.isEmpty()) {
            target.put("title", providerTitle).put("providerTitle", providerTitle);
        }
        copyProviderField(target, "originalTitle", providerValue(
                new String[]{"o_name", "orig_name", "original", "original_title", "originalTitle", "original_name", "originalName", "originalname"},
                match, season, episode));
        Double tmdbId = parseNumber(providerValue(new String[]{"tmdb_id", "tmdbId"}, match, season));
        if (tmdbId != null && tmdbId > 0 && tmdbId <= Integer.MAX_VALUE && tmdbId == Math.rint(tmdbId))
            target.put("tmdbId", tmdbId.intValue());
        copyProviderField(target, "year", providerValue(
                new String[]{"year", "release_year", "releaseYear", "release_date", "releaseDate"},
                match, season, episode));
        copyProviderField(target, "genres", providerValue(
                new String[]{"genres", "genre", "category_name", "categoryName"},
                match, season, episode));
        copyProviderField(target, "description", providerValue(
                new String[]{"description", "descr", "plot", "overview", "synopsis", "desc"},
                match, season, episode));
        copyProviderField(target, "artwork", providerValue(
                new String[]{"screenshot_uri", "screenshot", "pic", "poster", "poster_url", "posterUrl", "poster_path",
                        "backdrop", "backdrop_url", "backdropUrl", "backdrop_path", "cover_big", "image", "image_url",
                        "stream_icon", "streamIcon", "icon", "cover", "cover_url"},
                episode, season, match));
        copyProviderField(target, "rating", providerValue(
                new String[]{"rating", "vote_average", "voteAverage", "imdb_rating", "imdbRating", "rate"},
                match, season, episode));
        copyProviderField(target, "cast", providerValue(
                new String[]{"actors", "actor", "cast", "stars"}, match, season, episode));
        copyProviderField(target, "director", providerValue(
                new String[]{"director", "directors"}, match, season, episode));

        Object rawDuration = providerValue(
                new String[]{"duration", "runtime", "time", "length"},
                match, season, episode);
        // Keep the playback duration in milliseconds; provider metadata can use
        // seconds, minutes or a clock string and must not overwrite that field.
        copyProviderField(target, "providerRuntime", rawDuration);
        Object explicitMinutes = providerValue(
                new String[]{"runtimeMinutes", "runtime_minutes", "duration_minutes", "durationMinutes"},
                match, season, episode);
        Integer minutes = parseRuntimeMinutes(explicitMinutes);
        Object explicitSeconds = providerValue(
                new String[]{"duration_sec", "duration_secs", "duration_seconds"}, match, season, episode);
        if (minutes == null && explicitSeconds != null) {
            Double seconds = parseNumber(explicitSeconds);
            if (seconds != null && seconds > 0) minutes = Math.max(1, (int) Math.round(seconds / 60.0));
        }
        if (minutes == null) minutes = parseRuntimeMinutes(rawDuration);
        if (minutes != null && minutes > 0) target.put("runtimeMinutes", minutes);

        String seasonNumber = providerText(episode, "season", "season_number", "seasonNumber", "season_num");
        if (seasonNumber.isEmpty()) seasonNumber = providerText(season, "season", "season_number", "seasonNumber", "season_num");
        if (seasonNumber.isEmpty()) seasonNumber = providerText(match, "season", "season_number", "seasonNumber", "season_num");
        Integer parsedSeason = parseFirstInteger(seasonNumber);
        if (parsedSeason == null) parsedSeason = parseFirstInteger(providerText(season, "name", "title"));
        if (parsedSeason != null && parsedSeason > 0) target.put("season", parsedSeason);

        String episodeNumber = providerText(episode, "episode", "episode_number", "episodeNumber", "episode_num", "ep");
        if (episodeNumber.isEmpty()) episodeNumber = providerText(season, "episode", "episode_number", "episodeNumber", "episode_num", "ep");
        if (episodeNumber.isEmpty()) episodeNumber = providerText(match, "episode", "episode_number", "episodeNumber", "episode_num", "ep");
        Integer parsedEpisode = parseFirstInteger(episodeNumber);
        if (parsedEpisode != null && parsedEpisode > 0) target.put("episode", parsedEpisode);

        String episodeTitle = providerText(episode, "episode_title", "episodeTitle", "episode_name", "episodeName", "title", "name");
        if (episodeTitle.isEmpty()) episodeTitle = providerText(season, "episode_title", "episodeTitle", "episode_name", "episodeName");
        if (!episodeTitle.isEmpty()) target.put("episodeTitle", episodeTitle);
        target.put("metadataSource", "stalker");
    }

    /** Stalker portals often expose artwork as a root-relative path. */
    private static void normalizeProviderArtwork(JSONObject target, String baseAddress) throws org.json.JSONException {
        String artwork = target.optString("artwork", "").trim();
        if (artwork.isEmpty() || ExternalPlaybackStore.supportedUrl(artwork)) return;
        try {
            URL base = new URL(baseAddress);
            String absolute = artwork.startsWith("//")
                    ? base.getProtocol() + ":" + artwork
                    : new URL(base, artwork).toString();
            if (ExternalPlaybackStore.supportedUrl(absolute)) target.put("artwork", absolute);
            else target.remove("artwork");
        } catch (Exception ignored) {
            target.remove("artwork");
        }
    }

    private static void copyProviderField(JSONObject target, String key, Object value) throws org.json.JSONException {
        if (value == null || value == JSONObject.NULL) return;
        if (value instanceof String && ((String) value).trim().isEmpty()) return;
        target.put(key, value);
    }

    private static Object providerValue(String[] aliases, JSONObject... sources) {
        if (aliases == null || sources == null) return null;
        for (JSONObject source : sources) {
            if (source == null) continue;
            for (String alias : aliases) {
                if (alias == null || !source.has(alias)) continue;
                Object value = source.opt(alias);
                if (value == null || value == JSONObject.NULL) continue;
                if (value instanceof String && ((String) value).trim().isEmpty()) continue;
                return value;
            }
        }
        return null;
    }

    private static String providerText(JSONObject source, String... aliases) {
        Object value = providerValue(aliases, source);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static void copyEpisodeDuration(JSONObject target, JSONObject providerEpisode,
                                             JSONObject previous) throws org.json.JSONException {
        target.remove("duration");
        target.remove("runtimeMinutes");
        long playedDuration = previous.optLong("duration", 0L);
        if (playedDuration > 0L && (previous.optBoolean("started") || previous.optLong("position") > 0L)) {
            target.put("duration", playedDuration)
                    .put("runtimeMinutes", Math.max(1L, Math.round(playedDuration / 60_000d)));
            return;
        }
        Integer minutes = parseRuntimeMinutes(providerValue(new String[]{
                "runtimeMinutes", "runtime_minutes", "duration_minutes", "durationMinutes"}, providerEpisode));
        if (minutes != null && minutes <= 0) minutes = null;
        if (minutes == null) {
            Double seconds = parseNumber(providerValue(new String[]{
                    "duration_sec", "duration_secs", "duration_seconds"}, providerEpisode));
            if (seconds != null && seconds > 0) minutes = Math.max(1, (int) Math.round(seconds / 60d));
        }
        if (minutes == null) minutes = parseRuntimeMinutes(providerValue(new String[]{
                "duration", "runtime", "time", "length"}, providerEpisode));
        if (minutes != null && minutes > 0) target.put("runtimeMinutes", minutes)
                .put("duration", minutes * 60_000L);
    }

    private static Integer parseRuntimeMinutes(Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) return null;
        try {
            if (text.matches("\\d+")) {
                int number = Integer.parseInt(text);
                return number >= 3600 ? number / 60 : number;
            }
            int hours = 0;
            int minutes = 0;
            int hourMarker = text.indexOf('h');
            if (hourMarker >= 0) {
                Integer parsedHours = parseFirstInteger(text.substring(0, hourMarker));
                if (parsedHours != null) hours = parsedHours;
                Integer parsedMinutes = parseFirstInteger(text.substring(hourMarker + 1));
                if (parsedMinutes != null) minutes = parsedMinutes;
                return hours * 60 + minutes;
            }
            String[] parts = text.split(":");
            if (parts.length == 3) {
                return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]) + Integer.parseInt(parts[2]) / 60;
            }
            if (parts.length == 2) return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (NumberFormatException ignored) { }
        return null;
    }

    private static Integer parseFirstInteger(String value) {
        if (value == null) return null;
        int start = -1;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isDigit(value.charAt(i))) { start = i; break; }
        }
        if (start < 0) return null;
        int end = start;
        while (end < value.length() && Character.isDigit(value.charAt(end))) end++;
        try { return Integer.parseInt(value.substring(start, end)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static Integer releaseYear(String value) {
        if (value == null) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:19|20)\\d{2}").matcher(value);
        Integer year = null;
        while (matcher.find()) year = Integer.valueOf(matcher.group());
        return year;
    }

    private static Double parseNumber(Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        try { return Double.parseDouble(String.valueOf(value).trim().replace(',', '.')); }
        catch (NumberFormatException ignored) { return null; }
    }

    static JSONObject exact(JSONArray array, String title) {
        if (array == null) return null;
        String expectedTitle = providerTitleKey(title);
        String expectedDisplayTitle = providerDisplayTitleKey(title);
        Integer expectedYear = releaseYear(title);
        JSONObject displayMatch = null;
        JSONObject normalizedMatch = null;
        boolean displayAmbiguous = false;
        boolean normalizedAmbiguous = false;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            boolean titleMatches = false;
            boolean displayTitleMatches = false;
            for (String alias : new String[]{"name", "title", "o_name", "original_title", "originalTitle", "original_name"}) {
                String candidateTitle = providerText(item, alias);
                if (expectedDisplayTitle.equals(providerDisplayTitleKey(candidateTitle))) {
                    displayTitleMatches = true;
                    titleMatches = true;
                    break;
                }
                if (expectedTitle.equals(providerTitleKey(candidateTitle))) {
                    titleMatches = true;
                }
            }
            if (!titleMatches) continue;
            Integer candidateYear = releaseYear(providerText(item, "year", "release_year", "releaseYear", "release_date", "releaseDate"));
            if (candidateYear == null) candidateYear = releaseYear(providerText(item, "name", "title", "movie_name", "series_name"));
            // Some Stalker catalogues expose the year in TiviMate's title but
            // omit it from get_ordered_list. Keep rejecting a conflicting year,
            // while allowing one unique exact-title result with no provider year.
            if (expectedYear != null && candidateYear != null && !expectedYear.equals(candidateYear)) continue;
            if (displayTitleMatches) {
                if (displayMatch != null) displayAmbiguous = true;
                else displayMatch = item;
            }
            if (normalizedMatch != null) normalizedAmbiguous = true;
            else normalizedMatch = item;
        }
        if (displayMatch != null && !displayAmbiguous) return displayMatch;
        return normalizedAmbiguous ? null : normalizedMatch;
    }

    private static String providerDisplayTitleKey(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static String providerTitleKey(String value) {
        if (value == null) return "";
        return Normalizer.normalize(providerSearchTitle(value), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static String providerSearchTitle(String value) {
        if (value == null) return "";
        String cleaned = value.replace('_', ' ').replaceAll("\\s+", " ").trim();
        String previous;
        do {
            previous = cleaned;
            cleaned = cleaned.replaceFirst("(?i)\\s*(?:[-–—|:]\\s*)?(?:[\\[(]\\s*)?s\\s*\\d{1,2}\\s*e\\s*\\d{1,3}(?:\\s*[\\])])?\\s*$", "");
            cleaned = cleaned.replaceFirst("(?i)\\s*[\\[(][^\\])]*(?:4k|8k|hdr|uhd|fhd|2160p|1080p|720p|hevc|h\\.?264|h\\.?265|dolby|atmos|web[- ]?dl|bluray|multi|vostfr|vf)[^\\])]*[\\])]\\s*$", "");
            cleaned = cleaned.replaceFirst("\\s*[\\[(]\\s*(?:19|20)\\d{2}\\s*[\\])]\\s*$", "");
            cleaned = cleaned.replaceAll("\\s+", " ").trim();
        } while (!cleaned.equals(previous));
        return cleaned;
    }
    private static String firstNonEmpty(String first, String second) { return first == null || first.isEmpty() ? (second == null ? "" : second) : first; }
    private static String enc(String value) throws Exception { return URLEncoder.encode(value == null ? "" : value, "UTF-8"); }
}

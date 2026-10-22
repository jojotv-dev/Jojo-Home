package com.jojotv.home;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Public YouTube artwork only; selecting an image never changes the playback identity. */
final class YouTubeArtwork {
    static final class Video {
        final String id, title, author, thumbnail;
        final long duration;
        Video(String id, String title, String author, String thumbnail, long duration) {
            this.id = id; this.title = title; this.author = author;
            this.thumbnail = thumbnail; this.duration = duration;
        }
    }

    static final class Frame {
        final String url;
        final int x, y, width, height;
        final long timeMs;
        Frame(String url, int x, int y, int width, int height, long timeMs) {
            this.url = url; this.x = x; this.y = y;
            this.width = width; this.height = height; this.timeMs = timeMs;
        }
    }

    static List<Video> search(String title) throws Exception {
        if (title == null || title.trim().isEmpty()) return Collections.emptyList();
        return searchResults(initialJson(read("https://www.youtube.com/results?search_query="
                + URLEncoder.encode(title.trim(), "UTF-8")), "ytInitialData"));
    }

    static JSONObject player(String videoId) throws Exception {
        if (!validVideoId(videoId)) throw new IOException("Video YouTube non identifiee");
        JSONObject player = initialJson(read("https://www.youtube.com/watch?v=" + videoId), "ytInitialPlayerResponse");
        JSONObject details = player.optJSONObject("videoDetails");
        if (details == null || !videoId.equals(details.optString("videoId")))
            throw new IOException("Video YouTube indisponible");
        return player;
    }

    static JSONObject initialJson(String html, String variable) throws Exception {
        Matcher marker = Pattern.compile("(?:(?:var\\s+)?" + Pattern.quote(variable)
                + "\\s*=|window\\[\"" + Pattern.quote(variable) + "\"\\]\\s*=)\\s*").matcher(html);
        if (!marker.find()) throw new IOException("Metadonnees YouTube indisponibles");
        return new JSONObject(new JSONTokener(html.substring(marker.end())));
    }

    static List<Video> searchResults(JSONObject data) {
        LinkedHashMap<String, Video> videos = new LinkedHashMap<>();
        collectVideos(data, videos);
        return new ArrayList<>(videos.values());
    }

    private static void collectVideos(Object node, LinkedHashMap<String, Video> videos) {
        if (videos.size() >= 10) return;
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject video = object.optJSONObject("videoRenderer");
            if (video != null) {
                String id = video.optString("videoId");
                String title = text(video.optJSONObject("title"));
                String thumbnail = thumbnail(video.optJSONObject("thumbnail"), id);
                if (validVideoId(id) && !title.isEmpty() && !thumbnail.isEmpty())
                    videos.putIfAbsent(id, new Video(id, title, text(video.optJSONObject("ownerText")),
                            thumbnail, duration(text(video.optJSONObject("lengthText")))));
            }
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) collectVideos(object.opt(keys.next()), videos);
        } else if (node instanceof JSONArray) {
            JSONArray values = (JSONArray) node;
            for (int index = 0; index < values.length(); index++) collectVideos(values.opt(index), videos);
        }
    }

    static Video video(JSONObject player) {
        JSONObject details = player.optJSONObject("videoDetails");
        if (details == null || !validVideoId(details.optString("videoId"))) return null;
        return new Video(details.optString("videoId"), details.optString("title"), details.optString("author"),
                thumbnail(details.optJSONObject("thumbnail"), details.optString("videoId")), details.optLong("lengthSeconds", 0L) * 1_000L);
    }

    static String exactVideoId(List<Video> videos, String title, String author, long duration) {
        String result = null;
        for (Video video : videos) {
            if (!NotificationResumeRules.matchesYoutubeVideo(title, author, duration,
                    video.title, video.author, video.duration)) continue;
            if (result != null && !result.equals(video.id)) return null;
            result = video.id;
        }
        return result;
    }

    static List<Frame> frames(JSONObject player, String videoId) {
        JSONObject details = player.optJSONObject("videoDetails");
        JSONObject storyboards = player.optJSONObject("storyboards");
        JSONObject renderer = storyboards == null ? null : storyboards.optJSONObject("playerStoryboardSpecRenderer");
        if (details == null || !validVideoId(videoId) || !videoId.equals(details.optString("videoId")) || renderer == null)
            return Collections.emptyList();
        String[] levels = renderer.optString("spec").split("\\|");
        long duration = details.optLong("lengthSeconds", 0L) * 1_000L;
        if (levels.length < 2 || duration <= 0L) return Collections.emptyList();
        int best = -1;
        long bestArea = 0L;
        for (int level = 1; level < levels.length; level++) {
            try {
                String[] fields = levels[level].split("#", -1);
                if (fields.length != 8) continue;
                int width = Integer.parseInt(fields[0]), height = Integer.parseInt(fields[1]);
                int count = Integer.parseInt(fields[2]), columns = Integer.parseInt(fields[3]), rows = Integer.parseInt(fields[4]);
                long area = (long) width * height;
                if (width < 1 || width > 2_048 || height < 1 || height > 2_048 || count < 1 || count > 1_000_000 || columns < 1 || columns > 25 || rows < 1 || rows > 25
                        || area * columns * rows > 8_000_000L || area <= bestArea) continue;
                best = level; bestArea = area;
            } catch (NumberFormatException ignored) { }
        }
        if (best < 0) return Collections.emptyList();
        String[] fields = levels[best].split("#", -1);
        int width = Integer.parseInt(fields[0]), height = Integer.parseInt(fields[1]);
        int count = Integer.parseInt(fields[2]), columns = Integer.parseInt(fields[3]), rows = Integer.parseInt(fields[4]);
        String base = levels[0].replace("$L", Integer.toString(best - 1)).replace("$N", fields[6]);
        ArrayList<Frame> result = new ArrayList<>();
        int choices = Math.min(10, count);
        for (int choice = 0; choice < choices; choice++) {
            int frame = Math.min(count - 1, (int) ((long) (choice + 1) * count / (choices + 1)));
            int perSheet = columns * rows;
            String url = base.replace("$M", Integer.toString(frame / perSheet))
                    + (base.contains("?") ? "&" : "?") + "sigh=" + fields[7];
            if (!imageUrl(url, "/sb/" + videoId + "/")) return Collections.emptyList();
            int tile = frame % perSheet;
            result.add(new Frame(url, tile % columns * width, tile / columns * height, width, height,
                    (long) frame * duration / count));
        }
        return result;
    }

    private static String thumbnail(JSONObject value, String videoId) {
        JSONArray images = value == null ? null : value.optJSONArray("thumbnails");
        String result = "";
        long largest = -1L;
        if (images != null) for (int index = 0; index < images.length(); index++) {
            JSONObject image = images.optJSONObject(index);
            if (image == null) continue;
            String url = image.optString("url");
            long size = (long) image.optInt("width", 0) * image.optInt("height", 0);
            if (size > largest && (imageUrl(url, "/vi/" + videoId + "/")
                    || imageUrl(url, "/vi_webp/" + videoId + "/"))) { result = url; largest = size; }
        }
        return result;
    }

    private static boolean imageUrl(String value, String pathPrefix) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme()) && host != null
                    && (host.equalsIgnoreCase("ytimg.com") || host.toLowerCase(java.util.Locale.ROOT).endsWith(".ytimg.com"))
                    && uri.getPath() != null && uri.getPath().startsWith(pathPrefix);
        } catch (Exception ignored) { return false; }
    }

    private static boolean validVideoId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{11}");
    }

    private static String text(JSONObject value) {
        if (value == null) return "";
        String simple = value.optString("simpleText");
        if (!simple.isEmpty()) return simple;
        StringBuilder result = new StringBuilder();
        JSONArray runs = value.optJSONArray("runs");
        if (runs != null) for (int index = 0; index < runs.length(); index++) {
            JSONObject run = runs.optJSONObject(index);
            if (run != null) result.append(run.optString("text"));
        }
        return result.toString();
    }

    private static long duration(String text) {
        try {
            String[] parts = text.split(":");
            if (parts.length < 2 || parts.length > 3) return -1L;
            long seconds = 0L;
            for (String part : parts) seconds = seconds * 60L + Long.parseLong(part);
            return seconds * 1_000L;
        } catch (NumberFormatException ignored) { return -1L; }
    }

    private static String read(String url) throws IOException {
        return new String(download(url, 5 * 1_024 * 1_024), java.nio.charset.StandardCharsets.UTF_8);
    }

    static byte[] download(String url, int limit) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(5_000); connection.setReadTimeout(7_000);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");
        connection.setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9");
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8_192]; int read;
            while ((read = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted() || output.size() + read > limit)
                    throw new IOException("Reponse YouTube interrompue ou trop volumineuse");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally { connection.disconnect(); }
    }
}

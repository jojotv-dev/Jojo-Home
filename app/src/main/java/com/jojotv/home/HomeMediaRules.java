package com.jojotv.home;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure identity, artwork, order and duration rules shared by Home and the player.
 * Duration methods are public so Kotlin player code can reuse the same conversions. */
public final class HomeMediaRules {
    private HomeMediaRules() {}

    public static final int TITLE_LOGO_POLICY_VERSION = 3;
    public static final int TMDB_PEOPLE_POLICY_VERSION = 1;

    public static boolean isTmdbPresenter(String job) {
        return "Presenter".equalsIgnoreCase(mediaMetadataText(job));
    }

    /** Explicit TV presentation credits take precedence over episode guests, never movie cast. */
    public static <T> java.util.List<T> preferredTmdbPeople(String mediaType,
            java.util.List<T> cast, java.util.List<T> presenters) {
        return "tv".equalsIgnoreCase(mediaType) && !presenters.isEmpty() ? presenters : cast;
    }

    public static boolean currentTmdbPeoplePolicy(String mediaType, int version) {
        return !"tv".equalsIgnoreCase(mediaType) || version >= TMDB_PEOPLE_POLICY_VERSION;
    }

    public static String mediaMetadataText(String value) {
        String text = value == null ? "" : value.trim();
        String marker = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (marker.matches("n\\s*[/,.]\\s*a\\.?|null|undefined|none|unknown|-+|not available")
                || marker.equals("non disponible") || marker.equals("non renseigné")
                || marker.equals("non renseigne") || marker.equals("pas d'information")
                || marker.equals("[]") || marker.equals("{}")) return "";
        return text;
    }

    public static java.util.List<String> mediaMetadataNames(String value) {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (String part : mediaMetadataText(value).split("[,;|/]+")) {
            String name = mediaMetadataText(part);
            if (!name.isEmpty() && !names.contains(name)) names.add(name);
        }
        return names;
    }

    /** Resume pages come first; their count is also the index of Suggestions. */
    static int homeHeroPageCount(int resumeCount) {
        return Math.max(0, resumeCount) + 1;
    }

    static int homeHeroNextPage(int selectedPage, int resumeCount, int direction) {
        return Math.floorMod(selectedPage + direction, homeHeroPageCount(resumeCount));
    }

    /** Rows visible at once; the remaining suggestions scroll through this viewport. */
    static int homeSuggestionRowCount(int availableHeightDp, float fontScale) {
        int minimumRowHeight = Math.round(80 * Math.max(1f, fontScale));
        return Math.max(1, Math.min(3, (availableHeightDp + 8) / (minimumRowHeight + 8)));
    }

    static int homeSuggestionArtworkWidthDp(int cardWidthDp, int cardHeightDp) {
        return Math.max(1, Math.min(128, Math.min(Math.round(cardWidthDp * .42f),
                Math.max(1, cardHeightDp - 12) * 16 / 9)));
    }

    static int homeSuggestionTargetColumn(int[] counts, int row, int preferredColumn) {
        int target = -1, distance = counts.length;
        for (int column = 0; column < counts.length; column++) {
            if ((row == 0 || row < counts[column]) && Math.abs(column - preferredColumn) < distance) {
                target = column;
                distance = Math.abs(column - preferredColumn);
            }
        }
        return target;
    }

    private static String titleLogoLanguage(String language) {
        return language == null ? "" : language.trim().toLowerCase(Locale.ROOT)
                .replace('_', '-').split("-", 2)[0];
    }

    public static String titleLogoLanguages(String originalLanguage) {
        String original = titleLogoLanguage(originalLanguage);
        return original.isEmpty() ? "fr,en,null" : "en".equals(original) ? "en,null" : original + ",null,en";
    }

    public static String titleLogoCacheKey(String mediaType, int tmdbId) {
        String type = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT);
        if ("series".equals(type)) type = "tv";
        if (tmdbId <= 0 || !("movie".equals(type) || "tv".equals(type))) return "";
        return "tmdb_title_logo_original_v" + TITLE_LOGO_POLICY_VERSION + "_" + type + "_" + tmdbId;
    }

    private static final Pattern ECHAPPEES_BELLES_LEGACY_LOGO = Pattern.compile(
            "https://(?:image\\.tmdb\\.org|media\\.themoviedb\\.org)/t/p/[^/?#]+/"
                    + "(?:1xEwnnPnJmbagALIKZQ0S8asYJt|27Wr1MynP4OPe7oWG6wkOyYHnrn|9wBckds6BaGBgjZCVtn5b2URgD5)\\.png(?:[?#].*)?");

    public static String canonicalTitleLogoUrl(String url) {
        if (url == null) return "";
        // TMDB lists Cantal episode editions alongside the programme's logo.
        // Use the generic, high-resolution logo, including for previously cached URLs.
        return ECHAPPEES_BELLES_LEGACY_LOGO.matcher(url).matches()
                ? "https://image.tmdb.org/t/p/original/cEOIzbsAD6zM96P22Q1gUKHk6Z4.png" : url;
    }

    public static String preferredTitleLogoUrl(String existing, String fallback) {
        return canonicalTitleLogoUrl(existing == null || existing.trim().isEmpty() ? fallback : existing);
    }

    public static String originalTitleLogoUrl(java.util.Map<String, String> logos, String originalLanguage) {
        String original = titleLogoLanguage(originalLanguage);
        String selected = "";
        int bestRank = Integer.MAX_VALUE;
        for (java.util.Map.Entry<String, String> logo : logos.entrySet()) {
            String path = logo.getKey();
            if (path == null || !path.startsWith("/")) continue;
            String language = titleLogoLanguage(logo.getValue());
            int rank = !original.isEmpty() && original.equals(language) ? 0 : language.isEmpty() ? 1
                    : "en".equals(language) ? 2 : "fr".equals(language) ? 3 : 4;
            // API ordering and requested display size must not pick different
            // artwork for the same title. Prefer a vector, then a stable path.
            if (rank < bestRank || rank == bestRank && (selected.isEmpty()
                    || path.endsWith(".svg") && !selected.endsWith(".svg")
                    || path.endsWith(".svg") == selected.endsWith(".svg") && path.compareTo(selected) < 0)) {
                selected = path;
                bestRank = rank;
            }
        }
        return selected.isEmpty() ? "" : canonicalTitleLogoUrl("https://image.tmdb.org/t/p/original" + selected);
    }

    public static final String HOME_VIDEO_LIST_PREFIX = "home-video-list|";

    public static String homeVideoListDismissalKey(String path) {
        return path == null || path.isEmpty() ? "" : HOME_VIDEO_LIST_PREFIX + path;
    }

    public static boolean homeVideoListKeyMatches(String key, String path) {
        if (key == null || !key.startsWith(HOME_VIDEO_LIST_PREFIX) || path == null || path.isEmpty()) return false;
        String mask = key.substring(HOME_VIDEO_LIST_PREFIX.length());
        String expected = comparableVideoPath(mask);
        String actual = comparableVideoPath(path);
        return !expected.isEmpty() && (expected.equals(actual)
                || mask.endsWith("/") && actual.startsWith(expected + "/"));
    }

    static boolean hiddenHomeVideo(String path, java.util.Collection<String> hidden) {
        for (String key : hidden) if (homeVideoListKeyMatches(key, path)) return true;
        return false;
    }

    static String homeVideoListPath(String key) {
        return key != null && key.startsWith(HOME_VIDEO_LIST_PREFIX)
                ? key.substring(HOME_VIDEO_LIST_PREFIX.length()) : "";
    }

    static java.util.Set<String> homeVideoListRestorationKeys(String selectedKey,
            java.util.Collection<String> hidden) {
        java.util.Set<String> restored = new java.util.HashSet<>();
        String path = homeVideoListPath(selectedKey);
        if (path.isEmpty()) return restored;
        String expected = comparableVideoPath(path);
        for (String key : hidden) {
            String candidate = homeVideoListPath(key);
            if (!candidate.isEmpty() && expected.equals(comparableVideoPath(candidate))) restored.add(key);
        }
        return restored;
    }

    private static String comparableVideoPath(String path) {
        String decoded = path;
        try { decoded = java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8"); }
        catch (IllegalArgumentException | java.io.UnsupportedEncodingException ignored) { }
        decoded = java.text.Normalizer.normalize(decoded, java.text.Normalizer.Form.NFC)
                .replaceFirst("^file://", "").replaceAll("/+$", "");
        return decoded.startsWith("smb://") ? decoded.toLowerCase(Locale.ROOT) : decoded;
    }

    static boolean nativeIptvMovieResume(String contentId, String contentType) {
        return contentId != null && contentId.startsWith("iptv:") && "movie".equalsIgnoreCase(contentType);
    }

    static boolean nativeIptvVodResume(String contentId, String contentType) {
        return nativeIptvMovieResume(contentId, contentType)
                || (contentId != null && contentId.startsWith("iptv:") && "series".equalsIgnoreCase(contentType));
    }

    static boolean completedEpisodeResume(boolean hasEpisode, boolean watched, float progressFraction) {
        return hasEpisode && (watched || progressFraction
                >= com.nuvio.tv.domain.model.WatchProgress.EPISODE_COMPLETED_THRESHOLD);
    }

    static boolean thumbnailInProgress(long position, long duration, boolean completed) {
        return !completed && position > 0L && (duration <= 0L || position < duration);
    }

    public static java.time.LocalDate episodeAirDate(String value) {
        if (value == null || value.trim().length() < 10) return null;
        try { return java.time.LocalDate.parse(value.trim().substring(0, 10)); }
        catch (java.time.format.DateTimeParseException ignored) { return null; }
    }

    public static String seriesEpisodeLabel(int season, int episode, String airDate, java.time.LocalDate today) {
        String date = seriesEpisodeDateBadge(airDate, today);
        if (!date.isEmpty()) return "Diffusion le " + date;
        return String.format(Locale.ROOT, "S%02dE%02d", season, episode);
    }

    public static String seriesEpisodeDateBadge(String airDate, java.time.LocalDate today) {
        java.time.LocalDate date = episodeAirDate(airDate);
        if (date != null && date.isAfter(today)) {
            return date.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE));
        }
        return "";
    }

    public static boolean ongoingSeries(String status, boolean inProduction) {
        if ("Ended".equalsIgnoreCase(status) || "Canceled".equalsIgnoreCase(status)
                || "Cancelled".equalsIgnoreCase(status)) return false;
        return inProduction || "Returning Series".equalsIgnoreCase(status)
                || "In Production".equalsIgnoreCase(status) || "Planned".equalsIgnoreCase(status)
                || "Pilot".equalsIgnoreCase(status);
    }

    public static String seriesStatusLabel(String status, boolean inProduction) {
        String value = status == null ? "" : status.trim();
        if ("Ended".equalsIgnoreCase(value) || "Canceled".equalsIgnoreCase(value)
                || "Cancelled".equalsIgnoreCase(value) || "Terminé".equalsIgnoreCase(value)) return "TERMINÉ";
        if (ongoingSeries(value, inProduction) || "En cours".equalsIgnoreCase(value)) return "EN COURS";
        return "";
    }

    public static String seriesStatusBadgeText(String label, int seasonCount) {
        if (label == null || label.isEmpty()) return "";
        String seasons = seasonCount > 0 ? seasonCount + (seasonCount == 1 ? " SAISON  " : " SAISONS  ") : "";
        return seasons + "│  " + ("EN COURS".equals(label) ? "1 EN COURS" : label);
    }

    public static String seasonStatusLabel(int expectedEpisodes, java.util.Collection<String> airDates,
            java.time.LocalDate today) {
        if (expectedEpisodes <= 0 || airDates == null || airDates.size() != expectedEpisodes) return "";
        boolean aired = false;
        boolean upcoming = false;
        for (String value : airDates) {
            java.time.LocalDate date = episodeAirDate(value);
            if (date == null) return "";
            if (date.isAfter(today)) upcoming = true;
            else aired = true;
        }
        if (!aired) return "À VENIR";
        return upcoming ? "EN COURS" : "TERMINÉ";
    }

    public static boolean announcedSeriesEpisode(int season, int episode, String airDate,
            int lastProviderSeason, int lastProviderEpisode, java.time.LocalDate today) {
        java.time.LocalDate date = episodeAirDate(airDate);
        if (season <= 0 || episode <= 0 || date == null) return false;
        // Retain an announced episode after its date until the provider actually supplies it.
        return !date.isBefore(today) || (lastProviderSeason > 0
                && (season > lastProviderSeason || (season == lastProviderSeason && episode > lastProviderEpisode)));
    }

    static boolean persistentResumeTimestamp(String key) {
        return key.startsWith("home_resume_removed_p") || key.startsWith("home_resume_restored_p")
                || key.startsWith("video_resume_invalidated_");
    }

    static boolean continueWatchingCachePreference(String key, boolean manuallySelectedArtwork) {
        if (key == null) return false;
        if (key.equals("continue_watching_home_cache") || key.startsWith("resume_synopsis_")
                || key.startsWith("freebox_video_hero_metadata_") || key.startsWith("video_player_metadata_")
                || key.startsWith("video_artwork_tmdb_")) return true;
        return !manuallySelectedArtwork && key.matches("resume_artwork_[0-9a-f]{1,8}");
    }

    static boolean continueWatchingCacheFile(String name) {
        return name != null && (name.matches("resume_artwork_[0-9a-f]{1,8}(?:_landscape)?\\.png")
                || name.matches("home_hero_preview_[0-9a-f]{1,8}\\.png(?:\\.tmp)?"));
    }

    static java.util.Set<String> mergeResumeDismissals(java.util.Set<String> local,
            java.util.Set<String> remote, java.util.Map<String, Long> removals,
            java.util.Map<String, Long> restorations) {
        java.util.Set<String> result = new java.util.HashSet<>(local);
        result.addAll(remote);
        // Only an explicit restoration or new playback can lift an individual dismissal.
        // A newer snapshot of the entire set is not deletion evidence.
        result.removeIf(key -> restorations.getOrDefault(key, 0L) > removals.getOrDefault(key, 0L));
        return result;
    }

    static <T> java.util.Map<String, T> mergeDeviceAppOrders(java.util.Map<String, T> local,
            java.util.Map<String, T> remote) {
        java.util.Map<String, T> result = new java.util.LinkedHashMap<>(remote);
        // Historical blobs have no per-device clocks. Never replace the
        // current installation's saved order with such an older snapshot.
        result.putAll(local);
        return result;
    }

    static String titleYear(String title) {
        Matcher year = Pattern.compile("(?<!\\d)(?:19|20)\\d{2}(?!\\d)").matcher(title == null ? "" : title);
        return year.find() ? year.group() : "";
    }

    public static String programmeTitleLogoFallback(String title, int tmdbId, String mediaType) {
        String type = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT);
        if (!(type.isEmpty() || "unknown".equals(type) || "tv".equals(type) || "series".equals(type))) return "";
        boolean identifiedProgramme = tmdbId == 114323 && ("tv".equals(type) || "series".equals(type));
        if (tmdbId > 0 && !identifiedProgramme) return "";
        String programme = java.text.Normalizer.normalize(recurringProgrammeName(title),
                java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceFirst("\\.(?:3g2|3gp|avi|flv|m2ts|m4v|mkv|mov|mp4|mpeg|mpg|mts|ts|webm|wmv)$", "")
                .replaceAll("[^a-z0-9]", "");
        if (!identifiedProgramme && !"yaquelaveritequicompte".equals(programme)) return "";
        // TMDB 114323 has no separate logo. M6+ publishes this transparent
        // programme logo; keep it remote and share it across Home and playback.
        return "https://images-fio.6play.fr/v2/images/4697204/raw";
    }

    static String recurringProgrammeName(String name) {
        if (name == null) return "";
        // Remove airing dates before copy/year cleanup can consume part of them.
        String recordingDate = "\\b(?:(?:19|20)\\d{2}[-\\s._/]+\\d{1,2}[-\\s._/]+\\d{1,2}"
                + "|\\d{1,2}[-\\s._/]+\\d{1,2}[-\\s._/]+(?:19|20)?\\d{2}|\\d{8})\\b";
        return name.replace('_', ' ')
                .replaceFirst("(?:\\s+|[.])(?:[-–—:]\\s*)?(?:\\(\\s*)?" + recordingDate + ".*$", "")
                .replaceAll(recordingDate, " ")
                .replaceAll("(?i)\\b\\d{1,2}\\s*[h:.]\\s*\\d{2}\\b", " ");
    }

    static String recurringProgrammeArtworkIdentity(String name) {
        String title = recurringProgrammeName(name)
                .replaceFirst("(?i)\\.(?:3g2|3gp|avi|flv|m2ts|m4v|mkv|mov|mp4|mpeg|mpg|mts|ts|webm|wmv)(?:\\.\\d+)?$", "")
                .replaceFirst("(?:\\.\\d+|\\s+\\(\\d{1,3}\\))$", "");
        return java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    static java.util.List<String> providerArtworkSearchTitles(String title) {
        if (title == null) return java.util.Collections.emptyList();
        // A provider title is not a filename: "187" and "2049" are part of the work's identity.
        String cleaned = title.replaceAll("(?i)\\s*[\\[(][^\\])]*(?:4k|8k|sdr|hdr|uhd|fhd|2160p|1080p|720p|hevc|h\\.?26[45]|dolby|atmos|web[- ]?dl|bluray|multi|vostfr|vf)[^\\])]*[\\])]", " ")
                .replaceFirst("(?iu)\\s*(?:[-–—|:·]\\s*)?\\b(?:s\\s*\\d{1,2}\\s*e\\s*\\d{1,3}|\\d{1,2}\\s*x\\s*\\d{1,3})\\b.*$", "")
                .replaceFirst("(?iu)\\s*(?:[-–—|:·]\\s*)?\\b(?:s|saison|season)\\s*\\d{1,2}\\s*$", "")
                .replaceAll("\\(\\s*(?:19|20)\\d{2}\\s*\\)", " ")
                .replaceAll("\\s+", " ").trim();
        java.util.LinkedHashSet<String> titles = new java.util.LinkedHashSet<>();
        for (String part : cleaned.split("\\s+\\|\\s+")) if (!part.trim().isEmpty()) titles.add(part.trim());
        return new java.util.ArrayList<>(titles);
    }

    static String providerArtworkYear(String year, String title) {
        String value = year == null ? "" : year.trim();
        if (value.length() >= 4 && value.substring(0, 4).matches("(?:19|20)\\d{2}")) return value.substring(0, 4);
        Matcher matcher = Pattern.compile("\\(\\s*((?:19|20)\\d{2})\\s*\\)").matcher(title == null ? "" : title);
        return matcher.find() ? matcher.group(1) : "";
    }

    static boolean providerArtworkIdentityMatches(String type, int id, String year,
                                                   String candidateType, int candidateId, String candidateYear) {
        if (!type.isEmpty() && !type.equals(candidateType)) return false;
        if (id > 0) return !type.isEmpty() && id == candidateId;
        return year.isEmpty() || year.equals(candidateYear);
    }

    static java.util.List<String> recordingArtworkSearchTitles(String title) {
        java.util.LinkedHashSet<String> titles = new java.util.LinkedHashSet<>();
        String exact = title == null ? "" : title.trim();
        if (exact.isEmpty()) return new java.util.ArrayList<>(titles);
        titles.add(exact);
        String recording = recurringProgrammeName(exact).replaceAll("\\s+", " ").trim();
        if (recording.length() >= 3) titles.add(recording);
        // Recordings can append an episode title that TMDB does not index.
        String programme = recording.replaceFirst("\\s*\\(.*$", "").trim();
        if (programme.length() >= 3) titles.add(programme);
        programme = programme.replaceFirst("\\s+[-–—|]\\s+.*$|\\s*:\\s*.+$", "").trim();
        if (programme.length() >= 3) titles.add(programme);
        return new java.util.ArrayList<>(titles);
    }

    static int[] videoArtworkCrop(int width, int height, boolean portrait) {
        double ratio = portrait ? 2d / 3d : 16d / 9d;
        int cropWidth = Math.min(width, Math.max(1, (int) Math.round(height * ratio)));
        int cropHeight = Math.min(height, Math.max(1, (int) Math.round(width / ratio)));
        return new int[] { (width - cropWidth) / 2, (height - cropHeight) / 2, cropWidth, cropHeight };
    }

    static boolean freeboxMetadataMatches(String mediaType, String expectedYear, String candidateYear,
                                          int durationMinutes, int runtimeMinutes,
                                          boolean hasSelectedArtwork, boolean artworkMatches,
                                          boolean selectedIdentity) {
        // A saved TMDB id does not prove that the currently chosen image belongs to it.
        if (hasSelectedArtwork && !artworkMatches) return false;
        if (!expectedYear.isEmpty()) {
            if (candidateYear == null || candidateYear.isEmpty()) return false;
            if ("movie".equals(mediaType) && !expectedYear.equals(candidateYear)) return false;
            // An airing year is not a show's premiere year.
            if ("tv".equals(mediaType) && candidateYear.compareTo(expectedYear) > 0) return false;
        }
        if ("tv".equals(mediaType) && (selectedIdentity || artworkMatches)) return true;
        if (durationMinutes > 0) {
            if ("movie".equals(mediaType) && runtimeMinutes <= 0) return false;
            if (runtimeMinutes > 0 && Math.abs(runtimeMinutes - durationMinutes) > 12) return false;
        }
        return selectedIdentity || artworkMatches || !"movie".equals(mediaType)
                || !expectedYear.isEmpty() || durationMinutes > 0;
    }

    static boolean freeboxMetadataTitleMatches(String normalizedTitle, String normalizedCandidate) {
        return normalizedTitle != null && !normalizedTitle.isEmpty()
                && normalizedTitle.equals(normalizedCandidate);
    }

    static String seriesEpisodeArtwork(String providerArtwork, boolean genericArtwork, String stillPath) {
        String artwork = mediaMetadataText(providerArtwork);
        String still = mediaMetadataText(stillPath);
        if ((artwork.isEmpty() || genericArtwork) && still.startsWith("/") && !still.startsWith("//"))
            return "https://image.tmdb.org/t/p/w780" + still;
        return artwork;
    }

    static boolean artworkMatches(String artwork, Iterable<String> paths) {
        if (artwork == null || artwork.isEmpty()) return false;
        String url = artwork.split("[?#]", 2)[0];
        for (String path : paths) {
            if (path != null && !path.isEmpty() && url.endsWith(path)) return true;
        }
        return false;
    }

    static int heroRuntimeMinutes(String value) {
        return providerRuntimeMinutes(value);
    }

    public static int providerRuntimeMinutes(String value) {
        if (value == null) return 0;
        String text = value.trim().toLowerCase(Locale.ROOT).replace(',', '.');
        Matcher clock = Pattern.compile("^(\\d+):([0-5]\\d)(?::([0-5]\\d))?$").matcher(text);
        Matcher hours = Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*h(?:\\s*(\\d{1,2})\\s*(?:m(?:in(?:utes?)?)?)?)?$").matcher(text);
        Matcher minutes = Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*(?:m(?:in(?:utes?)?)?)?$").matcher(text);
        double duration;
        if (clock.matches()) {
            duration = Double.parseDouble(clock.group(1)) * 60d + Integer.parseInt(clock.group(2))
                    + (clock.group(3) == null ? 0d : Integer.parseInt(clock.group(3)) / 60d);
        } else if (hours.matches()) {
            duration = Double.parseDouble(hours.group(1)) * 60d
                    + (hours.group(2) == null ? 0d : Integer.parseInt(hours.group(2)));
        } else if (minutes.matches()) {
            duration = Double.parseDouble(minutes.group(1));
        } else return 0;
        return Double.isFinite(duration) && duration > 0d && duration <= Integer.MAX_VALUE
                ? Math.max(1, (int) Math.round(duration)) : 0;
    }

    public static int playbackRuntimeMinutes(long durationMs) {
        if (durationMs <= 0L) return 0;
        long minutes = durationMs / 60_000L;
        return minutes <= Integer.MAX_VALUE ? (int) minutes : 0;
    }

    public static int externalRuntimeMinutes(long durationMs, int cachedMinutes, String providerRuntime) {
        int minutes = playbackRuntimeMinutes(durationMs);
        if (minutes > 0) return minutes;
        minutes = externalProviderRuntimeMinutes(providerRuntime);
        return minutes > 0 ? minutes : Math.max(0, cachedMinutes);
    }

    private static int externalProviderRuntimeMinutes(String value) {
        if (value == null) return 0;
        String text = value.trim().replace(',', '.');
        Matcher clock = Pattern.compile("^(\\d+):([0-5]\\d)(?::([0-5]\\d))?$").matcher(text);
        double minutes;
        if (clock.matches()) {
            minutes = Double.parseDouble(clock.group(1)) * 60d + Integer.parseInt(clock.group(2));
        } else try {
            double numeric = Double.parseDouble(text);
            // Preserve legacy external-provider seconds; playback duration itself is always milliseconds.
            minutes = numeric > 600d ? numeric / 60d : numeric;
        } catch (NumberFormatException ignored) {
            return providerRuntimeMinutes(value);
        }
        return Double.isFinite(minutes) && minutes > 0d && minutes <= Integer.MAX_VALUE ? (int) minutes : 0;
    }

    static boolean isLowerFocusRow(int focusedTop, int focusedHeight, int candidateTop, int candidateHeight) {
        // Focus scaling shifts a card's top slightly above its neighbours.
        // Treat vertically overlapping controls as the same row.
        int overlapTolerance = Math.min(focusedHeight, candidateHeight) / 2;
        return candidateTop >= focusedTop + focusedHeight - overlapTolerance
                && candidateTop + candidateHeight / 2 > focusedTop + focusedHeight / 2;
    }

    /** Keep focus immediate and render only the last selection in the next frame. */
    static final class FocusUpdate implements Runnable {
        private final java.util.function.BooleanSupplier current;
        private final Runnable update;
        private final java.util.function.Consumer<Runnable> nextFrame;
        private boolean pending;

        FocusUpdate(java.util.function.BooleanSupplier current, Runnable update,
                    java.util.function.Consumer<Runnable> nextFrame) {
            this.current = current;
            this.update = update;
            this.nextFrame = nextFrame;
        }

        @Override public void run() {
            if (pending) return;
            pending = true;
            nextFrame.accept(this::flush);
        }

        void flush() {
            if (!pending) return;
            pending = false;
            if (current.getAsBoolean()) update.run();
        }
    }

    /** Publish the first cards now, then yield between small, cancellable batches. */
    static void renderInBatches(int total, java.util.function.BooleanSupplier current,
                                java.util.function.IntConsumer append,
                                java.util.function.Consumer<Runnable> nextFrame, Runnable afterBatch) {
        renderInBatches(total, current, append, nextFrame, afterBatch, System::nanoTime);
    }

    static void renderInBatches(int total, java.util.function.BooleanSupplier current,
                                java.util.function.IntConsumer append,
                                java.util.function.Consumer<Runnable> nextFrame, Runnable afterBatch,
                                java.util.function.LongSupplier clock) {
        new Runnable() {
            int index;
            @Override public void run() {
                if (!current.getAsBoolean()) return;
                long started = clock.getAsLong();
                int end = Math.min(total, index + 6);
                while (index < end && current.getAsBoolean()) {
                    append.accept(index++);
                    if (clock.getAsLong() - started >= 8_000_000L) break;
                }
                if (!current.getAsBoolean()) return;
                afterBatch.run();
                if (index < total) nextFrame.accept(this);
            }
        }.run();
    }

    static int compareEpisodes(String left, String right) {
        String a = filename(left), b = filename(right);
        int order = Long.compare(episode(a), episode(b));
        if (order != 0) return order;
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            if (Character.isDigit(a.charAt(i)) && Character.isDigit(b.charAt(j))) {
                int endA = i, endB = j;
                while (endA < a.length() && Character.isDigit(a.charAt(endA))) endA++;
                while (endB < b.length() && Character.isDigit(b.charAt(endB))) endB++;
                String na = a.substring(i, endA).replaceFirst("^0+(?!$)", "");
                String nb = b.substring(j, endB).replaceFirst("^0+(?!$)", "");
                order = Integer.compare(na.length(), nb.length());
                if (order == 0) order = na.compareTo(nb);
                if (order != 0) return order;
                i = endA; j = endB;
            } else {
                order = Character.compare(a.charAt(i++), b.charAt(j++));
                if (order != 0) return order;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private static String filename(String name) {
        return (name == null ? "" : name).toLowerCase(Locale.ROOT)
                .replaceFirst("\\.(mkv|mp4|ts|m2ts|avi|mov|webm)$", "").trim();
    }

    private static long episode(String value) {
        Matcher match = Pattern.compile("(?i)(?:^|[^a-z0-9])s(\\d{1,3})[ ._-]*e(\\d{1,4})(?!\\d)").matcher(value);
        if (!match.find()) match = Pattern.compile("(?i)(?:^|[^a-z0-9])(\\d{1,3})x(\\d{1,4})(?!\\d)").matcher(value);
        else return Long.parseLong(match.group(1)) * 100000L + Long.parseLong(match.group(2));
        if (match.find()) return Long.parseLong(match.group(1)) * 100000L + Long.parseLong(match.group(2));
        match = Pattern.compile("(?i)(?:^|[^a-z0-9])(?:episode|épisode|ep|e)[ ._-]*(\\d{1,4})(?!\\d)").matcher(value);
        return match.find() ? 100000L + Long.parseLong(match.group(1)) : Long.MAX_VALUE;
    }

    static java.util.List<String> liveChannelOrder(java.util.List<String> currentIds,
                                                   java.util.List<String> savedIds) {
        java.util.LinkedHashSet<String> remaining = new java.util.LinkedHashSet<>(currentIds);
        java.util.List<String> result = new java.util.ArrayList<>();
        for (String id : savedIds) if (remaining.remove(id)) result.add(id);
        result.addAll(remaining);
        return result;
    }

    static int liveGridNeighbor(int index, int count, int columns, int dx, int dy) {
        if (count <= 0 || columns <= 0 || index < 0 || index >= count) return index;
        int column = index % columns;
        if (dx < 0) return column > 0 ? index - 1 : index;
        if (dx > 0) return column + 1 < columns && index + 1 < count ? index + 1 : index;
        if (dy < 0) return index >= columns ? index - columns : index;
        if (dy > 0) return (index / columns + 1) * columns < count
                ? Math.min(index + columns, count - 1) : index;
        return index;
    }

    static String channelName(String title) {
        return (title == null ? "" : title).toUpperCase(Locale.ROOT).trim()
                .replaceFirst("^[A-Z]{2}\\s*[|:]\\s*", "")
                .replaceFirst("(?:[ ._-]+(?:FHD|UHD|HD|SD|4K|HDR|H265|HEVC))+$", "")
                .replaceAll("\\s+", " ").trim();
    }

    static String liveChannelDisplayName(String title) {
        String name = channelName(title);
        Matcher quality = Pattern.compile("(?:^|[ ._-])(UHD|FHD|4K)(?:[ ._-]+(?:HDR|H265|HEVC|HD|SD))*$",
                Pattern.CASE_INSENSITIVE).matcher(title == null ? "" : title.trim());
        if (!quality.find()) return name;
        String suffix = quality.group(1).toUpperCase(Locale.ROOT);
        return name + " " + ("4K".equals(suffix) ? "UHD" : suffix);
    }

    static boolean liveTitle(String title) {
        return title != null && title.matches("(?i)^\\s*[a-z]{2}\\s*\\|\\s*.+");
    }

    static int networkId(String title) {
        switch (channelName(title)) {
            case "TF1": return 290;
            case "FRANCE 2": return 361;
            case "M6": return 712;
            case "CANAL+": return 285;
            default: return 0;
        }
    }
}

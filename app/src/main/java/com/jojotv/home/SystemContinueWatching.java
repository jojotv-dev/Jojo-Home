package com.jojotv.home;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class SystemContinueWatching {
    static final class Item {
        final String sourcePackage;
        final String title;
        final String description;
        final String artwork;
        final String intentUri;
        final long duration;
        final long position;
        final String seasonNumber;
        final String episodeNumber;

        Item(String title, String description, String artwork, String intentUri) {
            this(null, title, description, artwork, intentUri, -1L, -1L, null, null);
        }

        Item(String sourcePackage, String title, String description, String artwork, String intentUri) {
            this(sourcePackage, title, description, artwork, intentUri, -1L, -1L, null, null);
        }

        Item(String sourcePackage, String title, String description, String artwork, String intentUri, long duration, long position) {
            this(sourcePackage, title, description, artwork, intentUri, duration, position, null, null);
        }

        Item(String sourcePackage, String title, String description, String artwork, String intentUri, long duration, long position, String seasonNumber, String episodeNumber) {
            this.sourcePackage = sourcePackage;
            this.title = title;
            this.description = description;
            this.artwork = artwork;
            this.intentUri = intentUri;
            this.duration = duration;
            this.position = position;
            this.seasonNumber = seasonNumber;
            this.episodeNumber = episodeNumber;
        }
    }

    static List<Item> resumableItems(List<Item> publishedItems) {
        ArrayList<Item> result = new ArrayList<>();
        for (Item item : publishedItems) if (item != null && !isBlank(item.title) && !isBlank(item.intentUri)) result.add(item);
        return Collections.unmodifiableList(result);
    }

    static boolean shouldRequestPermission(boolean permissionGranted, boolean choiceAlreadyRequested) {
        return !permissionGranted && !choiceAlreadyRequested;
    }

    static boolean resumeRowBeforeApps(String position) { return "before".equals(position); }

    static List<String> bridgeColumns() {
        return java.util.Arrays.asList("title", "description", "poster", "thumbnail", "intent_uri", "duration", "position", "season_number", "episode_number");
    }

    static List<String> legacyBridgeColumns() {
        return java.util.Arrays.asList("title", "description", "poster", "thumbnail", "intent_uri", "duration", "position");
    }

    static boolean hasProgress(long duration, long position) { return duration > 0L && position > 0L; }

    static boolean hasPublishedResumeLabel(String description) {
        if (isBlank(description)) return false;
        String normalized = description.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("reprendre la lecture") || normalized.startsWith("continue watching")
                || normalized.startsWith("resume playback") || normalized.startsWith("resume watching");
    }

    static String publishedResumeSynopsis(String packageName, String description) {
        return "netflix".equals(sharedResumeProvider(packageName)) && hasPublishedResumeLabel(description)
                ? "" : description == null ? "" : description.trim();
    }

    static String netflixResumeEpisodeTitle(String title, String description) {
        if (!hasPublishedResumeLabel(description)) return "";
        String episode = description.trim().replaceFirst(
                "(?iu)^(?:reprendre la lecture|continue watching|resume playback|resume watching)[\\s:|·–—-]*", "").trim();
        return normalizedResumeText(episode).equals(normalizedResumeText(title)) ? "" : episode;
    }

    /** Lookup candidates only: a comma also belongs to genuine movie and series titles. */
    static java.util.Map<String, String> netflixSeriesEpisodeCandidates(String title) {
        java.util.Map<String, String> candidates = new java.util.LinkedHashMap<>();
        if (isBlank(title)) return candidates;
        for (int comma = title.indexOf(','); comma >= 0; comma = title.indexOf(',', comma + 1)) {
            String series = title.substring(0, comma).trim();
            String episode = title.substring(comma + 1).trim();
            if (!series.isEmpty() && !episode.isEmpty()) candidates.put(series, episode);
        }
        return candidates;
    }

    static String netflixResumeMediaType(String title, String description, String season, String episode) {
        // A Netflix resume caption can be a film's label; resolve unknown types through TMDB.
        if ((season != null && season.matches(".*[1-9]\\d*.*"))
                || (episode != null && episode.matches(".*[1-9]\\d*.*"))
                || (title != null && java.util.regex.Pattern.compile(
                    "(?iu)\\b(?:s\\s*\\d{1,2}\\s*e\\s*\\d{1,3}|\\d{1,2}x\\d{1,3}|(?:saison|season)\\s*\\d{1,2})\\b")
                    .matcher(title).find())) return "tv";
        return "unknown";
    }

    /** A generic Netflix resume must not default to a same-name film when the type is unknown. */
    static String resolvedResumeMediaType(String hint, int movieId, int tvId) {
        if ("tv".equals(hint)) return tvId > 0 ? "tv" : "";
        if (movieId > 0 && tvId <= 0) return "movie";
        if (tvId > 0 && movieId <= 0) return "tv";
        return "";
    }

    static boolean netflixMovieMetadataMatches(String hint, String episodeTitle, String yearHint,
            int durationMinutes, String movieYear, int movieRuntimeMinutes) {
        if ("tv".equals(hint)) return false;
        boolean hasYear = !isBlank(yearHint);
        boolean hasDuration = durationMinutes > 0;
        if (hasYear && !yearHint.equals(movieYear)) return false;
        if (hasDuration && (movieRuntimeMinutes <= 0
                || Math.abs((long) durationMinutes - movieRuntimeMinutes) > 8L)) return false;
        // A separate episode caption needs corroboration before selecting a same-name film.
        return isBlank(episodeTitle) || hasYear || hasDuration;
    }

    static String netflixTitleLogoUrl(String url) {
        return url == null ? "" : url.replaceFirst(
                "^https://image\\.tmdb\\.org/t/p/(?:w\\d+|h\\d+)/", "https://image.tmdb.org/t/p/original/");
    }

    static boolean isFreeTvResume(String type, String title, String intentUri, int progressPercent) {
        return !isBlank(type) && !"profile".equalsIgnoreCase(type)
                && !isBlank(title) && !isBlank(intentUri) && progressPercent < 100;
    }

    static boolean isPublishedResumePreview(String description, long duration, long position) {
        if (duration > 0L && position >= duration) return false;
        return position > 0L || hasPublishedResumeLabel(description);
    }

    static String sharedResumeProvider(String packageName) {
        if (NotificationResumeRules.isTizenTubePackage(packageName)) return "tizentube";
        if (NotificationResumeRules.isFreeTvPackage(packageName)) return "free-tv";
        return packageName != null && packageName.toLowerCase(java.util.Locale.ROOT).contains("netflix")
                ? "netflix" : "";
    }

    /** Netflix also embeds numeric content IDs inside Android intent URIs. */
    static String netflixContentId(String reference) {
        if (isBlank(reference)) return "";
        java.util.regex.Matcher id = java.util.regex.Pattern.compile(
                "(?i)(?:netflix:|/(?:watch|title)/|[?&](?:movieid|episodeid|videoid)=)([0-9]+)(?:[/?&#;]|$)").matcher(reference);
        return id.find() ? id.group(1) : "";
    }

    static boolean netflixSnapshotReplacesLocal(String localProfile, String cloudProfile) {
        return !isBlank(cloudProfile) && (isBlank(localProfile) || sameNetflixProfile(localProfile, cloudProfile));
    }

    static <T> List<T> replaceNetflixProfileResumes(List<T> local, java.util.Map<String, List<T>> published,
                                                  java.util.function.Function<T, String> source,
                                                  java.util.function.Function<T, String> profile) {
        ArrayList<T> remaining = new ArrayList<>(local);
        ArrayList<T> result = new ArrayList<>();
        for (java.util.Map.Entry<String, List<T>> snapshot : published.entrySet()) {
            if (isBlank(snapshot.getKey()) || snapshot.getValue().isEmpty()) continue;
            remaining.removeIf(item -> "netflix".equals(sharedResumeProvider(source.apply(item)))
                    && netflixSnapshotReplacesLocal(profile.apply(item), snapshot.getKey()));
            result.addAll(snapshot.getValue());
        }
        result.addAll(remaining);
        return result;
    }

    static String netflixProfileContentKey(String profileKey, String contentId) {
        if (profileKey == null || !profileKey.matches("[0-9a-f]{64}") || contentId == null
                || !contentId.matches("[1-9][0-9]{0,19}")) return "";
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(
                    (profileKey + "|" + contentId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder("netflix_");
            for (byte value : digest) {
                result.append(Character.forDigit((value >>> 4) & 15, 16));
                result.append(Character.forDigit(value & 15, 16));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    static boolean sameNetflixProfile(String left, String right) {
        if (isBlank(left) || isBlank(right)) return false;
        return normalizedResumeText(java.text.Normalizer.normalize(left, java.text.Normalizer.Form.NFKC))
                .equals(normalizedResumeText(java.text.Normalizer.normalize(right, java.text.Normalizer.Form.NFKC)));
    }

    /** Package variants and seek/tracking parameters do not identify another video. */
    static String sharedResumeKey(String packageName, String title, String description,
                                  String reference, String season, String episode) {
        String provider = sharedResumeProvider(packageName);
        if (provider.isEmpty()) return "";
        String videoId = "tizentube".equals(provider) ? NotificationResumeRules.youtubeVideoId(reference) : null;
        String identity = videoId == null ? canonicalResumeReference(reference) : "youtube:" + videoId;
        if ("netflix".equals(provider) && reference != null) {
            String id = netflixContentId(reference);
            if (!id.isEmpty()) identity = "netflix:" + id;
        }
        if (identity.isEmpty()) identity = "title:" + normalizedResumeText(title)
                + ("tizentube".equals(provider) ? "|" + normalizedResumeText(description) : "")
                + "|" + normalizedResumeText(season) + "|" + normalizedResumeText(episode);
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(
                    (provider + "|" + identity).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder(provider + "_");
            for (byte value : digest) {
                key.append(Character.forDigit((value >>> 4) & 15, 16));
                key.append(Character.forDigit(value & 15, 16));
            }
            return key.toString();
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private static String normalizedResumeText(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String canonicalResumeReference(String reference) {
        if (isBlank(reference)) return "";
        try {
            java.net.URI uri = new java.net.URI(reference);
            String host = uri.getHost();
            if (host == null) return reference;
            java.util.ArrayList<String> parameters = new java.util.ArrayList<>();
            if (uri.getRawQuery() != null) for (String part : uri.getRawQuery().split("&")) {
                String name = part.split("=", 2)[0].toLowerCase(java.util.Locale.ROOT);
                if (java.util.Arrays.asList("t", "start", "time", "position", "positionms", "trkid",
                        "ref", "referrer", "source").contains(name) || name.startsWith("utm_")) continue;
                parameters.add(part);
            }
            java.util.Collections.sort(parameters);
            return host.toLowerCase(java.util.Locale.ROOT) + (uri.getPort() < 0 ? "" : ":" + uri.getPort())
                    + (uri.getRawPath() == null ? "" : uri.getRawPath())
                    + (parameters.isEmpty() ? "" : "?" + String.join("&", parameters));
        } catch (java.net.URISyntaxException ignored) { return reference; }
    }

    /** A first/unchanged local history snapshot must not overwrite a newer NAS resume. */
    static boolean sharedResumeProgressChanged(boolean hasBaseline, long previousPosition, long position,
                                                int previousPercent, int percent) {
        return sharedResumeProgressChanged(hasBaseline, previousPosition, position, previousPercent, percent, -1L, -1);
    }

    static boolean sharedResumeProgressChanged(boolean hasBaseline, long previousPosition, long position,
                                                int previousPercent, int percent, long rememberedPosition, int rememberedPercent) {
        if (!hasBaseline) return false;
        boolean moved = position >= 0L && previousPosition != position
                && (previousPosition >= 0L || (position > 0L && position >= rememberedPosition));
        boolean percentageChanged = percent >= 0 && previousPercent != percent
                && (previousPercent >= 0 || (percent > 0 && percent >= rememberedPercent));
        return moved || percentageChanged;
    }

    /** Netflix can replace an episode while publishing no position or percentage at all. */
    static boolean sharedResumeEpisodeChanged(boolean hasBaseline, Item previous, Item observed) {
        if (!hasBaseline || previous == null || observed == null
                || !"netflix".equals(sharedResumeProvider(observed.sourcePackage))) return false;
        if (publishedNumberChanged(previous.seasonNumber, observed.seasonNumber)
                || publishedNumberChanged(previous.episodeNumber, observed.episodeNumber)) return true;
        String before = netflixResumeEpisodeTitle(previous.title, previous.description);
        String after = netflixResumeEpisodeTitle(observed.title, observed.description);
        return !isBlank(before) && !isBlank(after)
                && !normalizedResumeText(before).equals(normalizedResumeText(after));
    }

    private static boolean publishedNumberChanged(String previous, String observed) {
        java.util.regex.Pattern digits = java.util.regex.Pattern.compile("\\d+");
        java.util.regex.Matcher before = digits.matcher(previous == null ? "" : previous);
        java.util.regex.Matcher after = digits.matcher(observed == null ? "" : observed);
        return before.find() && after.find()
                && !before.group().replaceFirst("^0+(?!$)", "").equals(after.group().replaceFirst("^0+(?!$)", ""));
    }

    /** Missing titles are removals only after both native Netflix sources have been read. */
    static java.util.Set<String> removedNetflixResumeKeys(java.util.Set<String> previouslyPublished,
                                                          java.util.Set<String> published,
                                                          boolean watchNextAvailable, boolean previewAvailable) {
        java.util.Set<String> removed = new java.util.HashSet<>();
        if (!watchNextAvailable || !previewAvailable) return removed;
        for (String key : previouslyPublished) {
            if (key.startsWith("netflix_") && !published.contains(key)) removed.add(key);
        }
        return removed;
    }

    static boolean mayRestoreSharedResume(boolean deleted, boolean providerRemoved,
                                           boolean newPlayback, String observationSource) {
        return !deleted || (newPlayback && !(providerRemoved && "notification".equals(observationSource)));
    }

    static long sharedResumeTiming(long remembered, long observed, boolean newPlayback) {
        return observed >= 0L && (newPlayback || remembered < 0L) ? observed : remembered;
    }

    /** JojoTV puts the episode identity in its resume intent as well as in the bridge columns. */
    static String jojoIntentExtra(String intentUri, String name) {
        if (isBlank(intentUri) || isBlank(name)) return null;
        String[] prefixes = { "i.", "l.", "S." };
        for (String prefix : prefixes) {
            String marker = prefix + name + "=";
            int start = intentUri.indexOf(marker);
            if (start < 0) continue;
            start += marker.length();
            int end = intentUri.indexOf(';', start);
            String value = intentUri.substring(start, end < 0 ? intentUri.length() : end).trim();
            if (!value.isEmpty()) return value;
        }
        return null;
    }

    static String episodeLabel(String seasonNumber, String episodeNumber) {
        boolean hasSeason = !isBlank(seasonNumber);
        boolean hasEpisode = !isBlank(episodeNumber);
        if (hasSeason && hasEpisode) return "Saison " + seasonNumber.trim() + " · Épisode " + episodeNumber.trim();
        if (hasSeason) return "Saison " + seasonNumber.trim();
        if (hasEpisode) return "Épisode " + episodeNumber.trim();
        return "";
    }

    static String playbackTimeLabel(long millis) {
        long minutes = Math.max(0L, millis) / 60_000L;
        long hours = minutes / 60L;
        long remainingMinutes = minutes % 60L;
        return hours > 0L ? hours + " h " + String.format(java.util.Locale.ROOT, "%02d", remainingMinutes) : minutes + " min";
    }

    static String compactPlaybackTimeLabel(long millis) {
        long safeMillis = Math.max(0L, millis);
        long minutes = safeMillis / 60_000L + (safeMillis % 60_000L >= 30_000L ? 1L : 0L);
        if (minutes < 60L) return minutes + " min";
        return (minutes / 60L) + "h" + String.format(java.util.Locale.ROOT, "%02d", minutes % 60L);
    }

    static String remainingTimeLabel(long duration, long position) {
        if (duration <= 0L) return "";
        long remaining = Math.max(0L, duration - Math.max(0L, position));
        if (remaining == 0L) return "";
        return compactPlaybackTimeLabel(remaining).replace(" min", "min") + " restantes";
    }

    static boolean shouldPullRemoteProgressSnapshot(
            long knownRevision, long oldestAvailableRevision, boolean hasForeignProgressChange) {
        return knownRevision <= 0L
                || (oldestAvailableRevision > 0L && knownRevision < oldestAvailableRevision - 1L)
                || hasForeignProgressChange;
    }

    static String elapsedPlaybackTimeLabel(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1_000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        if (minutes < 60L) {
            return minutes + "m" + String.format(java.util.Locale.ROOT, "%02d", seconds);
        }
        return (minutes / 60L) + "h"
                + String.format(java.util.Locale.ROOT, "%02d", minutes % 60L)
                + "m" + String.format(java.util.Locale.ROOT, "%02d", seconds);
    }

    /** The title is the programme name; the description is the synopsis and must not replace it. */
    static String displayTitle(String title, String description) {
        if (!isBlank(title)) return title.trim();
        if (!isBlank(description)) return description.trim();
        return "Vidéo";
    }

    /** The bridge may expose one JojoTV profile at a time; never discard an earlier profile snapshot. */
    static List<Item> mergeJojoProfileSnapshots(List<Item> remembered, List<Item> latest) {
        java.util.LinkedHashMap<String, Item> merged = new java.util.LinkedHashMap<>();
        if (remembered != null) for (Item item : remembered) if (item != null) merged.put(resumeIdentity(item), item);
        if (latest != null) for (Item item : latest) if (item != null) merged.put(resumeIdentity(item), item);
        return Collections.unmodifiableList(new ArrayList<>(merged.values()));
    }

    /** Replaces one successfully refreshed profile snapshot; an empty response is meaningful. */
    static List<Item> replaceJojoProfileSnapshot(List<Item> remembered, List<Item> latest, String activeProfileId) {
        java.util.LinkedHashMap<String, Item> replaced = new java.util.LinkedHashMap<>();
        if (!isBlank(activeProfileId) && remembered != null) {
            for (Item item : remembered) {
                if (item == null) continue;
                String itemProfileId = jojoProfileId(item);
                if (!isBlank(itemProfileId) && !activeProfileId.equals(itemProfileId)) replaced.put(resumeIdentity(item), item);
            }
        }
        if (latest != null) for (Item item : latest) if (item != null) replaced.put(resumeIdentity(item), item);
        return Collections.unmodifiableList(new ArrayList<>(replaced.values()));
    }

    static String jojoProfileId(Item item) {
        return item == null ? null : jojoIntentExtra(item.intentUri, "androidTvProfileId");
    }

    private static String resumeIdentity(Item item) {
        String profileId = jojoProfileId(item);
        String profilePrefix = isBlank(profileId) ? "" : profileId + "|";
        String contentId = jojoIntentExtra(item.intentUri, "contentId");
        if (!isBlank(contentId)) return profilePrefix + contentId + "|" + String.valueOf(item.seasonNumber) + "|" + String.valueOf(item.episodeNumber);
        if (!isBlank(item.title)) return profilePrefix + String.valueOf(item.title) + "|" + String.valueOf(item.seasonNumber) + "|" + String.valueOf(item.episodeNumber);
        if (!isBlank(item.intentUri)) return profilePrefix + item.intentUri;
        return profilePrefix + String.valueOf(item.description);
    }

    static boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }
}

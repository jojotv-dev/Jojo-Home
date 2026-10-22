package com.jojotv.home;

import java.util.Locale;
import java.util.Set;

/** Limits notification-derived resume cards to the applications selected by the user. */
final class NotificationResumeRules {
    static final long RETENTION_MS = 30L * 24L * 60L * 60L * 1000L;

    private NotificationResumeRules() { }

    static boolean isFresh(long updatedAt, long now) {
        return updatedAt > 0L && updatedAt <= now && now - updatedAt <= RETENTION_MS;
    }

    static String resumeKey(String packageName, String title) {
        String normalizedPackage = packageName == null ? "" : packageName.trim().toLowerCase(Locale.ROOT);
        String normalizedTitle = title == null ? "" : title.trim().toLowerCase(Locale.ROOT);
        return normalizedPackage + "|" + normalizedTitle;
    }

    static String netflixProfile(String packageName, String category, String group, String label) {
        if (!isNetflixResumeRecommendation(packageName, category, group) || label == null) return "";
        java.util.regex.Matcher profile = java.util.regex.Pattern.compile(
                "(?iu)^Reprendre avec le profil de\\s+(.+)$").matcher(label.trim());
        if (!profile.matches()) return "";
        String name = java.text.Normalizer.normalize(profile.group(1), java.text.Normalizer.Form.NFKC)
                .trim().replaceAll("\\s+", " ");
        if (name.isEmpty() || name.length() > 80 || name.contains("://")) return "";
        for (int index = 0; index < name.length(); index++) if (Character.isISOControl(name.charAt(index))) return "";
        return name;
    }

    static String resumeKey(String packageName, String title, String netflixProfile) {
        String key = resumeKey(packageName, title);
        return !"netflix".equals(SystemContinueWatching.sharedResumeProvider(packageName))
                || netflixProfile == null || netflixProfile.isEmpty() ? key : key + "|netflix-profile:"
                    + java.text.Normalizer.normalize(netflixProfile, java.text.Normalizer.Form.NFKC)
                        .trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    static String artworkFileName(String packageName, String title, byte[] image) throws java.security.NoSuchAlgorithmException {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        digest.update(resumeKey(packageName, title).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) 0);
        byte[] hash = digest.digest(image);
        StringBuilder name = new StringBuilder("notification_resume_");
        for (byte value : hash) {
            name.append(Character.forDigit((value >>> 4) & 15, 16));
            name.append(Character.forDigit(value & 15, 16));
        }
        return name.append(".png").toString();
    }

    static String retainedArtworkUri(String packageName, String artworkUri) {
        // Cobalt's old per-app file could contain the image of any later video.
        if (isTizenTubePackage(packageName) && artworkUri != null
                && artworkUri.endsWith("/notification_resume_" + Integer.toHexString(packageName.hashCode()) + ".png")) return "";
        return artworkUri;
    }

    static boolean isFreeTvPackage(String packageName) {
        if (packageName == null) return false;
        String normalized = packageName.toLowerCase(Locale.ROOT);
        return normalized.startsWith("net.oqee.") || normalized.contains("free.tv");
    }

    static boolean acceptsPackage(Set<String> selectedPackages, String packageName) {
        if (packageName == null) return false;
        String normalizedPackage = packageName.toLowerCase(Locale.ROOT);
        if (selectedPackages == null) return true;
        for (String selected : selectedPackages) {
            if (selected == null) continue;
            String normalizedSelected = selected.toLowerCase(Locale.ROOT);
            if (normalizedSelected.equals(normalizedPackage)) return true;
            if (acceptedAsSameApp(normalizedSelected, normalizedPackage)) return true;
        }
        return false;
    }

    private static boolean acceptedAsSameApp(String selected, String actual) {
        if (selected.contains("smarttube") && actual.contains("smarttube")) return true;
        if (isTizenTubePackage(selected) && isTizenTubePackage(actual)) return true;
        if (selected.contains("netflix") && actual.contains("netflix")) return true;
        if (isFreeTvPackage(selected) && isFreeTvPackage(actual)) return true;
        if ((selected.contains("primevideo") || selected.contains("amazon") || selected.contains("firebat") || selected.contains("firetv")) && actual.contains("amazon")) return true;
        if (selected.contains("youtube") && actual.contains("youtube")
                && isYoutubeVideoPackage(selected) && isYoutubeVideoPackage(actual)) return true;
        if (selected.contains("tivimate") && actual.contains("tivimate")) return true;
        if ((selected.contains("canal") || selected.contains("mycanal")) && (actual.contains("canal") || actual.contains("mycanal"))) return true;
        if (selected.contains("molotov") && actual.contains("molotov")) return true;
        if (selected.contains("kodi") && actual.contains("kodi")) return true;
        if (selected.contains("plex") && actual.contains("plex")) return true;
        if (selected.contains("vlc") && actual.contains("vlc")) return true;
        if (selected.contains("disney") && actual.contains("disney")) return true;
        if (selected.equals("com.jojo.tv") && actual.equals("com.jojo.tv.debug")) return true;
        if (selected.equals("com.jojo.tv.debug") && actual.equals("com.jojo.tv")) return true;
        return false;
    }

    static boolean isTizenTubePackage(String packageName) {
        return packageName != null && packageName.toLowerCase(Locale.ROOT).contains("tizentube");
    }

    static boolean isYoutubeVideoPackage(String packageName) {
        if (packageName == null) return false;
        String normalized = packageName.toLowerCase(Locale.ROOT);
        return normalized.contains("smarttube") || isTizenTubePackage(normalized)
                || (normalized.contains("youtube") && !normalized.contains("music"));
    }

    static boolean matchesYoutubeVideo(String title, String author, long duration,
                                       String candidateTitle, String candidateAuthor, long candidateDuration) {
        return title != null && author != null && !author.trim().isEmpty()
                && duration > 0L && candidateDuration > 0L && Math.abs(duration - candidateDuration) <= 2_000L
                && normalizedYoutubeText(title).equals(normalizedYoutubeText(candidateTitle))
                && normalizedYoutubeText(author).equals(normalizedYoutubeText(candidateAuthor));
    }

    private static String normalizedYoutubeText(String value) {
        if (value == null) return "";
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
                .replace('\u2019', '\'').replace('\u2018', '\'')
                .replace('\u201c', '"').replace('\u201d', '"')
                .trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    static String youtubeVideoId(String reference) {
        if (reference == null || reference.trim().isEmpty()) return null;
        String value = reference.trim();
        if (value.matches("[A-Za-z0-9_-]{11}")) return value;
        try {
            java.net.URI uri = new java.net.URI(value);
            String host = uri.getHost();
            if (host == null) return null;
            host = host.toLowerCase(Locale.ROOT);
            String path = uri.getPath();
            String id = null;
            if ("youtu.be".equals(host) && path != null) id = path.substring(1).split("/")[0];
            else if (("youtube.com".equals(host) || host.endsWith(".youtube.com")) && "/watch".equals(path)) {
                String query = uri.getRawQuery();
                if (query != null) for (String parameter : query.split("&")) {
                    if (parameter.startsWith("v=")) id = java.net.URLDecoder.decode(parameter.substring(2), "UTF-8");
                }
            } else if ("i.ytimg.com".equals(host) && path != null) {
                String[] parts = path.split("/");
                if (parts.length > 2 && ("vi".equals(parts[1]) || "vi_webp".equals(parts[1]))) id = parts[2];
            }
            return id != null && id.matches("[A-Za-z0-9_-]{11}") ? id : null;
        } catch (Exception ignored) { return null; }
    }

    static String firstContentTitle(String appLabel, String... candidates) {
        for (String candidate : candidates) {
            if (candidate == null || candidate.trim().isEmpty()) continue;
            String title = candidate.trim();
            if (appLabel == null || !title.equalsIgnoreCase(appLabel.trim())) return title;
        }
        return null;
    }

    static boolean playbackVideoChanged(String packageName, String previousReference, String reference) {
        if ("netflix".equals(SystemContinueWatching.sharedResumeProvider(packageName))) {
            String before = SystemContinueWatching.netflixContentId(previousReference);
            String after = SystemContinueWatching.netflixContentId(reference);
            return !before.isEmpty() && !after.isEmpty() && !before.equals(after);
        }
        if (!isTizenTubePackage(packageName)) return false;
        String before = youtubeVideoId(previousReference);
        String after = youtubeVideoId(reference);
        return before != null && after != null && !before.equals(after);
    }

    static long playbackPosition(String packageName, long duration, long reportedPosition,
                                 long previousPosition, boolean playbackActive) {
        if (reportedPosition < 0L) return previousPosition;
        // Cobalt can report the full duration while the video is still playing.
        // Keep a known position, or unknown progress, instead of hiding its card.
        if (isTizenTubePackage(packageName) && playbackActive && duration > 0L
                && reportedPosition >= duration) {
            return previousPosition >= 0L && previousPosition < duration ? previousPosition : -1L;
        }
        return reportedPosition;
    }

    static boolean isNetflixResumeRecommendation(String packageName, String category, String group) {
        return "netflix".equals(SystemContinueWatching.sharedResumeProvider(packageName))
                && "recommendation".equals(category) && "continueWatching".equals(group);
    }

    static boolean netflixSessionMatchesRecommendation(String sessionReference, String recommendationReference) {
        String sessionId = SystemContinueWatching.netflixContentId(sessionReference);
        return !sessionId.isEmpty() && sessionId.equals(SystemContinueWatching.netflixContentId(recommendationReference));
    }

    static long netflixRecommendationPosition(long duration, int progress, int maximum, long previousPosition,
                                              String previousSessionReference, String recommendationReference) {
        long position = notificationPosition(duration, progress, maximum);
        // Old versions combined anonymous sessions with whichever recommendation existed.
        // Preserve a session position only when its independently published ID matches.
        return position == 0L && previousPosition > 0L
                && netflixSessionMatchesRecommendation(previousSessionReference, recommendationReference)
                ? previousPosition : position;
    }

    static boolean acceptsPlaybackNotification(String packageName, String category, String group,
                                               boolean hasMediaSession, int maximum) {
        if ("netflix".equals(SystemContinueWatching.sharedResumeProvider(packageName))
                && "recommendation".equals(category))
            return isNetflixResumeRecommendation(packageName, category, group);
        return "transport".equals(category) || hasMediaSession || maximum > 0;
    }

    static long netflixRecommendationDuration(String packageName, String category, String group,
                                              int progress, int maximum) {
        // Netflix PreApp recommendations publish runtime/bookmark in seconds.
        // 100 is also the builder's placeholder when no runtime was supplied.
        if (!isNetflixResumeRecommendation(packageName, category, group)
                || maximum <= 100 || progress < 0 || progress > maximum) return -1L;
        return maximum * 1000L;
    }

    static long notificationPosition(long duration, int progress, int maximum) {
        if (duration <= 0L || progress < 0 || maximum <= 0 || progress > maximum) return -1L;
        return Math.round(duration * (progress / (double) maximum));
    }
}

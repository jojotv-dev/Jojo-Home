package com.jojotv.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.app.Notification;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.Iterator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Mirrors Monet's notification-listener path to snapshot active media sessions. */
public final class NotificationResumeListenerService extends NotificationListenerService {
    static final String PREFS = "home_settings";
    static final String ITEMS_KEY = "notification_resume_items";
    static final String ACTION_REFRESH = "com.jojo.tv.home.REFRESH_RESUME_FALLBACK";
    private static final android.util.LruCache<String, ArtworkSnapshot> artworkCache =
            new android.util.LruCache<String, ArtworkSnapshot>(4 * 1_024) {
                @Override protected int sizeOf(String key, ArtworkSnapshot value) {
                    return Math.max(1, value.bitmap.getAllocationByteCount() / 1_024);
                }
            };
    private static final class ArtworkSnapshot {
        final Bitmap bitmap;
        final int generation;
        final String uri;
        ArtworkSnapshot(Bitmap bitmap, String uri) {
            this.bitmap = bitmap; this.generation = bitmap.getGenerationId(); this.uri = uri;
        }
    }
    private final ExecutorService refreshExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService youtubeResolverExecutor = Executors.newSingleThreadExecutor();
    private final Set<String> resolvingYoutube = Collections.synchronizedSet(new HashSet<>());
    private final android.util.LruCache<String, Long> youtubeLookupAttempts = new android.util.LruCache<>(128);
    private final AtomicBoolean refreshRunning = new AtomicBoolean();
    private final AtomicBoolean refreshPending = new AtomicBoolean();
    private final AtomicBoolean destroyed = new AtomicBoolean();
    private final Handler sessionCallbackHandler = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<MediaSession.Token, SessionObserver> observedSessions = new LinkedHashMap<>();
    private MediaSessionManager mediaSessionManager;
    private SharedPreferences resumePreferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener selectedAppsListener = (prefs, key) -> {
        if ("content_apps".equals(key)) sessionCallbackHandler.post(this::startSessionObservation);
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener activeSessionsChangedListener =
            this::observeSessions;

    @Override public void onCreate() {
        super.onCreate();
        resumePreferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        resumePreferences.registerOnSharedPreferenceChangeListener(selectedAppsListener);
    }

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        startSessionObservation();
    }

    private void startSessionObservation() {
        mediaSessionManager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        if (mediaSessionManager != null) {
            try {
                ComponentName listener = new ComponentName(this, NotificationResumeListenerService.class);
                mediaSessionManager.removeOnActiveSessionsChangedListener(activeSessionsChangedListener);
                mediaSessionManager.addOnActiveSessionsChangedListener(
                        activeSessionsChangedListener, listener, sessionCallbackHandler);
                observeSessions(mediaSessionManager.getActiveSessions(listener));
            } catch (Exception error) {
                android.util.Log.w("JojoHomeResume", "Media session observation unavailable", error);
            }
        }
        scheduleRefresh();
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (ACTION_REFRESH.equals(intent == null ? null : intent.getAction())) startSessionObservation();
        return START_NOT_STICKY;
    }

    @Override public void onListenerDisconnected() {
        super.onListenerDisconnected();
        if (Build.VERSION.SDK_INT >= 24 && isAccessGranted(this)) {
            requestRebind(new ComponentName(this, NotificationResumeListenerService.class));
        }
    }

    static boolean isAccessGranted(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        if (enabled == null) return false;
        ComponentName listener = new ComponentName(context, NotificationResumeListenerService.class);
        for (String value : enabled.split(":")) {
            if (listener.equals(ComponentName.unflattenFromString(value))) return true;
        }
        return false;
    }

    static boolean isPlaybackActive(PlaybackState playback) {
        if (playback == null) return false;
        int state = playback.getState();
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_PAUSED
                || state == PlaybackState.STATE_BUFFERING || state == PlaybackState.STATE_CONNECTING;
    }

    static String mediaContentTitle(Context context, String packageName, MediaMetadata metadata) {
        if (metadata == null) return null;
        String label = null;
        try { label = context.getPackageManager().getApplicationLabel(context.getPackageManager().getApplicationInfo(packageName, 0)).toString(); }
        catch (Exception ignored) { }
        return NotificationResumeRules.firstContentTitle(label,
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
    }
    @Override public void onNotificationPosted(StatusBarNotification notification) { super.onNotificationPosted(notification); scheduleRefresh(); }
    @Override public void onNotificationRemoved(StatusBarNotification notification) { super.onNotificationRemoved(notification); scheduleRefresh(); }

    @Override public void onDestroy() {
        destroyed.set(true);
        refreshPending.set(false);
        if (resumePreferences != null) resumePreferences.unregisterOnSharedPreferenceChangeListener(selectedAppsListener);
        if (mediaSessionManager != null) {
            try { mediaSessionManager.removeOnActiveSessionsChangedListener(activeSessionsChangedListener); }
            catch (Exception ignored) { }
        }
        for (SessionObserver observer : observedSessions.values()) {
            observer.controller.unregisterCallback(observer.callback);
        }
        observedSessions.clear();
        sessionCallbackHandler.removeCallbacksAndMessages(null);
        refreshExecutor.shutdownNow();
        youtubeResolverExecutor.shutdownNow();
        super.onDestroy();
    }

    private void observeSessions(List<MediaController> controllers) {
        if ((controllers == null || controllers.isEmpty()) && mediaSessionManager != null) {
            try { controllers = mediaSessionManager.getActiveSessions(null); }
            catch (Exception ignored) { }
        }
        Set<String> selected = getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet("content_apps", null);
        LinkedHashMap<MediaSession.Token, MediaController> active = new LinkedHashMap<>();
        if (controllers != null) for (MediaController controller : controllers) {
            if (controller == null || !acceptsResumePackage(selected, controller.getPackageName())) continue;
            active.put(controller.getSessionToken(), controller);
        }

        Iterator<Map.Entry<MediaSession.Token, SessionObserver>> iterator = observedSessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<MediaSession.Token, SessionObserver> entry = iterator.next();
            if (active.containsKey(entry.getKey())) continue;
            SessionObserver observer = entry.getValue();
            observer.controller.unregisterCallback(observer.callback);
            iterator.remove();
        }

        for (Map.Entry<MediaSession.Token, MediaController> entry : active.entrySet()) {
            if (observedSessions.containsKey(entry.getKey())) continue;
            MediaController controller = entry.getValue();
            MediaController.Callback callback = new MediaController.Callback() {
                @Override public void onMetadataChanged(MediaMetadata metadata) { scheduleRefresh(); }
                @Override public void onPlaybackStateChanged(PlaybackState state) { scheduleRefresh(); }
                @Override public void onSessionDestroyed() { scheduleRefresh(); }
            };
            controller.registerCallback(callback, sessionCallbackHandler);
            observedSessions.put(entry.getKey(), new SessionObserver(controller, callback));
        }
        scheduleRefresh();
    }

    private static final class SessionObserver {
        final MediaController controller;
        final MediaController.Callback callback;

        SessionObserver(MediaController controller, MediaController.Callback callback) {
            this.controller = controller;
            this.callback = callback;
        }
    }

    private void scheduleRefresh() {
        if (destroyed.get()) return;
        refreshPending.set(true);
        if (!refreshRunning.compareAndSet(false, true)) return;
        try {
            refreshExecutor.execute(() -> {
                try {
                    while (!destroyed.get() && refreshPending.getAndSet(false)) refreshMediaSessions();
                } finally {
                    refreshRunning.set(false);
                    if (!destroyed.get() && refreshPending.get()) scheduleRefresh();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            refreshRunning.set(false);
        }
    }

    private void refreshMediaSessions() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            Set<String> selected = prefs.getStringSet("content_apps", null);
            MediaSessionManager manager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            List<MediaController> sessions = Collections.emptyList();
            if (manager != null) {
                try { sessions = manager.getActiveSessions(new ComponentName(this, NotificationResumeListenerService.class)); }
                catch (Exception error) { android.util.Log.w("JojoHomeResume", "Media sessions unavailable; reading playback notifications", error); }
            }
            long now = System.currentTimeMillis();
            LinkedHashMap<String, JSONObject> itemsByKey = readFreshItems(prefs, now);
            Set<String> sessionKeys = new HashSet<>();
            for (MediaController controller : sessions) {
                String packageName = controller.getPackageName();
                if (!acceptsResumePackage(selected, packageName)) continue;
                MediaMetadata metadata = controller.getMetadata();
                // A Netflix recommendation can still describe another profile's
                // older playback. Only an exact content ID may supply its timing.
                PlaybackState playback = controller.getPlaybackState();
                String title = mediaContentTitle(this, packageName, metadata);
                String playbackIntent = mediaPlaybackIntentUri(packageName, metadata);
                boolean netflix = "netflix".equals(SystemContinueWatching.sharedResumeProvider(packageName));
                StatusBarNotification playbackNotification = (title == null || netflix)
                        && (!netflix || isPlaybackActive(playback))
                        ? playbackNotificationForPackage(packageName, playbackIntent) : null;
                if (title == null && playbackNotification != null)
                    title = notificationContentTitle(packageName, playbackNotification.getNotification());
                if (title == null || title.trim().isEmpty()) continue;
                String description = metadata == null ? null :
                    firstText(metadata, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST);
                if (SystemContinueWatching.isBlank(description) && playbackNotification != null)
                    description = textValue(playbackNotification.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT));
                String netflixProfile = playbackNotification == null ? "" : netflixProfile(packageName, playbackNotification.getNotification());
                String key = NotificationResumeRules.resumeKey(packageName, title, netflixProfile);
                JSONObject item = itemsByKey.get(key);
                if (item == null) item = new JSONObject();
                long reportedDuration = metadata == null ? -1L : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
                if (playbackNotification != null) {
                    Notification notification = playbackNotification.getNotification();
                    if (reportedDuration <= 0L) reportedDuration = netflixRecommendationDuration(packageName, notification);
                    if (playbackIntent == null) playbackIntent = netflixRecommendationIntent(packageName, notification);
                }
                boolean episodeChanged = SystemContinueWatching.sharedResumeEpisodeChanged(true,
                        new SystemContinueWatching.Item(packageName, item.optString("title", ""), item.optString("description", ""), "", ""),
                        new SystemContinueWatching.Item(packageName, title, description, "", ""));
                if (NotificationResumeRules.playbackVideoChanged(packageName,
                        item.optString("youtubeVideoId", item.optString("intent", "")), playbackIntent)
                        || episodeChanged
                        || (NotificationResumeRules.isTizenTubePackage(packageName)
                        && !item.optString("youtubeVideoId", "").isEmpty()
                        && reportedDuration > 0L && description != null && !description.trim().isEmpty()
                        && !NotificationResumeRules.matchesYoutubeVideo(title, description, reportedDuration,
                            item.optString("title", ""), item.optString("description", ""), item.optLong("duration", -1L)))) {
                    // Episode/video transitions must not inherit the previous playback's state.
                    item = new JSONObject();
                }
                String currentVideoId = NotificationResumeRules.isTizenTubePackage(packageName)
                        ? NotificationResumeRules.youtubeVideoId(playbackIntent) : null;
                String artwork = currentVideoId == null ? item.optString("artwork", "")
                        : "https://i.ytimg.com/vi/" + currentVideoId + "/hqdefault.jpg";
                if (metadata != null && currentVideoId == null && item.optString("youtubeVideoId", "").isEmpty()) {
                    String currentArtwork = saveMediaArtwork(this, packageName, title, metadata);
                    if (currentArtwork != null) artwork = currentArtwork;
                }
                item.put("package", packageName); item.put("title", title);
                if (description != null && !description.trim().isEmpty()) item.put("description", description);
                if (artwork != null) item.put("artwork", artwork);
                else if (!item.has("artwork")) item.put("artwork", "");
                long duration = reportedDuration > 0L ? reportedDuration : item.optLong("duration", -1L);
                long position = NotificationResumeRules.playbackPosition(packageName, duration,
                        playback == null ? -1L : playback.getPosition(), item.optLong("position", -1L), isPlaybackActive(playback));
                item.put("duration", duration); item.put("position", position); item.put("updatedAt", now);
                item.put("playbackCaptured", true);
                item.put("playbackActive", isPlaybackActive(playback));
                if (playbackIntent != null) item.put("intent", playbackIntent);
                if (netflix) {
                    item.remove("netflixProfile");
                    if (!netflixProfile.isEmpty()) item.put("netflixProfile", netflixProfile);
                    item.remove("netflixSessionReference");
                    if (!SystemContinueWatching.netflixContentId(playbackIntent).isEmpty())
                        item.put("netflixSessionReference", playbackIntent);
                }
                if (currentVideoId != null) item.put("youtubeVideoId", currentVideoId);
                itemsByKey.put(key, item);
                sessionKeys.add(key);
            }
            // SmartTube on this Android TV plays audio without creating a
            // MediaSession.  When it does expose a media notification, read
            // the public notification extras instead of losing the resume.
            JSONArray netflixSnapshot = captureNotificationOnlyResumes(selected, itemsByKey, sessionKeys, now);
            JSONArray items = new JSONArray();
            // The Home row is capped.  Persist most recently observed playback
            // first so old retained entries cannot hide newer resumes.
            java.util.ArrayList<JSONObject> ordered = new java.util.ArrayList<>(itemsByKey.values());
            Collections.sort(ordered, (left, right) -> Long.compare(
                right.optLong("updatedAt", 0L), left.optLong("updatedAt", 0L)
            ));
            for (JSONObject item : ordered) items.put(item);
            prefs.edit().putString(ITEMS_KEY, items.toString()).apply();
            if (netflixSnapshot != null)
                com.jojotv.home.progresspilot.AccountSettingsRouting.observeNetflixSnapshot(this, netflixSnapshot.toString());
            for (JSONObject item : ordered) scheduleYoutubeResolution(item);
            HomeWatchProgressBridge.observeTizenProgress(this, items.toString(), now);
        } catch (Exception error) { android.util.Log.w("JojoHomeResume", "Playback resume snapshot could not be saved", error); }
    }

    private JSONArray captureNotificationOnlyResumes(Set<String> selected, LinkedHashMap<String, JSONObject> itemsByKey, Set<String> sessionKeys, long now) {
        try {
            StatusBarNotification[] notifications = getActiveNotifications();
            if (notifications == null) return null;
            JSONArray netflixSnapshot = new JSONArray();
            for (StatusBarNotification notification : notifications) {
                String packageName = notification.getPackageName();
                if (!acceptsResumePackage(selected, packageName)) continue;
                Notification value = notification.getNotification();
                Bundle extras = value == null ? null : value.extras;
                if (extras == null) continue;
                if (!acceptsPlaybackNotification(packageName, value)) continue;
                // Netflix and Canal+ do not consistently use EXTRA_TITLE on
                // Android TV.  Their active playback title can instead be in
                // the expanded title or, as a last resort, the main text.
                String title = notificationContentTitle(packageName, value);
                if (title == null) continue;
                CharSequence descriptionText = firstNotificationText(extras,
                        Notification.EXTRA_SUB_TEXT, Notification.EXTRA_TEXT);
                String profile = netflixProfile(packageName, value);
                String key = NotificationResumeRules.resumeKey(packageName, title, profile);
                if (!profile.isEmpty() && netflixSnapshot.length() < 100) {
                    String content = SystemContinueWatching.netflixContentId(netflixRecommendationIntent(packageName, value));
                    long duration = netflixRecommendationDuration(packageName, value);
                    long position = NotificationResumeRules.notificationPosition(duration,
                            extras.getInt(Notification.EXTRA_PROGRESS, -1), extras.getInt(Notification.EXTRA_PROGRESS_MAX, -1));
                    // Only the recommendation itself identifies its Netflix profile. Never attach
                    // an anonymous MediaSession's position or episode to that profile's older card.
                    if (!content.isEmpty()) netflixSnapshot.put(new JSONObject().put("profile", profile).put("content", content)
                            .put("title", title.replaceAll("\\s+", " ").trim()).put("description", descriptionText == null ? ""
                                : descriptionText.toString().replaceAll("\\s+", " ").trim())
                            .put("season", "").put("episode", "").put("duration", duration)
                            .put("position", position > 0L ? position : -1L));
                }
                // Notification progress is an arbitrary scale, not milliseconds.
                // A real MediaSession snapshot always owns its timing and route.
                if (sessionKeys.contains(key)) continue;
                JSONObject item = itemsByKey.get(key);
                if (item == null) item = new JSONObject();
                int maximum = extras.getInt(Notification.EXTRA_PROGRESS_MAX, -1);
                int progress = extras.getInt(Notification.EXTRA_PROGRESS, -1);
                String netflixIntent = netflixRecommendationIntent(packageName, value);
                if (NotificationResumeRules.playbackVideoChanged(packageName, item.optString("intent", ""), netflixIntent))
                    item = new JSONObject();
                long netflixDuration = netflixRecommendationDuration(packageName, value);
                // Older snapshots stored the notification's scale as a duration.
                // Clear that unverified timing when the same scale is observed.
                if (netflixDuration > 0L) item.put("duration", netflixDuration);
                else if (!item.optBoolean("playbackCaptured", false) && maximum > 0
                        && item.optLong("duration", -1L) == maximum) {
                    item.remove("duration");
                    item.remove("position");
                }
                item.put("package", packageName);
                if (!profile.isEmpty()) item.put("netflixProfile", profile);
                item.put("title", title);
                item.put("playbackCaptured", true);
                item.put("description", descriptionText == null ? "" : descriptionText.toString());
                long position = netflixDuration > 0L
                        ? NotificationResumeRules.netflixRecommendationPosition(netflixDuration, progress, maximum,
                                item.optLong("position", -1L), item.optString("netflixSessionReference", ""), netflixIntent)
                        : NotificationResumeRules.notificationPosition(item.optLong("duration", -1L), progress, maximum);
                if (position >= 0L && !extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)) item.put("position", position);
                if (netflixIntent != null) item.put("intent", netflixIntent);
                Object picture = extras.get(Notification.EXTRA_PICTURE);
                if (picture instanceof Bitmap) {
                    String artwork = saveBitmap(this, packageName, title, (Bitmap) picture);
                    if (artwork != null) item.put("artwork", artwork);
                }
                item.put("updatedAt", now);
                itemsByKey.put(key, item);
            }
            return netflixSnapshot;
        } catch (Exception ignored) { return null; }
    }

    private String netflixProfile(String packageName, Notification notification) {
        return NotificationResumeRules.netflixProfile(packageName, notification.category, notification.getGroup(),
                textValue(notification.extras.getCharSequence(Notification.EXTRA_TITLE)));
    }

    private CharSequence firstNotificationText(Bundle extras, String... keys) {
        for (String key : keys) {
            CharSequence value = extras.getCharSequence(key);
            if (value != null && value.length() > 0) return value;
        }
        return null;
    }

    private String notificationContentTitle(String packageName, Notification notification) {
        Bundle extras = notification.extras;
        String appLabel = null;
        try { appLabel = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(packageName, 0)).toString(); }
        catch (Exception ignored) { }
        if (NotificationResumeRules.isNetflixResumeRecommendation(packageName, notification.category, notification.getGroup()))
            return NotificationResumeRules.firstContentTitle(appLabel, textValue(extras.getCharSequence(Notification.EXTRA_TEXT)));
        return NotificationResumeRules.firstContentTitle(appLabel,
                textValue(extras.getCharSequence(Notification.EXTRA_TITLE)),
                textValue(extras.getCharSequence(Notification.EXTRA_TITLE_BIG)),
                textValue(extras.getCharSequence(Notification.EXTRA_TEXT)));
    }

    private static String textValue(CharSequence value) { return value == null ? null : value.toString(); }

    private boolean acceptsResumePackage(Set<String> selectedPackages, String packageName) {
        if (packageName == null) return false;
        String normalized = packageName.toLowerCase(java.util.Locale.ROOT);
        if (normalized.equals("ar.tvplayer.tv") || normalized.contains("tivimate")) return true;
        return NotificationResumeRules.acceptsPackage(selectedPackages, packageName);
    }

    private LinkedHashMap<String, JSONObject> readFreshItems(SharedPreferences prefs, long now) {
        LinkedHashMap<String, JSONObject> result = new LinkedHashMap<>();
        try {
            JSONArray items = new JSONArray(prefs.getString(ITEMS_KEY, "[]"));
            for (int index = 0; index < items.length(); index++) {
                JSONObject item = items.optJSONObject(index); if (item == null) continue;
                String packageName = item.optString("package", "");
                String title = item.optString("title", "");
                if (packageName.isEmpty() || title.isEmpty() || !NotificationResumeRules.isFresh(item.optLong("updatedAt", 0L), now)) continue;
                String normalizedPackage = packageName.toLowerCase(java.util.Locale.ROOT);
                if (normalizedPackage.contains("smarttube")
                        && !item.optBoolean("playbackCaptured", false)
                        && item.optLong("position", -1L) <= 0L
                        && item.optLong("duration", -1L) <= 0L) continue;
                item.put("artwork", NotificationResumeRules.retainedArtworkUri(packageName, item.optString("artwork", "")));
                result.put(NotificationResumeRules.resumeKey(packageName, title, item.optString("netflixProfile", "")), item);
            }
        } catch (Exception ignored) { }
        return result;
    }

    private boolean acceptsPlaybackNotification(String packageName, Notification notification) {
        return NotificationResumeRules.acceptsPlaybackNotification(packageName, notification.category, notification.getGroup(),
                notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION),
                notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX, -1));
    }

    private long netflixRecommendationDuration(String packageName, Notification notification) {
        return NotificationResumeRules.netflixRecommendationDuration(packageName, notification.category, notification.getGroup(),
                notification.extras.getInt(Notification.EXTRA_PROGRESS, -1),
                notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX, -1));
    }

    private String netflixRecommendationIntent(String packageName, Notification notification) {
        if (!NotificationResumeRules.isNetflixResumeRecommendation(packageName, notification.category, notification.getGroup())) return null;
        String id = notification.extras.getString("com.amazon.extra.CONTENT_ID", "");
        if (!id.matches("[0-9]+")) return null;
        return new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.netflix.com/watch/" + id))
                .setPackage(packageName).toUri(Intent.URI_INTENT_SCHEME);
    }

    private StatusBarNotification playbackNotificationForPackage(String packageName, String sessionReference) {
        try {
            StatusBarNotification[] notifications = getActiveNotifications();
            if (notifications == null) return null;
            StatusBarNotification netflixResume = null;
            boolean ambiguousNetflixResume = false;
            for (StatusBarNotification notification : notifications) {
                if (!packageName.equals(notification.getPackageName())) continue;
                Notification value = notification.getNotification();
                Bundle extras = value == null ? null : value.extras;
                if (extras == null) continue;
                if (!acceptsPlaybackNotification(packageName, value)
                        || notificationContentTitle(packageName, value) == null) continue;
                if (NotificationResumeRules.isNetflixResumeRecommendation(packageName, value.category, value.getGroup())) {
                    if (!NotificationResumeRules.netflixSessionMatchesRecommendation(
                            sessionReference, netflixRecommendationIntent(packageName, value))) continue;
                    if (netflixResume != null) ambiguousNetflixResume = true;
                    else netflixResume = notification;
                } else return notification;
            }
            return ambiguousNetflixResume ? null : netflixResume;
        } catch (Exception ignored) { }
        return null;
    }

    private String firstText(MediaMetadata metadata, String first, String second) {
        CharSequence value = metadata.getText(first); if (value == null || value.length() == 0) value = metadata.getText(second);
        return value == null ? null : value.toString();
    }

    static String mediaPlaybackIntentUri(String packageName, MediaMetadata metadata) {
        if (metadata == null) return null;
        String mediaUri = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI);
        if (NotificationResumeRules.isTizenTubePackage(packageName)) {
            String id = NotificationResumeRules.youtubeVideoId(mediaUri);
            if (id == null) id = NotificationResumeRules.youtubeVideoId(metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID));
            if (id == null) id = NotificationResumeRules.youtubeVideoId(metadata.getString(MediaMetadata.METADATA_KEY_ART_URI));
            if (id == null) id = NotificationResumeRules.youtubeVideoId(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI));
            if (id != null) mediaUri = "https://www.youtube.com/watch?v=" + id;
        }
        if (mediaUri == null || mediaUri.trim().isEmpty()) return null;
        return new Intent(Intent.ACTION_VIEW, Uri.parse(mediaUri)).setPackage(packageName).toUri(Intent.URI_INTENT_SCHEME);
    }

    private void scheduleYoutubeResolution(JSONObject item) {
        String packageName = item.optString("package", "");
        if (destroyed.get() || !NotificationResumeRules.isTizenTubePackage(packageName)
                || !item.optString("intent", "").isEmpty()) return;
        String title = item.optString("title", "");
        String author = item.optString("description", "");
        long duration = item.optLong("duration", -1L);
        if (title.isEmpty() || author.trim().isEmpty() || duration <= 0L) return;
        String key = NotificationResumeRules.resumeKey(packageName, title);
        String lookupKey = key + "|" + author + "|" + duration;
        Long lastAttempt = youtubeLookupAttempts.get(lookupKey);
        long now = System.currentTimeMillis();
        if (lastAttempt != null && now - lastAttempt < 5L * 60L * 1_000L) return;
        if (!resolvingYoutube.add(lookupKey)) return;
        youtubeLookupAttempts.put(lookupKey, now);
        try {
            youtubeResolverExecutor.execute(() -> {
                try {
                    String videoId = resolveYoutubeVideo(title, author, duration);
                    if (videoId != null && !destroyed.get()) refreshExecutor.execute(() -> {
                        try {
                            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
                            if (!acceptsResumePackage(prefs.getStringSet("content_apps", null), packageName)) return;
                            LinkedHashMap<String, JSONObject> items = readFreshItems(prefs, System.currentTimeMillis());
                            JSONObject current = items.get(key);
                            if (current == null || !current.optString("intent", "").isEmpty()
                                    || !NotificationResumeRules.matchesYoutubeVideo(title, author, duration,
                                        current.optString("title", ""), current.optString("description", ""), current.optLong("duration", -1L))) return;
                            current.put("youtubeVideoId", videoId);
                            current.put("intent", new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + videoId))
                                    .setPackage(packageName).toUri(Intent.URI_INTENT_SCHEME));
                            current.put("artwork", "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg");
                            JSONArray saved = new JSONArray();
                            for (JSONObject value : items.values()) saved.put(value);
                            prefs.edit().putString(ITEMS_KEY, saved.toString()).apply();
                        } catch (Exception error) { android.util.Log.w("JojoHomeResume", "YouTube resume link could not be saved", error); }
                    });
                } catch (Exception error) { android.util.Log.w("JojoHomeResume", "YouTube resume link unavailable", error); }
                finally { resolvingYoutube.remove(lookupKey); }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) { resolvingYoutube.remove(lookupKey); }
    }

    private String resolveYoutubeVideo(String title, String author, long duration) throws Exception {
        String query = java.net.URLEncoder.encode(title + " " + author, "UTF-8");
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(
                "https://www.youtube.com/results?search_query=" + query).openConnection();
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(5_000);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");
        connection.setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9");
        try {
            if (connection.getResponseCode() != 200) return null;
            java.io.ByteArrayOutputStream response = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[8_192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (response.size() + count > 3 * 1_024 * 1_024 || destroyed.get()) return null;
                    response.write(buffer, 0, count);
                }
            }
            String html = new String(response.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher marker = java.util.regex.Pattern.compile(
                    "(?:var\\s+ytInitialData\\s*=|window\\[\"ytInitialData\"\\]\\s*=)\\s*").matcher(html);
            if (!marker.find()) return null;
            JSONObject data = new JSONObject(new org.json.JSONTokener(html.substring(marker.end())));
            Set<String> matches = new HashSet<>();
            collectYoutubeMatches(data, title, author, duration, matches);
            return matches.size() == 1 ? matches.iterator().next() : null;
        } finally { connection.disconnect(); }
    }

    private void collectYoutubeMatches(Object node, String title, String author, long duration, Set<String> matches) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject video = object.optJSONObject("videoRenderer");
            if (video != null && NotificationResumeRules.matchesYoutubeVideo(title, author, duration,
                    youtubeText(video.optJSONObject("title")), youtubeText(video.optJSONObject("ownerText")),
                    youtubeDuration(youtubeText(video.optJSONObject("lengthText"))))) {
                String id = NotificationResumeRules.youtubeVideoId(video.optString("videoId", ""));
                if (id != null) matches.add(id);
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) collectYoutubeMatches(object.opt(keys.next()), title, author, duration, matches);
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int index = 0; index < array.length(); index++) collectYoutubeMatches(array.opt(index), title, author, duration, matches);
        }
    }

    private String youtubeText(JSONObject value) {
        if (value == null) return "";
        String text = value.optString("simpleText", "");
        if (!text.isEmpty()) return text;
        JSONArray runs = value.optJSONArray("runs");
        StringBuilder result = new StringBuilder();
        if (runs != null) for (int index = 0; index < runs.length(); index++) {
            JSONObject run = runs.optJSONObject(index);
            if (run != null) result.append(run.optString("text", ""));
        }
        return result.toString();
    }

    private long youtubeDuration(String value) {
        try {
            String[] parts = value.split(":");
            if (parts.length < 2 || parts.length > 3) return -1L;
            long seconds = 0L;
            for (String part : parts) seconds = seconds * 60L + Long.parseLong(part);
            return seconds * 1_000L;
        } catch (NumberFormatException ignored) { return -1L; }
    }

    static String saveMediaArtwork(Context context, String packageName, String title, MediaMetadata metadata) {
        for (String key : new String[] { MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI }) {
            String uri = metadata.getString(key);
            if (uri != null && !uri.trim().isEmpty()) return uri;
        }
        Bitmap artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (artwork == null) artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (artwork == null) artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        return artwork == null ? null : saveBitmap(context, packageName, title, artwork);
    }

    static String saveBitmap(Context context, String packageName, String title, Bitmap artwork) {
        try {
            String key = NotificationResumeRules.resumeKey(packageName, title);
            synchronized (artworkCache) {
                ArtworkSnapshot cached = artworkCache.get(key);
                if (cached != null && !cached.bitmap.isRecycled() && cached.bitmap.getGenerationId() == cached.generation
                        && cached.bitmap.sameAs(artwork) && new File(Uri.parse(cached.uri).getPath()).isFile()) {
                    artworkCache.put(key, new ArtworkSnapshot(artwork, cached.uri));
                    return cached.uri;
                }
            }
            java.io.ByteArrayOutputStream encoded = new java.io.ByteArrayOutputStream();
            if (!artwork.compress(Bitmap.CompressFormat.PNG, 90, encoded)) return null;
            byte[] image = encoded.toByteArray();
            File file = new File(context.getFilesDir(), NotificationResumeRules.artworkFileName(packageName, title, image));
            synchronized (artworkCache) {
                if (!file.isFile()) {
                    android.util.AtomicFile target = new android.util.AtomicFile(file);
                    FileOutputStream output = null;
                    try {
                        output = target.startWrite();
                        output.write(image);
                        target.finishWrite(output);
                    } catch (Exception error) {
                        if (output != null) target.failWrite(output);
                        throw error;
                    }
                }
                String uri = Uri.fromFile(file).toString();
                artworkCache.put(key, new ArtworkSnapshot(artwork, uri));
                return uri;
            }
        } catch (Exception ignored) { return null; }
    }
}

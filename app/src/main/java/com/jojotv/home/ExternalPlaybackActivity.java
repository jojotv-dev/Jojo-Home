package com.jojotv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import org.json.JSONObject;

/** Explicit external-player handoff; no background scraping of TiviMate. */
public final class ExternalPlaybackActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Uri uri = getIntent().getData();
        if (uri == null || !ExternalPlaybackStore.supportedUrl(uri.toString())) {
            Toast.makeText(this, "Adresse de lecture non prise en charge", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        String url = uri.toString();
        JSONObject item = ExternalPlaybackStore.get(this, ExternalPlaybackStore.id(url));
        try {
            String title = getIntent().getStringExtra("title");
            if (title == null || title.trim().isEmpty()) title = getIntent().getStringExtra("forcename");
            if (title == null || title.trim().isEmpty()) title = item.optString("title", "Vidéo");
            String logo = firstExtra("logo", "tvg-logo", "channelLogo", "androidTvLogo");
            String description = firstExtra("description", "descr", "plot", "overview", "synopsis", "desc");
            java.util.regex.Matcher episode = java.util.regex.Pattern
                    .compile("(?i)(?:^|\\b)s\\s*(\\d{1,2})\\s*e\\s*(\\d{1,3})(?:\\b|$)")
                    .matcher(title);
            if (episode.find()) {
                item.put("category", "series")
                        .put("season", Integer.parseInt(episode.group(1)))
                        .put("episode", Integer.parseInt(episode.group(2)));
                java.util.regex.Matcher year = java.util.regex.Pattern.compile("\\b(?:19|20)\\d{2}\\b").matcher(title);
                if (year.find()) item.put("year", year.group());
            }
            if ("unknown".equals(item.optString("category"))
                    && title.matches("(?i)^[a-z]{2}\\s*\\|\\s*.+")) {
                item.put("category", "live");
            }
            String storageId = item.optString("id");
            if (!storageId.matches("[a-f0-9]{64}")) storageId = ExternalPlaybackStore.id(url);
            item.put("id", storageId).put("url", url).put("title", title)
                    .put("deleted", false).put("favorite", item.optBoolean("favorite", false));
            if (!logo.isEmpty()) item.put("logo", logo);
            if (!description.isEmpty()) item.put("description", description);
        } catch (Exception e) { finish(); return; }
        // The external handoff is a playback event, not a favorite import.
        // Keep it out of the three favorite rails until a real TiviMate favorite
        // source is available; it belongs immediately in Continue Watching.
        launch(item, ExternalPlaybackStore.categoryValid(item.optString("category"))
                ? item.optString("category") : "unknown");
    }

    private void launch(JSONObject item, String category) {
        try {
            item.put("category", category);
            ExternalPlaybackStore.saveForPlayback(this, item);
            Intent player = ExternalPlaybackStore.playerIntent(this, item);
            // This player was opened by an external app (typically TiviMate).
            // Keep that origin so closing JojoHome returns to the IPTV app
            // instead of exposing JojoHome's Home screen.
            player.putExtra(NuvioPlayerActivity.EXTRA_RETURN_TO_TIVIMATE, true);
            startActivity(player);
        } catch (Exception e) {
            Toast.makeText(this, "Impossible d’ouvrir la lecture", Toast.LENGTH_LONG).show();
        }
        finish();
    }

    private String firstExtra(String... names) {
        for (String name : names) {
            String value = getIntent().getStringExtra(name);
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }
}

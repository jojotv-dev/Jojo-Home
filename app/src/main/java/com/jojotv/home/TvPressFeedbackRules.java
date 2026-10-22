package com.jojotv.home;

import android.view.KeyEvent;

/** Timing shared by the launcher's remote-control press feedback. */
final class TvPressFeedbackRules {
    static final float PRESS_SCALE = .97f;
    static final float PRESS_ALPHA = .90f;
    static final long PRESS_MS = 120L;
    static final long RELEASE_MS = 180L;
    static final long MIN_CLICK_INTERVAL_MS = 250L;

    private TvPressFeedbackRules() { }

    static boolean isSelectKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_BUTTON_A
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    static boolean acceptPress(long previousUptimeMs, long currentUptimeMs) {
        return previousUptimeMs < 0L
                || currentUptimeMs - previousUptimeMs >= MIN_CLICK_INTERVAL_MS;
    }
}

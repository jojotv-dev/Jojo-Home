package com.jojotv.home;

/** One visible photo page owns the timer; coverflow and fullscreen share the session. */
final class PhotoSlideshow implements Runnable {
    static final int DEFAULT_SECONDS = 5;
    static final int MAX_SECONDS = 9999;

    static final class State {
        int index;
        boolean playing;

        State(int index, boolean playing) {
            this.index = index;
            this.playing = playing;
        }
    }

    interface Scheduler {
        void schedule(Runnable task, long delayMillis);
        void cancel(Runnable task);
    }

    private final State state;
    private final int count;
    private final long delayMillis;
    private final Scheduler scheduler;
    private final Runnable changed;
    private boolean active;
    private boolean displayed;

    PhotoSlideshow(State state, int count, int seconds, Scheduler scheduler, Runnable changed) {
        this.state = state;
        this.count = Math.max(0, count);
        this.delayMillis = normalizeSeconds(seconds) * 1000L;
        this.scheduler = scheduler;
        this.changed = changed;
        state.index = Math.max(0, Math.min(state.index, this.count - 1));
    }

    static int normalizeSeconds(int seconds) {
        return Math.max(1, Math.min(seconds, MAX_SECONDS));
    }

    void setActive(boolean value) {
        if (active == value) return;
        active = value;
        restart();
    }

    void photoChanged() {
        displayed = false;
        scheduler.cancel(this);
    }

    void photoDisplayed() {
        if (displayed) return;
        displayed = true;
        restart();
    }

    void setPlaying(boolean value) {
        state.playing = value;
        restart();
    }

    void move(int direction) {
        if (count < 2) return;
        photoChanged();
        state.index = Math.floorMod(state.index + direction, count);
        changed.run();
    }

    private void restart() {
        scheduler.cancel(this);
        if (active && displayed && state.playing && count > 1)
            scheduler.schedule(this, delayMillis);
    }

    @Override public void run() {
        if (active && displayed && state.playing && count > 1) move(1);
    }
}

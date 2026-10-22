package com.jojotv.home;

import org.junit.Test;
import static org.junit.Assert.*;

public class PhotoSlideshowTest {
    private static final class Timer implements PhotoSlideshow.Scheduler {
        Runnable pending;
        long delay;
        int scheduled;

        @Override public void schedule(Runnable task, long delayMillis) {
            assertNull("Only one advance may be scheduled", pending);
            pending = task;
            delay = delayMillis;
            scheduled++;
        }

        @Override public void cancel(Runnable task) {
            if (pending == task) pending = null;
        }

        void fire() {
            Runnable task = pending;
            assertNotNull(task);
            pending = null;
            task.run();
        }
    }

    private final Timer timer = new Timer();
    private final PhotoSlideshow.State state = new PhotoSlideshow.State(0, true);
    private int renders;

    private PhotoSlideshow slideshow(int count, int seconds) {
        return new PhotoSlideshow(state, count, seconds, timer, () -> renders++);
    }

    @Test public void waitsForTheFirstDisplayedPhotoBeforeStartingTheFullDelay() {
        PhotoSlideshow show = slideshow(3, 7);
        show.setActive(true);
        assertNull(timer.pending);
        show.photoDisplayed();
        assertSame(show, timer.pending);
        assertEquals(7000L, timer.delay);
    }

    @Test public void cachedPhotoCanArriveBeforeThePageIsAttached() {
        PhotoSlideshow show = slideshow(3, 12);
        show.photoDisplayed();
        assertNull(timer.pending);
        show.setActive(true);
        assertEquals(12000L, timer.delay);
        assertSame(show, timer.pending);
    }

    @Test public void advancesOnePhotoThenWaitsForItsBitmap() {
        PhotoSlideshow show = slideshow(3, 5);
        show.setActive(true);
        show.photoDisplayed();
        timer.fire();
        assertEquals(1, state.index);
        assertEquals(1, renders);
        assertNull(timer.pending);
        show.photoDisplayed();
        assertSame(show, timer.pending);
    }

    @Test public void upgradingTheCurrentPhotoToHdDoesNotRestartTheDelay() {
        PhotoSlideshow show = slideshow(3, 5);
        show.setActive(true);
        show.photoDisplayed();
        show.photoDisplayed();
        show.photoDisplayed();
        assertEquals(1, timer.scheduled);
    }

    @Test public void loopsFromTheLastPhotoToTheFirst() {
        state.index = 2;
        PhotoSlideshow show = slideshow(3, 5);
        show.setActive(true);
        show.photoDisplayed();
        timer.fire();
        assertEquals(0, state.index);
    }

    @Test public void leftAndRightResetTheDelayAndKeepTheExactPosition() {
        PhotoSlideshow show = slideshow(4, 5);
        show.setActive(true);
        show.photoDisplayed();
        show.move(-1);
        assertEquals(3, state.index);
        assertNull(timer.pending);
        show.photoDisplayed();
        show.move(1);
        assertEquals(0, state.index);
        assertEquals(2, renders);
        assertNull(timer.pending);
    }

    @Test public void pauseStopsTheTimerButAllowsManualNavigation() {
        PhotoSlideshow show = slideshow(3, 5);
        show.setActive(true);
        show.photoDisplayed();
        show.setPlaying(false);
        assertNull(timer.pending);
        show.move(1);
        show.photoDisplayed();
        assertEquals(1, state.index);
        assertNull(timer.pending);
        show.setPlaying(true);
        assertSame(show, timer.pending);
    }

    @Test public void disabledAutomaticPlaybackCanBeStartedManually() {
        state.playing = false;
        PhotoSlideshow show = slideshow(3, 5);
        show.setActive(true);
        show.photoDisplayed();
        assertNull(timer.pending);
        show.setPlaying(true);
        assertSame(show, timer.pending);
    }

    @Test public void backgroundOrDetachedPagesCannotAdvance() {
        PhotoSlideshow show = slideshow(3, 5);
        show.setActive(true);
        show.photoDisplayed();
        show.setActive(false);
        assertNull(timer.pending);
        show.run();
        assertEquals(0, state.index);
        show.setActive(true);
        assertSame(show, timer.pending);
    }

    @Test public void redundantWindowFocusEventsDoNotResetTheCountdown() {
        PhotoSlideshow show = slideshow(3, 5);
        show.setActive(true);
        show.photoDisplayed();
        show.setActive(true);
        assertEquals(1, timer.scheduled);
    }

    @Test public void singlePhotosAndEmptyFoldersNeverSchedule() {
        for (int count : new int[]{0, 1}) {
            PhotoSlideshow show = slideshow(count, 5);
            show.setActive(true);
            show.photoDisplayed();
            show.move(1);
            assertNull(timer.pending);
        }
        assertEquals(0, renders);
    }

    @Test public void carouselAndFullscreenSharePauseStateAndCurrentPhoto() {
        PhotoSlideshow carousel = slideshow(4, 5);
        carousel.setActive(true);
        carousel.photoDisplayed();
        carousel.setActive(false);
        Timer fullscreenTimer = new Timer();
        PhotoSlideshow fullscreen = new PhotoSlideshow(state, 4, 5, fullscreenTimer, () -> renders++);
        fullscreen.setActive(true);
        fullscreen.photoDisplayed();
        fullscreenTimer.fire();
        fullscreen.setPlaying(false);
        fullscreen.setActive(false);
        carousel.photoChanged();
        carousel.photoDisplayed();
        carousel.setActive(true);
        assertEquals(1, state.index);
        assertFalse(state.playing);
        assertNull(timer.pending);
        assertNull(fullscreenTimer.pending);
    }

    @Test public void delayBoundsUseSecondsWithoutOverflow() {
        assertEquals(5, PhotoSlideshow.DEFAULT_SECONDS);
        assertEquals(1, PhotoSlideshow.normalizeSeconds(0));
        assertEquals(1, PhotoSlideshow.normalizeSeconds(Integer.MIN_VALUE));
        assertEquals(9999, PhotoSlideshow.normalizeSeconds(Integer.MAX_VALUE));
        PhotoSlideshow show = slideshow(3, 9999);
        show.setActive(true);
        show.photoDisplayed();
        assertEquals(9999000L, timer.delay);
    }
}

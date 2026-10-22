package com.jojotv.home;

import org.junit.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import static org.junit.Assert.*;

public class PhotoLoadQueueTest {
    private final ArrayDeque<Runnable> workers = new ArrayDeque<>();
    private final List<String> loaded = new ArrayList<>();
    private final Executor executor = workers::add;

    private Map<String, PhotoLoadQueue.Request<String>> requests(String... keys) {
        Map<String, PhotoLoadQueue.Request<String>> result = new LinkedHashMap<>();
        for (String key : keys) result.put(key, new PhotoLoadQueue.Request<>(() -> { loaded.add(key); return key; }, value -> { }));
        return result;
    }

    private void drain() { while (!workers.isEmpty()) workers.remove().run(); }

    @Test public void loadsTheWholeWindowWithoutAnotherNavigationEvent() {
        PhotoLoadQueue<String> queue = new PhotoLoadQueue<>(executor, 2);
        queue.replace(requests("current", "next", "previous", "next2", "previous2"));
        assertEquals(2, workers.size());
        drain();
        assertEquals(Arrays.asList("current", "next", "previous", "next2", "previous2"), loaded);
    }

    @Test public void aSlowCurrentPhotoDoesNotSerializeAllThePreviews() {
        PhotoLoadQueue<String> queue = new PhotoLoadQueue<>(executor, 2);
        queue.replace(requests("slowCurrent", "next", "previous", "next2"));
        Runnable slow = workers.remove();
        drain();
        assertEquals(Arrays.asList("next", "previous", "next2"), loaded);
        slow.run();
        assertEquals("slowCurrent", loaded.get(3));
    }

    @Test public void newNavigationDiscardsQueuedPhotosFromTheOldWindow() {
        PhotoLoadQueue<String> queue = new PhotoLoadQueue<>(executor, 1);
        queue.replace(requests("oldRunning", "obsolete1", "obsolete2"));
        queue.replace(requests("current", "next", "next2"));
        drain();
        assertEquals(Arrays.asList("oldRunning", "current", "next", "next2"), loaded);
    }

    @Test public void anAlreadyLoadingNeighbourIsReusedWhenItBecomesCurrent() {
        PhotoLoadQueue<String> queue = new PhotoLoadQueue<>(executor, 2);
        queue.replace(requests("current", "next", "previous"));
        queue.replace(requests("next", "next2", "current"));
        drain();
        assertEquals(Arrays.asList("current", "next", "next2"), loaded);
    }

    @Test public void leavingThePageDropsEveryQueuedPrefetch() {
        PhotoLoadQueue<String> queue = new PhotoLoadQueue<>(executor, 1);
        queue.replace(requests("running", "next", "next2"));
        queue.clear();
        drain();
        assertEquals(Arrays.asList("running"), loaded);
    }

    @Test public void aReturnedPageCanResumePreloading() {
        PhotoLoadQueue<String> queue = new PhotoLoadQueue<>(executor, 1);
        queue.replace(requests("old"));
        queue.clear();
        drain();
        queue.replace(requests("restored", "next"));
        drain();
        assertEquals(Arrays.asList("old", "restored", "next"), loaded);
    }

    @Test public void failedWorkDoesNotStrandTheRemainingPhotos() {
        PhotoLoadQueue<String> queue = new PhotoLoadQueue<>(executor, 1);
        Map<String, PhotoLoadQueue.Request<String>> work = requests("broken", "next", "next2");
        work.put("broken", new PhotoLoadQueue.Request<>(() -> { throw new IllegalStateException("decode failed"); }, value -> assertNull(value)));
        queue.replace(work);
        drain();
        assertEquals(Arrays.asList("next", "next2"), loaded);
    }

    @Test public void stoppedExecutorsDoNotLeavePhantomRunningRequests() {
        PhotoLoadQueue<String> queue = new PhotoLoadQueue<>(task -> { throw new RejectedExecutionException(); }, 2);
        queue.replace(requests("current", "next"));
        queue.clear();
        queue.replace(requests("current"));
        assertTrue(loaded.isEmpty());
    }

    @Test public void prefetchedBitmapIsDeliveredToItsNewActiveSlot() {
        PhotoLoadQueue<String> queue = new PhotoLoadQueue<>(executor, 1);
        List<String> delivered = new ArrayList<>();
        Map<String, PhotoLoadQueue.Request<String>> first = new LinkedHashMap<>();
        first.put("next", new PhotoLoadQueue.Request<>(() -> "bitmap", value -> delivered.add("oldSlot")));
        queue.replace(first);
        Map<String, PhotoLoadQueue.Request<String>> second = new LinkedHashMap<>();
        second.put("next", new PhotoLoadQueue.Request<>(() -> { fail("Must not decode twice"); return null; }, value -> delivered.add("active:" + value)));
        queue.replace(second);
        drain();
        assertEquals(Arrays.asList("active:bitmap"), delivered);
    }

    @Test public void detachedOrObsoleteViewsNeverReceiveLateBitmaps() {
        PhotoLoadQueue<String> queue = new PhotoLoadQueue<>(executor, 1);
        Map<String, PhotoLoadQueue.Request<String>> first = new LinkedHashMap<>();
        first.put("old", new PhotoLoadQueue.Request<>(() -> "bitmap", value -> fail("Obsolete callback")));
        queue.replace(first);
        queue.clear();
        drain();
    }

    @Test public void rapidNavigationKeepsOnlyTheLatestWindowQueued() {
        PhotoLoadQueue<String> queue = new PhotoLoadQueue<>(executor, 2);
        for (int index = 0; index < 100; index++) {
            String value = String.valueOf(index);
            queue.replace(requests(value, value + "+1", value + "+2", value + "-1", value + "-2"));
        }
        assertEquals(2, workers.size());
        drain();
        assertEquals(Arrays.asList("0", "0+1", "99", "99+1", "99+2", "99-1", "99-2"), loaded);
    }
}

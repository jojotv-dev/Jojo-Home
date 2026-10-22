package com.jojotv.home;

import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class FireTvRemoteClientTest {
    @Test public void preparationDoesNotWakeAnAlreadyListeningApi() throws Exception {
        AtomicInteger probes = new AtomicInteger();
        FireTvRemoteClient.prepareWhenNeeded(probes::incrementAndGet,
                () -> fail("Listening API must not be woken"));
        assertEquals(1, probes.get());
    }

    @Test public void preparationWakesAnUnavailableApiOnce() throws Exception {
        AtomicInteger wakes = new AtomicInteger();
        FireTvRemoteClient.prepareWhenNeeded(() -> { throw new IOException("Connection refused"); },
                wakes::incrementAndGet);
        assertEquals(1, wakes.get());
    }

    @Test public void preparationReportsWakeFailure() throws Exception {
        IOException failure = new IOException("DIAL timeout");
        try {
            FireTvRemoteClient.prepareWhenNeeded(() -> { throw new IOException("Connection refused"); },
                    () -> { throw failure; });
            fail("Expected wake failure");
        } catch (IOException actual) {
            assertSame(failure, actual);
        }
    }

    @Test public void readyApiLaunchesWithoutWakeOrDelay() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        int attempt = FireTvRemoteClient.launchWhenReady(calls::incrementAndGet,
                () -> fail("Ready API must not be woken"), () -> 0L,
                delay -> fail("Ready API must not wait"));
        assertEquals(1, attempt);
        assertEquals(1, calls.get());
    }

    @Test public void coldApiIsWokenOnlyAfterFirstConnectionFailure() throws Exception {
        List<String> requests = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        int attempt = FireTvRemoteClient.launchWhenReady(() -> {
            requests.add("launch");
            if (calls.incrementAndGet() == 1) throw new IOException("Connection refused");
        }, () -> requests.add("wake"), () -> 0L,
                delay -> fail("First retry after wake must be immediate"));
        assertEquals(2, attempt);
        assertEquals(Arrays.asList("launch", "wake", "launch"), requests);
    }

    @Test public void wakeTimeoutDoesNotPreventHttpsLaunch() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong clock = new AtomicLong();
        int attempt = FireTvRemoteClient.launchWhenReady(() -> {
            if (calls.incrementAndGet() == 1) throw new IOException("Connection refused");
        }, () -> {
            clock.addAndGet(1_000L);
            throw new IOException("DIAL timeout");
        }, clock::get, delay -> fail("HTTPS retry must be immediate"));
        assertEquals(2, attempt);
    }

    @Test public void startingApiRetriesWithoutRepeatedWakeRequests() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger wakes = new AtomicInteger();
        AtomicLong clock = new AtomicLong();
        int attempt = FireTvRemoteClient.launchWhenReady(() -> {
            if (calls.incrementAndGet() < 4) throw new IOException("API starting");
        }, wakes::incrementAndGet, clock::get, clock::addAndGet);
        assertEquals(4, attempt);
        assertEquals(1, wakes.get());
        assertEquals(500L, clock.get());
    }

    @Test public void httpRejectionIsNotRetriedOrWoken() throws Exception {
        FireTvRemoteClient.FireTvHttpException rejection = new FireTvRemoteClient.FireTvHttpException(401);
        try {
            FireTvRemoteClient.launchWhenReady(() -> { throw rejection; },
                    () -> fail("Rejected API must not be woken"), () -> 0L,
                    delay -> fail("Rejected request must not be retried"));
            fail("Expected HTTP rejection");
        } catch (FireTvRemoteClient.FireTvHttpException actual) {
            assertSame(rejection, actual);
        }
    }

    @Test public void cancelledWakeRequestIsNotRetriedOrWoken() throws Exception {
        java.util.concurrent.CancellationException cancelled = new java.util.concurrent.CancellationException();
        try {
            FireTvRemoteClient.launchWhenReady(() -> { throw cancelled; },
                    () -> fail("Cancelled wake must not restart the API"), () -> 0L,
                    delay -> fail("Cancelled wake must not wait or retry"));
            fail("Expected cancellation");
        } catch (java.util.concurrent.CancellationException actual) {
            assertSame(cancelled, actual);
        }
    }

    @Test public void cancellationAfterPreparationStopsTheHttpsRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger wakes = new AtomicInteger();
        try {
            FireTvRemoteClient.launchWhenReady(() -> {
                if (calls.incrementAndGet() == 1) throw new IOException("Connection refused");
                throw new java.util.concurrent.CancellationException();
            }, wakes::incrementAndGet, () -> 0L,
                    delay -> fail("Cancelled wake must not wait"));
            fail("Expected cancellation");
        } catch (java.util.concurrent.CancellationException expected) { }
        assertEquals(2, calls.get());
        assertEquals(1, wakes.get());
    }

    @Test public void unavailableApiStopsAtRetryDeadline() throws Exception {
        AtomicLong clock = new AtomicLong();
        AtomicInteger wakes = new AtomicInteger();
        IOException unavailable = new IOException("Connection refused");
        try {
            FireTvRemoteClient.launchWhenReady(() -> { throw unavailable; },
                    wakes::incrementAndGet, clock::get, clock::addAndGet);
            fail("Expected connection failure");
        } catch (IOException actual) {
            assertSame(unavailable, actual);
        }
        assertEquals(1, wakes.get());
        assertEquals(3_500L, clock.get());
    }

    @Test public void wakeTimeCountsTowardsRetryDeadline() throws Exception {
        AtomicLong clock = new AtomicLong();
        AtomicInteger calls = new AtomicInteger();
        IOException unavailable = new IOException("Connection refused");
        try {
            FireTvRemoteClient.launchWhenReady(() -> {
                calls.incrementAndGet();
                throw unavailable;
            }, () -> clock.addAndGet(3_500L), clock::get,
                    delay -> fail("Expired retry budget must not wait"));
            fail("Expected connection failure");
        } catch (IOException actual) {
            assertSame(unavailable, actual);
        }
        assertEquals(1, calls.get());
    }
}

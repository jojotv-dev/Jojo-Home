package com.jojotv.home;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Keeps only the current photo window queued, in visible-photo priority order. */
final class PhotoLoadQueue<T> {
    static final class Request<T> {
        final Callable<T> load;
        final Consumer<T> complete;

        Request(Callable<T> load, Consumer<T> complete) {
            this.load = load;
            this.complete = complete;
        }
    }

    private final Executor executor;
    private final int parallelism;
    private final LinkedHashMap<String, Request<T>> pending = new LinkedHashMap<>();
    private final Map<String, Request<T>> running = new LinkedHashMap<>();

    PhotoLoadQueue(Executor executor, int parallelism) {
        this.executor = executor;
        this.parallelism = Math.max(1, parallelism);
    }

    synchronized void replace(Map<String, Request<T>> requests) {
        pending.clear();
        for (String key : running.keySet()) running.put(key, requests.get(key));
        for (Map.Entry<String, Request<T>> request : requests.entrySet()) {
            if (!running.containsKey(request.getKey())) pending.put(request.getKey(), request.getValue());
        }
        dispatch();
    }

    synchronized void clear() {
        pending.clear();
        for (String key : running.keySet()) running.put(key, null);
    }

    private void dispatch() {
        while (running.size() < parallelism && !pending.isEmpty()) {
            Iterator<Map.Entry<String, Request<T>>> iterator = pending.entrySet().iterator();
            Map.Entry<String, Request<T>> next = iterator.next();
            String key = next.getKey();
            Request<T> request = next.getValue();
            iterator.remove();
            running.put(key, request);
            try {
                executor.execute(() -> {
                    T result = null;
                    try { result = request.load.call(); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    catch (Exception ignored) { }
                    finally {
                        Request<T> recipient;
                        synchronized (PhotoLoadQueue.this) {
                            recipient = running.remove(key);
                            dispatch();
                        }
                        if (recipient != null) recipient.complete.accept(result);
                    }
                });
            } catch (RejectedExecutionException stopped) {
                running.remove(key);
                pending.clear();
                return;
            }
        }
    }
}

package com.fish5.automation;

import com.fish5.utils.DaemonExecutors;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single worker thread for blocking automation sequences (menu flows, bazaar
 * buys). Only one job may run at a time; everything that touches Minecraft
 * state must go through mc.execute().
 */
public final class AutoWorker {

    private static final ExecutorService EXECUTOR = DaemonExecutors.single("fish5-automation");

    private static final AtomicReference<String> currentJob = new AtomicReference<>(null);

    /** Set by the user cancel-watcher; the running job polls it and bails cleanly. */
    private static volatile boolean abort = false;
    /** When the current job started — the watcher grants a short grace before arming. */
    public static volatile long jobStartedAt = 0;

    private AutoWorker() {}

    /** Submit a job; returns false (and skips) if another job is running. */
    public static boolean submit(String name, Runnable job) {
        if (!currentJob.compareAndSet(null, name)) {
            Msg.LOG.info("[AutoWorker] '{}' skipped — '{}' still running", name, currentJob.get());
            Msg.chat("§eBusy — '" + currentJob.get() + "' is still running.");
            return false;
        }
        EXECUTOR.execute(() -> {
            abort = false;
            jobStartedAt = System.currentTimeMillis();
            Msg.LOG.info("[AutoWorker] '{}' started", name);
            try {
                job.run();
            } catch (Exception e) {
                Msg.LOG.error("[AutoWorker] '{}' crashed", name, e);
                Msg.chat("§cAutomation error: " + e.getMessage());
            } finally {
                currentJob.set(null);
                Msg.LOG.info("[AutoWorker] '{}' finished", name);
            }
        });
        return true;
    }

    public static boolean isBusy() {
        return currentJob.get() != null;
    }

    /** Ask the running job to stop (from the render thread / cancel-watcher). */
    public static void requestAbort() {
        if (currentJob.get() != null) abort = true;
    }

    /** True once a cancel has been requested for the current job. */
    public static boolean aborted() {
        return abort;
    }

    public static void sleep(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (!abort) {
            long remaining = end - System.currentTimeMillis();
            if (remaining <= 0) return;
            try {
                Thread.sleep(Math.min(50, remaining));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Sleep base ± jitter, never below 25ms. */
    public static void sleepRandom(long baseMs, long jitterMs) {
        long delta = ThreadLocalRandom.current().nextLong(-jitterMs, jitterMs + 1);
        sleep(Math.max(25L, baseMs + delta));
    }

    public static long randomBetween(long min, long max) {
        return min >= max ? min : ThreadLocalRandom.current().nextLong(min, max + 1);
    }
}

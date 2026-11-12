package com.fish5.utils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Single-thread daemon executors whose thread exits after {@link #IDLE_KEEP_ALIVE_SECONDS} of no
 * work and is started again on the next task. Keeps each job type on its own thread (a blocking
 * task can't delay another queue) while costing no thread at all when idle.
 */
public final class DaemonExecutors {

    public static final long IDLE_KEEP_ALIVE_SECONDS = 30L;

    private DaemonExecutors() {}

    public static ExecutorService single(String threadName) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, IDLE_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), r -> daemon(r, threadName));
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /** A pending delayed task keeps the thread alive; it only exits once the queue is empty. */
    public static ScheduledExecutorService singleScheduled(String threadName) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> daemon(r, threadName));
        executor.setKeepAliveTime(IDLE_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static Thread daemon(Runnable r, String name) {
        Thread thread = new Thread(r, name);
        thread.setDaemon(true);
        return thread;
    }
}

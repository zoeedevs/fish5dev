package com.fish5.automation;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Lets automation jobs block until a chat line matches. The main mod feeds
 * every stripped, lowercased game message into offer().
 */
public final class ChatWaiter {

    private record Waiter(Predicate<String> test, CompletableFuture<String> future) {}

    private static final List<Waiter> waiters = new CopyOnWriteArrayList<>();

    private ChatWaiter() {}

    /** Future completes with the first chat line (stripped, lowercase) matching the predicate. */
    public static CompletableFuture<String> awaitMatch(Predicate<String> test) {
        CompletableFuture<String> future = new CompletableFuture<>();
        waiters.add(new Waiter(test, future));
        return future;
    }

    /** Stop watching for a match that no longer matters (e.g. after a timeout). */
    public static void cancel(CompletableFuture<String> future) {
        waiters.removeIf(w -> w.future == future);
        future.cancel(false);
    }

    public static void offer(String strippedLower) {
        for (Waiter w : waiters) {
            if (w.test.test(strippedLower)) {
                waiters.remove(w);
                w.future.complete(strippedLower);
            }
        }
    }
}

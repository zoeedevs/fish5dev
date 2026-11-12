package com.fish5.automation;

import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Warp commands with failure confirmation. Hypixel answers a failed warp with
 * "Couldn't warp you! Try again later." — when that shows up we wait 3s and
 * retry; no failure line within the window counts as success.
 */
public final class Warps {

    private static final long FAIL_WINDOW_MS = 4000;
    private static final long RETRY_DELAY_MS = 3000;

    private Warps() {}

    /** Blocking. Returns true when the warp went through (no failure message seen). */
    public static boolean warpWithRetry(Minecraft mc, String cmdNoSlash, int attempts) {
        for (int i = 1; i <= attempts; i++) {
            CompletableFuture<String> failed = ChatWaiter.awaitMatch(s -> s.contains("couldn't warp you"));
            mc.execute(() -> {
                if (mc.player != null && mc.getConnection() != null) mc.getConnection().sendCommand(cmdNoSlash);
            });
            try {
                failed.get(FAIL_WINDOW_MS, TimeUnit.MILLISECONDS);
                Msg.LOG.warn("[Warps] /{} failed (attempt {}/{}) — retrying in {}s",
                        cmdNoSlash, i, attempts, RETRY_DELAY_MS / 1000);
                AutoWorker.sleep(RETRY_DELAY_MS);
            } catch (TimeoutException e) {
                ChatWaiter.cancel(failed);
                return true;
            } catch (Exception e) {
                ChatWaiter.cancel(failed);
                return true; // interrupted — treat as sent
            }
        }
        Msg.LOG.warn("[Warps] /{} failed after {} attempts", cmdNoSlash, attempts);
        return false;
    }
}

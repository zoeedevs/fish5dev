// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.session;

import com.fish5.config.MacroConfig;
import com.fish5.macro.MacroStateManager;
import com.fish5.utils.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

// scheduled breaks: macro for ~N minutes, then rest ~M minutes, both randomized
// the countdown only runs while a macro runs; when it expires the break waits for the macro's safe point
// driven from the client tick (which keeps firing on the disconnected/title screens), so it costs no thread
public final class RestScheduler {
    private enum Phase { IDLE, COUNTING, DUE, RESTING_ONLINE, RESTING_OFFLINE, RECONNECTING }

    private static final String FALLBACK_SERVER = "mc.hypixel.net";
    private static final long RECONNECT_TIMEOUT_MS = 90_000L;
    // give the lobby a moment to load the scoreboard and tab list before the macro reads them
    private static final long RESUME_SETTLE_MIN_MS = 4_000L;
    private static final long RESUME_SETTLE_MAX_MS = 9_000L;

    private static Phase phase = Phase.IDLE;
    private static long remainingMs;
    private static long lastTickMs;
    private static long breakEndsAtMs;
    private static long breakDurationMs;
    private static long reconnectStartedAtMs;
    private static long resumeAtMs;
    private static volatile boolean intentionalDisconnect = false;
    private static String serverAddress = FALLBACK_SERVER;
    private static BooleanSupplier safePoint = () -> true;
    private static final CopyOnWriteArrayList<Runnable> RESUME_LISTENERS = new CopyOnWriteArrayList<>();

    private RestScheduler() {}

    // the macro reports when stopping is harmless (e.g. not mid-fight, line reeled in)
    public static void setSafePoint(BooleanSupplier supplier) {
        safePoint = supplier == null ? () -> true : supplier;
    }

    // run after a break ends (and after the reconnect has settled); the macro re-warps and calls startMacro itself
    public static void addResumeListener(Runnable listener) {
        RESUME_LISTENERS.addIfAbsent(listener);
    }

    public static void removeResumeListener(Runnable listener) {
        RESUME_LISTENERS.remove(listener);
    }

    public static boolean isOnBreak() {
        return phase == Phase.RESTING_ONLINE || phase == Phase.RESTING_OFFLINE || phase == Phase.RECONNECTING;
    }

    public static boolean isBreakDue() {
        return phase == Phase.DUE;
    }

    public static long getRemainingMacroMs() {
        return phase == Phase.COUNTING ? Math.max(0L, remainingMs) : 0L;
    }

    public static long getBreakEndsAtMs() {
        return breakEndsAtMs;
    }

    public static long getBreakDurationMs() {
        return breakDurationMs;
    }

    public static boolean willReconnect() {
        return MacroConfig.REST_RECONNECT.get() && !BanGuard.isLocked();
    }

    // drops the countdown and any break in progress, including a pending reconnect
    public static void cancel() {
        phase = Phase.IDLE;
        remainingMs = 0L;
        breakEndsAtMs = 0L;
        breakDurationMs = 0L;
        resumeAtMs = 0L;
    }

    static boolean consumeIntentionalDisconnect() {
        boolean was = intentionalDisconnect;
        intentionalDisconnect = false;
        return was;
    }

    public static void tick(Minecraft client) {
        long now = System.currentTimeMillis();
        if (!MacroConfig.REST_ENABLED.get()) {
            if (phase == Phase.COUNTING || phase == Phase.DUE) {
                cancel();
            }
            if (!isOnBreak()) {
                return;
            }
        }

        switch (phase) {
            case IDLE -> {
                if (MacroStateManager.isMacroRunning()) {
                    remainingMs = randomizedMinutes(
                            MacroConfig.REST_SCRIPTING_MIN_MINUTES.get(), MacroConfig.REST_SCRIPTING_MAX_MINUTES.get());
                    lastTickMs = now;
                    phase = Phase.COUNTING;
                }
            }
            case COUNTING -> {
                long delta = now - lastTickMs;
                lastTickMs = now;
                // stopping the macro pauses the session clock instead of resetting it
                if (MacroStateManager.isMacroRunning() && delta > 0) {
                    remainingMs -= delta;
                }
                if (remainingMs <= 0L) {
                    phase = Phase.DUE;
                    ClientUtils.sendMessage("§eScheduled break due. Resting at the next safe moment...", false);
                }
            }
            case DUE -> {
                if (MacroStateManager.isMacroRunning() && client.player != null && safePoint.getAsBoolean()) {
                    startBreak(client, now);
                }
            }
            case RESTING_ONLINE -> {
                if (now >= breakEndsAtMs) {
                    ClientUtils.sendMessage("§aBreak over.", false);
                    finishBreak();
                }
            }
            case RESTING_OFFLINE -> {
                if (now < breakEndsAtMs) {
                    return;
                }
                if (!willReconnect() || client.player != null) {
                    finishBreak();
                    return;
                }
                reconnect(client);
                reconnectStartedAtMs = now;
                resumeAtMs = 0L;
                phase = Phase.RECONNECTING;
            }
            case RECONNECTING -> {
                if (client.player != null && client.level != null) {
                    if (resumeAtMs == 0L) {
                        resumeAtMs = now + ThreadLocalRandom.current().nextLong(RESUME_SETTLE_MIN_MS, RESUME_SETTLE_MAX_MS + 1);
                    } else if (now >= resumeAtMs) {
                        finishBreak();
                    }
                } else if (now - reconnectStartedAtMs > RECONNECT_TIMEOUT_MS) {
                    // left on whatever screen the failed connect produced; the user takes it from here
                    cancel();
                }
            }
        }
    }

    private static void startBreak(Minecraft client, long now) {
        breakDurationMs = randomizedMinutes(
                MacroConfig.REST_BREAK_MIN_MINUTES.get(), MacroConfig.REST_BREAK_MAX_MINUTES.get());
        breakEndsAtMs = now + breakDurationMs;
        MacroStateManager.stopMacro(client, "Scheduled break", false);

        if (!MacroConfig.REST_DISCONNECT.get()) {
            ClientUtils.sendMessage(String.format(java.util.Locale.US,
                    "§eScheduled break: idling for %.1f minutes.", breakDurationMs / 60_000.0), false);
            phase = Phase.RESTING_ONLINE;
            return;
        }

        ServerData current = client.getCurrentServer();
        serverAddress = current != null && current.ip != null && !current.ip.isBlank() ? current.ip : FALLBACK_SERVER;
        intentionalDisconnect = true;
        phase = Phase.RESTING_OFFLINE;
        ClientUtils.disconnectWithScreen(new RestScreen(), Component.literal("Scheduled break"));
    }

    private static void finishBreak() {
        phase = Phase.IDLE;
        breakEndsAtMs = 0L;
        resumeAtMs = 0L;
        for (Runnable listener : RESUME_LISTENERS) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                ClientUtils.sendDebugMessage("rest resume listener failed: " + e);
            }
        }
    }

    private static void reconnect(Minecraft client) {
        if (client.getConnection() != null) {
            return;
        }
        ServerData serverData = new ServerData("Hypixel", serverAddress, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(
                new TitleScreen(),
                client,
                ServerAddress.parseString(serverAddress),
                serverData,
                false,
                null);
    }

    // base +/- offset, never under one minute
    // uniform between min and max minutes (either order), never under a minute
    private static long randomizedMinutes(int minMinutes, int maxMinutes) {
        long lo = Math.max(1, Math.min(minMinutes, maxMinutes)) * 60_000L;
        long hi = Math.max(1, Math.max(minMinutes, maxMinutes)) * 60_000L;
        return hi > lo ? ThreadLocalRandom.current().nextLong(lo, hi + 1) : lo;
    }
}

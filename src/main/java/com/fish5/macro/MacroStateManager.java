package com.fish5.macro;

import com.fish5.failsafe.FailsafeManager;
import com.fish5.rotation.RotationManager;
import com.fish5.utils.ClientUtils;
import net.minecraft.client.Minecraft;

import java.util.concurrent.CopyOnWriteArrayList;

// the single "is a macro running" switch the failsafes read; fish5 macros call start/stop/setState
public final class MacroStateManager {
    private static volatile MacroState.State state = MacroState.State.OFF;
    private static final CopyOnWriteArrayList<Runnable> STOP_LISTENERS = new CopyOnWriteArrayList<>();

    private MacroStateManager() {}

    public static MacroState.State getCurrentState() {
        return state;
    }

    public static boolean isMacroRunning() {
        return state != MacroState.State.OFF;
    }

    public static void setState(MacroState.State next) {
        state = next == null ? MacroState.State.OFF : next;
    }

    public static void startMacro(Minecraft client) {
        FailsafeManager.resetRuntimeState();
        FailsafeManager.syncSelectedSlotFromClient(client);
        FailsafeManager.syncExpectedRotationFromClient(client);
        state = MacroState.State.RUNNING;
    }

    // the macro registers here so a failsafe stop can tear down its own state machine
    public static void addStopListener(Runnable listener) {
        STOP_LISTENERS.addIfAbsent(listener);
    }

    public static void removeStopListener(Runnable listener) {
        STOP_LISTENERS.remove(listener);
    }

    public static void stopMacro(Minecraft client, String reason, boolean announce) {
        if (state == MacroState.State.OFF) {
            return;
        }
        state = MacroState.State.OFF;
        RotationManager.cancelRotation();
        ClientUtils.forceReleaseKeys();
        for (Runnable listener : STOP_LISTENERS) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                ClientUtils.sendDebugMessage("stop listener failed: " + e);
            }
        }
        ClientUtils.sendDebugMessage("Macro stopped: " + reason);
        if (announce) {
            ClientUtils.sendMessage("§cMacro stopped: " + reason, false);
        }
    }
}

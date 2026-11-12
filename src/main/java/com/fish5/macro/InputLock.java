package com.fish5.macro;

import com.fish5.features.impl.render.FreeCam;
import com.fish5.features.impl.render.FreeLook;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Input lock: while a macro runs, the player's own keyboard and mouse can't disturb it.
 *
 * <ul>
 *   <li>Key presses in game (no screen open) are dropped, except Escape, chat, the player list,
 *       screenshot / fullscreen / F1 / F3, and the movement keys while Freecam is active (they fly the
 *       camera). fish5's own keybinds (macro toggle, Freecam, Freelook, GUI) read the physical key state
 *       directly, so they keep working. Key releases always go through, so nothing gets stuck down.</li>
 *   <li>Mouse clicks are dropped; turning and scrolling only work in Freecam / Freelook
 *       ({@code MouseHandlerMixin}, {@code ScrollFreeLookMixin}).</li>
 *   <li>Physical movement keys never move the player ({@code KeyboardInputMixin}).</li>
 *   <li>Chat works, commands don't ({@code ChatScreenInputLockMixin}).</li>
 *   <li>The cursor is released except in Freecam / Freelook ({@code UngrabMouse}).</li>
 * </ul>
 * The macro's own input goes through {@code ClientUtils} / the trackers, not these paths, so it is unaffected.
 */
public final class InputLock {
    private InputLock() {}

    public static final String COMMAND_BLOCKED = "§cCurrently macroing, disable the macro to use commands.";

    /** True while a macro runs in a world. Always on; there is deliberately no setting to turn it off. */
    public static boolean isActive() {
        Minecraft mc = Minecraft.getInstance();
        return MacroStateManager.isMacroRunning() && mc.player != null && mc.level != null;
    }

    /** True while the camera is detached from the macro's view, so the mouse may look around. */
    public static boolean cameraFree() {
        return FreeCam.isActive() || FreeLook.isActive();
    }

    /** Whether a physical key press (no screen open) may reach vanilla while the lock is active. */
    public static boolean allowsKeyPress(Minecraft mc, KeyEvent event) {
        int key = event.key();
        if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_F1 || key == GLFW.GLFW_KEY_F3) {
            return true;
        }
        Options o = mc.options;
        if (matches(o.keyChat, event) || matches(o.keyCommand, event) || matches(o.keyPlayerList, event)
                || matches(o.keyScreenshot, event) || matches(o.keyFullscreen, event)) {
            return true;
        }
        if (FreeCam.isActive()) {
            return matches(o.keyUp, event) || matches(o.keyDown, event) || matches(o.keyLeft, event)
                    || matches(o.keyRight, event) || matches(o.keyJump, event) || matches(o.keyShift, event)
                    || matches(o.keySprint, event);
        }
        return false;
    }

    private static boolean matches(KeyMapping mapping, KeyEvent event) {
        return !mapping.isUnbound() && mapping.matches(event);
    }
}

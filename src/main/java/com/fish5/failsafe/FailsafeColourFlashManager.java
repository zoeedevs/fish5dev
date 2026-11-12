// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.failsafe;

import com.fish5.config.MacroConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class FailsafeColourFlashManager {
    private static volatile long triggeredAtNanos = Long.MIN_VALUE;
    private static volatile boolean active;

    private FailsafeColourFlashManager() {
    }

    public static void trigger() {
        if (MacroConfig.FAILSAFE_COLOUR_FLASH_ENABLED.get()) {
            triggeredAtNanos = System.nanoTime();
            active = true;
        }
    }

    public static void dismiss() {
        active = false;
    }

    public static void render(GuiGraphicsExtractor graphics) {
        if (!MacroConfig.FAILSAFE_COLOUR_FLASH_ENABLED.get()) {
            active = false;
            return;
        }

        if (!active) {
            return;
        }

        long elapsedNanos = Math.max(0L, System.nanoTime() - triggeredAtNanos);
        long swapDelayNanos = getSwapDelayNanos();
        boolean useSecondColour = (elapsedNanos / swapDelayNanos & 1L) != 0L;
        int colour = useSecondColour
                ? MacroConfig.FAILSAFE_COLOUR_FLASH_SECOND.get()
                : MacroConfig.FAILSAFE_COLOUR_FLASH_FIRST.get();
        float opacity = Math.max(0.0f, Math.min(1.0f, MacroConfig.FAILSAFE_COLOUR_FLASH_OPACITY.get()));
        if (opacity <= 0.0f) {
            return;
        }
        int alpha = Math.round(opacity * ((colour >>> 24) & 0xFF));
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), (alpha << 24) | (colour & 0x00FFFFFF));
    }

    private static long getSwapDelayNanos() {
        float delaySeconds = MacroConfig.FAILSAFE_COLOUR_FLASH_SWAP_DELAY_SECONDS.get();
        return Math.max(1L, Math.round(delaySeconds * 1_000_000_000.0));
    }
}

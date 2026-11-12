// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.session;

import com.fish5.macro.MacroStateManager;
import com.fish5.mixin.MouseHandlerAccessor;
import net.minecraft.client.Minecraft;

// drops the mouse delta that piles up while the window is unfocused, so refocusing mid-macro can't snap the view
public final class RefocusInputGuard {
    private static boolean wasFocused = true;

    private RefocusInputGuard() {}

    public static void reset() {
        wasFocused = true;
    }

    public static void tick(Minecraft client) {
        if (client == null || client.getWindow() == null) return;

        boolean focused = client.getWindow().isFocused();
        boolean regained = focused && !wasFocused;
        wasFocused = focused;

        if (!regained || !MacroStateManager.isMacroRunning()) return;

        // a snap the macro didn't make would also read as an external rotation and trip the rotation failsafe
        MouseHandlerAccessor handler = (MouseHandlerAccessor) client.mouseHandler;
        handler.fish5$setAccumulatedDX(0.0);
        handler.fish5$setAccumulatedDY(0.0);
        handler.fish5$setIgnoreFirstMove(true);
    }
}

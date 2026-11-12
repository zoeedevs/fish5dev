// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.failsafe;

import com.fish5.config.MacroConfig;
import com.fish5.utils.ClientUtils;
import com.fish5.utils.WindowFocusHelper;
import net.minecraft.client.Minecraft;

public final class FailsafeWindowFocusManager {
    private FailsafeWindowFocusManager() {
    }

    public static void bringWindowToFront() {
        if (!MacroConfig.FAILSAFE_AUTO_ALT_TAB.get()) {
            ClientUtils.sendDebugMessage("FailsafeWindowFocus: auto alt-tab disabled");
            return;
        }
        focusWindowNow();
    }

    public static void focusWindowNow() {
        if (Minecraft.getInstance() == null) {
            ClientUtils.sendDebugMessage("FailsafeWindowFocus: client is null");
            return;
        }
        WindowFocusHelper.focusMinecraftWindow("FailsafeWindowFocus");
    }
}

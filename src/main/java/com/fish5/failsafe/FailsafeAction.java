// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.failsafe;

import java.util.Locale;

public enum FailsafeAction {
    STOP,
    IGNORE;

    public static FailsafeAction fromConfig(String value) {
        if (value == null || value.isBlank()) {
            return STOP;
        }

        try {
            return FailsafeAction.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return STOP;
        }
    }
}

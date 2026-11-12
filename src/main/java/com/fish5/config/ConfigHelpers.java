// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.config;

import java.util.concurrent.ThreadLocalRandom;

public final class ConfigHelpers {
    private ConfigHelpers() {}

    public static int getRandomizedDelay(int baseDelay) {
        int jitter = Math.round(Math.abs(baseDelay) * 0.15f);
        return getRandomizedDelay(baseDelay - jitter, baseDelay + jitter);
    }

    public static int getRandomizedDelay(int minDelay, int maxDelay) {
        int lower = Math.max(0, Math.min(minDelay, maxDelay));
        int upper = Math.max(0, Math.max(minDelay, maxDelay));
        if (lower == upper) {
            return lower;
        }
        return ThreadLocalRandom.current().nextInt(lower, upper + 1);
    }
}

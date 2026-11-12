// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.pathfinding.util;

import com.fish5.pathfinding.wrapper.PathPosition;

public final class RegionKey {
    private static final long MASK_Y  = 0xFFFL;       // 12 bit
    private static final long MASK_XZ = 0x3FFFFFFL;   // 26 bit
    private static final int  SHIFT_Z = 12;
    private static final int  SHIFT_X = 38;           // 12 + 26

    private RegionKey() {}

    public static long pack(PathPosition pos) {
        return pack(pos.flooredX(), pos.flooredY(), pos.flooredZ());
    }

    public static long pack(int x, int y, int z) {
        return ((long) x & MASK_XZ) << SHIFT_X
             | ((long) z & MASK_XZ) << SHIFT_Z
             | ((long) y & MASK_Y);
    }
}

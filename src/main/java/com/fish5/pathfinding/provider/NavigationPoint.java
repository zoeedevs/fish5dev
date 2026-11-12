// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.pathfinding.provider;

public interface NavigationPoint {
    boolean isTraversable();
    boolean hasFloor();
    double getFloorLevel();
    boolean isClimbable();
    boolean isLiquid();
}

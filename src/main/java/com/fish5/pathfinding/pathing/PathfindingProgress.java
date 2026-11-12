// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.pathfinding.pathing;

import com.fish5.pathfinding.wrapper.PathPosition;

public final class PathfindingProgress {
    public final PathPosition start;
    public final PathPosition current;
    public final PathPosition target;

    public PathfindingProgress(PathPosition start, PathPosition current, PathPosition target) {
        this.start = start;
        this.current = current;
        this.target = target;
    }
}

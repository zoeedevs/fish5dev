// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.pathfinding.pathing;

import com.fish5.pathfinding.wrapper.PathPosition;
import com.fish5.pathfinding.wrapper.PathVector;

@FunctionalInterface
public interface INeighborStrategy {
    Iterable<PathVector> getOffsets();

    default Iterable<PathVector> getOffsets(PathPosition currentPosition) {
        return getOffsets();
    }
}

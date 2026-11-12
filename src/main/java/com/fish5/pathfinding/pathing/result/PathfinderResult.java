// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.pathfinding.pathing.result;

public interface PathfinderResult {
    boolean successful();
    boolean hasFailed();
    boolean hasFallenBack();
    PathState getPathState();
    Path getPath();
}

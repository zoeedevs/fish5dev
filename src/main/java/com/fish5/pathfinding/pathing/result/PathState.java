// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.pathfinding.pathing.result;

public enum PathState {
    ABORTED,
    FOUND,
    FAILED,
    FALLBACK,
    LENGTH_LIMITED,
    MAX_ITERATIONS_REACHED
}

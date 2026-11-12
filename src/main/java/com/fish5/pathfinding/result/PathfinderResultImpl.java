// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.pathfinding.result;

import com.fish5.pathfinding.pathing.result.Path;
import com.fish5.pathfinding.pathing.result.PathState;
import com.fish5.pathfinding.pathing.result.PathfinderResult;

public final class PathfinderResultImpl implements PathfinderResult {
    private final PathState state;
    private final Path path;

    public PathfinderResultImpl(PathState state, Path path) {
        this.state = state;
        this.path  = path;
    }

    @Override public boolean successful()    { return state == PathState.FOUND; }
    @Override public boolean hasFailed()     { return state == PathState.FAILED; }
    @Override public boolean hasFallenBack() {
        return state == PathState.FALLBACK
            || state == PathState.MAX_ITERATIONS_REACHED
            || state == PathState.LENGTH_LIMITED;
    }
    @Override public PathState getPathState() { return state; }
    @Override public Path getPath()           { return path; }
}

// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.pathfinding.pathing;

import com.fish5.pathfinding.pathing.context.EnvironmentContext;
import com.fish5.pathfinding.pathing.result.PathfinderResult;
import com.fish5.pathfinding.wrapper.PathPosition;
import java.util.concurrent.CompletionStage;

public interface Pathfinder {
    default CompletionStage<PathfinderResult> findPath(PathPosition start, PathPosition target) {
        return findPath(start, target, null);
    }

    CompletionStage<PathfinderResult> findPath(PathPosition start, PathPosition target,
                                               EnvironmentContext context);

    void abort();
}

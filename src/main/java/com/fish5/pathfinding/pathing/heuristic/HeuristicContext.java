// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.pathfinding.pathing.heuristic;

import com.fish5.pathfinding.pathing.PathfindingProgress;
import com.fish5.pathfinding.wrapper.PathPosition;

public final class HeuristicContext {
    public final PathfindingProgress pathfindingProgress;
    public final HeuristicWeights heuristicWeights;

    public HeuristicContext(PathfindingProgress pathfindingProgress, HeuristicWeights heuristicWeights) {
        this.pathfindingProgress = pathfindingProgress;
        this.heuristicWeights = heuristicWeights;
    }

    public HeuristicContext(PathPosition position, PathPosition startPosition,
                             PathPosition targetPosition, HeuristicWeights heuristicWeights) {
        this(new PathfindingProgress(startPosition, position, targetPosition), heuristicWeights);
    }

    public PathPosition position()      { return pathfindingProgress.current; }
    public PathPosition startPosition() { return pathfindingProgress.start; }
    public PathPosition targetPosition(){ return pathfindingProgress.target; }
}

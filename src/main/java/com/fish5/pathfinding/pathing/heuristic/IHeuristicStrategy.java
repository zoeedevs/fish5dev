// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.pathfinding.pathing.heuristic;

import com.fish5.pathfinding.wrapper.PathPosition;

public interface IHeuristicStrategy {
    double calculate(HeuristicContext context);
    double calculateTransitionCost(PathPosition from, PathPosition to);
}

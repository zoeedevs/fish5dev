// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.pathfinding.pathing.processing;

import com.fish5.pathfinding.pathing.processing.context.EvaluationContext;

public interface NodeProcessor extends Processor {
    default boolean isValid(EvaluationContext context) {
        return true;
    }

    default Cost calculateCostContribution(EvaluationContext context) {
        return Cost.ZERO;
    }
}

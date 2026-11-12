// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.pathfinding.pathing.processing.impl;

import com.fish5.pathfinding.movement.WalkabilityChecker;
import com.fish5.pathfinding.movement.FlightCollisionChecker;
import com.fish5.pathfinding.pathing.processing.Cost;
import com.fish5.pathfinding.pathing.processing.NodeProcessor;
import com.fish5.pathfinding.pathing.processing.context.EvaluationContext;
import com.fish5.pathfinding.wrapper.PathPosition;

// unlike the walking processor this only wants body clearance, no floor support
public final class FlyPathProcessor implements NodeProcessor {
    private static final double VERTICAL_COST = 0.08;

    private final FlightCollisionChecker checker;

    public FlyPathProcessor(WalkabilityChecker checker) {
        this(checker == null ? null : new FlightCollisionChecker(checker));
    }

    public FlyPathProcessor(FlightCollisionChecker checker) {
        this.checker = checker;
    }

    @Override
    public boolean isValid(EvaluationContext context) {
        if (checker == null) {
            return false;
        }

        PathPosition pos = context.getCurrentPathPosition();
        if (!hasFlightClearance(pos)) {
            return false;
        }

        PathPosition prev = context.getPreviousPathPosition();
        if (prev == null) {
            return true;
        }

        return checker.isClear(prev, pos);
    }

    @Override
    public Cost calculateCostContribution(EvaluationContext context) {
        PathPosition pos = context.getCurrentPathPosition();
        PathPosition prev = context.getPreviousPathPosition();
        if (checker == null || prev == null) {
            return Cost.ZERO;
        }

        double cost = Math.abs(pos.flooredY() - prev.flooredY()) * VERTICAL_COST
                + checker.clearanceCost(pos);

        return Cost.of(cost);
    }

    public boolean hasFlightClearance(PathPosition pos) {
        return checker != null && checker.hasClearance(pos);
    }

    public boolean hasFlightClearance(int x, int y, int z) {
        return hasFlightClearance(new PathPosition(x, y, z));
    }
}

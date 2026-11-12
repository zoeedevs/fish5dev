// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.pathfinding.pathing.processing.context;

import com.fish5.pathfinding.pathing.configuration.PathfinderConfiguration;
import com.fish5.pathfinding.pathing.context.EnvironmentContext;
import com.fish5.pathfinding.provider.NavigationPointProvider;
import com.fish5.pathfinding.wrapper.PathPosition;
import java.util.Map;

public interface EvaluationContext {
    PathPosition getCurrentPathPosition();
    PathPosition getPreviousPathPosition();       // nullable
    int getCurrentNodeDepth();
    double getCurrentNodeHeuristicValue();
    double getPathCostToPreviousPosition();
    double getBaseTransitionCost();
    SearchContext getSearchContext();
    PathPosition getGrandparentPathPosition();    // nullable
    default PathPosition getGreatGrandparentPathPosition() { return null; }

    default PathfinderConfiguration getPathfinderConfiguration() {
        return getSearchContext().getPathfinderConfiguration();
    }

    default NavigationPointProvider getNavigationPointProvider() {
        return getSearchContext().getNavigationPointProvider();
    }

    default Map<String, Object> getSharedData() {
        return getSearchContext().getSharedData();
    }

    default PathPosition getStartPathPosition() {
        return getSearchContext().getStartPathPosition();
    }

    default PathPosition getTargetPathPosition() {
        return getSearchContext().getTargetPathPosition();
    }

    default EnvironmentContext getEnvironmentContext() {
        return getSearchContext().getEnvironmentContext();
    }
}

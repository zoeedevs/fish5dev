// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.pathfinding.pathing.processing.context;

import com.fish5.pathfinding.pathing.configuration.PathfinderConfiguration;
import com.fish5.pathfinding.pathing.context.EnvironmentContext;
import com.fish5.pathfinding.provider.NavigationPointProvider;
import com.fish5.pathfinding.wrapper.PathPosition;
import java.util.Map;

public interface SearchContext {
    PathPosition getStartPathPosition();
    PathPosition getTargetPathPosition();
    PathfinderConfiguration getPathfinderConfiguration();
    NavigationPointProvider getNavigationPointProvider();
    Map<String, Object> getSharedData();
    EnvironmentContext getEnvironmentContext();
}

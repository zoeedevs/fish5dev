// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.pathfinding.pathing.processing;

import com.fish5.pathfinding.pathing.processing.context.SearchContext;

public interface Processor {
    default void initializeSearch(SearchContext context) {}
    default void finalizeSearch(SearchContext context) {}
}

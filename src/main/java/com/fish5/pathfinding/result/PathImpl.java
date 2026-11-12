// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.pathfinding.result;

import com.fish5.pathfinding.pathing.result.Path;
import com.fish5.pathfinding.wrapper.PathPosition;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public final class PathImpl implements Path {
    private final PathPosition start;
    private final PathPosition end;
    private final List<PathPosition> positions;

    public PathImpl(PathPosition start, PathPosition end, Collection<PathPosition> positions) {
        this.start = start;
        this.end   = end;
        this.positions = positions instanceof List<PathPosition> l ? l : List.copyOf(positions);
    }

    @Override public int length()                    { return positions.size(); }
    @Override public PathPosition getStart()         { return start; }
    @Override public PathPosition getEnd()           { return end; }
    @Override public Collection<PathPosition> collect() { return Collections.unmodifiableList(positions); }
    @Override public Iterator<PathPosition> iterator()  { return positions.iterator(); }
}

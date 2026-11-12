// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.macro;

public class MacroState {
    public enum State {
        OFF,
        // the macro owns input and every runtime failsafe watches it
        RUNNING,
        // a scripted step (warp, menu, recovery) is moving the camera or opening guis on purpose
        RECOVERING
    }

    public enum Location {
        GARDEN, CRYSTAL_HOLLOWS, HUB, LOBBY, LIMBO, UNKNOWN
    }
}

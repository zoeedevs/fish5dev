// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.utils;

import com.fish5.macro.MacroState;

import java.util.regex.Pattern;

public final class SkyblockLocation {
    private static final Pattern SKYBLOCK_TITLE = Pattern.compile("(?i)^SKYBLOCK(?:\\s|$)");

    private SkyblockLocation() {}

    public static MacroState.Location resolve(String sidebarTitle, boolean hasLobbyItems, String areaLine) {
        if (sidebarTitle == null) {
            return MacroState.Location.LIMBO;
        }
        String title = TablistUtils.stripColors(sidebarTitle).replace('\u00A0', ' ').trim();
        if (hasLobbyItems || !SKYBLOCK_TITLE.matcher(title).find()) {
            return MacroState.Location.LOBBY;
        }

        String area = TablistUtils.stripColors(areaLine).replace('\u00A0', ' ').trim();
        if (area.equalsIgnoreCase("Area: Garden")) {
            return MacroState.Location.GARDEN;
        }
        if (area.equalsIgnoreCase("Area: Crystal Hollows")) {
            return MacroState.Location.CRYSTAL_HOLLOWS;
        }
        return MacroState.Location.HUB;
    }
}

package com.fish5.sb;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Read-only helpers for SkyBlock state visible on the sidebar scoreboard.
 * Colour stripping, on-SkyBlock check, purse and tab-list reading. Render thread only.
 */
public final class SkyblockData {

    private static final Pattern STRIP_HEX_COLOR = Pattern.compile("(?i)§x(?:§[0-9a-f]){6}");
    private static final Pattern STRIP_COLOR = Pattern.compile("(?i)§[0-9a-z]");

    private SkyblockData() {}

    public static String stripColors(String s) {
        if (s == null || s.isEmpty()) return "";
        String out = STRIP_HEX_COLOR.matcher(s).replaceAll("");
        out = STRIP_COLOR.matcher(out).replaceAll("");
        return out.replace("§", "");
    }

    /**
     * The Hypixel SkyBlock item id of a stack (e.g. {@code ENCHANTED_CARROT}), read from the
     * {@code minecraft:custom_data} component's {@code id} tag, or null if the stack carries none
     * (vanilla items, glass-pane filler, etc.). This id matches the Bazaar product id for
     * items and keys both {@link NpcPrices} and {@code BazaarQuotes}.
     */
    public static String skyblockId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        String id = data.copyTag().getStringOr("id", "");
        return id.isEmpty() ? null : id;
    }

    /**
     * How many times an item's rarity has been upgraded (Recombobulator 3000), from the
     * {@code rarity_upgrades} tag. 0 if never recombobulated / absent. A value ≥1 means it can't be
     * recombed again.
     */
    public static int rarityUpgrades(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return 0;
        return data.copyTag().getIntOr("rarity_upgrades", 0);
    }

    /** Sidebar lines rebuilt as team prefix + entry + suffix (Hypixel hides text in team affixes). */
    public static List<String> getSidebarLines(Minecraft mc) {
        if (mc == null || mc.level == null || mc.player == null) return List.of();

        Scoreboard scoreboard = mc.level.getScoreboard();
        Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (sidebar == null) return List.of();

        List<String> lines = new ArrayList<>();
        for (PlayerScoreEntry entry : scoreboard.listPlayerScores(sidebar)) {
            String base = entry.ownerName() != null ? entry.ownerName().getString() : entry.owner();
            PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
            String full = team != null
                    ? team.getPlayerPrefix().getString() + base + team.getPlayerSuffix().getString()
                    : base;
            String clean = stripColors(full).trim();
            if (!clean.isEmpty()) lines.add(clean);
        }
        return lines;
    }

    /**
     * Tab-list ("hold Tab") display-name lines, one per fake-player entry Hypixel uses to draw the
     * Info panels. Unlike {@link #getSidebarLines}, which reads the always-on right-side scoreboard
     * (whose "Area:" line can lag behind or show the island-level area rather than exactly where you
     * are), this walks every currently-listed tab entry via its (possibly server-overridden) tab-list
     * display name. Render thread only; each entry is one visual line/cell of the grid.
     */
    public static List<String> getTabListLines(Minecraft mc) {
        if (mc == null || mc.getConnection() == null) return List.of();

        List<String> lines = new ArrayList<>();
        for (var info : mc.getConnection().getOnlinePlayers()) {
            Component comp = info.getTabListDisplayName();
            if (comp == null) continue;
            String clean = stripColors(comp.getString()).trim();
            if (!clean.isEmpty()) lines.add(clean);
        }
        return lines;
    }

    /** True when the sidebar objective title says SKYBLOCK. */
    public static boolean isOnSkyblock(Minecraft mc) {
        if (mc == null || mc.level == null) return false;
        Objective sidebar = mc.level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
        if (sidebar == null) return false;
        return stripColors(sidebar.getDisplayName().getString()).toLowerCase().contains("skyblock");
    }

    /** Purse in coins, -1 if not found. Handles "Purse: 26,000,000 (+300)" and Piggy banks. */
    public static long getPurse(Minecraft mc) {
        for (String line : getSidebarLines(mc)) {
            String lo = line.toLowerCase();
            String key = lo.contains("purse:") ? "purse:" : lo.contains("piggy:") ? "piggy:" : null;
            if (key == null) continue;
            try {
                String value = line.substring(lo.indexOf(key) + key.length()).trim();
                String main = value.split(" ")[0].replaceAll("[^0-9]", "");
                if (!main.isEmpty()) return Long.parseLong(main);
            } catch (Exception ignored) {}
        }
        return -1;
    }
}

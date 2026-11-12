package com.fish5.automation;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Logging + user-facing chat for Fish 5. */
public final class Msg {

    public static final Logger LOG = LoggerFactory.getLogger("fish5");

    /**
     * Global debug flag. When on, {@link #dbg} also echoes to chat; the log always records it.
     * Toggled by modules' Debug settings before they run.
     */
    public static volatile boolean debug = false;

    // "[Fish 5]" as a glossy purple tag: a dark→bright→dark sheen across the
    // letters gives a reflective highlight. Built as real per-character text
    // colours (TextColor) because vanilla ignores the §x legacy hex format.
    private static final Component PREFIX = buildPrefix("[Fish 5] ");

    private Msg() {}

    /**
     * Debug line: always logged at INFO with a [DBG] tag; also echoed to chat (grey) when
     * {@link #debug} is on. Use liberally for step-by-step automation tracing.
     */
    public static void dbg(String text) {
        LOG.info("[DBG] {}", text);
        if (debug) chat("§8[dbg] §7" + text);
    }

    /** Post a line to the player's chat (marshalled onto the render thread). */
    public static void chat(String text) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendSystemMessage(
                        Component.empty().append(PREFIX).append(Component.literal(text)));
            }
        });
    }

    /** Post a prebuilt (e.g. clickable) component to chat behind the [Fish 5] prefix. */
    public static void chat(Component text) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.empty().append(PREFIX).append(text));
            }
        });
    }

    /** Per-character purple gradient with a bright centre for a reflective look. */
    private static Component buildPrefix(String text) {
        int[] dark = {0x7A, 0x2B, 0xC4};   // deep purple (ends)
        int[] bright = {0xE6, 0xC2, 0xFF}; // pale lavender (centre highlight)
        MutableComponent out = Component.empty();
        int n = text.length();
        for (int i = 0; i < n; i++) {
            double t = n <= 1 ? 0.0 : (double) i / (n - 1);   // 0..1 across the tag
            double sheen = 1.0 - Math.abs(0.5 - t) * 2.0;     // 0 at ends, 1 at centre
            int r = lerp(dark[0], bright[0], sheen);
            int g = lerp(dark[1], bright[1], sheen);
            int b = lerp(dark[2], bright[2], sheen);
            out.append(Component.literal(String.valueOf(text.charAt(i)))
                    .setStyle(Style.EMPTY.withColor(TextColor.fromRgb((r << 16) | (g << 8) | b))));
        }
        return out;
    }

    private static int lerp(int a, int b, double t) {
        return (int) Math.round(a + (b - a) * t);
    }
}

package com.fish5.session;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

// shown while disconnected for a scheduled break; esc cancels the break and the auto-reconnect
public final class RestScreen extends Screen {
    private static final int BG = 0xFF0A0A14;
    private static final int BAR_BG = 0xFF26263A;
    private static final int BAR_FILL = 0xFF9B5DE5;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xFFA0A0B4;

    public RestScreen() {
        super(Component.literal("Scheduled break"));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, BG);

        long now = System.currentTimeMillis();
        long total = Math.max(1L, RestScheduler.getBreakDurationMs());
        long left = Math.max(0L, RestScheduler.getBreakEndsAtMs() - now);
        boolean onBreak = RestScheduler.isOnBreak();

        int cx = width / 2;
        int y = height / 2 - 30;
        centered(g, onBreak ? "Taking a scheduled break" : "Break cancelled", cx, y, TEXT);

        if (onBreak) {
            long secs = left / 1000L;
            centered(g, String.format(java.util.Locale.US, "%d:%02d left", secs / 60, secs % 60), cx, y + 14, TEXT_DIM);

            int barW = Math.min(240, width - 40);
            int barX = cx - barW / 2;
            int barY = y + 30;
            int fill = (int) (barW * (1.0 - (double) left / total));
            g.fill(barX, barY, barX + barW, barY + 4, BAR_BG);
            g.fill(barX, barY, barX + Math.max(0, Math.min(barW, fill)), barY + 4, BAR_FILL);

            centered(g, RestScheduler.willReconnect() ? "Reconnects automatically when the break ends"
                    : "Auto-reconnect is off", cx, y + 44, TEXT_DIM);
        }
        centered(g, "Esc: cancel the break", cx, y + 62, TEXT_DIM);
    }

    private void centered(GuiGraphicsExtractor g, String text, int cx, int y, int color) {
        g.text(font, Component.literal(text), cx - font.width(text) / 2, y, color, true);
    }

    @Override
    public void onClose() {
        RestScheduler.cancel();
        minecraft.gui.setScreen(new TitleScreen());
    }
}

package com.fish5.automation;

import com.fish5.mixin.AbstractContainerScreenAccessor;
import com.fish5.sb.SkyblockData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Client interaction helpers that avoid crafting raw packets:
 * commands go through {@code ClientPacketListener#sendCommand} (the vanilla
 * unsigned-command path) and container clicks route through the screen's own
 * {@code slotClicked} handler via {@link AbstractContainerScreenAccessor}.
 */
public final class ClientActions {

    private ClientActions() {}

    /**
     * Send a chat command (leading '/' optional) as the vanilla client would —
     * no manual packet. Marshalled onto the client thread.
     */
    public static void sendCommand(Minecraft mc, String cmd) {
        if (mc == null || cmd == null || cmd.isBlank()) return;
        mc.execute(() -> {
            if (mc.player == null || mc.getConnection() == null) return;
            if (cmd.startsWith("/")) {
                mc.getConnection().sendCommand(cmd.substring(1));
            } else {
                mc.getConnection().sendChat(cmd);
            }
            Msg.dbg("sendCommand: " + cmd);
        });
    }

    /** Left-click (PICKUP) a container slot through the screen's own handler. */
    public static void slotClick(Minecraft mc, int slotIndex) {
        slotClick(mc, slotIndex, 0, ContainerInput.PICKUP);
    }

    /**
     * Click a container slot without raw packets: call the screen's protected
     * {@code slotClicked} directly rather than {@code handleInventoryMouseClick}
     * or a raw packet. Marshalled onto the client thread.
     */
    public static void slotClick(Minecraft mc, int slotIndex, int mouseButton, ContainerInput type) {
        mc.execute(() -> {
            if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen) || mc.player == null) {
                Msg.LOG.warn("[ClientActions] slotClick({}) — no container open", slotIndex);
                return;
            }
            List<Slot> slots = screen.getMenu().slots;
            if (slotIndex < 0 || slotIndex >= slots.size()) {
                Msg.LOG.warn("[ClientActions] slotClick({}) out of bounds (size={})", slotIndex, slots.size());
                return;
            }
            Slot slot = slots.get(slotIndex);
            ItemStack stack = slot.getItem();
            Msg.dbg("slotClick idx=" + slotIndex + (type == ContainerInput.QUICK_MOVE ? " [shift]" : "") + " -> "
                    + (stack.isEmpty() ? "[empty]" : SkyblockData.stripColors(stack.getHoverName().getString())));
            ((AbstractContainerScreenAccessor) screen).fish5$invokeSlotClicked(slot, slot.index, mouseButton, type);
        });
    }

    /** Shift-click (QUICK_MOVE) a container slot — moves the stack across the open menu. */
    public static void shiftClick(Minecraft mc, int slotIndex) {
        slotClick(mc, slotIndex, 0, ContainerInput.QUICK_MOVE);
    }
}

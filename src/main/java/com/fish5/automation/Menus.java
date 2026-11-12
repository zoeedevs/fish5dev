package com.fish5.automation;

import com.fish5.sb.SkyblockData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Blocking GUI-menu primitives for the automation worker thread. Waits poll
 * the current screen; clicks are marshalled via mc.execute().
 * translated to 26.2 Mojmap: slot clicks go through
 * MultiPlayerGameMode.handleContainerInput, sign input via a direct
 * ServerboundSignUpdatePacket.
 */
public final class Menus {

    private static final long POLL_MS = 50;

    private Menus() {}

    // -- Reading ---------------------------------------------------------

    public static String currentTitle(Minecraft mc) {
        if (mc.gui.screen() instanceof AbstractContainerScreen<?> screen) {
            return SkyblockData.stripColors(screen.getTitle().getString()).trim();
        }
        return null;
    }

    public static String slotName(Minecraft mc, int slotIdx) {
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) return null;
        List<Slot> slots = screen.getMenu().slots;
        if (slotIdx < 0 || slotIdx >= slots.size()) return null;
        ItemStack stack = slots.get(slotIdx).getItem();
        if (stack.isEmpty()) return null;
        return SkyblockData.stripColors(stack.getHoverName().getString()).trim();
    }

    /** The stack in a container slot, or {@link ItemStack#EMPTY} if out of range / no menu. */
    public static ItemStack stackAt(Minecraft mc, int slotIdx) {
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) return ItemStack.EMPTY;
        List<Slot> slots = screen.getMenu().slots;
        if (slotIdx < 0 || slotIdx >= slots.size()) return ItemStack.EMPTY;
        return slots.get(slotIdx).getItem();
    }

    public static List<String> loreLines(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return List.of();
        List<String> out = new ArrayList<>(lore.lines().size());
        for (Component line : lore.lines()) {
            out.add(SkyblockData.stripColors(line.getString()).trim());
        }
        return out;
    }

    /** Index of first container slot whose stripped name contains target (case-insensitive), -1 if none. */
    public static int findSlotByName(Minecraft mc, String target, boolean exact) {
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) return -1;
        String lo = target.toLowerCase();
        List<Slot> slots = screen.getMenu().slots;
        int end = Math.max(0, slots.size() - 36);
        for (int i = 0; i < end; i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;
            String name = SkyblockData.stripColors(stack.getHoverName().getString()).trim().toLowerCase();
            if (exact ? name.equals(lo) : name.contains(lo)) return i;
        }
        return -1;
    }

    // -- Waiting ---------------------------------------------------------

    public static boolean waitForTitle(Minecraft mc, String titleContains, long timeoutMs) {
        String target = titleContains.toLowerCase();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (mc.level == null || AutoWorker.aborted()) return false;
            String title = currentTitle(mc);
            if (title != null && title.toLowerCase().contains(target)) return true;
            AutoWorker.sleep(POLL_MS);
        }
        Msg.LOG.warn("[Menus] Timeout waiting for title '{}' (current: '{}')", titleContains, currentTitle(mc));
        return false;
    }

    /** Wait until any container slot matches the name — robust when titles repeat between pages. */
    public static int waitForSlotNamed(Minecraft mc, String nameContains, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (mc.level == null || AutoWorker.aborted()) return -1;
            int idx = findSlotByName(mc, nameContains, false);
            if (idx != -1) return idx;
            AutoWorker.sleep(POLL_MS);
        }
        Msg.LOG.warn("[Menus] Timeout waiting for slot named '{}'", nameContains);
        return -1;
    }

    public static boolean waitForScreenClosed(Minecraft mc, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (mc.gui.screen() == null || AutoWorker.aborted()) return true;
            AutoWorker.sleep(POLL_MS);
        }
        return false;
    }

    // -- Clicking --------------------------------------------------------

    public static void clickSlot(Minecraft mc, int slotIdx) {
        clickSlot(mc, slotIdx, 0, ContainerInput.PICKUP);
    }

    /**
     * Clicks the slot through the screen's own input pipeline (a synthetic
     * mouse press+release at the slot's on-screen position) rather than a raw
     * container-input packet. The screen derives the action itself: shift
     * modifier → QUICK_MOVE, plain → PICKUP — same path as a real mouse. Only
     * if the screen refuses the event do we fall back to the direct path so the
     * flow doesn't stall. Randomised, human-like clicking.
     */
    public static void clickSlot(Minecraft mc, int slotIdx, int button, ContainerInput type) {
        mc.execute(() -> {
            if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen) || mc.player == null
                    || mc.gameMode == null) {
                Msg.LOG.warn("[Menus] clickSlot({}) — no container open", slotIdx);
                return;
            }
            AbstractContainerMenu menu = screen.getMenu();
            if (slotIdx < 0 || slotIdx >= menu.slots.size()) {
                Msg.LOG.warn("[Menus] clickSlot({}) out of bounds (size={})", slotIdx, menu.slots.size());
                return;
            }
            Slot slot = menu.slots.get(slotIdx);
            ItemStack stack = slot.getItem();
            Msg.LOG.info("[Menus] click slot {} ({}){}", slotIdx,
                    stack.isEmpty() ? "[empty]" : SkyblockData.stripColors(stack.getHoverName().getString()),
                    type == ContainerInput.QUICK_MOVE ? " [shift]" : "");

            int mods = type == ContainerInput.QUICK_MOVE ? GLFW.GLFW_MOD_SHIFT : 0;
            double cx = guiLeft(screen) + slot.x + 8 + (Math.random() * 6 - 3);
            double cy = guiTop(screen) + slot.y + 8 + (Math.random() * 6 - 3);
            MouseButtonEvent event = new MouseButtonEvent(cx, cy, new MouseButtonInfo(button, mods));
            if (screen.mouseClicked(event, false)) {
                screen.mouseReleased(event);
            } else {
                // Screen refused the event (shouldn't happen on container GUIs) —
                // fall back to the direct path so the flow doesn't stall
                Msg.LOG.warn("[Menus] physical click not handled, falling back to direct (slot {})", slotIdx);
                mc.gameMode.handleContainerInput(menu.containerId, slotIdx, button, type, mc.player);
            }
        });
    }

    // AbstractContainerScreen.leftPos / topPos are protected — read them reflectively.
    private static Field leftPosField;
    private static Field topPosField;

    private static int guiLeft(AbstractContainerScreen<?> screen) {
        try {
            if (leftPosField == null) {
                leftPosField = AbstractContainerScreen.class.getDeclaredField("leftPos");
                leftPosField.setAccessible(true);
            }
            return leftPosField.getInt(screen);
        } catch (Exception e) {
            return 0;
        }
    }

    private static int guiTop(AbstractContainerScreen<?> screen) {
        try {
            if (topPosField == null) {
                topPosField = AbstractContainerScreen.class.getDeclaredField("topPos");
                topPosField.setAccessible(true);
            }
            return topPosField.getInt(screen);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Wait for a slot with this name, click it. Returns false on timeout. */
    public static boolean clickSlotByName(Minecraft mc, String nameContains, long timeoutMs) {
        int idx = waitForSlotNamed(mc, nameContains, timeoutMs);
        if (idx == -1) return false;
        AutoWorker.sleepRandom(AutomationConfig.guiClickDelayMs, AutomationConfig.guiClickJitterMs);
        clickSlot(mc, idx);
        return true;
    }

    public static void closeScreen(Minecraft mc) {
        mc.execute(() -> {
            if (mc.player != null && mc.gui.screen() != null) {
                mc.player.closeContainer();
            }
        });
    }

    /** Index of first container slot whose lore contains the text (case-insensitive), -1 if none. */
    public static int findSlotByLore(Minecraft mc, String loreContains) {
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) return -1;
        String lo = loreContains.toLowerCase();
        List<Slot> slots = screen.getMenu().slots;
        int end = Math.max(0, slots.size() - 36);
        for (int i = 0; i < end; i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;
            for (String line : loreLines(stack)) {
                if (line.toLowerCase().contains(lo)) return i;
            }
        }
        return -1;
    }

    /** All non-empty container stacks (player inventory tail excluded). */
    public static List<ItemStack> containerStacks(Minecraft mc) {
        List<ItemStack> out = new ArrayList<>();
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) return out;
        List<Slot> slots = screen.getMenu().slots;
        int end = Math.max(0, slots.size() - 36);
        for (int i = 0; i < end; i++) {
            ItemStack stack = slots.get(i).getItem();
            if (!stack.isEmpty()) out.add(stack);
        }
        return out;
    }

    /** Dump the container contents (names only) — for logging menus we haven't seen. */
    public static String dumpContainer(Minecraft mc) {
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) return "(no container)";
        StringBuilder sb = new StringBuilder("title='").append(currentTitle(mc)).append("' ");
        List<Slot> slots = screen.getMenu().slots;
        int end = Math.max(0, slots.size() - 36);
        for (int i = 0; i < end; i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;
            sb.append(i).append(':').append(SkyblockData.stripColors(stack.getHoverName().getString()).trim()).append(" | ");
        }
        return sb.toString();
    }

    // -- Sign input ------------------------------------------------------

    /**
     * Type text into the open Hypixel sign editor and submit it — clears the
     * pre-filled line (Ctrl+A, Delete), types the value character-by-character,
     * then closes so the vanilla screen sends the sign-update packet itself.
     * No hand-rolled packets.
     */
    public static boolean submitSign(Minecraft mc, String text) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && !(mc.gui.screen() instanceof AbstractSignEditScreen)) {
            AutoWorker.sleep(POLL_MS);
        }
        if (!(mc.gui.screen() instanceof AbstractSignEditScreen)) {
            Msg.LOG.warn("[Menus] Sign screen never opened");
            Msg.dbg("sign: never opened — screen=" + (mc.gui.screen() == null ? "null" : mc.gui.screen().getClass().getSimpleName()));
            return false;
        }

        mc.execute(() -> {
            if (!(mc.gui.screen() instanceof AbstractSignEditScreen sign)) return;
            // Clear the pre-filled line, then type our value
            sign.keyPressed(new KeyEvent(GLFW.GLFW_KEY_A, 0, GLFW.GLFW_MOD_CONTROL));
            sign.keyPressed(new KeyEvent(GLFW.GLFW_KEY_DELETE, 0, 0));
            for (int i = 0; i < text.length(); i++) {
                sign.charTyped(new CharacterEvent(text.charAt(i)));
            }
        });
        AutoWorker.sleepRandom(300, 100);
        mc.execute(() -> {
            if (mc.gui.screen() instanceof AbstractSignEditScreen sign) {
                sign.onClose();
            }
        });
        return true;
    }
}

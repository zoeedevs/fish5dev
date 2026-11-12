// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

public final class ItemUtils {
    private ItemUtils() {}

    // etherwarp needs a void/end aspect with the ether transmission enchant, not just any aotv
    public static int findEtherwarpAspectOfTheVoidHotbarSlot(Minecraft client) {
        if (client.player == null) {
            return -1;
        }

        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.isEmpty() || !isAspectOfTheVoid(stack) || !hasEtherTransmission(stack)) {
                continue;
            }
            return i;
        }

        return -1;
    }

    public static boolean isAspectOfTheVoid(ItemStack stack) {
        String name = stack.getHoverName().getString();
        return name.contains("Aspect of the Void")
                || name.contains("Aspect of the End")
                || name.contains("AOTV")
                || name.contains("AOTE");
    }

    public static boolean hasEtherTransmission(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return false;
        }

        for (Component line : lore.lines()) {
            if (line.getString().contains("Ether Transmission")) {
                return true;
            }
        }

        return false;
    }
}

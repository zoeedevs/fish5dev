package com.fish5.mixin;

import com.fish5.features.impl.misc.ExploitPreventer;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Exploit Preventer: the anvil name box is filled from the item's hover name and sent back in the
// rename packet; don't translate mod-only keys in it.
@Mixin(AnvilScreen.class)
public abstract class AnvilScreenMixin {
    @Redirect(method = {"slotChanged", "onNameChanged"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/network/chat/Component;getString()Ljava/lang/String;"))
    private String fish5$sanitizeName(Component name) {
        return ExploitPreventer.sanitizeSignAnvilText() ? ExploitPreventer.sanitize(name) : name.getString();
    }
}

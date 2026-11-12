package com.fish5.mixin;

import com.fish5.features.impl.misc.ExploitPreventer;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Exploit Preventer: rebuild the editor's line strings so mod-only translation keys on a server-placed
// sign are not translated and echoed back in the sign update packet.
@Mixin(AbstractSignEditScreen.class)
public abstract class AbstractSignEditScreenMixin {
    @Shadow @Final private String[] messages;
    @Shadow private SignText text;

    @Inject(method = "<init>(Lnet/minecraft/world/level/block/entity/SignBlockEntity;ZZLnet/minecraft/network/chat/Component;)V", at = @At("RETURN"))
    private void fish5$sanitizeLines(SignBlockEntity sign, boolean isFrontText, boolean filtered, Component title, CallbackInfo ci) {
        if (!ExploitPreventer.sanitizeSignAnvilText()) return;
        for (int i = 0; i < messages.length; i++) {
            messages[i] = ExploitPreventer.sanitize(text.getMessage(i, filtered));
        }
    }
}

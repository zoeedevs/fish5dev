package com.fish5.mixin;

import com.fish5.automation.Msg;
import com.fish5.macro.InputLock;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Input lock: chat messages still send while a macro runs, but typed commands don't.
// fish5's own commands go through ClientUtils/ClientActions, not this screen, so the macro is unaffected.
@Mixin(ChatScreen.class)
public class ChatScreenInputLockMixin {
    @Inject(method = "handleChatInput", at = @At("HEAD"), cancellable = true)
    private void fish5$blockCommands(String message, boolean addToRecentChat, CallbackInfo ci) {
        if (message != null && message.trim().startsWith("/") && InputLock.isActive()) {
            Msg.chat(InputLock.COMMAND_BLOCKED);
            ci.cancel();
        }
    }
}

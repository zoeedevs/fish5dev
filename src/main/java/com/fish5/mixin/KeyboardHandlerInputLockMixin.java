package com.fish5.mixin;

import com.fish5.macro.InputLock;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Input lock: drops the player's in-game key presses while a macro runs (see InputLock for what stays
// allowed). Screens (chat, menus) still get every key, and releases always pass so no key stays held.
@Mixin(KeyboardHandler.class)
public class KeyboardHandlerInputLockMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void fish5$inputLock(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (action == GLFW.GLFW_RELEASE) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != null || !InputLock.isActive()) {
            return;
        }
        if (!InputLock.allowsKeyPress(mc, event)) {
            ci.cancel();
        }
    }
}

// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.mixin;

import com.fish5.macro.MacroRuntime;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    // stepping rotations per frame instead of per tick is what keeps the camera from moving in 50ms jumps
    @Inject(method = "extract", at = @At("HEAD"))
    private void fish5$onFrameStart(DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
        MacroRuntime.onRenderFrame(Minecraft.getInstance());
    }
}

package com.fish5.mixin;

import com.fish5.features.impl.fishing.Fishing;
import com.fish5.features.impl.render.PlayerEsp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.resources.Identifier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Player ESP world-render hooks: per-frame box gizmos, and swapping the entity-outline post chain for
 * the Clean (supersampled line) variant.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererEspMixin {

    /** Runs before the frame's gizmo collection is finalised, so boxes emitted here draw this frame. */
    @Inject(
        method = "render",
        at = @At("HEAD")
    )
    private void fish5$espBoxes(CallbackInfo ci) {
        float pt = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        PlayerEsp.emitBoxes(pt);
        com.fish5.features.MacroPersist.emit();  // pause square + timer while a macro is paused
        Fishing.emitHighlights();  // fishing block (cyan) + stand block (magenta) while a macro runs
    }

    /** Redirect only the vanilla {@code entity_outline} chain lookup; leave anything else alone. */
    @ModifyArg(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ShaderManager;getPostChain(Lnet/minecraft/resources/Identifier;Ljava/util/Set;)Lnet/minecraft/client/renderer/PostChain;"
        ),
        index = 0
    )
    private Identifier fish5$cleanOutlineChain(Identifier original) {
        if (original.getPath().equals("entity_outline") && PlayerEsp.useCleanChain()) {
            return PlayerEsp.cleanChainId();
        }
        return original;
    }
}

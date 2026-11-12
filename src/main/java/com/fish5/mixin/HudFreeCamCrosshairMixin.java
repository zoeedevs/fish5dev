package com.fish5.mixin;

import com.fish5.features.impl.render.FreeCam;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Keeps the crosshair in Freecam. Hud#extractCrosshair starts with
// `if (!options.getCameraType().isFirstPerson()) return;`, and Freecam's Show Body switches to third
// person, so the crosshair vanished. Report first person to that one check while Freecam is active.
// Same approach as zoeemod's GuiKeepCrosshairMixin.
@Mixin(Hud.class)
public class HudFreeCamCrosshairMixin {
    @Redirect(
            method = "extractCrosshair(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/CameraType;isFirstPerson()Z")
    )
    private boolean fish5$keepCrosshairInFreeCam(CameraType cameraType) {
        return cameraType.isFirstPerson() || FreeCam.isActive();
    }
}

package com.fish5.mixin;

import com.fish5.features.impl.render.FreeCam;

import net.minecraft.client.CameraType;
import net.minecraft.client.renderer.GameRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * FreeCam's "Show hands" setting: {@code renderItemInHand}'s own first-person gate (not sleeping, HUD
 * shown, not spectator — all still apply) is forced true while FreeCam is detached and Show Hands is
 * on, so the held item keeps rendering even though Show Body has switched the camera to third person.
 * Ported from Jasper Client's {@code FreeCamHandsMixin}.
 */
@Mixin(GameRenderer.class)
public abstract class FreeCamHandsMixin {

    @Redirect(method = "renderItemInHand",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/CameraType;isFirstPerson()Z"))
    private boolean fish5$freeCamHands(CameraType type) {
        return type.isFirstPerson() || (FreeCam.isActive() && FreeCam.keepHands());
    }
}

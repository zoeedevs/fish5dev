// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.pathfinding.rotation;

import net.minecraft.client.player.LocalPlayer;

public interface IRotationStrategy {
    // called each tick while rotating; null signals completion
    Rotation onRotate(LocalPlayer player, float targetYaw, float targetPitch);

    default void onStart() {}
    default void onStop() {}
}

// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {
    @Accessor("accumulatedDX")
    void fish5$setAccumulatedDX(double value);

    @Accessor("accumulatedDY")
    void fish5$setAccumulatedDY(double value);

    @Accessor("ignoreFirstMove")
    void fish5$setIgnoreFirstMove(boolean value);
}

package com.fish5.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes the protected {@link AbstractContainerScreen#slotClicked} so we can drive container
 * clicks through the screen's own handler (no-packet approach) instead of crafting a
 * raw container-input packet. See {@code ClientActions#slotClick}.
 *
 * <p>Also exposes the container GUI's top-left corner ({@code leftPos}/{@code topPos}, protected on
 * {@link AbstractContainerScreen}) so an overlay can map a menu slot's local {@code x}/{@code y} onto
 * screen coordinates.
 */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {

    @Invoker("slotClicked")
    void fish5$invokeSlotClicked(Slot slot, int slotId, int mouseButton, ContainerInput type);

    @Accessor("leftPos")
    int getLeftPos();

    @Accessor("topPos")
    int getTopPos();
}

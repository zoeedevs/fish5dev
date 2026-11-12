package com.fish5.features.impl.render

import com.fish5.clickgui.settings.impl.BooleanSetting
import com.fish5.clickgui.settings.impl.NumberSetting
import com.fish5.features.Category
import com.fish5.features.Module

/** Read by [com.fish5.mixin.LivingEntitySwingMixin]. */
object CustomSwingSpeed : Module(
    name = "Custom Swing Speed",
    category = Category.RENDER,
    description = "Re-time and smooth the hand swing animation.",
) {
    val speed by NumberSetting("Swing Speed", 6, 2, 20, 1, desc = "Swing animation duration in ticks. Lower is faster (vanilla = 6).")
    val ignoreHaste by BooleanSetting("Ignore Haste", false, desc = "Keep the swing speed constant, ignoring Haste and Mining Fatigue.")
    val smoothSwing by BooleanSetting("Smooth Swing", false, desc = "Visual only: while you're actively farming/mining, the first-person hand loops one uninterrupted swing instead of resetting. Fixes both the held-item re-equip dip (tools whose NBT changes every break) and the swing snapping back to the start. Does not touch gameplay, packets, or breaking speed.")
}

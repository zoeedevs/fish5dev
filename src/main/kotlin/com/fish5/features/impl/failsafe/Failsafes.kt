package com.fish5.features.impl.failsafe

import com.fish5.clickgui.settings.Setting.Companion.inGroup
import com.fish5.clickgui.settings.Setting.Companion.withDependency
import com.fish5.clickgui.settings.impl.ActionSetting
import com.fish5.clickgui.settings.impl.BooleanSetting
import com.fish5.clickgui.settings.impl.ColorSetting
import com.fish5.clickgui.settings.impl.GroupSetting
import com.fish5.clickgui.settings.impl.NumberSetting
import com.fish5.clickgui.settings.impl.RangeSetting
import com.fish5.clickgui.settings.impl.SelectorSetting
import com.fish5.config.MacroConfig
import com.fish5.failsafe.FailsafeSoundManager
import com.fish5.failsafe.FailsafeTestManager
import com.fish5.features.Category
import com.fish5.features.Module
import com.fish5.utils.Color

/*
 * Click-GUI front end for the failsafe core (ported from aether), laid out like aether's Failsafes
 * tab: a shared "General" page plus one toggleable page per failsafe. The module's on/off switch is
 * the failsafe's enable. Values live in these settings (saved by ModuleConfig) and are copied into
 * MacroConfig, which the failsafes read, by [FailsafeSettings.sync] (called from the client tick).
 */

private val ACTIONS = listOf("Stop", "Ignore")
private fun actionName(index: Int) = if (index == 0) "STOP" else "IGNORE"
private fun actionSetting(name: String, default: String, desc: String) =
    SelectorSetting(name, default, ACTIONS, desc = "$desc Stop: halt the macro and alert you. Ignore: alert only.")

/** Shared failsafe options (aether: Failsafes > General). Always on, not a toggle. */
object FailsafeSettings : Module(
    name = "Failsafe Settings",
    category = Category.OPSEC,
    description = "Shared failsafe options: alerts, sounds, colour flash and the extra human reaction delay.",
    toggled = true,
) {
    private const val SAME_AS_DEFAULT = "Same as Default"

    // the sound folder is listed once at startup; new files need a restart (or the folder button + restart)
    private val soundOptions: List<String> = run {
        FailsafeSoundManager.init()
        FailsafeSoundManager.getAvailableSounds().ifEmpty { listOf(FailsafeSoundManager.getDefaultSoundFileName()) }
    }

    private val alertCard = registerSetting(GroupSetting("Alerts", "How a triggered failsafe gets your attention."))
    private val desktopNotification by BooleanSetting("Desktop notification", false, desc = "Show a Windows notification when a failsafe triggers.").inGroup(alertCard)
    private val autoAltTab by BooleanSetting("Auto alt-tab", true, desc = "Bring the game window to the front when a failsafe triggers.").inGroup(alertCard)
    private val randomDelay by NumberSetting<Double>(
        "Additional random delay", 2.0, 0.0, 5.0, 0.1, unit = " s",
        desc = "Up to this much extra random wait on top of each failsafe's trigger delay, so reactions never look instant.",
    ).inGroup(alertCard)

    private val soundCard = registerSetting(GroupSetting("Sound", "Alarm played when a failsafe triggers."))
    private val soundEnabled by BooleanSetting("Play failsafe sound", true, desc = "Play an alarm when a failsafe triggers.").inGroup(soundCard)
    private val soundVolume by NumberSetting<Double>("Volume", 100.0, 0.0, 100.0, 1.0, unit = "%", desc = "Alarm volume.")
        .withDependency { soundEnabled }.inGroup(soundCard)
    private val defaultSound = registerSetting(SelectorSetting(
        "Default sound", FailsafeSoundManager.getDefaultSoundFileName(), soundOptions,
        desc = "Sound file from config/fish5 failsafe sounds folder.",
    ).withDependency { soundEnabled }.inGroup(soundCard))
    private val stopSound = registerSetting(SelectorSetting(
        "Stop sound", SAME_AS_DEFAULT, listOf(SAME_AS_DEFAULT) + soundOptions, desc = "Sound for failsafes set to Stop.",
    ).withDependency { soundEnabled }.inGroup(soundCard))
    private val ignoreSound = registerSetting(SelectorSetting(
        "Ignore sound", SAME_AS_DEFAULT, listOf(SAME_AS_DEFAULT) + soundOptions, desc = "Sound for failsafes set to Ignore.",
    ).withDependency { soundEnabled }.inGroup(soundCard))
    @Suppress("unused")
    private val openSoundFolder by ActionSetting("Open sound folder", desc = "Drop .wav files here, then restart the game to pick them.") {
        FailsafeSoundManager.openSoundFolder()
    }.withDependency { soundEnabled }.inGroup(soundCard)

    private val flashCard = registerSetting(GroupSetting("Colour Flash", "Flash the screen when a failsafe triggers."))
    private val flashEnabled by BooleanSetting("Colour flash", false, desc = "Flash two colours over the screen when a failsafe triggers.").inGroup(flashCard)
    private val flashFirst by ColorSetting("First colour", Color(0xFFFF2020.toInt()), allowAlpha = false, desc = "First flash colour.")
        .withDependency { flashEnabled }.inGroup(flashCard)
    private val flashSecond by ColorSetting("Second colour", Color(0xFF2020FF.toInt()), allowAlpha = false, desc = "Second flash colour.")
        .withDependency { flashEnabled }.inGroup(flashCard)
    private val flashOpacity by NumberSetting<Double>("Opacity", 35.0, 0.0, 100.0, 1.0, unit = "%", desc = "How strong the flash is.")
        .withDependency { flashEnabled }.inGroup(flashCard)
    private val flashSwap by NumberSetting<Double>("Colour swap delay", 0.5, 0.1, 5.0, 0.1, unit = " s", desc = "Time between the two colours.")
        .withDependency { flashEnabled }.inGroup(flashCard)


    private var syncCountdown = 0

    /** Copy every failsafe setting into MacroConfig. Cheap; throttled to twice a second. */
    fun sync() {
        if (--syncCountdown > 0) return
        syncCountdown = 10

        MacroConfig.FAILSAFE_DESKTOP_NOTIFICATION_ENABLED.set(desktopNotification)
        MacroConfig.FAILSAFE_AUTO_ALT_TAB.set(autoAltTab)
        MacroConfig.FAILSAFE_ADDITIONAL_RANDOM_DELAY_SECONDS.set(randomDelay.toFloat())
        MacroConfig.FAILSAFE_SOUND_ENABLED.set(soundEnabled)
        MacroConfig.FAILSAFE_SOUND_VOLUME.set((soundVolume / 100.0).toFloat())
        MacroConfig.FAILSAFE_SOUND_FILE.set(soundOptions.getOrElse(defaultSound.value) { soundOptions[0] })
        MacroConfig.FAILSAFE_SOUND_FILE_STOP.set(if (stopSound.value <= 0) "" else soundOptions.getOrElse(stopSound.value - 1) { "" })
        MacroConfig.FAILSAFE_SOUND_FILE_IGNORE.set(if (ignoreSound.value <= 0) "" else soundOptions.getOrElse(ignoreSound.value - 1) { "" })
        MacroConfig.FAILSAFE_COLOUR_FLASH_ENABLED.set(flashEnabled)
        MacroConfig.FAILSAFE_COLOUR_FLASH_FIRST.set(flashFirst.rgba)
        MacroConfig.FAILSAFE_COLOUR_FLASH_SECOND.set(flashSecond.rgba)
        MacroConfig.FAILSAFE_COLOUR_FLASH_OPACITY.set((flashOpacity / 100.0).toFloat())
        MacroConfig.FAILSAFE_COLOUR_FLASH_SWAP_DELAY_SECONDS.set(flashSwap.toFloat())

        RotationFailsafeModule.sync()
        InventorySlotFailsafeModule.sync()
        GuiOpenedFailsafeModule.sync()
        WorldChangeFailsafeModule.sync()
        BreakSettings.sync()
    }
}

/**
 * Scheduled breaks ([com.fish5.session.RestScheduler]): run a while, rest, carry on. The switch is the
 * enable. Copied into MacroConfig by [FailsafeSettings.sync].
 */
object BreakSettings : Module(
    name = "Breaks",
    category = Category.OPSEC,
    description = "Take scheduled breaks: macro for a while, then rest, then carry on. Each length varies a little every time.",
) {
    private val session = registerSetting(RangeSetting("Macro time", 27, 33, 5, 120, 1, unit = " min",
        desc = "How long to macro before a break, picked at random in this range each time."))
    private val breakLength = registerSetting(RangeSetting("Break length", 17, 23, 1, 60, 1, unit = " min",
        desc = "How long each break lasts, picked at random in this range each time."))
    private val disconnect by BooleanSetting(
        "Disconnect during break", false,
        desc = "Leave the server for the break instead of idling in game. Worm Fishing can only carry on by itself after an " +
            "in-game break: after a disconnect you land in a new Crystal Hollows instance and your spot is gone.",
    )
    private val reconnect by BooleanSetting("Reconnect after break", true, desc = "Join the same server again when the break ends.")
        .withDependency { disconnect }

    fun sync() {
        MacroConfig.REST_ENABLED.set(enabled)
        MacroConfig.REST_SCRIPTING_MIN_MINUTES.set(session.low)
        MacroConfig.REST_SCRIPTING_MAX_MINUTES.set(session.high)
        MacroConfig.REST_BREAK_MIN_MINUTES.set(breakLength.low)
        MacroConfig.REST_BREAK_MAX_MINUTES.set(breakLength.high)
        MacroConfig.REST_DISCONNECT.set(disconnect)
        MacroConfig.REST_RECONNECT.set(reconnect)
    }
}

/** aether: Failsafes > Rotation. */
object RotationFailsafeModule : Module(
    name = "Rotation Failsafe",
    category = Category.OPSEC,
    description = "Triggers when your camera is turned by something other than the macro (e.g. a staff check).",
    toggled = true,
) {
    private val card = registerSetting(GroupSetting("Rotation Failsafe", "Camera moved beyond the thresholds without the macro doing it."))
    private val action = registerSetting(actionSetting("Action", "Ignore", "What to do when it triggers.").inGroup(card))
    private val yaw by NumberSetting<Double>("Yaw threshold", 10.0, 5.0, 30.0, 1.0, unit = "°", desc = "Horizontal turn that counts as unexpected.").inGroup(card)
    private val pitch by NumberSetting<Double>("Pitch threshold", 10.0, 5.0, 30.0, 1.0, unit = "°", desc = "Vertical turn that counts as unexpected.").inGroup(card)
    private val delay by NumberSetting<Double>("Trigger delay", 2.0, 0.0, 5.0, 0.1, unit = " s", desc = "Wait this long (plus the random delay) before reacting.").inGroup(card)

    private val trackCard = registerSetting(GroupSetting("While Tracking", "While the macro follows a moving target (mob, bobber)."))
    private val duringTracking by BooleanSetting("Trigger while tracking", true, desc = "Also check rotation while the macro is tracking a target.").inGroup(trackCard)
    private val trackingAction = registerSetting(actionSetting("Tracking action", "Stop", "What to do when it triggers mid-tracking.")
        .withDependency { duringTracking }.inGroup(trackCard))
    private val trackingDelay by NumberSetting<Double>("Tracking delay", 750.0, 0.0, 5000.0, 50.0, unit = " ms", desc = "Grace before a tracking rotation counts.")
        .withDependency { duringTracking }.inGroup(trackCard)

    @Suppress("unused")
    private val test by ActionSetting("Test (5 s)", desc = "Nudges your camera in 5 seconds. Only triggers while a macro is running.") {
        FailsafeTestManager.scheduleRotation(15f, 15f)
    }.inGroup(card)

    fun sync() {
        MacroConfig.FAILSAFE_ROTATION.set(enabled)
        MacroConfig.FAILSAFE_ROTATION_ACTION.set(actionName(action.value))
        MacroConfig.FAILSAFE_ROTATION_YAW_THRESHOLD.set(yaw.toInt())
        MacroConfig.FAILSAFE_ROTATION_PITCH_THRESHOLD.set(pitch.toInt())
        MacroConfig.FAILSAFE_ROTATION_TRIGGER_DELAY_SECONDS.set(delay.toFloat())
        MacroConfig.FAILSAFE_ROTATION_TRIGGER_DURING_TRACKING.set(duringTracking)
        MacroConfig.FAILSAFE_TRACKING_ROTATION_ACTION.set(actionName(trackingAction.value))
        MacroConfig.FAILSAFE_ROTATION_TRACKING_DELAY_MS.set(trackingDelay.toInt())
    }
}

/** aether: Failsafes > Inventory Slot Changed. */
object InventorySlotFailsafeModule : Module(
    name = "Inventory Slot Failsafe",
    category = Category.OPSEC,
    description = "Triggers when your selected hotbar slot changes without the macro doing it.",
    toggled = true,
) {
    private val card = registerSetting(GroupSetting("Inventory Slot Changed", "Hotbar slot changed unexpectedly."))
    private val action = registerSetting(actionSetting("Action", "Ignore", "What to do when it triggers.").inGroup(card))
    private val delay by NumberSetting<Double>("Trigger delay", 2.0, 0.0, 5.0, 0.1, unit = " s", desc = "Wait this long (plus the random delay) before reacting.").inGroup(card)
    @Suppress("unused")
    private val test by ActionSetting("Test (5 s)", desc = "Switches to hotbar slot 1 in 5 seconds. Only triggers while a macro is running.") {
        FailsafeTestManager.scheduleInventorySlot(1)
    }.inGroup(card)

    fun sync() {
        MacroConfig.FAILSAFE_INVENTORY_SLOT_CHANGED.set(enabled)
        MacroConfig.FAILSAFE_INVENTORY_SLOT_CHANGED_ACTION.set(actionName(action.value))
        MacroConfig.FAILSAFE_INVENTORY_SLOT_CHANGED_DELAY_SECONDS.set(delay.toFloat())
    }
}

/** aether: Failsafes > GUI Opened. */
object GuiOpenedFailsafeModule : Module(
    name = "GUI Opened Failsafe",
    category = Category.OPSEC,
    description = "Triggers when a menu opens that the macro didn't open.",
    toggled = true,
) {
    private val card = registerSetting(GroupSetting("GUI Opened", "An inventory menu opened unexpectedly."))
    private val action = registerSetting(actionSetting("Action", "Ignore", "What to do when it triggers.").inGroup(card))
    private val delay by NumberSetting<Double>("Trigger delay", 2.0, 0.0, 5.0, 0.1, unit = " s", desc = "Wait this long (plus the random delay) before reacting.").inGroup(card)
    @Suppress("unused")
    private val test by ActionSetting("Test (5 s)", desc = "Opens an empty menu for a moment in 5 seconds. Only triggers while a macro is running.") {
        FailsafeTestManager.scheduleGuiFlash(20)
    }.inGroup(card)

    fun sync() {
        MacroConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI.set(enabled)
        MacroConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI_ACTION.set(actionName(action.value))
        MacroConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI_DELAY_SECONDS.set(delay.toFloat())
    }
}

/** aether: Failsafes > World Change. */
object WorldChangeFailsafeModule : Module(
    name = "World Change Failsafe",
    category = Category.OPSEC,
    description = "Triggers when you're moved to another world/server while the macro runs (e.g. kicked to hub).",
    toggled = true,
) {
    private val card = registerSetting(GroupSetting("World Change", "Unexpected world or server change."))
    private val action = registerSetting(actionSetting("Action", "Ignore", "What to do when it triggers.").inGroup(card))

    fun sync() {
        MacroConfig.FAILSAFE_WORLD_CHANGE.set(enabled)
        MacroConfig.FAILSAFE_WORLD_CHANGE_ACTION.set(actionName(action.value))
    }
}

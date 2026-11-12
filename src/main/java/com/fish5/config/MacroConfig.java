package com.fish5.config;

// tuning for the rotation / pathfinding / failsafe core ported from aether (GPL-3.0, see com/fish5/PORTED.md)
// plain in-memory values for now; wire them to clickgui settings when a module owns them
public final class MacroConfig {
    private MacroConfig() {}

    public static final class Entry<T> {
        private volatile T value;
        private final T defaultValue;

        Entry(T defaultValue) {
            this.defaultValue = defaultValue;
            this.value = defaultValue;
        }

        public T get() { return value; }
        public void set(T value) { this.value = value; }
        public T getDefault() { return defaultValue; }
        public void reset() { value = defaultValue; }
    }

    private static <T> Entry<T> of(T value) { return new Entry<>(value); }

    // -- rotation
    public static final Entry<Integer> ROTATION_TIME = of(100);
    public static final Entry<Float> ROTATION_DYNAMIC_DURATION_MS_PER_DEGREE = of(2.0f);
    public static final Entry<Boolean> ROTATION_EASE_IN = of(true);
    public static final Entry<Float> ROTATION_EASE_IN_FACTOR = of(2.0f);
    public static final Entry<Boolean> ROTATION_EASE_OUT = of(true);
    public static final Entry<Float> ROTATION_EASE_OUT_FACTOR = of(2.0f);
    public static final Entry<Float> ROTATION_TRACKING_NOISE_MIN = of(2.0f);
    public static final Entry<Float> ROTATION_TRACKING_NOISE_MAX = of(6.0f);

    // -- gui clicks
    public static final Entry<Integer> GUI_CLICK_DELAY_MIN = of(100);
    public static final Entry<Integer> GUI_CLICK_DELAY_MAX = of(250);
    public static final Entry<Integer> GUI_FIRST_CLICK_DELAY_MIN = of(150);
    public static final Entry<Integer> GUI_FIRST_CLICK_DELAY_MAX = of(250);

    // -- pathfinding
    public static final Entry<Float> PATHFINDER_AIM_LOOKAHEAD = of(3.5f);
    public static final Entry<Float> PATHFINDER_JUMP_LOOKAHEAD_TICKS = of(2.0f);
    public static final Entry<Integer> PATHFINDER_MAX_JUMP_HEIGHT = of(1);
    public static final Entry<Boolean> PATHFINDER_RAYCAST_JUMP = of(true);
    public static final Entry<Boolean> PATHFINDER_SPRINT = of(true);
    public static final Entry<Integer> PATHFINDER_STUCK_TIMEOUT_MS = of(1800);
    public static final Entry<Float> PATHFINDER_TURN_SPEED = of(240.0f);
    public static final Entry<Float> FLY_BRAKING_LOOKAHEAD_TICKS = of(2.0f);

    // -- scheduled breaks (aether's dynamic rest); off until the user opts in
    public static final Entry<Boolean> REST_ENABLED = of(false);
    public static final Entry<Integer> REST_SCRIPTING_MIN_MINUTES = of(27);
    public static final Entry<Integer> REST_SCRIPTING_MAX_MINUTES = of(33);
    public static final Entry<Integer> REST_BREAK_MIN_MINUTES = of(17);
    public static final Entry<Integer> REST_BREAK_MAX_MINUTES = of(23);
    // disconnect for the break (like aether) instead of idling in the lobby
    public static final Entry<Boolean> REST_DISCONNECT = of(true);
    public static final Entry<Boolean> REST_RECONNECT = of(true);

    // -- failsafes: general
    public static final Entry<String> FAILSAFE_ACTION = of("STOP");
    public static final Entry<Float> FAILSAFE_ADDITIONAL_RANDOM_DELAY_SECONDS = of(2.0f);
    public static final Entry<Boolean> FAILSAFE_AUTO_ALT_TAB = of(true);
    public static final Entry<Boolean> FAILSAFE_DESKTOP_NOTIFICATION_ENABLED = of(false);
    public static final Entry<Boolean> FAILSAFE_WORLD_CHANGE = of(true);
    public static final Entry<String> FAILSAFE_WORLD_CHANGE_ACTION = of("IGNORE");

    // -- failsafes: rotation
    public static final Entry<Boolean> FAILSAFE_ROTATION = of(true);
    public static final Entry<String> FAILSAFE_ROTATION_ACTION = of("IGNORE");
    public static final Entry<Integer> FAILSAFE_ROTATION_YAW_THRESHOLD = of(10);
    public static final Entry<Integer> FAILSAFE_ROTATION_PITCH_THRESHOLD = of(10);
    public static final Entry<Float> FAILSAFE_ROTATION_TRIGGER_DELAY_SECONDS = of(2.0f);
    public static final Entry<Integer> FAILSAFE_ROTATION_WARP_GRACE_MS = of(2000);
    // while the macro is tracking a target, a mismatch freezes further turning and fires faster
    public static final Entry<Boolean> FAILSAFE_ROTATION_TRIGGER_DURING_TRACKING = of(true);
    public static final Entry<Integer> FAILSAFE_ROTATION_TRACKING_DELAY_MS = of(750);
    public static final Entry<String> FAILSAFE_TRACKING_ROTATION_ACTION = of("STOP");

    // -- failsafes: inventory
    public static final Entry<Boolean> FAILSAFE_INVENTORY_SLOT_CHANGED = of(true);
    public static final Entry<String> FAILSAFE_INVENTORY_SLOT_CHANGED_ACTION = of("IGNORE");
    public static final Entry<Float> FAILSAFE_INVENTORY_SLOT_CHANGED_DELAY_SECONDS = of(2.0f);
    public static final Entry<Boolean> FAILSAFE_UNEXPECTED_INVENTORY_GUI = of(true);
    public static final Entry<String> FAILSAFE_UNEXPECTED_INVENTORY_GUI_ACTION = of("IGNORE");
    public static final Entry<Float> FAILSAFE_UNEXPECTED_INVENTORY_GUI_DELAY_SECONDS = of(2.0f);

    // -- failsafes: alerts
    public static final Entry<Boolean> FAILSAFE_SOUND_ENABLED = of(true);
    public static final Entry<String> FAILSAFE_SOUND_FILE = of("");
    public static final Entry<String> FAILSAFE_SOUND_FILE_IGNORE = of("");
    public static final Entry<String> FAILSAFE_SOUND_FILE_STOP = of("");
    public static final Entry<Float> FAILSAFE_SOUND_VOLUME = of(1.0f);
    public static final Entry<Boolean> FAILSAFE_COLOUR_FLASH_ENABLED = of(false);
    public static final Entry<Integer> FAILSAFE_COLOUR_FLASH_FIRST = of(0xFFFF2020);
    public static final Entry<Integer> FAILSAFE_COLOUR_FLASH_SECOND = of(0xFF2020FF);
    public static final Entry<Float> FAILSAFE_COLOUR_FLASH_OPACITY = of(0.35f);
    public static final Entry<Float> FAILSAFE_COLOUR_FLASH_SWAP_DELAY_SECONDS = of(0.5f);

    public static void save() {
        // no-op until these are backed by fish5's module config
    }
}

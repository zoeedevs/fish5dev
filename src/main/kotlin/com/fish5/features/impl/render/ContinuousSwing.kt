package com.fish5.features.impl.render

/**
 * Visual-only "continuous swing" phase state for the first-person hand.
 *
 * <p>Vanilla restarts the swing animation from the beginning every time the player swings (see
 * {@code LivingEntity.swing}: while farming/mining fast this re-triggers mid-stroke, so the arm
 * snaps back to the start on every break — the "reset -> swing -> reset" look). This holds a phase
 * that advances continuously in real time and loops seamlessly, so a held farm/mine shows one
 * uninterrupted swing cycle.
 *
 * <p>Nothing here touches gameplay: the swing event only pokes {@link #onSwing} to say "we're still
 * active"; it never resets the phase. The renderer reads {@link #progress} and substitutes it for
 * the vanilla attack-anim value on the first-person hand only.
 *
 * <p>The vanilla first-person swing transform is identity at both progress {@code 0} and {@code 1}
 * (the +45°/-45° yaw offsets cancel and every {@code sin} term is zero), so wrapping the phase from
 * ~1 back to 0 produces no snap.
 */
object ContinuousSwing {

    /** How long after the last swing we keep looping before smoothly settling back to rest. */
    private const val TIMEOUT_MS = 250L

    /** Current phase in [0, 1). Never reset by swing events — only advanced by [progress]. */
    private var phase = 0f
    private var lastNanos = 0L
    private var lastSwingMs = 0L

    /** Called on every local-player swing event. Marks the animation active; does NOT reset phase. */
    fun onSwing() {
        lastSwingMs = System.currentTimeMillis()
    }

    /**
     * Advances and returns the continuous swing progress in [0, 1), or a negative value to signal
     * "fall back to the vanilla animation" (i.e. we're at rest).
     *
     * @param durationTicks full-swing duration in ticks (vanilla 6); controls loop speed.
     */
    fun progress(durationTicks: Int): Float {
        val now = System.nanoTime()
        var dt = if (lastNanos == 0L) 0f else (now - lastNanos) / 1_000_000_000f
        lastNanos = now
        // Guard against the first frame, a paused game, or the feature being re-enabled after a gap.
        if (dt < 0f || dt > 0.1f) dt = 0f

        val cycleSec = durationTicks.coerceAtLeast(1) / 20f
        val advance = dt / cycleSec

        val active = System.currentTimeMillis() - lastSwingMs < TIMEOUT_MS
        if (active) {
            phase = (phase + advance) % 1f
            return phase
        }

        // Not actively swinging: finish the current stroke, then hand back to vanilla at rest so the
        // arm settles smoothly instead of snapping to 0.
        if (phase > 1e-4f) {
            phase += advance
            if (phase >= 1f) {
                phase = 0f
                return -1f
            }
            return phase
        }
        return -1f
    }
}

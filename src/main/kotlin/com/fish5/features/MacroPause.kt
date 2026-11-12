package com.fish5.features

/**
 * Lets a side task (auto sell) borrow the game from a running macro without stopping it.
 * The task sets [requested]; the macro finishes what it's doing, and at its next safe point (line in,
 * no fight) sets [parked] and does nothing more until [release]. Macros that don't support it just
 * never park, so the task never starts.
 */
object MacroPause {
    @Volatile var requested = false
    @Volatile var parked = false

    fun request() { requested = true }

    fun release() {
        requested = false
        parked = false
    }
}

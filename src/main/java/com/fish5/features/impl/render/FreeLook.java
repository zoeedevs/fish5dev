package com.fish5.features.impl.render;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/**
 * Toggle free look: decouples the camera from the player's body rotation. Ported 1:1 from Jasper
 * Client's {@code utils/FreeLook.java}; {@link FreelookModule} is the Odin-style settings container +
 * on/off switch (mirrors {@link FreeCam}/{@link FreecamModule}).
 *
 * <p>While active, mouse input accumulates into a separate yaw/pitch applied to the Camera. Scroll
 * wheel controls zoom (FOV offset). The player's actual yaw/pitch (used for server packets) is never
 * touched in Camera mode. In Player mode the mouse turns the real body as normal instead, and the
 * decoupled camera just auto-orbits — see {@link FreelookModule#isCameraMode}. FreeLook and FreeCam
 * can't both be active; activating one deactivates the other.
 */
public final class FreeLook {
    private FreeLook() {}

    private static boolean active        = false;
    private static float   yaw           = 0f;
    private static float   pitch         = 0f;
    private static float   zoomFovOffset = 0f;
    private static long    lastInputMs   = 0L;   // wall-clock of the last manual look input (0 = none yet → orbit now)
    private static CameraType savedPerspective = null; // perspective to restore on deactivate (null = don't)

    private static final float ZOOM_STEP = 3f;
    private static final float ZOOM_MIN  = -30f;
    private static final float ZOOM_MAX  =  15f;
    private static final double ORBIT_DEG_PER_TICK = 0.8;     // auto-orbit speed (~16°/s at 20 TPS)
    private static final long   IDLE_RESUME_MS     = 3_000L;  // resume orbiting this long after the last manual look input

    public static boolean isActive()          { return active;        }
    public static float   getYaw()            { return yaw;           }
    public static float   getPitch()          { return pitch;         }
    public static float   getZoomFovOffset()  { return zoomFovOffset; }

    /** Through Blocks: the camera clips through terrain instead of stopping at a wall behind you. */
    public static boolean throughBlocks() {
        return active && FreelookModule.throughBlocks();
    }

    /** Called by {@link FreelookModule#onEnable} — the module owns the toggle, this just does the work. */
    public static void activate(float initialYaw, float initialPitch) {
        FreeCam.deactivate(); // the two can't both be active
        yaw           = initialYaw;
        pitch         = initialPitch;
        zoomFovOffset = 0f;
        lastInputMs   = 0L;   // orbit the player by default the instant it's enabled
        active        = true;

        Minecraft mc = Minecraft.getInstance();
        savedPerspective = null;
        if (FreelookModule.togglePerspective()) {
            CameraType current = mc.options.getCameraType();
            savedPerspective = current;
            if (current == CameraType.FIRST_PERSON) {
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            }
        }
    }

    /** Called by {@link FreelookModule#onDisable}. */
    public static void deactivate() {
        active        = false;
        zoomFovOffset = 0f;
        if (savedPerspective != null) {
            Minecraft.getInstance().options.setCameraType(savedPerspective);
            savedPerspective = null;
        }
    }

    /**
     * Per-tick update: slowly auto-orbit the camera around the player (yaw) while active. Orbits by
     * default the moment FreeLook is enabled; a manual look input pauses it for {@link #IDLE_RESUME_MS},
     * after which it resumes. {@code allowOrbit} is false while a screen is open or the player is
     * unloaded — FreeLook stays active (the view holds), it just doesn't spin.
     */
    public static void tick(boolean allowOrbit) {
        if (!active || !allowOrbit) return;
        // Keep the perspective enforced: a world change can bounce the view back to first-person, which
        // would otherwise hide the decoupled camera/orbit this feature exists to show.
        if (savedPerspective != null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.options.getCameraType() == CameraType.FIRST_PERSON) {
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            }
        }
        if (lastInputMs != 0L && System.currentTimeMillis() - lastInputMs < IDLE_RESUME_MS) return;
        yaw = Mth.wrapDegrees(yaw + (float) ORBIT_DEG_PER_TICK);
    }

    /**
     * Accumulate mouse deltas into the free-look camera (Camera mode only — Player mode lets the mouse
     * fall through to vanilla body rotation instead; see MouseFreeLookMixin).
     * Divides by Camera Sensitivity instead of the vanilla 0.15 per-pixel factor (a higher setting damps
     * the turn more — it's a divisor, not a classic "sensitivity" multiplier).
     */
    public static void addMouseDelta(double dx, double dy) {
        float divisor = (float) Math.max(FreelookModule.cameraSensitivity(), 0.1);
        yaw  += (float) (dx / divisor);
        pitch = Mth.clamp(pitch + (float) (dy / divisor), -90f, 90f);
        lastInputMs = System.currentTimeMillis();   // manual look → pause the auto-orbit (resumes after IDLE_RESUME_MS)
    }

    /**
     * Adjust zoom via scroll wheel. Positive delta = scroll up = zoom in (lower FOV).
     */
    public static void scrollZoom(double scrollDelta) {
        zoomFovOffset = Mth.clamp(
                zoomFovOffset - (float) (scrollDelta * ZOOM_STEP), ZOOM_MIN, ZOOM_MAX);
    }
}

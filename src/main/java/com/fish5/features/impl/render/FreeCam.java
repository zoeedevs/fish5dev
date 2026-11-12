package com.fish5.features.impl.render;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

public final class FreeCam {
    private FreeCam() {}

    private static boolean active = false;
    private static double  x, y, z;
    private static double  prevX, prevY, prevZ;
    private static float   yaw, pitch;
    private static CameraType savedPerspective = null; // restored on deactivate when Show Body forced it
    private static float   scrolled = 1.0f;             // transient scroll multiplier on top of the Speed setting
    private static ChunkPos focusedChunk = null;
    private static int      viewRadius   = -1;
    private static int      preChunkRadius = -1;        // the render distance to restore on release

    public static boolean isActive() { return active; }
    public static double  getX()     { return x; }
    public static double  getY()     { return y; }
    public static double  getZ()     { return z; }
    public static float   getYaw()   { return yaw; }
    public static float   getPitch() { return pitch; }

    public static double getInterpolatedX(float tickDelta) { return prevX + (x - prevX) * tickDelta; }
    public static double getInterpolatedY(float tickDelta) { return prevY + (y - prevY) * tickDelta; }
    public static double getInterpolatedZ(float tickDelta) { return prevZ + (z - prevZ) * tickDelta; }

    /** Call once per tick before moving, so the render thread can interpolate. */
    public static void savePrevPos() { prevX = x; prevY = y; prevZ = z; }

    /** Called by {@link FreecamModule#onEnable} — the module owns the toggle, this just does the work. */
    public static void activate(double startX, double startY, double startZ,
                                float startYaw, float startPitch) {
        FreeLook.deactivate(); // the two can't both be active
        x = prevX = startX;
        y = prevY = startY;
        z = prevZ = startZ;
        yaw   = startYaw;
        pitch = startPitch;
        active = true;
        scrolled = 1.0f;

        Minecraft mc = Minecraft.getInstance();
        savedPerspective = null;
        if (FreecamModule.showBody()) {
            CameraType current = mc.options.getCameraType();
            savedPerspective = current;
            if (current.isFirstPerson()) {
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            }
        }
        focusedChunk = null;
        viewRadius = -1;
        preChunkRadius = mc.level != null ? mc.options.getEffectiveRenderDistance() : -1;
        followChunks(mc);
    }

    /** Called by {@link FreecamModule#onDisable}. */
    public static void deactivate() {
        if (!active) return;
        active = false;
        scrolled = 1.0f;
        Minecraft mc = Minecraft.getInstance();
        if (savedPerspective != null) {
            mc.options.setCameraType(savedPerspective);
            savedPerspective = null;
        }
        releaseChunks(mc);
    }

    /** Called by MouseFreeLookMixin — accumulates raw mouse deltas into yaw/pitch. */
    public static void addMouseDelta(double dx, double dy) {
        yaw  += (float)(dx * 0.15);
        pitch = Mth.clamp(pitch + (float)(dy * 0.15), -90f, 90f);
    }

    /**
     * Per-tick fly: flat (yaw-only) WASD, Space/Sneak for vertical at Speed x Vertical, scaled by
     * Smooth/the scroll multiplier, plus the view-radius follow so terrain stays loaded ahead of it.
     * Call once per tick while active.
     */
    public static void tick(Minecraft mc) {
        if (!active || mc.player == null) return;
        savePrevPos();

        Options options = mc.options;
        boolean fwd  = options.keyUp.isDown();
        boolean back = options.keyDown.isDown();
        boolean lft  = options.keyLeft.isDown();
        boolean rgt  = options.keyRight.isDown();
        boolean up   = options.keyJump.isDown();
        boolean dn   = options.keyShift.isDown();

        double step = flySpeed() * (FreecamModule.smooth() ? 0.5 : 1.0);
        double ahead = (fwd ? 1 : 0) - (back ? 1 : 0);
        double side  = (lft ? 1 : 0) - (rgt ? 1 : 0);
        double rise  = (up  ? 1 : 0) - (dn   ? 1 : 0);

        Vec3 look = Vec3.directionFromRotation(0f, yaw);
        Vec3 left = new Vec3(look.z, 0.0, -look.x);
        x += (look.x * ahead + left.x * side) * step;
        z += (look.z * ahead + left.z * side) * step;
        y += rise * step * FreecamModule.verticalSpeed();

        followChunks(mc);
    }

    private static double flySpeed() {
        double speed = FreecamModule.speed();
        return !FreecamModule.scrollResets() ? speed
                : Mth.clamp((float) (speed * scrolled), 0.1f, 50.0f);
    }

    /**
     * Mouse-wheel speed adjust (Scroll Speed). Returns true if it consumed the scroll (caller cancels
     * the vanilla scroll handling).
     */
    public static boolean scroll(double amount) {
        if (!active || !FreecamModule.scrollSpeedEnabled() || amount == 0.0) return false;
        float factor = amount > 0.0 ? 1.2f : (1f / 1.2f);
        double speed = FreecamModule.speed();
        if (FreecamModule.scrollResets()) {
            scrolled = Mth.clamp((float) (scrolled * factor), (float) (0.1 / speed), (float) (50.0 / speed));
        } else {
            FreecamModule.setSpeed(Mth.clamp((float) (speed * factor), 0.1f, 50.0f));
        }
        return true;
    }

    public static void turn(double yRot, double xRot) {
        yaw += (float) yRot * 0.15f;
        pitch = Mth.clamp(pitch + (float) xRot * 0.15f, -90f, 90f);
    }

    public static boolean keepHands() {
        return FreecamModule.showHands();
    }

    public static boolean showBody() {
        return FreecamModule.showBody();
    }

    // ── Chunk follow ─────────────────────────────────────────────────────────────
    // Keeps the client's loaded/centred view radius around the flying camera instead of the body, at
    // whatever render distance the player already has set (no separate FreeCam setting), so flying far
    // away doesn't show unloaded terrain. Plain client-chunk-cache calls, no render-thread nudge needed —
    // FreeCamChunkVisibilityMixin treats every section as ready while FreeCam is active, so a
    // freshly-centred chunk that hasn't compiled yet doesn't show as a hole.

    private static void followChunks(Minecraft mc) {
        if (!active || mc.level == null || mc.player == null) return;
        ClientChunkCache cache = mc.level.getChunkSource();
        int renderDistance = mc.options.getEffectiveRenderDistance();
        if (renderDistance != viewRadius) {
            cache.updateViewRadius(renderDistance);
            viewRadius = renderDistance;
        }
        ChunkPos camera = new ChunkPos(Mth.floor(x) >> 4, Mth.floor(z) >> 4);
        if (focusedChunk == null || camera.x() != focusedChunk.x() || camera.z() != focusedChunk.z()) {
            cache.updateViewCenter(camera.x(), camera.z());
            focusedChunk = camera;
        }
    }

    private static void releaseChunks(Minecraft mc) {
        focusedChunk = null;
        viewRadius = -1;
        if (mc.level != null && mc.player != null) {
            ClientChunkCache cache = mc.level.getChunkSource();
            ChunkPos feet = mc.player.chunkPosition();
            cache.updateViewCenter(feet.x(), feet.z());
            cache.updateViewRadius(preChunkRadius >= 0 ? preChunkRadius : mc.options.getEffectiveRenderDistance());
        }
        preChunkRadius = -1;
    }
}

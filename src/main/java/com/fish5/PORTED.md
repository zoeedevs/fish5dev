# Code ported from aether

Source: https://github.com/mizly/aether at commit `34d49159ab214a14ab6629699fae8c4f0d438a2d` (2026-10-30).
License: GNU GPL v3.0 or later. Every ported file keeps a header pointing here. Because GPL code is
combined into fish5, the mod as a whole has to be distributed under GPL-3.0-or-later.

## What came across

| fish5 package | aether origin | notes |
| --- | --- | --- |
| `com.fish5.rotation` | `modules/rotation` | `RotationManager` unchanged apart from config/failsafe names |
| `com.fish5.pathfinding` | `modules/pathfinding` | whole subsystem; AOTV lookup moved to `utils/ItemUtils` |
| `com.fish5.failsafe` | `modules/failsafe` | see changes below |
| `com.fish5.utils` | `util/ClientUtils`, `RotationUtils`, `TablistUtils`, `SkyblockLocation`, `Programmatic*Tracker`, `WindowFocusHelper`, `gear/GearManager` (AOTV finder) | |
| `com.fish5.config.ConfigHelpers` | `config/ConfigHelpers` | randomized delays only |
| `com.fish5.macro.MacroState` | `macro/MacroState` | states reduced to OFF / RUNNING / RECOVERING |
| `com.fish5.mixin` | `AccessorKeyMapping`, `AccessorInventory`, `AccessorWindow`, `MixinKeyboardInput`, `MixinMouseHandler`, `MixinMinecraftAttackInput`, `MixinGameRenderer` | renamed to fish5 style; call fish5 classes directly instead of aether's bootstrap hook layer |
| `com.fish5.session` | `telemetry/ban/HypixelBanDetector`, `util/RefocusInputGuard`, `modules/session/DynamicRestManager` + `macro/ReconnectScheduler` (as `RestScheduler`) | see changes below |
| `com.fish5.mixin` (session) | `AccessorMouseHandler` (→ `MouseHandlerAccessor`), `MixinClientPacketListener` (→ `ClientCommonPacketListenerMixin`, disconnect hook only) | |

Written fresh for fish5 (no aether code): `config/MacroConfig`, `macro/MacroStateManager`, `macro/MacroRuntime`,
`session/BanGuard`, `session/RestScreen`.

## Changes from aether

- Rebranded: packages, thread names, chat prefix (`[Fish 5]`), log dir `config/fish5/logs`, sound dir `config/fish5/sounds`.
- Failsafes: dropped BPS, dirt and ghost-block (farming only), custom movement replays (needed aether's
  movement recorder), Discord alerts. The `CUSTOM` failsafe action is gone; it falls back to `STOP`.
- "Pest cleaner" rotation handling became generic target tracking: `FailsafeManager.setTargetTracking(true)`
  while following a mob/bobber. A rotation check during tracking freezes further turning and fires on
  `FAILSAFE_ROTATION_TRACKING_DELAY_MS`.
- Unexpected-GUI failsafe: aether's list of farming managers became one flag, `FailsafeManager.setExpectingGui`.
- World-change failsafe moved into `failsafe/WorldChangeFailsafe` (no auto-recovery).
- Colour flash draws through a Fabric HUD element instead of NanoVG. The default alert sound is a generated
  WAV (aether's bundled mp3 is not included).
- Ban detection: only the local text parser came across. Aether's ban telemetry (`AetherBanService`, reports to
  its API) is not ported, and the ban ID is no longer parsed at all. fish5 only stops and locks reconnects.
- The resource-pack spoof in aether's `MixinClientPacketListener` is deliberately not ported. The fish5 mixin
  only observes the disconnect.
- Scheduled breaks: merged aether's rest manager and reconnect scheduler into one tick-driven `RestScheduler`
  (no executor thread, no state file).
  - Macro time and break length are min-max ranges (picked uniformly) instead of a base + offset.
  - The break waits for a macro-provided safe point instead of aether's farming state.
  - It can idle in game instead of disconnecting.
  - Dropped: the daily-hours cap, `/setspawn`, the NanoVG rest screen.
- `ClientUtils`: removed garden-only helpers (plot, purse, Jacob's contest, loadout/wardrobe waits) and the
  streamer-mode / ungrab-mouse branches.
- Threads: every single-thread executor (`ClientUtils`, `FailsafeSoundManager`, `DesktopNotificationManager`,
  `WindowFocusHelper`) now comes from `utils/DaemonExecutors`, and its thread exits after 30 s idle.
  `ClientUtils`' GUI-close executor was merged into the command executor (input keeps its own thread).
  `PathfindingManager.startPathTest` no longer starts a raw thread; it continues on the shared pathing pool.
- Ungrab Mouse: aether's `UngrabMouse` came back as the `features/impl/misc/UngrabMouse` module. One per-tick
  sync replaces the macro/visual request flags and the Freecam/Freelook suspend bookkeeping. The cursor is
  released while a macro runs (or always, if configured) and grabbed again while Freecam or Freelook is active.
  `MouseHandlerMixin` now cancels `grabMouse`, `onButton` and `onScroll` while the cursor is released, and
  swallows physical clicks while Freecam is active, as aether did.
- `KeyboardInputMixin`: while Freecam (ported from zoeemod) is active, the physical keys are cleared before the
  macro-held keys are applied, so a running macro keeps moving the body while you fly the camera.
- Input lock (`macro/InputLock`, always on, no setting): while a macro
  runs, `KeyboardInputMixin` also clears the physical movement keys, `MouseHandlerMixin` drops in-game clicks,
  and drops scrolling and mouse turning unless Freecam or Freelook is active, and `UngrabMouse` releases the
  cursor whenever the lock is active (except in Freecam / Freelook), even with the module off.

## Using it from a macro

```java
MacroStateManager.startMacro(client);            // failsafes start watching
MacroStateManager.addStopListener(myMacro::stop); // a failsafe STOP tears your macro down
FailsafeManager.selectHotbarSlot(client, slot);   // swap without tripping the slot failsafe
RotationManager.trackRotation(client, aimPoint, smoothingMs, maxDegPerSec); // follow a moving target
MacroStateManager.setState(MacroState.State.RECOVERING); // around warps / menus you open on purpose
MacroStateManager.stopMacro(client, "done", true);
```

`MacroConfig` holds every tuning value with aether's defaults. They are in-memory only until a module
exposes them as click-GUI settings.

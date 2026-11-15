# AGENTS.md

Guidance for coding agents working on HyperTorchIsland. User-facing docs are in `README.md`.

## Project

A single-module LSPosed module on the modern libxposed API 102 (`compileOnly`). It runs only inside `com.android.systemui` and hooks both System UI and the `miui.systemui.plugin` code that System UI loads, so the flashlight Super Island hosts the stock vertical brightness UI.

- Language: Kotlin, JVM 17. AGP 9.2.1, Gradle 9.5.1, compile/target SDK 37, min SDK 33.
- No third-party runtime dependencies. Keep it that way.

## Layout

```text
app/src/main/
  kotlin/io/github/leset0ng/hypertorchisland/
    TorchIslandModule.kt          XposedModule entry, process filter, hot reload save/restore
    hook/ReloadableHooks.kt       hook installer that swaps old-generation hooks on hot reload
    hook/PluginTargetResolver.kt  all reflection lookups -> SystemUiTargets / PluginTargets
    hook/TorchIslandHooks.kt      all hook bodies and flashlight island logic
    ui/TorchIslandHost.kt         TorchIslandSpecs (sizes), TorchIslandRegistry, host/fake views
  resources/META-INF/xposed/
    java_init.list                entry class, must match TorchIslandModule's FQCN
    module.prop                   minApiVersion/targetApiVersion=102, autoHotReload, staticScope
    scope.list                    com.android.systemui only
re-hyperos/                       local reverse engineering notes + decompiled evidence (gitignored)
```

`re-hyperos/` is gitignored and may not exist. When it does, `re-hyperos/README.md` explains why each hook exists and cites the decompiled sources under `re-hyperos/evidence/`. Read it before changing hook behavior. Never commit it, and never copy decompiled Xiaomi code into `app/`.

## Build and verify

```sh
./gradlew :app:assembleDebug      # compile check
./gradlew :app:assembleRelease    # R8 minified, debug signed unless TORCH_ISLAND_KEYSTORE_* is set
```

CI lives in `.github/workflows/`. `build.yml` runs `:app:lintRelease :app:assembleRelease` with the release key on every push to `main` and syncs `README.md` to `Xposed-Modules-Repo/io.github.leset0ng.hypertorchisland`. `release.yml` is started manually with a version, then publishes the APK to this repo's Releases and to the Xposed Modules Repo. The Gradle tasks CI runs must keep passing locally.

There are no automated tests. Behavior can only be verified on a HyperOS device or emulator with LSPosed:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk   # hot reloads into running System UI
adb shell killall com.android.systemui                      # full restart when hot reload isn't enough
```

Then turn the torch on from Control Center and check the island. Hook logs (tag `HyperTorchIsland`) show up in the LSPosed log. A successful install logs `SystemUI plugin bridge hooks installed` and then `MIUISystemUIPlugin hooks installed`.

If you can't run on a device, say so instead of claiming the change works.

## Architecture notes

- **Two class loaders.** `TorchIslandHooks.installSystemUi` hooks System UI classes from `onPackageReady`. Plugin classes (`miui.systemui.dynamicisland.*`, `miui.systemui.notification.focus.*`, `miui.systemui.flashlight.*`) only exist after `DynamicIslandPluginController#onPluginLoaded` hands over the plugin's class loader. Only then does `installPluginHooks` run.
- **Same process, different UID.** Plugin code runs in the System UI process (uid 10217) but its notifications belong to `miui.systemui.plugin` (uid 1000). That's why `NotificationManager.showNotification` and `closeNotification` are no-ops. The island notification is driven by broadcasting `ACTION_TOGGLE_FLASHLIGHT` with a `notification_operation` (`0` cancel, `2` resend, `3` used to suppress reposts during drag gestures).
- **Real and fake views.** `DynamicIslandData` carries an expanded view and a fake view used during expand/collapse animations. Both are swapped per notification key (`hostViewsByKey`, `fakeViewsByKey`). `TorchIslandHostView` creates `MiFlashlightLayout` only while the island is expanded and dismisses it 350 ms after collapse.
- **Strength fallback.** `MiFlashlightController` hides the seekbar and shows its Lottie switch button when `isSupportStrengthLevel()` is false (same `maxStrength >= 100` gate). The island keeps that switch instead of hiding it, re-seats it in the seekbar slot (`TorchIslandSpecs.fallbackSwitch*`), and `fallbackSwitchMode` stops the vertical-drag passthrough since there is no seekbar to receive it.
- **Scoping.** Every hook body must return to stock behavior unless the target is the flashlight island. Use `isFlashlightIsland(targets, data)` / `isFlashlightSbn(sbn)` for island-level hooks and `TorchIslandRegistry.isIslandLayout(layout)` for `MiFlashlightLayout`/`MiFlashlightController` hooks. This keeps the full-screen `MiFlashlightActivity` and other islands unchanged.
- **Drag-to-zero.** `DragState` keeps the island alive while a gesture crosses zero brightness and for `TORCH_GESTURE_SETTLE_MS` after release, because the torch-off callback is asynchronous. Changes here are easy to regress (see the last few `fix:` commits).
- **Shared state** lives in `Settings.Global`: `mi_flashlight_is_torch_on`, `mi_flashlight_is_flashlight_show` (must stay `0` while the island is shown, otherwise `ToggleChangeReceiver` ignores close requests), and `mi_flashlight_strength`.
- **Geometry.** Island and seekbar sizes are in `TorchIslandSpecs`. The corner radius must equal the plugin's `@dimen/island_radius`, because `DynamicIslandBackgroundView` draws its stroke with that radius and any larger content radius exposes it at the corners.

## Hot reload

`module.prop` sets `autoHotReload=true`, so each reinstall loads a new generation of the module into the live process.

- Install every hook through `ReloadableHooks.install(method, id, hooker)`, never `hook()` directly. The new generation replaces the old hook with the same `(executable, id)` instead of stacking, and old hooks that aren't re-installed get unhooked in `finishAdoption`.
- Hook ids are stable strings shaped like `<sysui|plugin>.<component>.<method>`, for example `plugin.flashlightCtrl.onStart`. The `(executable, id)` pair must be unique, because it's how the next generation finds the hook to replace.
- Anything the next generation needs goes through `onHotReloading`'s saved state (`KEY_*` in `TorchIslandModule`). Kotlin `object`s such as `TorchIslandRegistry` are per generation and are not shared.
- `releaseForReload` must drop every view, map and handler callback the old generation owns. Views that are already on screen keep old-generation callbacks, so after a reload `resendFlashlightIslandAfterReload` reposts the notification to rebuild the island.

## Adding or changing a hook

1. Resolve the target in `PluginTargetResolver` and add it to `PluginTargets`/`SystemUiTargets`. A required target uses `getDeclaredMethod`/`getDeclaredField` and fails the whole plugin install if it's missing. An optional target is nullable via `runCatching { ... }.getOrNull()`, and the install site logs when it's absent. Call `isAccessible = true` on private fields.
2. Install it in the matching `install*Hooks` group in `TorchIslandHooks` with a new unique id.
3. Scope it to the flashlight island (see above) and `chain.proceed()` in every other case.
4. When you return a value without proceeding, return what the original method would have returned: `null` for `void`, otherwise a value of the right boxed type.
5. Touch views and island state on the main thread only.

R8 keeps `TorchIslandModule`, `hook.**` and `ui.**` (`app/proguard-rules.pro`). A new package needs a matching keep rule.

## Conventions

- Code, comments, logs and commit messages are in English.
- `README.md` is the default Chinese README. `README_en.md` is its English translation. Update both together.
- Comments explain *why* a hook exists or what stock behavior it works around, ideally naming the stock class/method. Don't narrate what the code does.
- Wrap reflective calls that may fail on other ROM builds in `runCatching` and report through `logError`. Never let an exception escape into System UI.
- Commits use Conventional Commits in lowercase, for example `fix: keep flashlight island alive during zero crossing gestures`.
- Don't commit `local.properties`, keystores, `build/` or `re-hyperos/`.

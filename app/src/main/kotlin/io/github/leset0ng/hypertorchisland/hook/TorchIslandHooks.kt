package io.github.leset0ng.hypertorchisland.hook

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import io.github.leset0ng.hypertorchisland.ui.TorchIslandFakeView
import io.github.leset0ng.hypertorchisland.ui.TorchIslandHostView
import io.github.leset0ng.hypertorchisland.ui.TorchIslandRegistry
import io.github.leset0ng.hypertorchisland.ui.TorchIslandSpecs
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs

internal class TorchIslandHooks(
    private val hooks: ReloadableHooks,
    private val logInfo: (String) -> Unit,
    private val logError: (String, Throwable) -> Unit,
) {
    @Volatile
    var pluginClassLoader: ClassLoader? = null
        private set

    @Volatile
    var pluginContext: Context? = null
        private set

    @Volatile
    private var pluginHooksInstalled = false

    private var pluginTargets: PluginTargets? = null
    private val hostViewsByKey = HashMap<String, TorchIslandHostView>()
    private val fakeViewsByKey = HashMap<String, TorchIslandFakeView>()
    private val controllerByLayout = Collections.synchronizedMap(WeakHashMap<View, Any>())
    private val dragStates = Collections.synchronizedMap(WeakHashMap<Any, DragState>())

    @Volatile
    private var latestTouchInteractorRef: WeakReference<Any>? = null

    private class DragState {
        var isDragging: Boolean = false
        var draggedToZero: Boolean = false
    }

    fun installSystemUi(
        systemUiClassLoader: ClassLoader,
        restoredPluginLoader: ClassLoader? = null,
        restoredPluginContext: Context? = null,
    ) {
        val sysTargets = PluginTargetResolver.resolveSystemUi(systemUiClassLoader)

        hooks.install(sysTargets.onPluginLoadedMethod, "sysui.onPluginLoaded") { chain ->
            val plugin = chain.getArg(0)
            val ctx = chain.getArg(1) as? Context
            val result = chain.proceed()
            if (plugin != null) {
                onPluginAvailable(plugin, ctx)
            }
            result
        }

        sysTargets.getContentMethod?.let { getContent ->
            hooks.install(getContent, "sysui.getContent") { chain ->
                val result = chain.proceed()
                if (!pluginHooksInstalled) {
                    val plugin = extractPluginFromController(chain.thisObject, sysTargets)
                    if (plugin != null) {
                        onPluginAvailable(plugin, null)
                    }
                }
                result
            }
        }

        sysTargets.handleFocusNotificationMethod?.let { handleFocusMethod ->
            hooks.install(handleFocusMethod, "sysui.flashlightCtrl.handleFocusNotification") { chain ->
                if (isTorchOffByDragToZero()) {
                    val isOpen = chain.getArg(0) as Boolean
                    val op = chain.getArg(1) as Int
                    val user = chain.getArg(2)
                    // The torch was dragged to 0 by the user: keep the expanded island so it can be dragged
                    // back up. Turning the torch off from anywhere else still closes the island normally.
                    if (op == 0 || op == 1) {
                        return@install chain.proceed(arrayOf(isOpen, 3, user))
                    }
                }
                chain.proceed()
            }
        }

        logInfo("SystemUI plugin bridge hooks installed")

        if (restoredPluginLoader != null) {
            pluginClassLoader = restoredPluginLoader
            pluginContext = restoredPluginContext
            installPluginHooks(restoredPluginLoader)
        }
    }

    private fun extractPluginFromController(controller: Any?, sysTargets: SystemUiTargets): Any? {
        if (controller == null) return null
        val field = sysTargets.currentPluginField ?: return null
        val stateFlow = runCatching { field.get(controller) }.getOrNull() ?: return null
        val getValueMethod = stateFlow.javaClass.methods.firstOrNull {
            it.name == "getValue" && it.parameterCount == 0
        } ?: return null
        val holder = runCatching { getValueMethod.invoke(stateFlow) }.getOrNull() ?: return null
        val pluginField = runCatching {
            holder.javaClass.getDeclaredField("plugin").apply { isAccessible = true }
        }.getOrNull() ?: return null
        return runCatching { pluginField.get(holder) }.getOrNull()
    }

    private fun onPluginAvailable(plugin: Any, ctx: Context?) {
        val loader = plugin.javaClass.classLoader ?: return
        pluginClassLoader = loader
        if (ctx != null) {
            pluginContext = ctx
        } else if (pluginContext == null) {
            pluginContext = runCatching {
                val base = Class.forName("miui.systemui.plugins.PluginBase", false, loader)
                base.getDeclaredMethod("getPluginContext").invoke(plugin) as? Context
            }.getOrNull()
        }
        if (!pluginHooksInstalled || pluginTargets?.pluginClassLoader !== loader) {
            installPluginHooks(loader)
        }
    }

    @Synchronized
    private fun installPluginHooks(loader: ClassLoader) {
        val targets = try {
            PluginTargetResolver.resolvePlugin(loader)
        } catch (t: Throwable) {
            logError("Failed to resolve MIUISystemUIPlugin targets", t)
            return
        }
        pluginTargets = targets

        installDynamicIslandDataHooks(targets)
        installLayoutAndSizingHooks(targets)
        installTouchAndClickHooks(targets)
        installFlashlightSideEffectHooks(targets)

        pluginHooksInstalled = true
        logInfo("MIUISystemUIPlugin hooks installed (loader=${loader.javaClass.simpleName})")
    }

    private fun installDynamicIslandDataHooks(targets: PluginTargets) {
        val replaceDataViews: (Any?) -> Unit = { data ->
            if (data != null && isFlashlightIsland(targets, data)) {
                val key = (targets.dataGetKeyMethod.invoke(data) as? String).orEmpty()
                val ctx = pluginContext ?: resolvePluginContextFallback(targets, data)
                if (ctx != null) {
                    val host = getOrCreateHostView(targets, ctx, key)
                    val fake = getOrCreateFakeView(ctx, key)
                    targets.dataSetViewMethod.invoke(data, host)
                    targets.dataSetFakeViewMethod.invoke(data, fake)
                }
            }
        }

        hooks.install(targets.addDynamicIslandViewMethod, "plugin.windowCtrl.add") { chain ->
            replaceDataViews(chain.getArg(0))
            chain.proceed()
        }

        hooks.install(targets.updateDynamicIslandViewMethod, "plugin.windowCtrl.update") { chain ->
            replaceDataViews(chain.getArg(0))
            chain.proceed()
        }
    }

    private fun installLayoutAndSizingHooks(targets: PluginTargets) {
        hooks.install(targets.updateExpandedSizeMethod, "plugin.baseContent.updateExpandedSize") { chain ->
            val data = chain.getArg(2)
            if (data == null || !isFlashlightIsland(targets, data)) {
                return@install chain.proceed()
            }
            val contentView = chain.thisObject as View
            val ctx = pluginContext ?: contentView.context
            val targetWidth = TorchIslandSpecs.dp(ctx, TorchIslandSpecs.ISLAND_WIDTH_DP)
            val targetHeight = TorchIslandSpecs.dp(ctx, TorchIslandSpecs.ISLAND_HEIGHT_DP)
            val screenWidth = (targets.getScreenWidthOldMethod.invoke(
                targets.dynamicIslandUtilsInstance,
                ctx,
            ) as? Int) ?: ctx.resources.displayMetrics.widthPixels

            targets.setExpandedViewWidthMethod.invoke(contentView, targetWidth)
            targets.setExpandedViewHeightMethod.invoke(contentView, targetHeight)
            targets.setExpandedViewMarginHorizontalMethod.invoke(
                contentView,
                (screenWidth - targetWidth) / 2,
            )

            targets.dataGetViewMethod.invoke(data)?.let { view ->
                targets.updateViewLayoutParamsMethod.invoke(contentView, view, targetWidth, targetHeight)
            }
            targets.dataGetFakeViewMethod.invoke(data)?.let { fake ->
                targets.updateViewLayoutParamsMethod.invoke(contentView, fake, targetWidth, targetHeight)
            }
            null
        }

        hooks.install(targets.containerClipRadiusMethod, "plugin.propertyUpdater.clipRadius") { chain ->
            val stock = chain.proceed() as Float
            val updater = chain.thisObject
            val baseContentView = targets.propertyUpdaterViewField.get(updater) as? View ?: return@install stock
            val data = targets.getCurrentIslandDataMethod.invoke(baseContentView) ?: return@install stock
            if (!isFlashlightIsland(targets, data)) return@install stock

            val state = targets.getStateMethod.invoke(baseContentView)
            val lastState = targets.getLastStateMethod.invoke(baseContentView)
            val expandingOrExpanded = isExpandedLike(targets, state) || isExpandedLike(targets, lastState)
            if (!expandingOrExpanded) return@install stock

            val top = chain.getArg(0) as Float
            val bottom = chain.getArg(1) as Float
            val maxRadius = ((bottom - top) / 2.0f).coerceAtLeast(0f)
            val desired = TorchIslandSpecs.dpF(baseContentView.context, TorchIslandSpecs.ISLAND_CORNER_RADIUS_DP)
            minOf(maxRadius, desired)
        }

        hooks.install(targets.setStateMethod, "plugin.baseContent.setState") { chain ->
            val result = chain.proceed()
            val baseContentView = chain.thisObject as? View
            if (baseContentView != null) {
                val data = targets.getCurrentIslandDataMethod.invoke(baseContentView)
                if (isFlashlightIsland(targets, data)) {
                    findHostView(baseContentView)?.syncState()
                }
            }
            result
        }
    }

    private fun markAsUserExpanded(targets: PluginTargets, contentView: View) {
        val state = runCatching { targets.getStateMethod.invoke(contentView) }.getOrNull() ?: return
        if (!isExpandedLike(targets, state)) return
        val coordinator = runCatching { targets.getEventCoordinatorMethod.invoke(contentView) }.getOrNull()
            ?: return
        val alreadyExpanded = runCatching {
            targets.getUserExpandedMethod.invoke(coordinator) as? Boolean
        }.getOrNull() ?: false
        if (alreadyExpanded) return
        // Tapping outside the island only collapses it when getUserExpanded() is true, so the auto
        // expanded flashlight island has to be flagged as user expanded to stay dismissible.
        runCatching { targets.setUserExpandedMethod.invoke(coordinator, true) }
            .onFailure { t -> logError("Failed to mark flashlight island as user expanded", t) }

        // A user opened island also registers the outside tap monitor through its expanded region.
        // The auto expanded one has no expanded region yet, so push it after the next layout pass.
        contentView.post { updateOutsideTapMonitor(targets, contentView, true) }
    }

    private fun updateOutsideTapMonitor(targets: PluginTargets, contentView: View, enable: Boolean) {
        runCatching {
            val coordinator = targets.getEventCoordinatorMethod.invoke(contentView) as? Any ?: return
            val region = android.graphics.Region()
            if (enable) {
                val rect = android.graphics.Rect()
                if (!contentView.getGlobalVisibleRect(rect) || rect.isEmpty) return
                region.set(rect)
            }
            targets.handleExpandGestureListenerMethod.invoke(coordinator, region)
        }.onFailure { t -> logError("Failed to update outside tap monitor", t) }
    }

    private fun findContentView(view: View?): View? {
        var current = view
        while (current != null) {
            val targets = pluginTargets
            if (targets != null && targets.baseContentViewClass.isInstance(current)) return current
            val parent = current.parent
            current = parent as? View
        }
        return null
    }

    private fun installTouchAndClickHooks(targets: PluginTargets) {
        hooks.install(targets.onIslandClickMethod, "plugin.contentView.onIslandClick") { chain ->
            val contentView = chain.thisObject
            val state = targets.getStateMethod.invoke(contentView)
            val data = targets.getCurrentIslandDataMethod.invoke(contentView)
            if (isExpandedLike(targets, state) && isFlashlightIsland(targets, data) &&
                isIslandCurrentlyExpanded(targets, contentView as? View)
            ) {
                // Keep the user inside the expanded torch island instead of launching MiFlashlightActivity.
                return@install null
            }
            chain.proceed()
        }

        hooks.install(targets.onInterceptTouchEventMethod, "plugin.touchInteractor.onIntercept") { chain ->
            val event = chain.getArg(0) as? MotionEvent ?: return@install chain.proceed()
            val interactor = chain.thisObject
            latestTouchInteractorRef = WeakReference(interactor)
            val action = event.actionMasked

            if (action == MotionEvent.ACTION_MOVE) {
                val downInExpanded = targets.touchDownInExpandedField.getBoolean(interactor)
                val currentIntercepted = targets.touchInterceptedField.get(interactor)
                if (downInExpanded && currentIntercepted == null && isTorchCurrentlyExpanded(targets, interactor)) {
                    val initX = targets.touchInitXField.getFloat(interactor)
                    val initY = targets.touchInitYField.getFloat(interactor)
                    val dx = event.x - initX
                    val dy = event.y - initY
                    val absX = abs(dx)
                    val absY = abs(dy)
                    val touchSlop = readTouchSlop(targets, interactor)
                    if (absY > touchSlop && absY >= absX) {
                        targets.touchDirectionField.set(
                            interactor,
                            if (dy > 0f) targets.directionDown else targets.directionUp,
                        )
                        targets.touchInterceptedField.set(interactor, java.lang.Boolean.FALSE)
                        return@install null
                    }
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (targets.touchDownInExpandedField.getBoolean(interactor) &&
                    isTorchCurrentlyExpanded(targets, interactor)
                ) {
                    resetExpandedPress(targets, interactor)
                }
            }
            chain.proceed()
        }
    }

    private fun installFlashlightSideEffectHooks(targets: PluginTargets) {
        hooks.install(targets.disableStatusBarMethod, "plugin.flashlightLayout.disableStatusBar") { chain ->
            val layout = chain.thisObject as? View
            if (TorchIslandRegistry.isIslandLayout(layout)) {
                return@install null
            }
            chain.proceed()
        }

        hooks.install(targets.controllerStartMethod, "plugin.flashlightCtrl.onStart") { chain ->
            val controller = chain.thisObject
            val layout = targets.controllerLayoutField.get(controller) as? View
            if (TorchIslandRegistry.isIslandLayout(layout) && layout != null) {
                targets.controllerContextField.set(controller, layout.context)
                controllerByLayout[layout] = controller
            }
            val result = chain.proceed()
            if (TorchIslandRegistry.isIslandLayout(layout)) {
                // onStart() marks "full screen flashlight is showing". For the island that flag would make
                // ToggleChangeReceiver ignore close requests, so the notification could never be removed.
                clearFlashlightShowFlag(layout?.context)
            }
            result
        }

        hooks.install(targets.layoutShowViewMethod, "plugin.flashlightLayout.showView") { chain ->
            val result = chain.proceed()
            val layout = chain.thisObject as? View
            if (TorchIslandRegistry.isIslandLayout(layout) && layout != null) {
                val needAnim = targets.layoutIsNeedAnimMethod.invoke(layout) as? Boolean ?: true
                if (!needAnim) {
                    // Without MiFlashlightLayout's alpha/blur show animation, onAnimEnd() never runs,
                    // so resume() must be called explicitly to start MglRenderer's FrameSync render loop.
                    targets.layoutResumeMethod.invoke(layout)
                }
                val controller = controllerByLayout[layout]
                if (controller != null) {
                    val ui = targets.controllerUiField.get(controller)
                    val manager = targets.controllerManagerField.get(controller)
                    val savedStrength = (manager?.let {
                        targets.managerGetLogicStrengthMethod.invoke(it) as? Float
                    }) ?: targets.controllerLogicProgressField.getFloat(controller)
                    if (ui != null && savedStrength > 0f) {
                        targets.uiSetProgressMethod.invoke(ui, savedStrength.coerceIn(0.15f, 1.0f))
                    }
                }
            }
            result
        }

        hooks.install(targets.seekBarTouchDoTouchMethod, "plugin.flashlightSeekBar.doTouch") { chain ->
            val touchListener = chain.thisObject
            val controller = targets.seekBarTouchControllerField.get(touchListener)
            val layout = controller?.let { targets.controllerLayoutField.get(it) as? View }
            if (controller == null || !TorchIslandRegistry.isIslandLayout(layout)) {
                return@install chain.proceed()
            }
            val event = chain.getArg(0) as? MotionEvent
            val state = dragStates.getOrPut(controller) { DragState() }
            when (event?.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    state.isDragging = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    state.isDragging = false
                }
            }
            chain.proceed()
        }

        hooks.install(targets.controllerSetFlashlightStatusMethod, "plugin.flashlightCtrl.setFlashlightStatus") { chain ->
            val controller = chain.thisObject
            val layout = targets.controllerLayoutField.get(controller) as? View
            if (!TorchIslandRegistry.isIslandLayout(layout)) {
                return@install chain.proceed()
            }
            val percentage = chain.getArg(0) as Float
            val state = dragStates.getOrPut(controller) { DragState() }
            if (state.isDragging && percentage <= 0.0f) {
                state.draggedToZero = true
            } else if (percentage > 0.0f) {
                state.draggedToZero = false
            }
            // Keep the \"torch is on\" bookkeeping in sync: dragging to 0 switches the torch off directly,
            // and MiFlashlightController.onStop relies on this flag to drop the island notification.
            runCatching {
                val ctx = targets.controllerContextField.get(controller) as? Context
                if (ctx != null) {
                    android.provider.Settings.Global.putInt(
                        ctx.contentResolver,
                        "mi_flashlight_is_torch_on",
                        if (percentage > 0.0f) 1 else 0,
                    )
                }
            }.onFailure { t -> logError("Failed to sync mi_flashlight_is_torch_on", t) }
            // Always proceed so MiFlashlightManager.asyncOperate(false) turns off the real torch immediately!
            chain.proceed()
        }

        hooks.install(targets.controllerUpdateFlashlightUiMethod, "plugin.flashlightCtrl.updateFlashlightUi") { chain ->
            val controller = chain.thisObject
            val layout = targets.controllerLayoutField.get(controller) as? View
            if (!TorchIslandRegistry.isIslandLayout(layout)) {
                return@install chain.proceed()
            }
            val state = dragStates[controller]
            val f2 = (chain.getArg(0) as Float).coerceIn(0f, 1f)
            if (state != null && (state.isDragging || state.draggedToZero)) {
                val ui = targets.controllerUiField.get(controller)
                if (ui != null) {
                    targets.uiSetProgressMethod.invoke(ui, f2)
                }
                return@install null
            }
            chain.proceed()
        }

        hooks.install(targets.controllerUpdateSeekBarProcessMethod, "plugin.flashlightCtrl.updateSeekBarProcess") { chain ->
            val controller = chain.thisObject
            val layout = targets.controllerLayoutField.get(controller) as? View
            if (!TorchIslandRegistry.isIslandLayout(layout)) {
                return@install chain.proceed()
            }
            val state = dragStates[controller]
            val f2 = (chain.getArg(0) as Float).coerceIn(0f, 1f)
            if (state != null && (state.isDragging || state.draggedToZero)) {
                val seekbar = targets.controllerGetSeekbarMethod.invoke(controller) as? ProgressBar
                if (seekbar != null && seekbar.visibility != View.GONE) {
                    seekbar.progress = (seekbar.max * f2).toInt()
                }
                return@install null
            }
            chain.proceed()
        }

        hooks.install(targets.enableStatusBarMethod, "plugin.flashlightLayout.enableStatusBar") { chain ->
            val layout = chain.thisObject as? View
            if (TorchIslandRegistry.isIslandLayout(layout)) {
                return@install null
            }
            chain.proceed()
        }

        hooks.install(targets.saveFlashlightShowStatusMethod, "plugin.toggleReceiver.saveShowStatus") { chain ->
            // MiFlashlightActivity marks "full screen flashlight is showing", which makes ToggleChangeReceiver
            // ignore notification open/close requests. While our vertical island is on screen the flag must
            // stay false, otherwise the island notification can never be posted or closed.
            if (hostViewsByKey.values.any { it.isAttachedToWindow && it.isShown }) {
                return@install chain.proceed(arrayOf(chain.getArg(0), false))
            }
            chain.proceed()
        }

        targets.focusCtrlAddIslandViewMethod?.let { addIslandViewMethod ->
            hooks.install(addIslandViewMethod, "plugin.focusCtrl.addDynamicIslandView") { chain ->
                val sbn = chain.getArg(1) as? StatusBarNotification
                if (sbn != null && isFlashlightSbn(sbn)) {
                    // The stock flashlight notification ships "islandFirstFloat": false, which keeps the
                    // island collapsed until the user taps it. Flip it so the island expands when posted.
                    runCatching {
                        sbn.notification.extras?.putBoolean(EXTRA_ISLAND_FIRST_FLOAT, true)
                        logInfo("forced islandFirstFloat=true on flashlight focus notification")
                    }.onFailure { t -> logError("Failed to force islandFirstFloat for flashlight", t) }
                }
                chain.proceed()
            }
        } ?: logInfo("FocusNotificationController.addDynamicIslandView not found")

        targets.safeguardsDelayCollapsedMethod?.let { delayCollapsedMethod ->
            hooks.install(delayCollapsedMethod, "plugin.safeguards.delayCollapsed") { chain ->
                if (isTorchIslandShowing(targets, chain.thisObject)) {
                    // Keep the auto expanded flashlight island on screen instead of collapsing after 5s.
                    return@install null
                }
                chain.proceed()
            }
        } ?: logInfo("DynamicIslandSafeguardsController.delayCollapsed not found")

        hooks.install(targets.showNotificationMethod, "plugin.notifMgr.showNotification") {
            // SystemUI (uid 10217) cannot post/update notifications as miui.systemui.plugin (uid 1000).
            null
        }

        hooks.install(targets.closeNotificationMethod, "plugin.notifMgr.closeNotification") {
            null
        }

        hooks.install(targets.controllerSetSwitchLottieMethod, "plugin.flashlightCtrl.setSwitchLottie") { chain ->
            val controller = chain.thisObject
            val layout = targets.controllerLayoutField.get(controller) as? View
            if (!TorchIslandRegistry.isIslandLayout(layout)) {
                return@install chain.proceed()
            }
            // Hide switch button & bottom draw-up indicator inside the vertical island.
            (targets.controllerGetSwitchLottieMethod.invoke(controller) as? View)?.visibility = View.GONE
            (targets.controllerGetBottomDrawUpMethod.invoke(controller) as? View)?.visibility = View.GONE
            null
        }

        hooks.install(targets.controllerSetSeekbarMethod, "plugin.flashlightCtrl.setSeekbar") { chain ->
            val controller = chain.thisObject
            val layout = targets.controllerLayoutField.get(controller) as? View
            if (!TorchIslandRegistry.isIslandLayout(layout)) {
                return@install chain.proceed()
            }
            var supported = chain.getArg(0) as Boolean
            if (!supported && isEmulator()) {
                val manager = targets.controllerManagerField.get(controller)
                if (manager != null) {
                    val curMax = targets.managerMaxStrengthField.getInt(manager)
                    if (curMax < 100) {
                        targets.managerMaxStrengthField.setInt(manager, 100)
                    }
                }
                supported = true
            }
            val result = chain.proceed(arrayOf(supported))
            if (supported) {
                adaptSeekbarForIsland(targets, controller)
                if (isEmulator()) {
                    targets.controllerSetSwitchStatusMethod.invoke(controller, java.lang.Boolean.TRUE)
                }
            }
            (targets.controllerGetBottomDrawUpMethod.invoke(controller) as? View)?.visibility = View.GONE
            (targets.controllerGetSwitchLottieMethod.invoke(controller) as? View)?.visibility = View.GONE
            result
        }

        targets.controllerGetSeekBarTouchLengthMethod?.let { getTouchLen ->
            hooks.install(getTouchLen, "plugin.flashlightCtrl.seekBarTouchLength") { chain ->
                val controller = chain.thisObject
                val layout = targets.controllerLayoutField.get(controller) as? View
                if (TorchIslandRegistry.isIslandLayout(layout)) {
                    val ctx = layout?.context ?: pluginContext
                    if (ctx != null) {
                        return@install TorchIslandSpecs.dp(ctx, TorchIslandSpecs.SEEKBAR_HEIGHT_DP)
                    }
                }
                chain.proceed()
            }
        }
    }

    private fun adaptSeekbarForIsland(targets: PluginTargets, controller: Any) {
        val seekbarContainer = targets.controllerGetSeekbarContainerMethod.invoke(controller) as? View ?: return
        val touchView = targets.controllerGetSeekbarTouchViewMethod.invoke(controller) as? View
        val ctx = seekbarContainer.context

        (seekbarContainer.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.width = TorchIslandSpecs.dp(ctx, TorchIslandSpecs.SEEKBAR_WIDTH_DP)
            lp.height = TorchIslandSpecs.dp(ctx, TorchIslandSpecs.SEEKBAR_HEIGHT_DP)
            lp.bottomMargin = TorchIslandSpecs.dp(ctx, TorchIslandSpecs.SEEKBAR_MARGIN_BOTTOM_DP)
            seekbarContainer.layoutParams = lp
        }

        (touchView?.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            lp.bottomMargin = 0
            lp.marginStart = 0
            lp.marginEnd = 0
            touchView.layoutParams = lp
        }
    }

    private fun getOrCreateHostView(
        targets: PluginTargets,
        ctx: Context,
        key: String,
    ): TorchIslandHostView {
        hostViewsByKey[key]?.let { return it }
        val host = TorchIslandHostView(
            pluginContext = ctx,
            createFlashlightLayout = { context ->
                val manager = PluginTargetResolver.resolveFlashlightManager(targets)
                    ?: error("MiFlashlightManager unavailable in PluginComponent")
                targets.getMiFlashlightLayoutMethod.invoke(manager, context, false) as View
            },
            dismissFlashlightLayout = { layoutHash ->
                val manager = PluginTargetResolver.resolveFlashlightManager(targets)
                if (manager != null) {
                    targets.dismissMethod.invoke(manager, layoutHash)
                }
            },
            isExpandedState = { view ->
                isAncestorExpanded(targets, view)
            },
            onReleaseTouch = {
                resetLatestExpandedPress(targets)
            },
            onExpandedLayout = { layout ->
                findContentView(layout)?.let { contentView ->
                    markAsUserExpanded(targets, contentView)
                    updateOutsideTapMonitor(targets, contentView, true)
                }
            },
            onCollapsedWithLayout = { layout ->
                findContentView(layout)?.let { contentView ->
                    updateOutsideTapMonitor(targets, contentView, false)
                }
                if (isTorchTurnedOffForLayout(targets, layout)) {
                    sendCloseFlashlightIslandBroadcast(layout.context)
                }
            },
            logError = logError,
        )
        hostViewsByKey[key] = host
        return host
    }

    private fun isIslandCurrentlyExpanded(targets: PluginTargets, contentView: View?): Boolean {
        if (contentView == null) return false
        val coordinator = runCatching { targets.getEventCoordinatorMethod.invoke(contentView) }.getOrNull()
            ?: return false
        val windowView = runCatching { targets.coordinatorGetWindowViewMethod.invoke(coordinator) }.getOrNull()
            ?: return false
        val expanded = runCatching { targets.getCurrentExpandedStateMethod.invoke(windowView) }.getOrNull()
        return expanded != null && expanded === contentView
    }

    private fun isExpandedLike(targets: PluginTargets, state: Any?): Boolean {
        if (state == null) return false
        return targets.stateExpandedClass.isInstance(state) ||
            targets.stateBigIslandClass.isInstance(state) ||
            targets.stateShowOnceBigIslandClass.isInstance(state)
    }

    private fun isAncestorExpanded(targets: PluginTargets, view: View): Boolean {
        var parent = view.parent
        while (parent != null) {
            if (targets.baseContentViewClass.isInstance(parent)) {
                val state = targets.getStateMethod.invoke(parent)
                return isExpandedLike(targets, state)
            }
            parent = parent.parent
        }
        return false
    }

    private fun findHostView(root: View): TorchIslandHostView? {
        if (root is TorchIslandHostView) return root
        if (root !is ViewGroup) return null
        for (i in 0 until root.childCount) {
            val found = findHostView(root.getChildAt(i))
            if (found != null) return found
        }
        return null
    }

    private fun isEmulator(): Boolean {
        return Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
            Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
            Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true)
    }

    private fun getOrCreateFakeView(ctx: Context, key: String): TorchIslandFakeView {
        fakeViewsByKey[key]?.let { return it }
        val fake = TorchIslandFakeView(ctx)
        fakeViewsByKey[key] = fake
        return fake
    }

    private fun resolvePluginContextFallback(targets: PluginTargets, data: Any): Context? {
        val existing = targets.dataGetViewMethod.invoke(data) as? View
        val ctx = existing?.context
        if (ctx != null) {
            pluginContext = ctx
            return ctx
        }
        return null
    }

    private fun isTorchIslandShowing(targets: PluginTargets, safeguardsController: Any?): Boolean {
        val windowView = targets.safeguardsWindowViewField.get(safeguardsController) ?: return false
        val candidates = listOfNotNull(
            runCatching { targets.getCurrentExpandedStateMethod.invoke(windowView) }.getOrNull(),
            runCatching { targets.getCurrentBigIslandStateMethod.invoke(windowView) }.getOrNull(),
        )
        return candidates.any { contentView ->
            val data = runCatching { targets.getCurrentIslandDataMethod.invoke(contentView) }.getOrNull()
            isFlashlightIsland(targets, data)
        }
    }

    private fun isFlashlightSbn(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == PLUGIN_PKG && sbn.notification.channelId == "flashlight_channel") {
            return true
        }
        val param = sbn.notification.extras?.getString("miui.focus.param")
        if (param != null && param.contains("\"miui_flashlight\"")) {
            return true
        }
        val ticker = sbn.notification.extras?.getString("miui.island.tickerData")
        return ticker != null && ticker.contains("flash_light_icon")
    }

    private fun isFlashlightIsland(targets: PluginTargets, data: Any?): Boolean {
        if (data == null) return false
        val extras = targets.dataGetExtrasMethod.invoke(data) as? Bundle ?: return false
        @Suppress("DEPRECATION")
        val sbn = extras.getParcelable<StatusBarNotification>("miui.sbn") ?: return false
        return isFlashlightSbn(sbn)
    }

    private fun isTorchCurrentlyExpanded(targets: PluginTargets, interactor: Any): Boolean {
        val windowView = targets.touchWindowViewField.get(interactor) ?: return false
        val expandedContentView = targets.getCurrentExpandedStateMethod.invoke(windowView) ?: return false
        val data = targets.getCurrentIslandDataMethod.invoke(expandedContentView) ?: return false
        return isFlashlightIsland(targets, data)
    }

    private fun isTorchOffByDragToZero(): Boolean {
        synchronized(dragStates) {
            return dragStates.values.any { it.draggedToZero }
        }
    }

    private fun clearDragToZero() {
        synchronized(dragStates) {
            dragStates.values.forEach { it.draggedToZero = false }
        }
    }

    private fun isTorchTurnedOffForLayout(targets: PluginTargets, layout: View): Boolean {
        val controller = controllerByLayout[layout] ?: return false
        val state = dragStates[controller]
        if (state?.draggedToZero == true) return true
        val switchStatus = runCatching {
            targets.controllerGetSwitchStatusMethod.invoke(controller) as? Boolean
        }.getOrNull()
        return switchStatus == false
    }

    private fun clearFlashlightShowFlag(context: Context?) {
        val ctx = context ?: return
        runCatching {
            android.provider.Settings.Global.putInt(ctx.contentResolver, "mi_flashlight_is_flashlight_show", 0)
        }.onFailure { t -> logError("Failed to clear mi_flashlight_is_flashlight_show", t) }
    }

    private fun sendCloseFlashlightIslandBroadcast(fallbackContext: Context) {
        clearDragToZero()
        runCatching {
            val sysCtx = runCatching {
                Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null) as? Context
            }.getOrNull() ?: fallbackContext
            // ToggleChangeReceiver ignores close requests while this flag is set.
            runCatching {
                android.provider.Settings.Global.putInt(
                    sysCtx.contentResolver,
                    "mi_flashlight_is_flashlight_show",
                    0,
                )
            }
            val intent = Intent("miui.systemui.action.ACTION_TOGGLE_FLASHLIGHT").apply {
                setPackage(PLUGIN_PKG)
                putExtra("flashlight_is_open_or_close", false)
                putExtra("notification_operation", 0)
            }
            sysCtx.sendBroadcast(intent)
        }.onFailure { t ->
            logError("Failed to close flashlight island notification on collapse while torch off", t)
        }
    }

    private fun resetLatestExpandedPress(targets: PluginTargets) {
        val interactor = latestTouchInteractorRef?.get() ?: return
        resetExpandedPress(targets, interactor)
    }

    private fun resetExpandedPress(targets: PluginTargets, interactor: Any) {
        runCatching {
            val provider = targets.touchEventCoordinatorField.get(interactor) ?: return
            val getMethod = provider.javaClass.methods.firstOrNull { it.name == "get" && it.parameterCount == 0 }
                ?: return
            val coordinator = getMethod.invoke(provider) ?: return
            targets.eventCoordinatorResetPressMethod.invoke(
                coordinator,
                java.lang.Boolean.FALSE,
                java.lang.Boolean.FALSE,
                java.lang.Boolean.TRUE,
            )
        }
    }

    private fun readTouchSlop(targets: PluginTargets, interactor: Any): Float {
        val constants = targets.touchConstantsField.get(interactor) ?: return DEFAULT_TOUCH_SLOP_PX
        val flow = targets.touchConstantsGetTouchSlopMethod.invoke(constants) ?: return DEFAULT_TOUCH_SLOP_PX
        val getValue = flow.javaClass.methods.firstOrNull { it.name == "getValue" && it.parameterCount == 0 }
            ?: return DEFAULT_TOUCH_SLOP_PX
        val value = getValue.invoke(flow) as? Int ?: return DEFAULT_TOUCH_SLOP_PX
        return value.toFloat()
    }

    fun releaseForReload() {
        for (host in hostViewsByKey.values) {
            host.releaseNow()
        }
        hostViewsByKey.clear()
        fakeViewsByKey.clear()
        controllerByLayout.clear()
        dragStates.clear()
        latestTouchInteractorRef = null
    }

    private companion object {
        const val PLUGIN_PKG = "miui.systemui.plugin"
        const val EXTRA_ISLAND_FIRST_FLOAT = "miui.island.firstFloat"
        const val DEFAULT_TOUCH_SLOP_PX = 24f
    }
}

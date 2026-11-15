package io.github.leset0ng.hypertorchisland.hook

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.github.leset0ng.hypertorchisland.ui.TorchIslandFakeView
import io.github.leset0ng.hypertorchisland.ui.TorchIslandHostView
import io.github.leset0ng.hypertorchisland.ui.TorchIslandRegistry
import io.github.leset0ng.hypertorchisland.ui.TorchIslandSpecs
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
            val expandingOrExpanded = targets.stateExpandedClass.isInstance(state) ||
                targets.stateExpandedClass.isInstance(lastState)
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

    private fun installTouchAndClickHooks(targets: PluginTargets) {
        hooks.install(targets.onIslandClickMethod, "plugin.contentView.onIslandClick") { chain ->
            val contentView = chain.thisObject
            val state = targets.getStateMethod.invoke(contentView)
            val data = targets.getCurrentIslandDataMethod.invoke(contentView)
            if (targets.stateExpandedClass.isInstance(state) && isFlashlightIsland(targets, data)) {
                // Keep the user inside the expanded torch island instead of launching MiFlashlightActivity.
                return@install null
            }
            chain.proceed()
        }

        hooks.install(targets.onInterceptTouchEventMethod, "plugin.touchInteractor.onIntercept") { chain ->
            val event = chain.getArg(0) as? MotionEvent ?: return@install chain.proceed()
            val interactor = chain.thisObject

            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
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
            // Only skip if at least one island layout is currently active, so full-screen MiFlashlightActivity keeps normal behavior.
            if (hostViewsByKey.values.any { it.isAttachedToWindow && it.isShown }) {
                return@install null
            }
            chain.proceed()
        }

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
            val marginH = TorchIslandSpecs.dp(ctx, TorchIslandSpecs.TOUCH_VIEW_MARGIN_HORIZONTAL_DP)
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = TorchIslandSpecs.dp(ctx, TorchIslandSpecs.TOUCH_VIEW_HEIGHT_DP)
            lp.bottomMargin = TorchIslandSpecs.dp(ctx, TorchIslandSpecs.TOUCH_VIEW_MARGIN_BOTTOM_DP)
            lp.marginStart = marginH
            lp.marginEnd = marginH
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
            logError = logError,
        )
        hostViewsByKey[key] = host
        return host
    }

    private fun isAncestorExpanded(targets: PluginTargets, view: View): Boolean {
        var parent = view.parent
        while (parent != null) {
            if (targets.baseContentViewClass.isInstance(parent)) {
                val state = targets.getStateMethod.invoke(parent)
                return targets.stateExpandedClass.isInstance(state)
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

    private fun isFlashlightIsland(targets: PluginTargets, data: Any?): Boolean {
        if (data == null) return false
        val extras = targets.dataGetExtrasMethod.invoke(data) as? Bundle ?: return false
        @Suppress("DEPRECATION")
        val sbn = extras.getParcelable<StatusBarNotification>("miui.sbn")
        if (sbn != null) {
            if (sbn.packageName == PLUGIN_PKG && sbn.notification.channelId == "flashlight_channel") {
                return true
            }
            val param = sbn.notification.extras?.getString("miui.focus.param")
            if (param != null && param.contains("\"miui_flashlight\"")) {
                return true
            }
            val ticker = sbn.notification.extras?.getString("miui.island.tickerData")
            if (ticker != null && ticker.contains("flash_light_icon")) {
                return true
            }
        }
        return false
    }

    private fun isTorchCurrentlyExpanded(targets: PluginTargets, interactor: Any): Boolean {
        val windowView = targets.touchWindowViewField.get(interactor) ?: return false
        val expandedContentView = targets.getCurrentExpandedStateMethod.invoke(windowView) ?: return false
        val data = targets.getCurrentIslandDataMethod.invoke(expandedContentView) ?: return false
        return isFlashlightIsland(targets, data)
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
    }

    private companion object {
        const val PLUGIN_PKG = "miui.systemui.plugin"
        const val DEFAULT_TOUCH_SLOP_PX = 24f
    }
}

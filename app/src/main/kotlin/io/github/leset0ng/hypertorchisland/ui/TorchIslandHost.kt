package io.github.leset0ng.hypertorchisland.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import java.util.Collections
import java.util.WeakHashMap

internal object TorchIslandSpecs {
    const val ISLAND_WIDTH_DP = 216f
    const val ISLAND_HEIGHT_DP = 292f

    private const val PLUGIN_PKG = "miui.systemui.plugin"
    private const val ISLAND_RADIUS_RES = "island_radius"
    private const val ISLAND_RADIUS_FALLBACK_DP = 30f

    @Volatile
    private var cachedCornerRadiusPx = 0f

    // DynamicIslandBackgroundView paints the island outline from a shape drawable whose corner radius is
    // @dimen/island_radius (30dp). Clipping the content to a larger radius lets that 12% white stroke show
    // through at the corners, so the content has to reuse the exact same radius instead.
    fun cornerRadiusPx(context: Context): Float {
        cachedCornerRadiusPx.takeIf { it > 0f }?.let { return it }
        val resolved = runCatching {
            val id = context.resources.getIdentifier(ISLAND_RADIUS_RES, "dimen", PLUGIN_PKG)
            if (id == 0) return@runCatching 0f
            context.resources.getDimension(id)
        }.getOrDefault(0f)
        val radius = if (resolved > 0f) resolved else dpF(context, ISLAND_RADIUS_FALLBACK_DP)
        cachedCornerRadiusPx = radius
        return radius
    }

    const val SEEKBAR_WIDTH_DP = 7f
    const val SEEKBAR_HEIGHT_DP = 108f
    const val SEEKBAR_MARGIN_BOTTOM_DP = 22f

    const val TOUCH_VIEW_HEIGHT_DP = 184f
    const val TOUCH_VIEW_MARGIN_BOTTOM_DP = 8f
    const val TOUCH_VIEW_MARGIN_HORIZONTAL_DP = 18f

    fun dp(context: Context, dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics,
        ).toInt()
    }

    fun dpF(context: Context, dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics,
        )
    }
}

internal object TorchIslandRegistry {
    private val islandLayouts = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<View, Boolean>()),
    )

    fun registerLayout(layout: View) {
        islandLayouts.add(layout)
    }

    fun unregisterLayout(layout: View) {
        islandLayouts.remove(layout)
    }

    fun isIslandLayout(layout: View?): Boolean {
        return layout != null && islandLayouts.contains(layout)
    }
}

internal class PluginSafeContext(base: Context) : ContextWrapper(base) {
    private val safeInflater: LayoutInflater by lazy {
        LayoutInflater.from(base).cloneInContext(this)
    }

    override fun getApplicationContext(): Context = this

    override fun getSystemService(name: String): Any? {
        return if (LAYOUT_INFLATER_SERVICE == name) safeInflater else super.getSystemService(name)
    }
}

internal class TorchIslandHostView(
    pluginContext: Context,
    private val createFlashlightLayout: (Context) -> View,
    private val dismissFlashlightLayout: (Int) -> Unit,
    private val isExpandedState: (View) -> Boolean,
    private val onReleaseTouch: () -> Unit,
    private val onCollapsedWithLayout: (View) -> Unit,
    private val onExpandedLayout: (View) -> Unit,
    private val logError: (String, Throwable) -> Unit,
) : FrameLayout(pluginContext) {

    private val safePluginContext = PluginSafeContext(pluginContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val targetWidthPx = TorchIslandSpecs.dp(pluginContext, TorchIslandSpecs.ISLAND_WIDTH_DP)
    private val targetHeightPx = TorchIslandSpecs.dp(pluginContext, TorchIslandSpecs.ISLAND_HEIGHT_DP)
    private val cornerRadiusPx = TorchIslandSpecs.cornerRadiusPx(pluginContext)

    private var activeFlashlightLayout: View? = null
    private var collapseNotifiedForCurrentAttach = false
    private val pendingDetachRunnable = Runnable {
        if (!shouldStayActive()) {
            detachFlashlightLayout()
        }
    }

    init {
        layoutParams = LayoutParams(targetWidthPx, targetHeightPx, Gravity.CENTER_HORIZONTAL)
        setBackgroundColor(Color.BLACK)
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(targetWidthPx, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(targetHeightPx, MeasureSpec.EXACTLY),
        )
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(ev)
        val action = ev.actionMasked
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            onReleaseTouch()
        }
        return handled
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncState()
    }

    override fun onDetachedFromWindow() {
        mainHandler.removeCallbacks(pendingDetachRunnable)
        detachFlashlightLayout()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncState()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncState()
    }

    fun syncState() {
        if (!isAttachedToWindow) return
        if (shouldStayActive()) {
            mainHandler.removeCallbacks(pendingDetachRunnable)
            ensureFlashlightLayoutAttached()
        } else {
            val layout = activeFlashlightLayout
            if (layout != null && !collapseNotifiedForCurrentAttach) {
                collapseNotifiedForCurrentAttach = true
                onCollapsedWithLayout(layout)
            }
            mainHandler.removeCallbacks(pendingDetachRunnable)
            mainHandler.postDelayed(pendingDetachRunnable, DETACH_DELAY_MS)
        }
    }

    fun releaseNow() {
        mainHandler.removeCallbacks(pendingDetachRunnable)
        detachFlashlightLayout()
    }

    private fun shouldStayActive(): Boolean {
        return isAttachedToWindow && isShown && isExpandedState(this)
    }

    private fun ensureFlashlightLayoutAttached() {
        val current = activeFlashlightLayout
        if (current != null && current.parent === this) return
        detachFlashlightLayout()
        try {
            val layout = createFlashlightLayout(safePluginContext)
            TorchIslandRegistry.registerLayout(layout)
            layout.layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(layout)
            activeFlashlightLayout = layout
            collapseNotifiedForCurrentAttach = false
            layout.post { onExpandedLayout(layout) }
        } catch (t: Throwable) {
            logError("Failed to attach native MiFlashlightLayout", t)
        }
    }

    private fun detachFlashlightLayout() {
        val layout = activeFlashlightLayout ?: return
        if (!collapseNotifiedForCurrentAttach) {
            collapseNotifiedForCurrentAttach = true
            onCollapsedWithLayout(layout)
        }
        activeFlashlightLayout = null
        val hash = layout.hashCode()
        TorchIslandRegistry.unregisterLayout(layout)
        runCatching {
            removeView(layout)
        }
        runCatching {
            dismissFlashlightLayout(hash)
        }.onFailure { t ->
            logError("Failed to dismiss MiFlashlightLayout($hash)", t)
        }
    }

    private companion object {
        const val DETACH_DELAY_MS = 350L
    }
}

internal class TorchIslandFakeView(
    pluginContext: Context,
) : FrameLayout(pluginContext) {

    private val targetWidthPx = TorchIslandSpecs.dp(pluginContext, TorchIslandSpecs.ISLAND_WIDTH_DP)
    private val targetHeightPx = TorchIslandSpecs.dp(pluginContext, TorchIslandSpecs.ISLAND_HEIGHT_DP)
    private val cornerRadiusPx = TorchIslandSpecs.cornerRadiusPx(pluginContext)

    init {
        layoutParams = LayoutParams(targetWidthPx, targetHeightPx, Gravity.CENTER_HORIZONTAL)
        setBackgroundColor(Color.BLACK)
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
            }
        }

        val barWidth = TorchIslandSpecs.dp(pluginContext, TorchIslandSpecs.SEEKBAR_WIDTH_DP)
        val barHeight = TorchIslandSpecs.dp(pluginContext, TorchIslandSpecs.SEEKBAR_HEIGHT_DP)
        val barMarginBottom = TorchIslandSpecs.dp(pluginContext, TorchIslandSpecs.SEEKBAR_MARGIN_BOTTOM_DP)
        val trackPlaceholder = View(pluginContext).apply {
            val bgResId = pluginContext.resources.getIdentifier(
                "mi_seekbar_bg",
                "drawable",
                "miui.systemui.plugin",
            )
            if (bgResId != 0) {
                setBackgroundResource(bgResId)
            } else {
                background = GradientDrawable().apply {
                    setColor(0x2EFFFFFF)
                    cornerRadius = barWidth / 2f
                }
            }
        }
        addView(
            trackPlaceholder,
            LayoutParams(barWidth, barHeight, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = barMarginBottom
            },
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(targetWidthPx, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(targetHeightPx, MeasureSpec.EXACTLY),
        )
    }
}

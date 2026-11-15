package io.github.leset0ng.hypertorchisland.hook

import android.content.Context
import android.view.MotionEvent
import android.view.View
import java.lang.reflect.Field
import java.lang.reflect.Method

internal data class SystemUiTargets(
    val pluginControllerClass: Class<*>,
    val onPluginLoadedMethod: Method,
    val getContentMethod: Method?,
    val currentPluginField: Field?,
)

internal data class PluginTargets(
    val pluginClassLoader: ClassLoader,
    val getPluginContextMethod: Method,
    val pluginComponentInitializerClass: Class<*>,
    val getPluginComponentMethod: Method,
    val miFlashlightManagerClass: Class<*>,
    val managerMaxStrengthField: Field,
    val getMiFlashlightLayoutMethod: Method,
    val dismissMethod: Method,
    val hideFlashlightMethod: Method,
    val miFlashlightLayoutClass: Class<*>,
    val disableStatusBarMethod: Method,
    val enableStatusBarMethod: Method,
    val miFlashlightControllerClass: Class<*>,
    val controllerContextField: Field,
    val controllerLayoutField: Field,
    val controllerManagerField: Field,
    val controllerStartMethod: Method,
    val controllerStopMethod: Method,
    val controllerSetSeekbarMethod: Method,
    val controllerSetSwitchLottieMethod: Method,
    val controllerSetSwitchStatusMethod: Method,
    val controllerGetSeekbarContainerMethod: Method,
    val controllerGetSeekbarTouchViewMethod: Method,
    val controllerGetSeekbarBgMethod: Method,
    val controllerGetSeekbarMethod: Method,
    val controllerGetSwitchLottieMethod: Method,
    val controllerGetBottomDrawUpMethod: Method,
    val controllerGetSeekBarTouchLengthMethod: Method?,
    val toggleChangeReceiverClass: Class<*>,
    val toggleCompanionField: Field,
    val saveFlashlightShowStatusMethod: Method,
    val showNotificationMethod: Method,
    val closeNotificationMethod: Method,
    val windowViewControllerClass: Class<*>,
    val addDynamicIslandViewMethod: Method,
    val updateDynamicIslandViewMethod: Method,
    val dynamicIslandDataClass: Class<*>,
    val dataGetKeyMethod: Method,
    val dataGetViewMethod: Method,
    val dataSetViewMethod: Method,
    val dataGetFakeViewMethod: Method,
    val dataSetFakeViewMethod: Method,
    val dataGetExtrasMethod: Method,
    val baseContentViewClass: Class<*>,
    val updateExpandedSizeMethod: Method,
    val getCurrentIslandDataMethod: Method,
    val getStateMethod: Method,
    val setStateMethod: Method,
    val getLastStateMethod: Method,
    val getExpandedViewMethod: Method,
    val setExpandedViewWidthMethod: Method,
    val setExpandedViewHeightMethod: Method,
    val setExpandedViewMarginHorizontalMethod: Method,
    val updateViewLayoutParamsMethod: Method,
    val contentViewClass: Class<*>,
    val onIslandClickMethod: Method,
    val contentFakeViewClass: Class<*>,
    val stateExpandedClass: Class<*>,
    val touchInteractorClass: Class<*>,
    val onInterceptTouchEventMethod: Method,
    val touchInitXField: Field,
    val touchInitYField: Field,
    val touchDirectionField: Field,
    val touchInterceptedField: Field,
    val touchDownInExpandedField: Field,
    val touchConstantsField: Field,
    val touchWindowViewField: Field,
    val touchConstantsGetTouchSlopMethod: Method,
    val directionUp: Any,
    val directionDown: Any,
    val windowViewClass: Class<*>,
    val getCurrentExpandedStateMethod: Method,
    val islandPropertyUpdaterClass: Class<*>,
    val containerClipRadiusMethod: Method,
    val propertyUpdaterViewField: Field,
    val dynamicIslandUtilsClass: Class<*>,
    val dynamicIslandUtilsInstance: Any,
    val getScreenWidthOldMethod: Method,
)

internal object PluginTargetResolver {
    fun resolveSystemUi(classLoader: ClassLoader): SystemUiTargets {
        val controllerClass = Class.forName(
            "com.android.systemui.statusbar.notification.DynamicIslandPluginController",
            false,
            classLoader,
        )
        val pluginClass = Class.forName("com.android.systemui.plugins.Plugin", false, classLoader)
        val lifecycleMgrClass = Class.forName(
            "com.android.systemui.plugins.PluginLifecycleManager",
            false,
            classLoader,
        )
        val onPluginLoaded = controllerClass.getDeclaredMethod(
            "onPluginLoaded",
            pluginClass,
            Context::class.java,
            lifecycleMgrClass,
        )
        val getContent = controllerClass.declaredMethods.firstOrNull {
            it.name.startsWith("getContent") && it.parameterCount == 0
        }
        val currentPluginField = runCatching {
            controllerClass.getDeclaredField("_currentPlugin").apply { isAccessible = true }
        }.getOrNull() ?: runCatching {
            controllerClass.getDeclaredField("currentPlugin").apply { isAccessible = true }
        }.getOrNull()
        return SystemUiTargets(
            pluginControllerClass = controllerClass,
            onPluginLoadedMethod = onPluginLoaded,
            getContentMethod = getContent,
            currentPluginField = currentPluginField,
        )
    }

    fun resolvePlugin(pluginLoader: ClassLoader): PluginTargets {
        val pluginBaseClass = Class.forName("miui.systemui.plugins.PluginBase", false, pluginLoader)
        val getPluginContextMethod = pluginBaseClass.getDeclaredMethod("getPluginContext")

        val pluginCompInitClass = Class.forName(
            "miui.systemui.dagger.PluginComponentInitializer",
            false,
            pluginLoader,
        )
        val getPluginComponentMethod = pluginCompInitClass.getDeclaredMethod("getPluginComponent")

        val managerClass = Class.forName("miui.systemui.flashlight.MiFlashlightManager", false, pluginLoader)
        val managerMaxStrengthField = managerClass.getDeclaredField("maxStrength").apply { isAccessible = true }
        val getMiFlashlightLayoutMethod = managerClass.getDeclaredMethod(
            "getMiFlashlightLayout",
            Context::class.java,
            Boolean::class.javaPrimitiveType,
        )
        val dismissMethod = managerClass.getDeclaredMethod("dismiss", Int::class.javaPrimitiveType)
        val hideFlashlightMethod = managerClass.getDeclaredMethod("hideFlashlight")

        val layoutClass = Class.forName("miui.systemui.flashlight.view.MiFlashlightLayout", false, pluginLoader)
        val disableStatusBarMethod = layoutClass.getDeclaredMethod("disableStatusBar")
        val enableStatusBarMethod = layoutClass.getDeclaredMethod("enableStatusBar")

        val controllerClass = Class.forName("miui.systemui.flashlight.MiFlashlightController", false, pluginLoader)
        val controllerContextField = controllerClass.getDeclaredField("context").apply { isAccessible = true }
        val controllerLayoutField = controllerClass.getDeclaredField("miFlashlightLayout").apply { isAccessible = true }
        val controllerManagerField = controllerClass.getDeclaredField("miFlashlightManager").apply { isAccessible = true }
        val controllerStartMethod = controllerClass.getDeclaredMethod("onStart")
        val controllerStopMethod = controllerClass.getDeclaredMethod("onStop")
        val controllerSetSeekbarMethod = controllerClass.getDeclaredMethod("setSeekbar", Boolean::class.javaPrimitiveType)
        val controllerSetSwitchLottieMethod = baseSetSwitchLottie(controllerClass)
        val controllerSetSwitchStatusMethod = controllerClass.getDeclaredMethod(
            "setSwitchStatus",
            Boolean::class.javaObjectType,
        ).apply { isAccessible = true }
        val controllerGetSeekbarContainerMethod = controllerClass.getDeclaredMethod("getSeekbarContainer")
            .apply { isAccessible = true }
        val controllerGetSeekbarTouchViewMethod = controllerClass.getDeclaredMethod("getSeekbarTouchView")
            .apply { isAccessible = true }
        val controllerGetSeekbarBgMethod = controllerClass.getDeclaredMethod("getSeekBarBg")
            .apply { isAccessible = true }
        val controllerGetSeekbarMethod = controllerClass.getDeclaredMethod("getSeekbar")
            .apply { isAccessible = true }
        val controllerGetSwitchLottieMethod = controllerClass.getDeclaredMethod("getSwitchLottieView")
            .apply { isAccessible = true }
        val controllerGetBottomDrawUpMethod = controllerClass.getDeclaredMethod("getBottomDrawUpView")
            .apply { isAccessible = true }
        val controllerGetSeekBarTouchLengthMethod = runCatching {
            controllerClass.getDeclaredMethod("getSeekBarTouchLength").apply { isAccessible = true }
        }.getOrNull()

        val toggleReceiverClass = Class.forName("miui.systemui.flashlight.ToggleChangeReceiver", false, pluginLoader)
        val toggleCompanionField = toggleReceiverClass.getDeclaredField("Companion").apply { isAccessible = true }
        val toggleCompanionClass = Class.forName(
            "miui.systemui.flashlight.ToggleChangeReceiver\$Companion",
            false,
            pluginLoader,
        )
        val saveFlashlightShowStatusMethod = toggleCompanionClass.getDeclaredMethod(
            "saveFlashlightShowStatus",
            Context::class.java,
            Boolean::class.javaPrimitiveType,
        )

        val notifMgrClass = Class.forName("miui.systemui.flashlight.NotificationManager", false, pluginLoader)
        val showNotificationMethod = notifMgrClass.getDeclaredMethod("showNotification", Context::class.java)
        val closeNotificationMethod = notifMgrClass.getDeclaredMethod("closeNotification", Context::class.java)

        val windowViewControllerClass = Class.forName(
            "miui.systemui.dynamicisland.window.DynamicIslandWindowViewController",
            false,
            pluginLoader,
        )
        val dataClass = Class.forName(
            "com.android.systemui.plugins.miui.dynamicisland.DynamicIslandData",
            false,
            pluginLoader,
        )
        val addDynamicIslandViewMethod = windowViewControllerClass.getDeclaredMethod(
            "addDynamicIslandView",
            dataClass,
            Boolean::class.javaPrimitiveType,
        )
        val updateDynamicIslandViewMethod = windowViewControllerClass.getDeclaredMethod(
            "updateDynamicIslandView",
            dataClass,
            Boolean::class.javaPrimitiveType,
        )

        val dataGetKeyMethod = dataClass.getMethod("getKey")
        val dataGetViewMethod = dataClass.getMethod("getView")
        val dataSetViewMethod = dataClass.getMethod("setView", View::class.java)
        val dataGetFakeViewMethod = dataClass.getMethod("getFakeView")
        val dataSetFakeViewMethod = dataClass.getMethod("setFakeView", View::class.java)
        val dataGetExtrasMethod = dataClass.getMethod("getExtras")

        val baseContentViewClass = Class.forName(
            "miui.systemui.dynamicisland.window.content.DynamicIslandBaseContentView",
            false,
            pluginLoader,
        )
        val updateExpandedSizeMethod = baseContentViewClass.getDeclaredMethod(
            "updateExpandedSize",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            dataClass,
        )
        val getCurrentIslandDataMethod = baseContentViewClass.getMethod("getCurrentIslandData")
        val stateClass = Class.forName("miui.systemui.dynamicisland.event.DynamicIslandState", false, pluginLoader)
        val getStateMethod = baseContentViewClass.getMethod("getState")
        val setStateMethod = baseContentViewClass.getDeclaredMethod("setState", stateClass)
        val getLastStateMethod = baseContentViewClass.getMethod("getLastState")
        val getExpandedViewMethod = baseContentViewClass.getMethod("getExpandedView")
        val setExpandedViewWidthMethod = baseContentViewClass.getDeclaredMethod(
            "setExpandedViewWidth",
            Int::class.javaPrimitiveType,
        )
        val setExpandedViewHeightMethod = baseContentViewClass.getDeclaredMethod(
            "setExpandedViewHeight",
            Int::class.javaPrimitiveType,
        )
        val setExpandedViewMarginHorizontalMethod = baseContentViewClass.getDeclaredMethod(
            "setExpandedViewMarginHorizontal",
            Int::class.javaPrimitiveType,
        )
        val updateViewLayoutParamsMethod = baseContentViewClass.getDeclaredMethod(
            "updateViewLayoutParams",
            View::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        val contentViewClass = Class.forName(
            "miui.systemui.dynamicisland.window.content.DynamicIslandContentView",
            false,
            pluginLoader,
        )
        val onIslandClickMethod = contentViewClass.getDeclaredMethod("onIslandClick")

        val contentFakeViewClass = Class.forName(
            "miui.systemui.dynamicisland.window.content.DynamicIslandContentFakeView",
            false,
            pluginLoader,
        )
        val stateExpandedClass = Class.forName(
            "miui.systemui.dynamicisland.event.DynamicIslandState\$Expanded",
            false,
            pluginLoader,
        )

        val touchInteractorClass = Class.forName(
            "miui.systemui.dynamicisland.touch.domain.interactor.DynamicIslandTouchInteractor",
            false,
            pluginLoader,
        )
        val onInterceptTouchEventMethod = touchInteractorClass.getDeclaredMethod(
            "onInterceptTouchEvent",
            MotionEvent::class.java,
            String::class.java,
        )
        val touchInitXField = touchInteractorClass.getDeclaredField("initX").apply { isAccessible = true }
        val touchInitYField = touchInteractorClass.getDeclaredField("initY").apply { isAccessible = true }
        val touchDirectionField = touchInteractorClass.getDeclaredField("direction").apply { isAccessible = true }
        val touchInterceptedField = touchInteractorClass.getDeclaredField("intercepted").apply { isAccessible = true }
        val touchDownInExpandedField = touchInteractorClass.getDeclaredField("downInExpanded").apply { isAccessible = true }
        val touchConstantsField = touchInteractorClass.getDeclaredField("touchConstants").apply { isAccessible = true }
        val touchWindowViewField = touchInteractorClass.getDeclaredField("windowView").apply { isAccessible = true }

        val touchConstantsClass = Class.forName(
            "miui.systemui.dynamicisland.touch.data.repository.DynamicIslandTouchConstantsRepository",
            false,
            pluginLoader,
        )
        val touchConstantsGetTouchSlopMethod = touchConstantsClass.getMethod("getTouchSlop")

        val directionClass = Class.forName("miui.systemui.dynamicisland.touch.Direction", false, pluginLoader)
        val enumConstants = directionClass.enumConstants ?: error("Direction enum empty")
        val directionUp = enumConstants.first { (it as Enum<*>).name == "UP" }
        val directionDown = enumConstants.first { (it as Enum<*>).name == "DOWN" }

        val windowViewClass = Class.forName(
            "miui.systemui.dynamicisland.window.DynamicIslandWindowView",
            false,
            pluginLoader,
        )
        val getCurrentExpandedStateMethod = windowViewClass.getMethod("getCurrentExpandedState")

        val propertyUpdaterClass = Class.forName(
            "miui.systemui.dynamicisland.anim.ui.animator.IslandPropertyUpdater",
            false,
            pluginLoader,
        )
        val containerClipRadiusMethod = propertyUpdaterClass.getDeclaredMethod(
            "containerClipRadius",
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val propertyUpdaterViewField = propertyUpdaterClass.getDeclaredField("view").apply { isAccessible = true }

        val utilsClass = Class.forName("miui.systemui.dynamicisland.DynamicIslandUtils", false, pluginLoader)
        val utilsInstance = utilsClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)!!
        val getScreenWidthOldMethod = utilsClass.getMethod("getScreenWidthOld", Context::class.java)

        return PluginTargets(
            pluginClassLoader = pluginLoader,
            getPluginContextMethod = getPluginContextMethod,
            pluginComponentInitializerClass = pluginCompInitClass,
            getPluginComponentMethod = getPluginComponentMethod,
            miFlashlightManagerClass = managerClass,
            managerMaxStrengthField = managerMaxStrengthField,
            getMiFlashlightLayoutMethod = getMiFlashlightLayoutMethod,
            dismissMethod = dismissMethod,
            hideFlashlightMethod = hideFlashlightMethod,
            miFlashlightLayoutClass = layoutClass,
            disableStatusBarMethod = disableStatusBarMethod,
            enableStatusBarMethod = enableStatusBarMethod,
            miFlashlightControllerClass = controllerClass,
            controllerContextField = controllerContextField,
            controllerLayoutField = controllerLayoutField,
            controllerManagerField = controllerManagerField,
            controllerStartMethod = controllerStartMethod,
            controllerStopMethod = controllerStopMethod,
            controllerSetSeekbarMethod = controllerSetSeekbarMethod,
            controllerSetSwitchLottieMethod = controllerSetSwitchLottieMethod,
            controllerSetSwitchStatusMethod = controllerSetSwitchStatusMethod,
            controllerGetSeekbarContainerMethod = controllerGetSeekbarContainerMethod,
            controllerGetSeekbarTouchViewMethod = controllerGetSeekbarTouchViewMethod,
            controllerGetSeekbarBgMethod = controllerGetSeekbarBgMethod,
            controllerGetSeekbarMethod = controllerGetSeekbarMethod,
            controllerGetSwitchLottieMethod = controllerGetSwitchLottieMethod,
            controllerGetBottomDrawUpMethod = controllerGetBottomDrawUpMethod,
            controllerGetSeekBarTouchLengthMethod = controllerGetSeekBarTouchLengthMethod,
            toggleChangeReceiverClass = toggleReceiverClass,
            toggleCompanionField = toggleCompanionField,
            saveFlashlightShowStatusMethod = saveFlashlightShowStatusMethod,
            showNotificationMethod = showNotificationMethod,
            closeNotificationMethod = closeNotificationMethod,
            windowViewControllerClass = windowViewControllerClass,
            addDynamicIslandViewMethod = addDynamicIslandViewMethod,
            updateDynamicIslandViewMethod = updateDynamicIslandViewMethod,
            dynamicIslandDataClass = dataClass,
            dataGetKeyMethod = dataGetKeyMethod,
            dataGetViewMethod = dataGetViewMethod,
            dataSetViewMethod = dataSetViewMethod,
            dataGetFakeViewMethod = dataGetFakeViewMethod,
            dataSetFakeViewMethod = dataSetFakeViewMethod,
            dataGetExtrasMethod = dataGetExtrasMethod,
            baseContentViewClass = baseContentViewClass,
            updateExpandedSizeMethod = updateExpandedSizeMethod,
            getCurrentIslandDataMethod = getCurrentIslandDataMethod,
            getStateMethod = getStateMethod,
            setStateMethod = setStateMethod,
            getLastStateMethod = getLastStateMethod,
            getExpandedViewMethod = getExpandedViewMethod,
            setExpandedViewWidthMethod = setExpandedViewWidthMethod,
            setExpandedViewHeightMethod = setExpandedViewHeightMethod,
            setExpandedViewMarginHorizontalMethod = setExpandedViewMarginHorizontalMethod,
            updateViewLayoutParamsMethod = updateViewLayoutParamsMethod,
            contentViewClass = contentViewClass,
            onIslandClickMethod = onIslandClickMethod,
            contentFakeViewClass = contentFakeViewClass,
            stateExpandedClass = stateExpandedClass,
            touchInteractorClass = touchInteractorClass,
            onInterceptTouchEventMethod = onInterceptTouchEventMethod,
            touchInitXField = touchInitXField,
            touchInitYField = touchInitYField,
            touchDirectionField = touchDirectionField,
            touchInterceptedField = touchInterceptedField,
            touchDownInExpandedField = touchDownInExpandedField,
            touchConstantsField = touchConstantsField,
            touchWindowViewField = touchWindowViewField,
            touchConstantsGetTouchSlopMethod = touchConstantsGetTouchSlopMethod,
            directionUp = directionUp,
            directionDown = directionDown,
            windowViewClass = windowViewClass,
            getCurrentExpandedStateMethod = getCurrentExpandedStateMethod,
            islandPropertyUpdaterClass = propertyUpdaterClass,
            containerClipRadiusMethod = containerClipRadiusMethod,
            propertyUpdaterViewField = propertyUpdaterViewField,
            dynamicIslandUtilsClass = utilsClass,
            dynamicIslandUtilsInstance = utilsInstance,
            getScreenWidthOldMethod = getScreenWidthOldMethod,
        )
    }

    private fun baseSetSwitchLottie(controllerClass: Class<*>): Method {
        return controllerClass.getDeclaredMethod("setSwitchLottieView", Boolean::class.javaPrimitiveType)
    }

    fun resolveFlashlightManager(targets: PluginTargets): Any? {
        val component = targets.getPluginComponentMethod.invoke(null) ?: return null
        val directField = runCatching {
            component.javaClass.getDeclaredField("miFlashlightManagerProvider").apply { isAccessible = true }
        }.getOrNull()
        if (directField != null) {
            val provider = directField.get(component)
            if (provider != null) {
                val value = invokeProviderGet(provider)
                if (targets.miFlashlightManagerClass.isInstance(value)) return value
            }
        }
        // Fallback scan in case field name changes across minor plugin updates.
        for (field in component.javaClass.declaredFields) {
            field.isAccessible = true
            val obj = runCatching { field.get(component) }.getOrNull() ?: continue
            if (targets.miFlashlightManagerClass.isInstance(obj)) return obj
            val provided = runCatching { invokeProviderGet(obj) }.getOrNull()
            if (targets.miFlashlightManagerClass.isInstance(provided)) return provided
        }
        return null
    }

    private fun invokeProviderGet(provider: Any): Any? {
        val getMethod = provider.javaClass.methods.firstOrNull {
            it.name == "get" && it.parameterCount == 0
        } ?: return null
        return getMethod.invoke(provider)
    }
}

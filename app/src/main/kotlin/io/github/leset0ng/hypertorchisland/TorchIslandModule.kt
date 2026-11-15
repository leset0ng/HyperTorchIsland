package io.github.leset0ng.hypertorchisland

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.leset0ng.hypertorchisland.hook.ReloadableHooks
import io.github.leset0ng.hypertorchisland.hook.TorchIslandHooks
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.concurrent.FutureTask

class TorchIslandModule : XposedModule() {
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val hooks = ReloadableHooks(this)
    private val torchHooks = TorchIslandHooks(
        hooks = hooks,
        logInfo = { msg -> log(Log.INFO, TAG, msg) },
        logError = { msg, err -> log(Log.ERROR, TAG, "$msg: ${Log.getStackTraceString(err)}") },
    )

    private var processName: String = ""
    private var hostLoader: ClassLoader? = null
    private var pendingReloadState: Map<String, Any>? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        if (!param.isSystemServer) {
            log(Log.INFO, TAG, "loaded API $apiVersion in $processName")
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != SYSTEMUI_PKG || processName != SYSTEMUI_PKG) return
        val loader = param.classLoader
        hostLoader = loader
        try {
            torchHooks.installSystemUi(loader)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to install SystemUI hooks in $processName", t)
        }
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        if (!hooks.pause()) {
            log(Log.WARN, TAG, "Hot reload deferred; hook invocation in flight")
            return false
        }
        return try {
            val state = onMain {
                val base = HashMap(pendingReloadState ?: emptyMap())
                hostLoader?.let { base[KEY_HOST_LOADER] = it }
                torchHooks.pluginClassLoader?.let { base[KEY_PLUGIN_LOADER] = it }
                torchHooks.pluginContext?.let { base[KEY_PLUGIN_CONTEXT] = it }
                base[KEY_PROCESS_NAME] = processName
                torchHooks.releaseForReload()
                mainHandler.removeCallbacksAndMessages(null)
                base
            }
            param.setSavedInstanceState(state)
            log(Log.INFO, TAG, "old generation prepared for hot reload in $processName")
            true
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Hot reload aborted; resuming current generation", t)
            hooks.resume()
            false
        }
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        val state = HashMap<String, Any>()
        (param.savedInstanceState as? Map<*, *>)?.forEach { (k, v) ->
            if (k is String && v != null) state[k] = v
        }
        pendingReloadState = state
        hooks.adopt(param.oldHookHandles)
        try {
            processName = (state[KEY_PROCESS_NAME] as? String) ?: param.processName
            val loader = state[KEY_HOST_LOADER] as? ClassLoader ?: return
            val pluginLoader = state[KEY_PLUGIN_LOADER] as? ClassLoader
            val pluginCtx = state[KEY_PLUGIN_CONTEXT] as? Context
            hostLoader = loader

            onMain {
                torchHooks.installSystemUi(
                    systemUiClassLoader = loader,
                    restoredPluginLoader = pluginLoader,
                    restoredPluginContext = pluginCtx,
                )
            }
            pendingReloadState = null
            log(Log.INFO, TAG, "hot reload complete in $processName")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Hot reload restoration failed in $processName", t)
        } finally {
            hooks.finishAdoption()
        }
    }

    private fun <T> onMain(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val task = FutureTask(action)
        check(mainHandler.post(task)) { "Main looper unavailable" }
        return task.get()
    }

    private companion object {
        const val TAG = "HyperTorchIsland"
        const val SYSTEMUI_PKG = "com.android.systemui"
        const val KEY_HOST_LOADER = "hostLoader"
        const val KEY_PLUGIN_LOADER = "pluginLoader"
        const val KEY_PLUGIN_CONTEXT = "pluginContext"
        const val KEY_PROCESS_NAME = "processName"
    }
}

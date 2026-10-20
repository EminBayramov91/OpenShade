package io.github.eminbayramov.openshade.shell

import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import io.github.eminbayramov.openshade.BuildConfig
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.util.concurrent.Executors

/**
 * Optional privileged access provided by Shizuku. Calls are made from this process and
 * forwarded by Shizuku with the shell identity, so no extra service process is started.
 * When Shizuku is not installed, not running or not granted, [isReady] is false and
 * callers fall back to opening system settings panels.
 */
object ShizukuShell {

    enum class Status { NOT_RUNNING, NO_PERMISSION, READY }

    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Identifies our disable request to the status bar service. It is tied to this process,
     * so the stock quick settings come back on their own if OpenShade stops or crashes.
     */
    private val disableToken: IBinder = Binder()

    val status: Status
        get() = when {
            !Shizuku.pingBinder() -> Status.NOT_RUNNING
            Shizuku.isPreV11() -> Status.NOT_RUNNING
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Status.NO_PERMISSION
            else -> Status.READY
        }

    val isReady: Boolean get() = status == Status.READY

    /** Runs [command] with `sh -c` in the background; [onDone] is called on that background thread. */
    fun run(command: String, onDone: (success: Boolean) -> Unit = {}) {
        if (!isReady) return onDone(false)
        executor.execute {
            val ok = runCatching {
                // Public replacement for newProcess is a user service, which fails to start on
                // some Android 14 builds; the method itself is stable since Shizuku 11.
                val process = newProcess.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
                process.waitFor() == 0
            }
                .onFailure { Log.w(TAG, "exec failed: $command", it) }
                .getOrDefault(false)
            onDone(ok)
        }
    }

    /**
     * Sets or clears StatusBarManager.DISABLE2_QUICK_SETTINGS. The XOS control center checks
     * this flag before handling any touch, so with it set the stock panel never opens, while
     * the stock notification shade keeps working.
     */
    fun setStockQuickSettingsDisabled(disabled: Boolean, onDone: (success: Boolean) -> Unit = {}) {
        if (!isReady) {
            Log.i(TAG, "Stock quick settings left as is, Shizuku is $status")
            return onDone(false)
        }
        executor.execute {
            val ok = runCatching {
                statusBarService().javaClass
                    .getMethod("disable2", Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
                    .invoke(statusBarService(), if (disabled) DISABLE2_QUICK_SETTINGS else 0, disableToken, BuildConfig.APPLICATION_ID)
                Log.i(TAG, "Stock quick settings " + if (disabled) "disabled" else "enabled")
                true
            }
                .onFailure { Log.w(TAG, "disable2 failed", it) }
                .getOrDefault(false)
            onDone(ok)
        }
    }

    private val newProcess by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).apply { isAccessible = true }
    }

    private var statusBar: Any? = null

    /** IStatusBarService whose transactions run as shell, which holds STATUS_BAR. */
    private fun statusBarService(): Any = statusBar ?: run {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) HiddenApiBypass.addHiddenApiExemptions("L")
        val binder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("statusbar"))
        Class.forName("com.android.internal.statusbar.IStatusBarService\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!
            .also { statusBar = it }
    }

    private const val DISABLE2_QUICK_SETTINGS = 1
    private const val TAG = "OpenShade"
}

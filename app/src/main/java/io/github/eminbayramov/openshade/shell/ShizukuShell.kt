package io.github.eminbayramov.openshade.shell

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import io.github.eminbayramov.openshade.BuildConfig
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/**
 * Optional privileged shell provided by Shizuku. When it is not installed, not running or
 * not granted, [isReady] is false and callers fall back to opening system settings panels.
 */
object ShizukuShell {

    enum class Status { NOT_RUNNING, NO_PERMISSION, READY }

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var service: IShellService? = null
    private var binding = false

    private val args = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, ShellService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("shell")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.takeIf { it.pingBinder() }?.let(IShellService.Stub::asInterface)
            binding = false
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
        }
    }

    val status: Status
        get() = when {
            !Shizuku.pingBinder() -> Status.NOT_RUNNING
            Shizuku.isPreV11() -> Status.NOT_RUNNING
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Status.NO_PERMISSION
            else -> Status.READY
        }

    val isReady: Boolean get() = status == Status.READY

    /** Binds the shell service ahead of time so the first tile tap is not delayed. */
    fun warmUp() {
        if (!isReady || service != null || binding) return
        binding = true
        runCatching { Shizuku.bindUserService(args, connection) }
            .onFailure { binding = false; Log.w(TAG, "bindUserService failed", it) }
    }

    /** Runs [command] in the background; [onDone] is called on that background thread. */
    fun run(command: String, onDone: (success: Boolean) -> Unit = {}) {
        warmUp()
        executor.execute {
            // The first call may arrive before the service has connected.
            var shell = service
            var waited = 0
            while (shell == null && waited < 2000) {
                Thread.sleep(50); waited += 50; shell = service
            }
            val ok = runCatching { shell?.exec(command) == 0 }
                .onFailure { Log.w(TAG, "exec failed: $command", it) }
                .getOrDefault(false)
            onDone(ok)
        }
    }

    private const val TAG = "OpenShade"
}

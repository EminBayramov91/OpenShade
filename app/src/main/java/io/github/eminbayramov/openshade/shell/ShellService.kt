package io.github.eminbayramov.openshade.shell

import android.content.Context
import kotlin.system.exitProcess

/**
 * Started by Shizuku in a separate process that runs with the shell uid, the same
 * privileges ADB has. That is what allows switching Wi-Fi, Bluetooth, mobile data and
 * airplane mode, which regular apps are not allowed to do.
 */
class ShellService() : IShellService.Stub() {

    // Shizuku passes a context on newer versions; it is not needed here.
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    override fun exec(command: String): Int =
        Runtime.getRuntime().exec(arrayOf("sh", "-c", command)).waitFor()

    override fun destroy() {
        exitProcess(0)
    }
}

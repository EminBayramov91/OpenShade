package io.github.eminbayramov.openshade.tiles

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AirplanemodeActive
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.eminbayramov.openshade.shell.ShizukuShell

data class Tile(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val active: Boolean,
    val subtitle: String,
)

/**
 * Reads and toggles the state behind each quick settings tile.
 *
 * Modern Android does not let regular apps switch Wi-Fi, Bluetooth, mobile data or
 * airplane mode. With Shizuku running those tiles toggle directly through a privileged
 * shell; without it they open the matching system panel.
 */
class TileController(private val context: Context) {

    private val resolver = context.contentResolver
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val camera = context.getSystemService(CameraManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    private val flashCameraId: String? = camera.cameraIdList.firstOrNull { id ->
        camera.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    }

    @Volatile
    private var torchOn = false

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == flashCameraId) torchOn = enabled
        }
    }

    init {
        camera.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper()))
    }

    fun release() {
        camera.unregisterTorchCallback(torchCallback)
    }

    fun snapshot(): List<Tile> {
        val wifiOn = wifi.isWifiEnabled
        // Read from global settings: BluetoothAdapter.isEnabled needs a runtime permission.
        val btOn = Settings.Global.getInt(resolver, Settings.Global.BLUETOOTH_ON, 0) != 0
        val dndOn = notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        val rotateOn = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
        val dataOn = Settings.Global.getInt(resolver, "mobile_data", 1) == 1
        val airplaneOn = Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

        return listOf(
            Tile(WIFI, "Internet", Icons.Rounded.Wifi, wifiOn, if (wifiOn) "Wi-Fi on" else "Off"),
            Tile(BLUETOOTH, "Bluetooth", Icons.Rounded.Bluetooth, btOn, onOff(btOn)),
            Tile(DATA, "Mobile data", Icons.Rounded.SignalCellularAlt, dataOn, onOff(dataOn)),
            Tile(FLASHLIGHT, "Flashlight", Icons.Rounded.FlashlightOn, torchOn, onOff(torchOn)),
            Tile(DND, "Do Not Disturb", Icons.Rounded.DoNotDisturbOn, dndOn, onOff(dndOn)),
            Tile(ROTATE, "Auto-rotate", Icons.Rounded.ScreenRotation, rotateOn, onOff(rotateOn)),
            Tile(AIRPLANE, "Airplane mode", Icons.Rounded.AirplanemodeActive, airplaneOn, onOff(airplaneOn)),
        )
    }

    /**
     * Toggles a tile. Returns an intent when the action has to be finished in system
     * settings (missing permission or a toggle apps are not allowed to perform);
     * the caller starts it.
     */
    fun toggle(id: String, onChanged: () -> Unit = {}): Intent? {
        privilegedCommand(id)?.let { command ->
            ShizukuShell.run(command) { onChanged() }
            return null
        }
        when (id) {
            WIFI -> return panelOr(Settings.Panel.ACTION_WIFI, Settings.ACTION_WIFI_SETTINGS)
            BLUETOOTH -> return Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            DATA -> return panelOr(Settings.Panel.ACTION_INTERNET_CONNECTIVITY, Settings.ACTION_WIRELESS_SETTINGS)
            AIRPLANE -> return Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            FLASHLIGHT -> {
                val cameraId = flashCameraId ?: return null
                camera.setTorchMode(cameraId, !torchOn)
            }
            DND -> {
                if (!notifications.isNotificationPolicyAccessGranted) {
                    return Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                }
                val on = notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
                notifications.setInterruptionFilter(
                    if (on) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY
                )
            }
            ROTATE -> {
                if (!Settings.System.canWrite(context)) return writeSettingsIntent()
                val on = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
                Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, if (on) 0 else 1)
            }
        }
        return null
    }

    /** Settings screen shown on long press. */
    fun detailsIntent(id: String): Intent = when (id) {
        WIFI -> Intent(Settings.ACTION_WIFI_SETTINGS)
        BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        DATA -> Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)
        DND -> Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS)
        ROTATE -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
        AIRPLANE -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
        else -> Intent(Settings.ACTION_SETTINGS)
    }

    /**
     * Shell command that flips the tile, when Shizuku is available. These are the same
     * commands Android's own `adb shell` tooling uses.
     */
    private fun privilegedCommand(id: String): String? {
        if (!ShizukuShell.isReady) return null
        fun verb(on: Boolean) = if (on) "disable" else "enable"
        return when (id) {
            WIFI -> "svc wifi ${verb(wifi.isWifiEnabled)}"
            BLUETOOTH -> {
                val v = verb(Settings.Global.getInt(resolver, Settings.Global.BLUETOOTH_ON, 0) != 0)
                // bluetooth_manager exists from Android 13; svc bluetooth covers older versions.
                "cmd bluetooth_manager $v || svc bluetooth $v"
            }
            DATA -> "svc data ${verb(Settings.Global.getInt(resolver, "mobile_data", 1) == 1)}"
            AIRPLANE -> "cmd connectivity airplane-mode ${verb(Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1)}"
            else -> null
        }
    }

    private fun onOff(on: Boolean) = if (on) "On" else "Off"

    /** Settings panels exist from Android 10; older versions get the full settings screen. */
    private fun panelOr(panel: String, fallback: String) =
        Intent(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) panel else fallback)

    private fun writeSettingsIntent() =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))

    companion object {
        const val WIFI = "wifi"
        const val BLUETOOTH = "bluetooth"
        const val DATA = "data"
        const val FLASHLIGHT = "flashlight"
        const val DND = "dnd"
        const val ROTATE = "rotate"
        const val AIRPLANE = "airplane"
    }
}

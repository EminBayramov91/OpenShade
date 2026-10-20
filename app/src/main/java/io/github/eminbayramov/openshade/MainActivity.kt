package io.github.eminbayramov.openshade

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import io.github.eminbayramov.openshade.shell.ShizukuShell
import rikka.shizuku.Shizuku
import kotlin.math.roundToInt
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import io.github.eminbayramov.openshade.ui.shadeColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/** Setup screen: walks the user through the permissions OpenShade needs. */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SetupScreen() }
    }

    @Composable
    private fun SetupScreen() {
        val settings = remember { AppSettings(this) }
        var colorSource by remember { mutableStateOf(settings.colorSource) }
        var opacity by remember { mutableFloatStateOf(settings.opacity) }
        var blur by remember { mutableStateOf(settings.blur) }
        // Bumped on every resume so the checklist reflects permissions granted in Settings.
        var resumeCount by remember { mutableIntStateOf(0) }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeCount++ }

        MaterialTheme(colorScheme = shadeColors(LocalContext.current, colorSource)) {
            val colors = MaterialTheme.colorScheme
            Column(
                Modifier
                    .fillMaxSize()
                    .background(colors.surface)
                    .verticalScroll(rememberScrollState())
                    .safeDrawingPadding()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                key(resumeCount) { Checklist() }
                Spacer(Modifier.height(8.dp))
                Appearance(
                    colorSource = colorSource,
                    onColorSource = { colorSource = it; settings.colorSource = it },
                    opacity = opacity,
                    onOpacity = { opacity = it; settings.opacity = it },
                    blur = blur,
                    onBlur = { blur = it; settings.blur = it },
                )
            }
        }
    }

    @Composable
    private fun Appearance(
        colorSource: AppSettings.ColorSource,
        onColorSource: (AppSettings.ColorSource) -> Unit,
        opacity: Float,
        onOpacity: (Float) -> Unit,
        blur: Boolean,
        onBlur: (Boolean) -> Unit,
    ) {
        val colors = MaterialTheme.colorScheme
        Text("Appearance", color = colors.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Card {
            Text("Colors", color = colors.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val options = listOf(
                    AppSettings.ColorSource.WALLPAPER to "Wallpaper",
                    AppSettings.ColorSource.SYSTEM to "System theme",
                )
                options.forEachIndexed { index, (source, label) ->
                    SegmentedButton(
                        selected = colorSource == source,
                        onClick = { onColorSource(source) },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    ) { Text(label) }
                }
            }
        }
        Card {
            Text(
                "Background opacity: ${(opacity * 100).roundToInt()}%",
                color = colors.onSurface,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
            )
            Slider(value = opacity, onValueChange = onOpacity, valueRange = AppSettings.MIN_OPACITY..1f)
        }
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Blur behind the panel", color = colors.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled
                    Text(
                        if (supported) "Frosted-glass look with a lower opacity." else "Not supported on this device.",
                        color = colors.onSurfaceVariant,
                        fontSize = 14.sp,
                    )
                }
                Switch(checked = blur, onCheckedChange = onBlur)
            }
        }
    }

    @Composable
    private fun Card(content: @Composable () -> Unit) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(20.dp),
        ) { content() }
    }

    @Composable
    private fun Checklist() {
        val colors = MaterialTheme.colorScheme
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("OpenShade", color = colors.onSurface, fontSize = 34.sp, fontWeight = FontWeight.Medium)
            Text(
                "A Material You quick settings shade. Swipe down from the top edge of the screen to open it.",
                color = colors.onSurfaceVariant,
                fontSize = 16.sp,
            )
            Spacer(Modifier.height(8.dp))
            Step(
                title = "Enable the OpenShade service",
                done = isServiceEnabled(),
                action = "Open accessibility settings",
            ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            Step(
                title = "Allow modifying system settings",
                subtitle = "Needed for the brightness slider and auto-rotate.",
                done = Settings.System.canWrite(this@MainActivity),
                action = "Allow",
            ) {
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            }
            Step(
                title = "Allow Do Not Disturb access",
                subtitle = "Needed for the Do Not Disturb tile.",
                done = getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted,
                action = "Allow",
            ) { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
            ShizukuStep()
        }
    }

    /** Optional: Shizuku lets the Wi-Fi, Bluetooth, mobile data and airplane tiles toggle directly. */
    @Composable
    private fun ShizukuStep() {
        @Suppress("UNUSED_VARIABLE") val refresh = shizukuTick.intValue
        val installed = runCatching { packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) }.isSuccess
        val status = ShizukuShell.status
        val subtitle = "Optional. Lets Wi-Fi, Bluetooth, mobile data and airplane mode toggle with a tap " +
            "instead of opening a settings panel. " + when {
                !installed -> "Install Shizuku first."
                status == ShizukuShell.Status.NOT_RUNNING -> "Open Shizuku and start it (wireless debugging or ADB)."
                else -> ""
            }
        Step(
            title = "Direct toggles with Shizuku",
            subtitle = subtitle.trim(),
            done = status == ShizukuShell.Status.READY,
            action = when {
                !installed -> "Get Shizuku"
                status == ShizukuShell.Status.NOT_RUNNING -> "Open Shizuku"
                else -> "Grant access"
            },
        ) {
            when {
                !installed -> startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU_PACKAGE"))
                )
                status == ShizukuShell.Status.NOT_RUNNING ->
                    packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)?.let(::startActivity)
                else -> Shizuku.requestPermission(SHIZUKU_REQUEST)
            }
        }
    }

    private val shizukuTick = mutableIntStateOf(0)
    private val shizukuResult = Shizuku.OnRequestPermissionResultListener { _, _ -> shizukuTick.intValue++ }

    override fun onStart() {
        super.onStart()
        Shizuku.addRequestPermissionResultListener(shizukuResult)
    }

    override fun onStop() {
        Shizuku.removeRequestPermissionResultListener(shizukuResult)
        super.onStop()
    }

    @Composable
    private fun Step(title: String, done: Boolean, action: String, subtitle: String? = null, onClick: () -> Unit) {
        val colors = MaterialTheme.colorScheme
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surfaceContainerHigh)
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (done) colors.primary else colors.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Text(title, color = colors.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            }
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, color = colors.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(start = 36.dp))
            }
            if (!done) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onClick, modifier = Modifier.padding(start = 36.dp)) { Text(action) }
            }
        }
    }

    private fun isServiceEnabled(): Boolean {
        val expected = ComponentName(this, ShadeService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val SHIZUKU_REQUEST = 1
    }
}

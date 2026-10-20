package io.github.eminbayramov.openshade

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
        setContent {
            MaterialTheme(colorScheme = shadeColors(LocalContext.current)) {
                SetupScreen()
            }
        }
    }

    @Composable
    private fun SetupScreen() {
        // Bumped on every resume so the checklist reflects permissions granted in Settings.
        var resumeCount by remember { mutableIntStateOf(0) }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeCount++ }
        key(resumeCount) { Checklist() }
    }

    @Composable
    private fun Checklist() {
        val colors = MaterialTheme.colorScheme
        Column(
            Modifier
                .fillMaxSize()
                .background(colors.surface)
                .safeDrawingPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
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
        }
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
}

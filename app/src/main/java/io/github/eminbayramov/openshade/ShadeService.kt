package io.github.eminbayramov.openshade

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import io.github.eminbayramov.openshade.tiles.BrightnessController
import io.github.eminbayramov.openshade.tiles.TileController
import io.github.eminbayramov.openshade.ui.ShadeActions
import io.github.eminbayramov.openshade.ui.ShadePanel
import io.github.eminbayramov.openshade.ui.ShadeState
import io.github.eminbayramov.openshade.ui.shadeColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hosts two overlay windows:
 *  - a thin, invisible trigger strip along the top edge that detects a downward swipe;
 *  - the full-screen shade panel, added only while it is open.
 */
class ShadeService : AccessibilityService(), ShadeActions {

    private lateinit var windowManager: WindowManager
    private lateinit var tiles: TileController
    private lateinit var brightness: BrightnessController

    private var trigger: View? = null
    private var panelRoot: View? = null
    private var panelLifecycle: OverlayLifecycleOwner? = null

    private val panelVisibility = MutableTransitionState(false)
    private val state = mutableStateOf(emptyState())

    private val refresher = object : Runnable {
        override fun run() {
            if (panelRoot == null) return
            refresh()
            trigger?.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onServiceConnected() {
        windowManager = getSystemService(WindowManager::class.java)
        tiles = TileController(this)
        brightness = BrightnessController(this)
        addTrigger()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // While our panel is open, any stock shade that slipped through is closed again.
        if (panelRoot != null && event?.packageName == SYSTEM_UI) collapseSystemShade()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        removePanel()
        trigger?.let { windowManager.removeView(it) }
        trigger = null
        if (::tiles.isInitialized) tiles.release()
        super.onDestroy()
    }

    // region Trigger strip

    @SuppressLint("ClickableViewAccessibility")
    private fun addTrigger() {
        val view = View(this)
        var startY = 0f
        var opened = false
        // XOS steals top-edge swipes after roughly 70 px, so open on the first downward move.
        val threshold = 1 * resources.displayMetrics.density

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    opened = false
                }
                MotionEvent.ACTION_MOVE -> if (!opened && event.rawY - startY > threshold) {
                    opened = true
                    showPanel()
                }
            }
            true
        }

        // Only the right half is covered, matching the split layout many ROMs use:
        // swiping on the left still opens the stock notification shade.
        val params = WindowManager.LayoutParams(
            resources.displayMetrics.widthPixels / 2,
            statusBarHeight(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        windowManager.addView(view, params)
        trigger = view
    }

    @SuppressLint("InternalInsetResource", "DiscouragedApi")
    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else (28 * resources.displayMetrics.density).toInt()
    }

    // endregion

    // region Panel window

    private fun showPanel() {
        if (panelRoot != null) return
        refresh()

        val lifecycle = OverlayLifecycleOwner().also { it.onCreate() }
        val root = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    onDismiss()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        val compose = ComposeView(this).apply {
            setContent {
                ShadePanel(panelVisibility, state.value, shadeColors(context), this@ShadeService)
                // Remove the window once the exit animation has finished.
                if (panelVisibility.isIdle && !panelVisibility.currentState && !panelVisibility.targetState) {
                    post { removePanel() }
                }
            }
        }
        root.addView(compose)
        lifecycle.attachTo(root)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        windowManager.addView(root, params)
        lifecycle.onResume()
        panelRoot = root
        panelLifecycle = lifecycle
        panelVisibility.targetState = true
        trigger?.postDelayed(refresher, REFRESH_MS)
        // Some ROMs (XOS among them) intercept the same swipe and start opening their own
        // shade underneath ours. Collapse it right away and a few more times while the
        // gesture settles; onAccessibilityEvent catches anything later.
        collapseSystemShade()
        for (delay in longArrayOf(50, 150, 300, 600, 1000)) {
            trigger?.postDelayed(::collapseSystemShade, delay)
        }
    }

    private fun collapseSystemShade() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        }
    }

    private fun removePanel() {
        val root = panelRoot ?: return
        panelRoot = null
        trigger?.removeCallbacks(refresher)
        panelLifecycle?.onDestroy()
        panelLifecycle = null
        windowManager.removeView(root)
    }

    private fun refresh() {
        val now = Date()
        state.value = ShadeState(
            time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now),
            date = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(now),
            tiles = tiles.snapshot(),
            brightness = brightness.read(),
            autoBrightness = brightness.isAuto,
        )
    }

    private fun emptyState() = ShadeState("", "", emptyList(), 0.5f, false)

    private fun launch(intent: Intent) {
        onDismiss()
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // endregion

    // region ShadeActions

    override fun onTileClick(id: String) {
        tiles.toggle(id)?.let(::launch) ?: trigger?.postDelayed({ refresh() }, 250)
    }

    override fun onTileLongClick(id: String) = launch(tiles.detailsIntent(id))

    override fun onBrightnessChange(value: Float) {
        if (!brightness.canWrite) {
            launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            return
        }
        brightness.write(value)
        state.value = state.value.copy(brightness = value, autoBrightness = false)
    }

    override fun onAutoBrightnessToggle() {
        brightness.isAuto = !brightness.isAuto
        refresh()
    }

    override fun onOpenSettings() = launch(Intent(Settings.ACTION_SETTINGS))

    override fun onDismiss() {
        panelVisibility.targetState = false
    }

    // endregion

    private companion object {
        const val REFRESH_MS = 1000L
        const val SYSTEM_UI = "com.android.systemui"
    }
}

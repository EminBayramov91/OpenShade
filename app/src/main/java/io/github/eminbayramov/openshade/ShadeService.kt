package io.github.eminbayramov.openshade

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.animation.ValueAnimator
import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import io.github.eminbayramov.openshade.shell.ShizukuShell
import io.github.eminbayramov.openshade.tiles.BrightnessController
import io.github.eminbayramov.openshade.tiles.TileController
import io.github.eminbayramov.openshade.ui.ShadeActions
import io.github.eminbayramov.openshade.ui.ShadePanel
import io.github.eminbayramov.openshade.ui.ShadeState
import io.github.eminbayramov.openshade.ui.shadeColors
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hosts two overlay windows:
 *  - a thin, invisible trigger strip along the right half of the top edge;
 *  - the full-screen shade panel, created once and toggled between hidden and shown.
 */
class ShadeService : AccessibilityService(), ShadeActions {

    private lateinit var windowManager: WindowManager
    private lateinit var tiles: TileController
    private lateinit var brightness: BrightnessController

    private var trigger: View? = null
    private var panelRoot: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelLifecycle: OverlayLifecycleOwner? = null
    private var panelShown = false

    private val panelVisibility = MutableTransitionState(false)

    // While the stock shade is visible underneath (it follows the finger until it is
    // lifted), the panel stays fully opaque; once it is gone the panel fades to the
    // chosen opacity and the blur fades in.
    private val covering = mutableStateOf(false)
    private var gestureEndedAt = 0L
    private var clearPolls = 0
    private var blurAnimator: ValueAnimator? = null

    private val coverWatcher = object : Runnable {
        override fun run() {
            if (!panelShown || !covering.value) return
            if (stockShadeVisible()) {
                clearPolls = 0
                collapseSystemShade()
            } else {
                clearPolls++
            }
            val released = gestureEndedAt != 0L && SystemClock.uptimeMillis() - gestureEndedAt >= REVEAL_DELAY_MS
            if (released && clearPolls >= 2) reveal() else trigger?.postDelayed(this, COVER_POLL_MS)
        }
    }
    private val state = mutableStateOf(emptyState())

    private val refresher = object : Runnable {
        override fun run() {
            if (!panelShown) return
            refresh()
            trigger?.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onServiceConnected() {
        windowManager = getSystemService(WindowManager::class.java)
        tiles = TileController(this)
        brightness = BrightnessController(this)
        settings = AppSettings(this)
        settings.registerListener(settingsListener)
        WallpaperManager.getInstance(this).addOnColorsChangedListener(wallpaperListener, Handler(mainLooper))
        updateAppearance()
        Shizuku.addBinderReceivedListenerSticky(shizukuListener)
        refresh()
        addPanel()
        addTrigger()
    }

    private val shizukuListener = Shizuku.OnBinderReceivedListener { ShizukuShell.warmUp() }

    private lateinit var settings: AppSettings
    // Replaced in onServiceConnected; the service has no context yet at construction.
    private val appearance = mutableStateOf(Appearance(darkColorScheme(), 0.85f, false))

    private data class Appearance(val colors: ColorScheme, val opacity: Float, val blur: Boolean)

    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> updateAppearance() }
    private val wallpaperListener = WallpaperManager.OnColorsChangedListener { _, which ->
        if (which and WallpaperManager.FLAG_SYSTEM != 0) updateAppearance()
    }

    private fun updateAppearance() {
        val blur = settings.blur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && windowManager.isCrossWindowBlurEnabled
        appearance.value = Appearance(shadeColors(this, settings.colorSource), settings.opacity, blur)
        if (panelShown) applyPanelFlags(shown = true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!panelShown || event == null) return
        when (event.packageName) {
            // Any stock shade that slipped through is closed again.
            SYSTEM_UI -> collapseSystemShade()
            packageName -> Unit
            // Another app or the launcher came to the front, e.g. after the system
            // swipe-up-for-home gesture at the bottom edge, which never reaches our window.
            else -> if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) onDismiss()
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (::settings.isInitialized) {
            settings.unregisterListener(settingsListener)
            WallpaperManager.getInstance(this).removeOnColorsChangedListener(wallpaperListener)
        }
        Shizuku.removeBinderReceivedListener(shizukuListener)
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
        var moved = false
        val threshold = 8 * resources.displayMetrics.density

        // The stock shade on XOS reacts to the same touch in parallel and cannot be
        // stopped, so the panel is shown on touch-down to cover it from the first frame.
        // A plain tap without a downward swipe closes it again.
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    moved = false
                    showPanel()
                }
                MotionEvent.ACTION_MOVE -> if (event.rawY - startY > threshold) moved = true
                // CANCEL arrives when XOS takes over the gesture; the finger may still be
                // down, which is why the cover watcher looks at the stock shade itself.
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved && event.actionMasked == MotionEvent.ACTION_UP) onDismiss()
                    gestureEndedAt = SystemClock.uptimeMillis()
                }
            }
            true
        }

        // Only the right half is covered, matching the split layout many ROMs use:
        // swiping on the left still opens the stock notification shade. The strip is a
        // little taller than the status bar to catch swipes that start slightly lower.
        val params = WindowManager.LayoutParams(
            resources.displayMetrics.widthPixels / 2,
            statusBarHeight() + (12 * resources.displayMetrics.density).toInt(),
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

    /**
     * The panel window is created once and kept attached but hidden and untouchable.
     * Building a Compose window on demand takes a few hundred milliseconds, long enough
     * for the stock shade to appear first, so showing has to be instant.
     */
    private fun addPanel() {
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
                val look = appearance.value
                ShadePanel(panelVisibility, state.value, look.colors, if (covering.value) 1f else look.opacity, this@ShadeService)
                // Hide the window again once the exit animation has finished.
                if (panelVisibility.isIdle && !panelVisibility.currentState && !panelVisibility.targetState) {
                    post { hidePanelWindow() }
                }
            }
        }
        root.addView(compose)
        lifecycle.attachTo(root)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            HIDDEN_FLAGS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        root.visibility = View.INVISIBLE
        windowManager.addView(root, params)
        lifecycle.onResume()
        panelRoot = root
        panelParams = params
        panelLifecycle = lifecycle
    }

    private fun showPanel() {
        val root = panelRoot ?: return
        if (panelShown) {
            // Still fading out from a previous dismiss: just reverse the animation.
            panelVisibility.targetState = true
            return
        }
        panelShown = true
        refresh()
        // Start opaque and without blur; reveal() fades both in once the stock shade,
        // which XOS opens from the same swipe, is gone.
        covering.value = true
        gestureEndedAt = 0L
        clearPolls = 0
        blurAnimator?.cancel()
        applyPanelFlags(shown = true, blurRadius = 0)
        root.visibility = View.VISIBLE
        panelVisibility.targetState = true
        trigger?.postDelayed(refresher, REFRESH_MS)
        collapseSystemShade()
        trigger?.postDelayed(coverWatcher, COVER_POLL_MS)
    }

    /** True while a large System UI window (the stock shade) is on screen. */
    private fun stockShadeVisible(): Boolean {
        val minHeight = resources.displayMetrics.heightPixels / 3
        val bounds = Rect()
        return windows.any { window ->
            window.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
                bounds.also(window::getBoundsInScreen).height() > minHeight
        }
    }

    /** Fades the background to the chosen opacity and animates the blur in with it. */
    private fun reveal() {
        covering.value = false
        if (!appearance.value.blur) return
        val target = (BLUR_DP * resources.displayMetrics.density).toInt()
        blurAnimator?.cancel()
        blurAnimator = ValueAnimator.ofInt(0, target).apply {
            duration = REVEAL_MS
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener { if (panelShown) applyPanelFlags(shown = true, blurRadius = it.animatedValue as Int) }
            start()
        }
    }

    private fun collapseSystemShade() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        }
    }

    private fun hidePanelWindow() {
        val root = panelRoot ?: return
        if (!panelShown) return
        panelShown = false
        trigger?.removeCallbacks(refresher)
        trigger?.removeCallbacks(coverWatcher)
        blurAnimator?.cancel()
        root.visibility = View.INVISIBLE
        applyPanelFlags(shown = false)
    }

    /**
     * Switches the window between interactive (and optionally blurred) and pass-through.
     * [blurRadius] defaults to the full radius, or none while the panel is still covering.
     */
    private fun applyPanelFlags(shown: Boolean, blurRadius: Int? = null) {
        val root = panelRoot ?: return
        val params = panelParams ?: return
        val blur = shown && appearance.value.blur
        params.flags = (if (shown) SHOWN_FLAGS else HIDDEN_FLAGS) or
            (if (blur) WindowManager.LayoutParams.FLAG_BLUR_BEHIND else 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val full = (BLUR_DP * resources.displayMetrics.density).toInt()
            params.blurBehindRadius = when {
                !blur -> 0
                blurRadius != null -> blurRadius
                covering.value -> 0
                else -> full
            }
        }
        windowManager.updateViewLayout(root, params)
    }

    private fun removePanel() {
        val root = panelRoot ?: return
        panelRoot = null
        panelShown = false
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
        val intent = tiles.toggle(id) { trigger?.post { refresh() } }
        if (intent != null) {
            launch(intent)
            return
        }
        // Flip the tile right away; the real state follows from refresh() once the
        // radio has actually switched.
        state.value = state.value.copy(
            tiles = state.value.tiles.map { if (it.id == id) it.copy(active = !it.active) else it }
        )
        trigger?.postDelayed({ refresh() }, 250)
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
        const val BLUR_DP = 24
        const val COVER_POLL_MS = 100L
        const val REVEAL_DELAY_MS = 250L
        const val REVEAL_MS = 500L

        const val SHOWN_FLAGS =
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        // Hidden: touches and key events pass straight through to whatever is below.
        const val HIDDEN_FLAGS = SHOWN_FLAGS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    }
}

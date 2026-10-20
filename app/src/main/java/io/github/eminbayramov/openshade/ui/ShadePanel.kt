package io.github.eminbayramov.openshade.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.eminbayramov.openshade.tiles.Tile

data class ShadeState(
    val time: String,
    val date: String,
    val tiles: List<Tile>,
    val brightness: Float,
    val autoBrightness: Boolean,
)

interface ShadeActions {
    fun onTileClick(id: String)
    fun onTileLongClick(id: String)
    fun onBrightnessChange(value: Float)
    fun onAutoBrightnessToggle()
    fun onOpenSettings()
    fun onDismiss()
}

/**
 * Full-height panel. The background uses the surface color at [opacity]; when the
 * window blurs what is behind it, a lower opacity gives a frosted-glass look.
 */
@Composable
fun ShadePanel(
    visibility: MutableTransitionState<Boolean>,
    state: ShadeState,
    colors: ColorScheme,
    opacity: Float,
    actions: ShadeActions,
) {
    MaterialTheme(colorScheme = colors) {
        AnimatedVisibility(
            visibility,
            // The panel shows the moment the finger touches the edge, so it has to be
            // on screen within a frame or two to cover the stock shade.
            enter = fadeIn(tween(90)) + slideInVertically(tween(160)) { -it / 12 },
            exit = fadeOut(tween(180)) + slideOutVertically(tween(200)) { -it / 6 },
        ) {
            PanelSurface(state, opacity, actions)
        }
    }
}

@Composable
private fun PanelSurface(state: ShadeState, opacity: Float, actions: ShadeActions) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.surfaceContainer.copy(alpha = opacity))
            // Tapping empty space closes the panel, like the stock shade.
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = actions::onDismiss)
            // Swipe up anywhere on the panel to close it.
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onVerticalDrag = { _, dy -> total += dy },
                    onDragEnd = { if (total < -60f) actions.onDismiss() },
                )
            }
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 12.dp),
    ) {
        Header(state, actions)
        Spacer(Modifier.height(16.dp))
        BrightnessSlider(state.brightness, state.autoBrightness, actions)
        Spacer(Modifier.height(16.dp))
        TileGrid(state.tiles, actions)
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(width = 36.dp, height = 4.dp)
                .clip(CircleShape)
                .background(colors.onSurfaceVariant.copy(alpha = 0.5f))
        )
    }
}

@Composable
private fun Header(state: ShadeState, actions: ShadeActions) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(state.time, color = colors.onSurface, fontSize = 44.sp, fontWeight = FontWeight.Normal)
            Text(state.date, color = colors.onSurfaceVariant, fontSize = 15.sp)
        }
        IconButton(onClick = actions::onOpenSettings) {
            Icon(Icons.Rounded.Settings, contentDescription = "Settings", tint = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun BrightnessSlider(value: Float, auto: Boolean, actions: ShadeActions) {
    val colors = MaterialTheme.colorScheme
    var width by remember { mutableFloatStateOf(1f) }
    var local by remember(value) { mutableFloatStateOf(value) }

    fun update(offset: Offset) {
        local = (offset.x / width).coerceIn(0.04f, 1f)
        actions.onBrightnessChange(local)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .height(52.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(colors.surfaceContainerHighest)
                .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.firstOrNull()?.let { change ->
                                if (change.pressed) {
                                    update(change.position)
                                    change.consume()
                                }
                            }
                        }
                    }
                },
        ) {
            Box(
                Modifier
                    .fillMaxWidth(local.coerceIn(0.1f, 1f))
                    .height(52.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(colors.primary),
                contentAlignment = Alignment.CenterStart,
            ) {
                Icon(
                    Icons.Rounded.BrightnessMedium,
                    contentDescription = null,
                    tint = colors.onPrimary,
                    modifier = Modifier.padding(start = 16.dp).size(22.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(if (auto) colors.primary else colors.surfaceContainerHighest)
                .clickable(onClick = actions::onAutoBrightnessToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.BrightnessAuto,
                contentDescription = "Adaptive brightness",
                tint = if (auto) colors.onPrimary else colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TileGrid(tiles: List<Tile>, actions: ShadeActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { tile -> TileView(tile, actions, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TileView(tile: Tile, actions: ShadeActions, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val background = if (tile.active) colors.primary else colors.surfaceContainerHighest
    val content = if (tile.active) colors.onPrimary else colors.onSurface
    val secondary = if (tile.active) colors.onPrimary.copy(alpha = 0.8f) else colors.onSurfaceVariant

    Row(
        modifier
            .height(72.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(background)
            .combinedClickable(
                onClick = { actions.onTileClick(tile.id) },
                onLongClick = { actions.onTileLongClick(tile.id) },
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(tile.icon, contentDescription = null, tint = content, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(tile.label, color = content, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tile.subtitle, color = secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

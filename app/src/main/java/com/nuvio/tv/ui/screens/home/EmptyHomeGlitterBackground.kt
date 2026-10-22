package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.sin

private data class GlitterStar(val angle: Float, val seed: Float, val speed: Float)

/**
 * Lightweight Compose adaptation of the supplied Glitter Wrap effect.  It is
 * deliberately used only for an empty Home: no image decoding, no bitmap cache
 * and a fixed small star pool so Android TV navigation stays responsive.
 */
@Composable
internal fun EmptyHomeGlitterBackground(modifier: Modifier = Modifier) {
    val inspectionMode = LocalInspectionMode.current
    val stars = remember {
        List(92) { index ->
            val seed = ((index * 73) % 97) / 97f
            GlitterStar(
                angle = (index * 2.3999632f) + seed * 0.82f,
                seed = seed,
                speed = 0.42f + ((index * 31) % 29) / 45f
            )
        }
    }
    var elapsedSeconds by remember { mutableFloatStateOf(0f) }
    // Theme colors are composable values: resolve them before entering Canvas'
    // non-composable draw scope.
    val primary = NuvioColors.Primary
    val secondary = NuvioColors.Secondary
    val textSecondary = NuvioColors.TextSecondary
    if (!inspectionMode) {
        LaunchedEffect(Unit) {
            var originNanos = 0L
            while (isActive) {
                withFrameNanos { now ->
                    if (originNanos == 0L) originNanos = now
                    elapsedSeconds = (now - originNanos) / 1_000_000_000f
                }
            }
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = kotlin.math.hypot(size.width, size.height) * 0.62f
        val palette = listOf(primary, secondary, textSecondary)

        stars.forEachIndexed { index, star ->
            val progress = ((elapsedSeconds * star.speed + star.seed) % 1f).coerceIn(0f, 1f)
            val radius = progress * progress * maxRadius
            val previousRadius = (progress - 0.055f).coerceAtLeast(0f).let { it * it * maxRadius }
            val wobble = sin(elapsedSeconds * 1.25f + star.seed * 18f) * 0.045f * progress
            val angle = star.angle + wobble
            val color = palette[index % palette.size]
            val alpha = (0.10f + progress * 0.58f).coerceAtMost(0.68f)
            val start = Offset(
                centre.x + cos(angle) * previousRadius,
                centre.y + sin(angle) * previousRadius
            )
            val end = Offset(
                centre.x + cos(angle) * radius,
                centre.y + sin(angle) * radius
            )
            drawLine(
                color = color.copy(alpha = alpha * 0.52f),
                start = start,
                end = end,
                strokeWidth = (0.6f + progress * 1.4f).dp.toPx(),
                cap = StrokeCap.Round
            )
            drawCircle(
                color = color.copy(alpha = alpha),
                radius = (0.7f + progress * 2.1f).dp.toPx(),
                center = end
            )
        }
    }
}

package com.hereliesaz.stencilartist.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import com.hereliesaz.stencilartist.core.paint.CanColours
import com.hereliesaz.stencilartist.app.generated.resources.Res
import com.hereliesaz.stencilartist.app.generated.resources.mtn94_original_packshot
import com.hereliesaz.stencilartist.app.generated.resources.mtn94_original_top_strip
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.imageResource

/*
 * Generic cans are drawn as deterministic vector shapes. The MTN 94 carousel is the exception:
 * it uses the user's original unmodified transparent PNG, with an independent colour fill below
 * the existing translucent shoulder. No pixel of that photograph is masked, cut or regenerated.
 */

private fun tone(rgb: Int) = Color(rgb)
private fun lighter(rgb: Int, by: Double) = Color(CanColours.lighten(rgb, by))
private fun darker(rgb: Int, by: Double) = Color(CanColours.darken(rgb, by))

/**
 * Exact unmodified supplied spray-can TOP artwork, composited over one solid RGB circle
 * for this can. The grid (not this tile) supplies the SINGLE shared white background.
 *
 * The supplied 724×2172 PNG contains five metal tops. Their per-frame source bounds are located at the transparent seam minima, so adjacent tops do not bleed into one another. We
 * crop only at draw time; no uploaded file pixels are recoloured, masked or resampled
 * before Compose draws them. The per-can paint circle remains an independent layer.
 */
@Composable
internal fun CanTop(
    colour: Int?,
    selected: Boolean,
    sampled: Boolean,
    size: Dp,
    modifier: Modifier = Modifier,
    artIndex: Int = 0,
) {
    // Unknown colours never receive invented colour swatches or placeholder can art.
    if (colour == null) return
    val original = imageResource(Res.drawable.mtn94_original_top_strip)
    Canvas(modifier.size(size)) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val diameter = this.size.minDimension
        drawCircle(Color(colour), diameter * 0.45f, center)
        // Five separate views from one unchanged image file. Source coordinates deliberately
        // preserve the full metal ring and transparent shoulder of each top.
        val frame = artIndex.mod(5)
        // Transparency minima measured across the original PNG separate its tops
        // at rows 428, 847, 1264, 1678 and 2111. No frame shares a neighbouring
        // ring. They differ slightly in height, so use each source height as-is.
        val frameTop = when (frame) {
            0 -> 0
            1 -> 428
            2 -> 847
            3 -> 1264
            else -> 1678
        }
        val frameBottom = when (frame) {
            0 -> 428
            1 -> 847
            2 -> 1264
            3 -> 1678
            else -> 2111
        }
        drawImage(
            image = original,
            srcOffset = IntOffset(140, frameTop),
            srcSize = IntSize(440, frameBottom - frameTop),
            dstSize = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()),
            filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
        )
        if (selected) {
            drawCircle(Color.White, diameter / 2f - 1.5f, center, style = Stroke(diameter * 0.07f))
            val badge = diameter * 0.17f
            val b = Offset(this.size.width - badge * 1.05f, this.size.height - badge * 1.05f)
            drawCircle(Color.White, badge, b)
            val check = Path().apply {
                moveTo(b.x - badge * 0.5f, b.y)
                lineTo(b.x - badge * 0.12f, b.y + badge * 0.4f)
                lineTo(b.x + badge * 0.52f, b.y - badge * 0.38f)
            }
            drawPath(check, Color.Black, style = Stroke(badge * 0.3f, cap = StrokeCap.Round))
        }
        if (sampled) {
            val dot = diameter * 0.07f
            val at = Offset(this.size.width - dot * 2.2f, dot * 2.2f)
            drawCircle(Color(0xFFE6E6E6), dot, at)
            drawCircle(Color(0xFF0E0E0E), dot * 0.45f, at)
        }
    }
}

/**
 * The exact user-supplied MTN 94 photograph, unmodified and placed above a separate paint layer.
 *
 * The original 1122×1402 RGBA PNG has a semitransparent white shoulder. Its RGB/alpha remain
 * unchanged; paint is drawn only BEHIND that region, with opaque white behind both layers.
 * ContentScale.Crop removes empty lateral viewport space, never any part of the photographed can.
 * This is deliberately not used for Montana Cans, MTN Hardcore or MTN fluorescent/transparent lines.
 */
@Composable
internal fun Mtn94Packshot(colour: Int?, width: Dp, height: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(width, height).background(Color.White)) {
        if (colour != null) {
            Canvas(Modifier.fillMaxSize()) {
                // ContentScale.Crop scales the 1122×1402 original by its full source height.
                // Map the independent shoulder underlay from the original source coordinates.
                val scale = size.height / 1402f
                val offsetX = (size.width - 1122f * scale) / 2f
                val x: (Float) -> Float = { offsetX + it * scale }
                val y: (Float) -> Float = { it * scale }
                val shoulder = Path().apply {
                    moveTo(x(423f), y(223f))
                    cubicTo(x(394f), y(230f), x(362f), y(254f), x(354f), y(285f))
                    lineTo(x(751f), y(285f))
                    cubicTo(x(737f), y(255f), x(703f), y(226f), x(677f), y(220f))
                    close()
                }
                drawPath(shoulder, Color(colour))
            }
        }
        Image(
            painter = painterResource(Res.drawable.mtn94_original_packshot),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * A whole can seen from the side, [width] by [height] (about 1:2.2 reads as a can): a nozzle and cap
 * in [colour], a silver shoulder, a body with a label band in [colour] and a lighter strip across it,
 * and cylindrical shading over all of it. [colour] null draws a plain grey can with no label colour.
 */
@Composable
internal fun CanSide(colour: Int?, width: Dp, height: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width, height)) {
        val w = this.size.width
        val h = this.size.height
        val label = colour ?: 0xFF555555.toInt()
        val bodyTop = h * 0.30f
        val bodyBottom = h * 0.97f
        val x0 = w * 0.06f
        val x1 = w * 0.94f
        // Body: dark can with the label colour as a wide band.
        drawRoundRect(Color(0xFF1E1E1E), Offset(x0, bodyTop), Size(x1 - x0, bodyBottom - bodyTop), CornerRadius(w * 0.06f))
        val bandTop = bodyTop + (bodyBottom - bodyTop) * 0.14f
        val bandBottom = bodyTop + (bodyBottom - bodyTop) * 0.86f
        drawRect(tone(label), Offset(x0, bandTop), Size(x1 - x0, bandBottom - bandTop))
        drawRect(lighter(label, 0.35), Offset(x0, bandTop + (bandBottom - bandTop) * 0.40f), Size(x1 - x0, (bandBottom - bandTop) * 0.16f))
        // Shoulder: a silver trapezoid from the body to the narrower neck.
        val neckL = w * 0.30f
        val neckR = w * 0.70f
        val shoulder = Path().apply {
            moveTo(x0, bodyTop + w * 0.02f)
            lineTo(neckL, h * 0.19f)
            lineTo(neckR, h * 0.19f)
            lineTo(x1, bodyTop + w * 0.02f)
            close()
        }
        drawPath(shoulder, Brush.verticalGradient(listOf(Color(0xFFD8D8D8), Color(0xFF8E8E8E)), startY = h * 0.19f, endY = bodyTop))
        // Cap in the can's colour, with its lip and nozzle.
        val capTop = h * 0.04f
        drawRoundRect(darker(label, 0.35), Offset(neckL - w * 0.02f, h * 0.16f), Size(neckR - neckL + w * 0.04f, h * 0.04f), CornerRadius(w * 0.02f))
        drawRoundRect(tone(label), Offset(neckL, capTop + h * 0.04f), Size(neckR - neckL, h * 0.13f), CornerRadius(w * 0.05f))
        drawRoundRect(lighter(label, 0.3), Offset(neckL + w * 0.04f, capTop + h * 0.05f), Size(w * 0.06f, h * 0.11f), CornerRadius(w * 0.03f))
        drawRoundRect(darker(label, 0.6), Offset(w * 0.46f, capTop), Size(w * 0.14f, h * 0.06f), CornerRadius(w * 0.02f))
        // Cylinder shading across the whole can: dark edges, a bright stripe left of centre.
        drawRect(
            Brush.horizontalGradient(
                0f to Color.Black.copy(alpha = 0.45f),
                0.22f to Color.White.copy(alpha = 0.28f),
                0.5f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.5f),
            ),
            Offset(x0, capTop), Size(x1 - x0, bodyBottom - capTop),
        )
        // The seam at the foot.
        drawRoundRect(Color(0xFF8E8E8E), Offset(x0, bodyBottom - h * 0.03f), Size(x1 - x0, h * 0.03f), CornerRadius(w * 0.03f))
    }
}

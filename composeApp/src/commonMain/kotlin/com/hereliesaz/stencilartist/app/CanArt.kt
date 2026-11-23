package com.hereliesaz.stencilartist.app

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import com.hereliesaz.stencilartist.core.paint.CanColours

/*
 * Spray cans drawn from nothing but their colour value. There are no photographs of cans and no
 * licence for the makers' pictures, so a can is a few shapes: deterministic, cheap, and the same on
 * every target. The colour is the can's cap, which is how most art cans are colour-coded.
 */

private fun tone(rgb: Int) = Color(rgb)
private fun lighter(rgb: Int, by: Double) = Color(CanColours.lighten(rgb, by))
private fun darker(rgb: Int, by: Double) = Color(CanColours.darken(rgb, by))

/**
 * An uncapped can seen from directly above: grey metal rim, coloured shoulder donut and a centred
 * silver valve/nozzle. Only the donut is painted; the metal and nozzle stay neutral. [colour] null
 * draws a hatched donut because no RGB value is known. [selected] adds a ring and check; [sampled]
 * adds a small dot for a colour sampled from the maker's image.
 */
@Composable
internal fun CanTop(colour: Int?, selected: Boolean, sampled: Boolean, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val r = this.size.minDimension / 2 * (if (selected) 0.86f else 0.92f)
        // Flat, centred metal shoulder: unlike a cap, the colour occupies only the outer donut.
        drawCircle(Color(0xFF9A9A9A), r, c)
        drawCircle(Color(0xFF484848), r * 0.94f, c)
        val ring = r * 0.87f
        if (colour == null) {
            drawCircle(Color(0xFF303030), ring, c)
            val clip = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, ring)) }
            clipPath(clip) {
                val step = ring / 3.2f
                var x = c.x - ring * 2
                while (x < c.x + ring * 2) {
                    drawLine(Color(0xFF737373), Offset(x, c.y + ring), Offset(x + ring * 2, c.y - ring), strokeWidth = r * 0.07f)
                    x += step
                }
            }
        } else {
            drawCircle(tone(colour), ring, c)
            drawArc(
                Color.White.copy(alpha = 0.28f), startAngle = 204f, sweepAngle = 66f,
                useCenter = false, topLeft = Offset(c.x - ring * 0.88f, c.y - ring * 0.88f),
                size = Size(ring * 1.76f, ring * 1.76f),
                style = Stroke(r * 0.055f, cap = StrokeCap.Round),
            )
        }
        drawCircle(Color(0xFFB7B7B7), r * 0.59f, c)
        drawCircle(Color(0xFFE3E3E3), r * 0.53f, c)
        drawCircle(Color(0xFF8A8A8A), r * 0.38f, c)
        drawCircle(Color(0xFFD2D2D2), r * 0.31f, c)
        // Every circle uses exactly c: there is no off-centre actuator or cap.
        drawCircle(Color(0xFF343434), r * 0.20f, c)
        drawCircle(Color(0xFF7D7D7D), r * 0.15f, c)
        drawCircle(Color(0xFF252525), r * 0.057f, c)
        if (selected) {
            drawCircle(Color.White, this.size.minDimension / 2 - 1.5f, c, style = Stroke(this.size.minDimension * 0.07f))
            val badge = this.size.minDimension * 0.17f
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
            val d = this.size.minDimension * 0.07f
            drawCircle(Color(0xFFE6E6E6), d, Offset(this.size.width - d * 2.2f, d * 2.2f))
            drawCircle(Color(0xFF0E0E0E), d * 0.45f, Offset(this.size.width - d * 2.2f, d * 2.2f))
        }
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

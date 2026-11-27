package com.hereliesaz.stencilartist.core.paint

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The top of a spray can, seen from above: the owner's photograph of a can top laid OVER a disc of the
 * paint colour. The photograph's dome is clear in places, so the colour shows through it, while the
 * rim, the nozzle ring and the glints stay silver, as they do on a real can.
 *
 * The photograph is stored as grey level and alpha. [pixels] puts the colour behind it: ordinary
 * "over" compositing of the picture on a colour disc that fits its outer rim. Pure, deterministic
 * arithmetic on ARGB ints, the same on every target.
 */
object CanTop {
    /** Width and height of the picture in pixels; it is square. */
    const val SIZE: Int = CanTopArt.SIZE

    @OptIn(ExperimentalEncodingApi::class)
    private val raw: ByteArray by lazy { Base64.decode(CanTopArt.DATA) }

    /**
     * The can top over a disc of [rgb], as [SIZE] x [SIZE] straight-alpha ARGB pixels, row-major.
     * Outside the can everything is clear. The disc has a one-pixel soft edge so the can's outline is
     * not stair-stepped where the picture is clear at the rim.
     */
    fun pixels(rgb: Int): IntArray {
        val data = raw
        val cr = ((rgb shr 16) and 0xFF).toDouble()
        val cg = ((rgb shr 8) and 0xFF).toDouble()
        val cb = (rgb and 0xFF).toDouble()
        return IntArray(SIZE * SIZE) { i ->
            val x = i % SIZE + 0.5
            val y = i / SIZE + 0.5
            val disc = (CanTopArt.RADIUS + 0.5 - hypot(x - CanTopArt.CENTRE_X, y - CanTopArt.CENTRE_Y)).coerceIn(0.0, 1.0)
            val grey = (data[2 * i].toInt() and 0xFF).toDouble()
            val a = (data[2 * i + 1].toInt() and 0xFF) / 255.0
            // Picture over colour: out = a*picture + (1-a)*disc*colour, coverage = a + (1-a)*disc.
            val coverage = a + (1 - a) * disc
            if (coverage <= 0.0) return@IntArray 0
            fun channel(c: Double): Int = ((grey * a + c * disc * (1 - a)) / coverage).roundToInt().coerceIn(0, 255)
            ((coverage * 255).roundToInt().coerceIn(0, 255) shl 24) or
                (channel(cr) shl 16) or (channel(cg) shl 8) or channel(cb)
        }
    }

    /** The picture on its own, over nothing: grey with its own alpha. */
    fun plain(): IntArray {
        val data = raw
        return IntArray(SIZE * SIZE) { i ->
            val grey = data[2 * i].toInt() and 0xFF
            val alpha = data[2 * i + 1].toInt() and 0xFF
            (alpha shl 24) or (grey shl 16) or (grey shl 8) or grey
        }
    }
}

package com.hereliesaz.stencilartist.core

/**
 * An image as the pipeline sees it: [width] x [height] pixels, row-major, each a straight-alpha
 * (not premultiplied) ARGB int. Platform bitmaps are converted to and from this at the edges, so
 * nothing in `core` knows what a Bitmap or a canvas is.
 */
class RgbaImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width >= 0 && height >= 0) { "an image cannot have a negative size" }
        require(pixels.size == width * height) { "expected ${width * height} pixels, got ${pixels.size}" }
    }
}

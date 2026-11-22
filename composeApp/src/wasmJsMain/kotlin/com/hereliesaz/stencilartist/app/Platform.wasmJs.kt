@file:OptIn(ExperimentalWasmJsInterop::class)

package com.hereliesaz.stencilartist.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.hereliesaz.stencilartist.core.RgbaImage
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

/**
 * Opens the browser's file chooser and decodes the chosen image with the browser's own decoder
 * (which also applies a photo's EXIF orientation), scaled to fit [maxEdge]. Calls [onDecoded] with
 * the pixels as an Int32Array over canvas RGBA bytes and the size, or [onFailed] if the file is not
 * an image the browser can read. Calls neither if the chooser is cancelled.
 */
private fun chooseImage(maxEdge: Int, onDecoded: (JsAny, Int, Int) -> Unit, onFailed: () -> Unit): Unit = js(
    """{
        const input = document.createElement('input');
        input.type = 'file';
        input.accept = 'image/*';
        input.onchange = async () => {
            const file = input.files && input.files[0];
            if (!file) return;
            try {
                const bitmap = await createImageBitmap(file);
                const scale = Math.min(1, maxEdge / Math.max(bitmap.width, bitmap.height));
                const w = Math.max(1, Math.floor(bitmap.width * scale));
                const h = Math.max(1, Math.floor(bitmap.height * scale));
                const canvas = document.createElement('canvas');
                canvas.width = w;
                canvas.height = h;
                const context = canvas.getContext('2d', { willReadFrequently: true });
                context.imageSmoothingQuality = 'high';
                context.drawImage(bitmap, 0, 0, w, h);
                bitmap.close();
                onDecoded(new Int32Array(context.getImageData(0, 0, w, h).data.buffer), w, h);
            } catch (e) {
                onFailed();
            }
        };
        input.click();
    }"""
)

private fun intAt(array: JsAny, index: Int): Int = js("array[index]")

@Composable
actual fun rememberImagePicker(onPicked: (RgbaImage) -> Unit, onError: (String) -> Unit): () -> Unit {
    val picked = rememberUpdatedState(onPicked)
    val failed = rememberUpdatedState(onError)
    return remember {
        {
            chooseImage(
                MAX_WORKING_EDGE,
                onDecoded = { data, width, height ->
                    // Canvas bytes are R,G,B,A; read as little-endian ints that is ABGR. Swap R and B.
                    val pixels = IntArray(width * height) { i ->
                        val abgr = intAt(data, i)
                        (abgr and 0xFF00FF00.toInt()) or ((abgr and 0xFF) shl 16) or ((abgr shr 16) and 0xFF)
                    }
                    picked.value(RgbaImage(width, height, pixels))
                },
                onFailed = { failed.value("Couldn't open that image.") },
            )
        }
    }
}

actual fun argbToImageBitmap(pixels: IntArray, width: Int, height: Int): ImageBitmap {
    // An ARGB int, least significant byte first, is B,G,R,A: Skia's BGRA_8888.
    val bytes = ByteArray(pixels.size * 4)
    for (i in pixels.indices) {
        val p = pixels[i]
        bytes[i * 4] = p.toByte()
        bytes[i * 4 + 1] = (p shr 8).toByte()
        bytes[i * 4 + 2] = (p shr 16).toByte()
        bytes[i * 4 + 3] = (p ushr 24).toByte()
    }
    val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
    return Image.makeRaster(info, bytes, width * 4).toComposeImageBitmap()
}

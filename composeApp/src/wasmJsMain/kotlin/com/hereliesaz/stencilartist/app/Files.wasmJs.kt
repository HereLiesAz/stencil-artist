@file:OptIn(ExperimentalWasmJsInterop::class)

package com.hereliesaz.stencilartist.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

private fun newBytes(size: Int): JsAny = js("new Uint8Array(size)")
private fun setByte(array: JsAny, index: Int, value: Int): Unit = js("{ array[index] = value; }")

/** Starts a download of [bytes] (a Uint8Array) as [name]. Returns false if the page could not. */
private fun download(bytes: JsAny, name: String, mimeType: String): Boolean = js(
    """{
        try {
            const url = URL.createObjectURL(new Blob([bytes], { type: mimeType }));
            const link = document.createElement('a');
            link.href = url;
            link.download = name;
            document.body.appendChild(link);
            link.click();
            link.remove();
            setTimeout(() => URL.revokeObjectURL(url), 60000);
            return true;
        } catch (e) {
            return false;
        }
    }"""
)

@Composable
actual fun rememberFileSaver(): FileSaver = remember { DownloadSaver }

/** Saves by starting a browser download. Where it lands is the browser's and the user's business. */
private object DownloadSaver : FileSaver {
    override fun save(file: ExportFile, onResult: (Boolean) -> Unit) {
        val bytes = newBytes(file.bytes.size)
        for (i in file.bytes.indices) setByte(bytes, i, file.bytes[i].toInt() and 0xFF)
        onResult(download(bytes, file.name, file.mimeType))
    }
}

actual fun encodePng(pixels: IntArray, width: Int, height: Int): ByteArray {
    // An ARGB int, least significant byte first, is B,G,R,A: Skia's BGRA_8888.
    val bytes = ByteArray(pixels.size * 4)
    for (i in pixels.indices) {
        val p = pixels[i]
        bytes[i * 4] = p.toByte()
        bytes[i * 4 + 1] = (p shr 8).toByte()
        bytes[i * 4 + 2] = (p shr 16).toByte()
        bytes[i * 4 + 3] = (p ushr 24).toByte()
    }
    val image = Image.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL), bytes, width * 4)
    return image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("PNG encoding failed")
}

@file:OptIn(ExperimentalWasmJsInterop::class)

package com.hereliesaz.stencilartist.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
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

/** Opt in with ?profile=1 on the app URL; diagnostics stay in the browser console. */
internal actual fun stencilPerformanceEnabled(): Boolean =
    js("new URLSearchParams(globalThis.location.search).get('profile') === '1'")

/** Numeric and categorical diagnostics only, in the local browser console. */
internal actual fun reportStencilPerformance(record: String): Unit =
    js("console.info('StencilPerf ' + record)")


/**
 * The browser's top-layer dialog makes the rest of the document inert, including canvas
 * controls and their accessibility nodes. Compose/Wasm popup disposal can permanently stop
 * accessibility synchronization (CMP-10623); do not create a Compose Dialog for this notice.
 * A single element survives progress updates and restarts until Kotlin removes the notice.
 */
@Composable
internal actual fun PlatformLoadingNotice(
    heading: String, tip: String, processing: Boolean, progress: Float,
    error: String?, onDismiss: () -> Unit,
) {
    val dismiss = rememberUpdatedState(onDismiss)
    DisposableEffect(Unit) {
        onDispose { removeBrowserLoadingNotice() }
    }
    SideEffect {
        updateBrowserLoadingNotice(heading, tip, processing, progress, error ?: "") { dismiss.value() }
    }
}

/** Native dialog lifecycle; Kotlin owns visibility and the generation-safe dismissal guard. */
private fun updateBrowserLoadingNotice(
    heading: String, tip: String, processing: Boolean, progress: Float,
    error: String, onDismiss: () -> Unit,
): Unit = js(
    """{
        let dialog = document.getElementById('stencil-loading-notice');
        if (!dialog) {
            dialog = document.createElement('dialog');
            dialog.id = 'stencil-loading-notice';
            dialog.setAttribute('aria-labelledby', 'stencil-notice-heading');
            dialog.setAttribute('aria-describedby', 'stencil-notice-tip');
            dialog.style.cssText = 'box-sizing:border-box;position:fixed;inset:0;width:100%;height:100%;max-width:none;max-height:none;margin:0;border:0;padding:24px;background:rgba(0,0,0,.67);color:#efebe5;font:14px system-ui,sans-serif;overflow:auto';
            const panel = document.createElement('div');
            panel.style.cssText = 'box-sizing:border-box;position:relative;top:50%;transform:translateY(-50%);width:100%;max-width:100%;padding:18px;background:#202728;border:1px solid #71888b;border-radius:14px;display:flex;flex-direction:column;gap:10px';
            const title = document.createElement('div');
            title.id = 'stencil-notice-heading';
            title.style.cssText = 'font-size:12px;color:#bac8c8';
            const advice = document.createElement('div');
            advice.id = 'stencil-notice-tip';
            const problem = document.createElement('div');
            problem.id = 'stencil-notice-error';
            problem.style.color = '#ffb4a7';
            problem.setAttribute('role','alert');
            const meter = document.createElement('progress');
            meter.id = 'stencil-notice-progress';
            meter.max = 1;
            meter.setAttribute('aria-label','Preparing stencil');
            meter.style.cssText = 'width:100%;height:8px;accent-color:#97c4c9';
            const button = document.createElement('button');
            button.id = 'stencil-notice-continue';
            button.type = 'button';
            button.textContent = 'Continue';
            button.style.cssText = 'width:100%;min-height:40px;border:0;border-radius:24px;background:#97c4c9;color:#132a2e;font:inherit;cursor:pointer';
            panel.append(title, advice, problem, meter, button);
            dialog.append(panel);
            // Escape is not an acknowledgement; pressing it must not close the modal.
            dialog.addEventListener('cancel', event => event.preventDefault());
            document.body.append(dialog);
        }
        const button = dialog.querySelector('#stencil-notice-continue');
        const meter = dialog.querySelector('#stencil-notice-progress');
        const problem = dialog.querySelector('#stencil-notice-error');
        const wasProcessing = button.hidden;
        dialog.querySelector('#stencil-notice-heading').textContent = heading;
        dialog.querySelector('#stencil-notice-tip').textContent = tip;
        problem.textContent = error;
        problem.hidden = processing || !error;
        meter.hidden = !processing;
        meter.value = Math.max(0, Math.min(1, progress));
        button.hidden = processing;
        button.disabled = processing;
        // All taps acknowledge completed work, but neither taps nor Escape interrupt it.
        dialog.onclick = () => { if (!button.disabled) onDismiss(); };
        if (!dialog.open) dialog.showModal();
        if (!processing && wasProcessing) button.focus();
    }"""
)

private fun removeBrowserLoadingNotice(): Unit = js(
    """{
        const dialog = document.getElementById('stencil-loading-notice');
        if (dialog) {
            dialog.close();
            dialog.remove();
        }
    }"""
)

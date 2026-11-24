package com.hereliesaz.stencilartist.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.hereliesaz.stencilartist.core.RgbaImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

@Composable
actual fun rememberImagePicker(onPicked: (RgbaImage) -> Unit, onError: (String) -> Unit): () -> Unit {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val picked = rememberUpdatedState(onPicked)
    val failed = rememberUpdatedState(onError)
    // The system's own chooser: it needs no storage permission, and hands back only what was chosen.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val image = withContext(Dispatchers.IO) { runCatching { decode(context, uri) }.getOrNull() }
            if (image != null) picked.value(image) else failed.value("Couldn't open that image.")
        }
    }
    return { launcher.launch("image/*") }
}

/** Reads [uri] upright, in software, no larger than [MAX_WORKING_EDGE]. */
private fun decode(context: Context, uri: Uri): RgbaImage? {
    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        // ImageDecoder applies the photo's EXIF orientation itself.
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > MAX_WORKING_EDGE) {
                val scale = MAX_WORKING_EDGE.toFloat() / longest
                decoder.setTargetSize(
                    max(1, (info.size.width * scale).toInt()),
                    max(1, (info.size.height * scale).toInt()),
                )
            }
        }
    } else {
        // API 26-27. Orientation is not applied here: a sideways photo opens sideways.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_WORKING_EDGE) sample *= 2
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val longest = max(decoded.width, decoded.height)
        if (longest <= MAX_WORKING_EDGE) decoded else {
            val scale = MAX_WORKING_EDGE.toFloat() / longest
            Bitmap.createScaledBitmap(decoded, max(1, (decoded.width * scale).toInt()), max(1, (decoded.height * scale).toInt()), true)
        }
    }
    val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
    val pixels = IntArray(argb.width * argb.height)
    // getPixels returns straight (un-premultiplied) ARGB, which is what the pipeline takes.
    argb.getPixels(pixels, 0, argb.width, 0, 0, argb.width, argb.height)
    return RgbaImage(argb.width, argb.height, pixels)
}

actual fun argbToImageBitmap(pixels: IntArray, width: Int, height: Int): ImageBitmap =
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()

/** Enable via adb shell setprop log.tag.StencilPerf DEBUG; disabled at the normal INFO level. */
internal actual fun stencilPerformanceEnabled(): Boolean =
    android.util.Log.isLoggable("StencilPerf", android.util.Log.DEBUG)

/** Numeric and categorical diagnostics only, in local Android logcat. */
internal actual fun reportStencilPerformance(record: String) {
    android.util.Log.d("StencilPerf", record)
}

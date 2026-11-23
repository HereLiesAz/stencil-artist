package com.hereliesaz.stencilartist.app

import android.app.Activity
import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@Composable
actual fun rememberFileSaver(): FileSaver {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val saver = remember { DocumentSaver(context, scope) }
    // The system's "save as" screen: the user picks where the file goes, and the app needs no
    // storage permission because it is only ever given that one document.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        saver.finish(if (result.resultCode == Activity.RESULT_OK) result.data else null)
    }
    saver.launch = { launcher.launch(it) }
    return saver
}

private class DocumentSaver(private val context: Context, private val scope: CoroutineScope) : FileSaver {
    var launch: (Intent) -> Unit = {}

    /** The file waiting for the user to choose where it goes, and who to tell. One at a time. */
    private var pending: Pair<ExportFile, (Boolean) -> Unit>? = null

    override fun save(file: ExportFile, onResult: (Boolean) -> Unit) {
        pending = file to onResult
        launch(
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(file.mimeType)
                .putExtra(Intent.EXTRA_TITLE, file.name)
        )
    }

    fun finish(chosen: Intent?) {
        val (file, onResult) = pending ?: return
        pending = null
        val uri = chosen?.data
        if (uri == null) {
            onResult(false)
            return
        }
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(file.bytes) } != null }
                    .getOrDefault(false)
            }
            onResult(written)
        }
    }
}

@Composable
actual fun rememberStencilPackSharer(): StencilPackSharer {
    val context = LocalContext.current
    return remember(context) {
        object : StencilPackSharer {
            override suspend fun share(file: ExportFile) {
                require(file.name.endsWith(".stencilpack")) { "Only StencilPack archives can be shared here" }
                val written = withContext(Dispatchers.IO) {
                    File(context.cacheDir, "stencil-shares").apply { mkdirs() }
                        .resolve("stencil-layers-${UUID.randomUUID()}.stencilpack")
                        .also { it.writeBytes(file.bytes) }
                }
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.stencilpack.fileprovider", written,
                )
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = file.mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("StencilPack", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "Send stencil layers"))
            }
        }
    }
}

actual fun encodePng(pixels: IntArray, width: Int, height: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    try {
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG encoding failed" }
        return output.toByteArray()
    } finally {
        // Bundling many sheets should not retain an extra native 1600px bitmap per PNG.
        bitmap.recycle()
    }
}

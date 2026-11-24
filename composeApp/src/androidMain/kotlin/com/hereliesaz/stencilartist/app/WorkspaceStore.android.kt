package com.hereliesaz.stencilartist.app

import android.util.AtomicFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val workspaceFileLock = Mutex()

@Composable
internal actual fun rememberWorkspaceStore(): WorkspaceStore {
    val context = LocalContext.current.applicationContext
    return remember(context) { AndroidWorkspaceStore(AtomicFile(File(context.noBackupFilesDir, "stencil-workspace-v1"))) }
}

/** No backup/cloud upload; AtomicFile retains the last complete checkpoint on interrupted writes. */
private class AndroidWorkspaceStore(private val file: AtomicFile) : WorkspaceStore {
    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        workspaceFileLock.withLock {
            try { file.readFully().decodeToString() }
            catch (missing: FileNotFoundException) {
                if (file.baseFile.exists()) throw missing else null
            }
        }
    }
    override suspend fun write(value: String) = withContext(Dispatchers.IO) {
        workspaceFileLock.withLock {
            val stream = file.startWrite()
            try { stream.write(value.encodeToByteArray()); file.finishWrite(stream) }
            catch (error: Exception) { file.failWrite(stream); throw error }
        }
    }
}

package com.hereliesaz.stencilartist.app

import androidx.compose.runtime.Composable

/** A single atomic, local-only checkpoint; failures are reported rather than silently dropped. */
internal interface WorkspaceStore {
    suspend fun read(): String?
    suspend fun write(value: String)
}

@Composable
internal expect fun rememberWorkspaceStore(): WorkspaceStore

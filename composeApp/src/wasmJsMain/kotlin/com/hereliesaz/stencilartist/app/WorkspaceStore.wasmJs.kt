@file:OptIn(ExperimentalWasmJsInterop::class)

package com.hereliesaz.stencilartist.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.ExperimentalWasmJsInterop

/** IndexedDB, not localStorage: a working-resolution image can exceed localStorage's entire quota. */
private fun checkpointTransaction(write: Boolean, value: String, success: (String?) -> Unit,
    failure: (String) -> Unit): Unit = js(
    """{
        let finished = false;
        let db;
        const fail = error => {
            if (finished) return;
            finished = true;
            if (db) db.close();
            failure(String(error || 'Workspace storage failed'));
        };
        try {
            const open = indexedDB.open('stencil-artist-workspace', 1);
            open.onupgradeneeded = () => open.result.createObjectStore('checkpoint');
            open.onerror = () => fail(open.error);
            open.onblocked = () => fail('Workspace database is blocked');
            open.onsuccess = () => {
                db = open.result;
                if (finished) { db.close(); return; }
                try {
                    const tx = db.transaction('checkpoint', write ? 'readwrite' : 'readonly');
                    const store = tx.objectStore('checkpoint');
                    const request = write ? store.put(value, 'active') : store.get('active');
                    let result = null;
                    request.onsuccess = () => { if (!write) result = request.result ?? null; };
                    tx.onabort = () => fail(tx.error);
                    tx.onerror = () => fail(tx.error);
                    tx.oncomplete = () => {
                        if (finished) return;
                        finished = true;
                        db.close();
                        success(result);
                    };
                } catch (e) { fail(e); }
            };
        } catch (e) { fail(e); }
    }"""
)

private object BrowserWorkspaceStore : WorkspaceStore {
    private suspend fun transact(write: Boolean, value: String): String? = suspendCoroutine { continuation ->
        checkpointTransaction(write, value, { continuation.resume(it) },
            { continuation.resumeWithException(IllegalStateException(it)) })
    }
    override suspend fun read(): String? = transact(false, "")
    override suspend fun write(value: String) { transact(true, value) }
}

@Composable
internal actual fun rememberWorkspaceStore(): WorkspaceStore = remember { BrowserWorkspaceStore }

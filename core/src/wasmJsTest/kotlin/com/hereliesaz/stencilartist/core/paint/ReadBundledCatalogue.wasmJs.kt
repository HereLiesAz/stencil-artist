@file:OptIn(ExperimentalWasmJsInterop::class)

package com.hereliesaz.stencilartist.core.paint

import kotlin.js.ExperimentalWasmJsInterop

/** Looks for [relative] in the working directory of the Node process and each directory above it. */
private fun readUpwards(relative: String): String? = js(
    """(() => {
        const fs = process.getBuiltinModule('fs');
        const path = process.getBuiltinModule('path');
        let dir = process.cwd();
        for (let i = 0; i < 16; i++) {
            const file = path.join(dir, relative);
            if (fs.existsSync(file)) return fs.readFileSync(file, 'utf8');
            const parent = path.dirname(dir);
            if (parent === dir) break;
            dir = parent;
        }
        return null;
    })()"""
)

internal actual fun readBundledCatalogue(): String? = readUpwards("models/${PaintCatalogue.FILE_NAME}")

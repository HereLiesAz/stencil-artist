package com.hereliesaz.stencilartist.core.paint

import java.io.File

internal actual fun readBundledCatalogue(): String? {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
        val file = File(dir, "models/${PaintCatalogue.FILE_NAME}")
        if (file.isFile) return file.readText(Charsets.UTF_8)
        dir = dir.parentFile
    }
    return null
}

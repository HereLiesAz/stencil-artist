package com.hereliesaz.stencilartist.core

/** Deterministic JSON metadata for the public StencilPack v1 interchange format. */
object StencilPackManifest {
    fun encode(separation: Separation): String {
        val layers = separation.sheets.mapIndexed { index, sheet ->
            val digits = maxOf(3, separation.sheets.size.toString().length)
            val filename = "stencil-sheet-${(index + 1).toString().padStart(digits, '0')}.png"
            val color = sheet.color.toUInt().toString(16).padStart(8, '0').uppercase()
            """{"name":"Sheet ${index + 1}","file":"$filename","color":"#$color","visible":true}"""
        }.joinToString(",")
        return buildString {
            append("""{"format":"com.hereliesaz.stencilpack","version":1,"width":""")
            append(separation.width)
            append(""","height":""")
            append(separation.height)
            append(""","order":"bottom-to-top","coverage":"overpaint","composite":"stencil-composite.png","layers":[""")
            append(layers)
            append("]}")
        }
    }
}

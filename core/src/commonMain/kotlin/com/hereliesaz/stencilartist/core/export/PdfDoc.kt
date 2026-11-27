package com.hereliesaz.stencilartist.core.export

/**
 * Collects objects, then writes them as PDF 1.4 with a correct cross-reference table: uncompressed
 * streams, Helvetica (F1) and Helvetica-Bold (F2), the same bytes on every target. The file is 7-bit ASCII
 * (accented letters are octal escapes, see [PdfText]), so a string index is a byte offset.
 */
internal class PdfDoc {
    private val bodies = ArrayList<String>()
    private val pages = ArrayList<Int>()
    private val pageLabels = ArrayList<String>()
    private val alphas = LinkedHashMap<Int, Pair<String, Int>>()
    private val catalog = reserve()
    private val pageTree = reserve()
    private val font = add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
    private val fontBold = add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>")
    private val resources = reserve()
    private val info = reserve()

    /** How many pages have been added. */
    val pageCount: Int get() = pages.size

    private fun reserve(): Int {
        bodies.add("")
        return bodies.size
    }

    private fun add(body: String): Int {
        bodies.add(body)
        return bodies.size
    }

    /** The name of the graphics state for [argb]'s opacity, or null when it is opaque. */
    fun alphaState(argb: Int): String? {
        val a = argb ushr 24
        if (a == 255) return null
        return alphas.getOrPut(a) {
            val v = Decimal.format(a / 255.0, 4)
            "GA$a" to add("<< /Type /ExtGState /ca $v /CA $v >>")
        }.first
    }

    /** Adds a page of [widthPt] x [heightPt] drawn by [content], with its non-printing viewer [label]. */
    fun addPage(widthPt: Double, heightPt: Double, content: String, label: String) {
        pageLabels.add(label)
        val contentObj = add("<< /Length ${content.length} >>\nstream\n$content\nendstream")
        val box = "[0 0 ${Decimal.format(widthPt, 3)} ${Decimal.format(heightPt, 3)}]"
        pages.add(add("<< /Type /Page /Parent $pageTree 0 R /MediaBox $box /Contents $contentObj 0 R /Resources $resources 0 R >>"))
    }

    /** The finished file. */
    fun finish(title: String): ByteArray {
        // A prefix-only page label is the exact identifier, without an appended page number.
        // It identifies a tile even when its retained stock cannot safely fit printed text.
        val labels = pageLabels.mapIndexed { index, label -> "$index << /P (${PdfText.escape(label)}) >>" }.joinToString(" ")
        bodies[catalog - 1] = "<< /Type /Catalog /Pages $pageTree 0 R /PageLabels << /Nums [$labels] >> >>"
        bodies[pageTree - 1] = "<< /Type /Pages /Kids [${pages.joinToString(" ") { "$it 0 R" }}] /Count ${pages.size} >>"
        val gs = if (alphas.isEmpty()) "" else " /ExtGState << ${alphas.values.joinToString(" ") { "/${it.first} ${it.second} 0 R" }} >>"
        bodies[resources - 1] = "<< /ProcSet [/PDF /Text] /Font << /F1 $font 0 R /F2 $fontBold 0 R >>$gs >>"
        bodies[info - 1] = "<< /Title (${title.filter { it.code in 32..126 && it != '(' && it != ')' && it != '\\' }}) /Producer (Stencil Artist) >>"

        val sb = StringBuilder("%PDF-1.4\n")
        val offsets = IntArray(bodies.size)
        for ((i, body) in bodies.withIndex()) {
            offsets[i] = sb.length
            sb.append(i + 1).append(" 0 obj\n").append(body).append("\nendobj\n")
        }
        val xref = sb.length
        sb.append("xref\n0 ${bodies.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) sb.append(o.toString().padStart(10, '0')).append(" 00000 n \n")
        sb.append("trailer\n<< /Size ${bodies.size + 1} /Root $catalog 0 R /Info $info 0 R >>\nstartxref\n$xref\n%%EOF\n")
        val text = sb.toString()
        return ByteArray(text.length) { text[it].code.toByte() }
    }
}

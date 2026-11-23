package com.hereliesaz.stencilartist.core.paint

/**
 * What the camera scanners do with a number a decoder has read, before anything else sees it.
 *
 * A mis-read is worse than no read: a wrong number could add the wrong can to the user's selection and
 * they would not notice. So every read goes through [GtinCheck], and a number whose check digit is wrong
 * is dropped, never reported. The scanner then simply sees the next frame.
 */
object BarcodeScan {
    /**
     * The number to report for [raw] as read by a decoder, or null if it is not a valid retail barcode.
     *
     * A good UPC-A (12 digits) comes back as its EAN-13 form (a leading zero), so the same can scanned
     * by a decoder that says UPC-A and by one that says EAN-13 is the same string. EAN-13 and EAN-8 come
     * back as they were read. Set [upcE] when the decoder says the symbol is UPC-E, the short form some
     * small cans print: its 8 digits are expanded to the UPC-A they stand for first, because its check
     * digit belongs to the long form, not to the 8 digits as such.
     */
    fun accept(raw: String, upcE: Boolean = false): String? {
        val digits = raw.trim()
        if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return null
        val full = if (upcE && digits.length == 8) expandUpcE(digits) ?: return null else digits
        if (!GtinCheck.isValid(full)) return null
        return if (full.length == 12) "0$full" else full
    }

    /**
     * The 12-digit UPC-A that the 8-digit UPC-E [upcE] stands for, or null if it is not 8 digits or does
     * not start with number system 0 or 1 (the only two UPC-E uses). The check digit is carried over
     * unchanged; whether it is right is for [GtinCheck] to say about the result.
     *
     * The six middle digits are the manufacturer and product numbers with zeros squeezed out, and the
     * last of the six says where the zeros go: 0, 1 or 2 put three zeros after the third digit
     * (as the digit's own value stands in), 3 puts five zeros after the third, 4 five after the fourth,
     * and 5 to 9 put four zeros before that last digit.
     */
    fun expandUpcE(upcE: String): String? {
        if (upcE.length != 8 || upcE.any { it !in '0'..'9' }) return null
        val system = upcE[0]
        if (system != '0' && system != '1') return null
        val d = upcE.substring(1, 7)
        val check = upcE[7]
        val body = when (d[5]) {
            '0', '1', '2' -> "${d.substring(0, 2)}${d[5]}0000${d.substring(2, 5)}"
            '3' -> "${d.substring(0, 3)}00000${d.substring(3, 5)}"
            '4' -> "${d.substring(0, 4)}00000${d[4]}"
            else -> "${d.substring(0, 5)}0000${d[5]}"
        }
        return "$system$body$check"
    }
}

/**
 * Stops a camera that sees the same barcode in every frame from reporting it in every frame.
 *
 * A number is reported when it differs from the last one reported, or when the last report of it is at
 * least [windowMs] old. Every sighting of the number just reported restarts its window, so a can held in
 * view stays one report; it is reported again only after it has been out of sight for the whole window.
 * Time is passed in, not read, so the rule can be tested and each platform uses its own clock.
 */
class ScanDebouncer(private val windowMs: Long = 2000L) {
    private var lastCode: String? = null
    private var lastSeenMs = 0L

    /** True if [code], seen at [nowMs] (any steady millisecond clock), is a new report and should go on. */
    fun accept(code: String, nowMs: Long): Boolean {
        val repeat = code == lastCode && nowMs - lastSeenMs < windowMs
        lastCode = code
        lastSeenMs = nowMs
        return !repeat
    }

    /** Forgets the last number, so the next sighting of anything is reported. Used when a scan is started again. */
    fun reset() {
        lastCode = null
    }
}

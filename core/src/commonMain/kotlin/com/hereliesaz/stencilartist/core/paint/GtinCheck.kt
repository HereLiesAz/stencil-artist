package com.hereliesaz.stencilartist.core.paint

/**
 * Barcode numbers (GS1 GTINs: UPC-A is 12 digits, EAN-13 is 13, EAN-8 is 8, GTIN-14 is 14).
 *
 * The last digit of every one is a check digit: the others, weighted 3, 1, 3, 1... starting with the
 * digit next to the check digit and going left, are summed, and the check digit is what brings the
 * sum up to a multiple of ten. A mistyped or mis-scanned number nearly always fails it, so a scanner
 * can reject a bad read before looking anything up.
 *
 * A UPC-A is an EAN-13 with a leading zero, and either is a GTIN-14 with leading zeros. [canonical]
 * pads every number to 14 digits, so the same product is the same string however it was printed.
 */
object GtinCheck {
    /** The check digit that belongs after [body], the number without its last digit. */
    fun checkDigit(body: String): Int {
        var sum = 0
        var weight = 3
        for (i in body.length - 1 downTo 0) {
            val c = body[i]
            require(c in '0'..'9') { "a barcode is digits only, got '$c'" }
            sum += (c - '0') * weight
            weight = 4 - weight
        }
        return (10 - sum % 10) % 10
    }

    /** True if [gtin] is 8, 12, 13 or 14 digits and its last digit is the right check digit. */
    fun isValid(gtin: String): Boolean {
        if (gtin.length != 8 && gtin.length != 12 && gtin.length != 13 && gtin.length != 14) return false
        for (c in gtin) if (c !in '0'..'9') return false
        return checkDigit(gtin.substring(0, gtin.length - 1)) == gtin[gtin.length - 1] - '0'
    }

    /**
     * [gtin] as 14 digits, padded with leading zeros, so a UPC-A and its EAN-13 form give the same
     * string; or null if it is not a valid barcode. Spaces and hyphens around and inside the digits
     * (as people type and some scanners report) are ignored.
     */
    fun canonical(gtin: String): String? {
        val digits = if (gtin.all { it in '0'..'9' }) gtin else gtin.filter { it != ' ' && it != '-' }
        if (!isValid(digits)) return null
        return digits.padStart(14, '0')
    }
}

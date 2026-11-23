package com.hereliesaz.stencilartist.core.paint

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BarcodeScanTest {
    @Test
    fun goodEan13PassesUnchanged() {
        assertEquals("4250397612911", BarcodeScan.accept("4250397612911"))
    }

    @Test
    fun upcAIsReportedAsItsEan13() {
        assertEquals("0020066172572", BarcodeScan.accept("020066172572"))
        assertEquals(BarcodeScan.accept("0020066172572"), BarcodeScan.accept("020066172572"))
    }

    @Test
    fun goodEan8PassesUnchanged() {
        assertEquals("96385074", BarcodeScan.accept("96385074"))
    }

    @Test
    fun badCheckDigitIsRejected() {
        assertNull(BarcodeScan.accept("4250397612912"))
        assertNull(BarcodeScan.accept("020066172573"))
        assertNull(BarcodeScan.accept("96385075"))
    }

    @Test
    fun everySingleDigitMisreadIsRejected() {
        val good = "4250397612911"
        for (i in good.indices) for (d in '0'..'9') {
            if (d == good[i]) continue
            val bad = good.substring(0, i) + d + good.substring(i + 1)
            assertNull(BarcodeScan.accept(bad), "a single wrong digit at $i must fail the check: $bad")
        }
    }

    @Test
    fun nonNumbersAndWrongLengthsAreRejected() {
        for (raw in listOf("", "abc", "4250397612 911", "425039761291", "12345", "42503976129111111")) {
            assertNull(BarcodeScan.accept(raw), raw)
        }
    }

    @Test
    fun surroundingWhitespaceIsIgnored() {
        assertEquals("4250397612911", BarcodeScan.accept(" 4250397612911\n"))
    }

    @Test
    fun upcEExpandsByEachLastDigitRule() {
        // One per rule of the UPC-E specification; the first is the specification's own example.
        for ((upcE, upcA) in listOf(
            "04252614" to "042100005264", // ends 1 (the specification's example)
            "01234505" to "012000003455", // ends 0
            "01234514" to "012100003454", // ends 1
            "01234523" to "012200003453", // ends 2
            "01234531" to "012300000451", // ends 3
            "01234543" to "012340000053", // ends 4
            "01234565" to "012345000065", // ends 5 (6 to 9 alike)
            "01234596" to "012345000096", // ends 9
        )) {
            assertEquals(upcA, BarcodeScan.expandUpcE(upcE), upcE)
            assertTrue(GtinCheck.isValid(upcA), "the expansion of $upcE keeps a valid check digit")
        }
    }

    @Test
    fun upcEIsAcceptedAsTheUpcAItStandsFor() {
        assertEquals("0042100005264", BarcodeScan.accept("04252614", upcE = true))
        assertEquals("0012345000065", BarcodeScan.accept("01234565", upcE = true))
    }

    @Test
    fun upcEWithAWrongCheckDigitOrSystemIsRejected() {
        assertNull(BarcodeScan.accept("04252615", upcE = true))
        assertNull(BarcodeScan.expandUpcE("24252614"))
        assertNull(BarcodeScan.expandUpcE("0425261"))
    }

    @Test
    fun upcEFlagDoesNotBreakOtherLengths() {
        assertEquals("4250397612911", BarcodeScan.accept("4250397612911", upcE = true))
    }

    @Test
    fun debouncerIgnoresTheSameCodeInsideTheWindow() {
        val d = ScanDebouncer(2000)
        assertTrue(d.accept("A", 0))
        assertFalse(d.accept("A", 100))
        assertFalse(d.accept("A", 1999))
    }

    @Test
    fun debouncerReportsADifferentCodeAtOnce() {
        val d = ScanDebouncer(2000)
        assertTrue(d.accept("A", 0))
        assertTrue(d.accept("B", 10))
        assertTrue(d.accept("A", 20))
    }

    @Test
    fun debouncerWindowSlidesWhileACodeStaysInView() {
        val d = ScanDebouncer(2000)
        assertTrue(d.accept("A", 0))
        for (t in 500L..9500L step 500L) assertFalse(d.accept("A", t), "still in view at $t")
        assertTrue(d.accept("A", 11500), "reported again after a whole window out of sight")
    }

    @Test
    fun debouncerReportsAgainAfterTheWindowAndAfterReset() {
        val d = ScanDebouncer(2000)
        assertTrue(d.accept("A", 0))
        assertTrue(d.accept("A", 2000))
        assertFalse(d.accept("A", 2100))
        d.reset()
        assertTrue(d.accept("A", 2200))
    }
}

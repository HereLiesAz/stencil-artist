package com.hereliesaz.stencilartist.core.paint

import com.hereliesaz.stencilartist.core.ColorParams
import com.hereliesaz.stencilartist.core.ColorSeparator
import com.hereliesaz.stencilartist.core.Oklab
import com.hereliesaz.stencilartist.core.RgbaImage

/**
 * The spray paint catalogue: every can the app knows, with search, barcode lookup and colour
 * matching. Pure and immutable; build one with [parse] from the bundled `paints.tsv`.
 *
 * File format (written by tools/catalogue/build.py): UTF-8 lines, fields separated by a tab.
 *
 * ~~~
 * #stencil-artist-paints<TAB>1
 * #brand<TAB>id<TAB>name<TAB>source hosts, comma separated
 * #line<TAB>id<TAB>brand id<TAB>name
 * line id<TAB>name<TAB>finish<TAB>code<TAB>hex<TAB>hex source<TAB>gtin
 * ~~~
 *
 * Brand and line names are written once, in the header, and a row names its line by number, which
 * keeps the file small and parsing cheap. An empty field is a missing value; `hex` is six hex digits
 * without `#`; `hex source` is `o` (official) or `s` (sampled). See `models/paints.NOTICE.txt` for
 * where the data came from and why it may not be shipped publicly yet.
 *
 * What a few terms in the API mean:
 *  - [allowed] (in [search], [bestCans], [pickFor]) restricts the cans to some brands or lines. An
 *    entry is a brand name, a line name, or `brand|line` (to tell apart lines of the same name in
 *    different brands), exactly as [brands] and [lines] list them. An empty set means no restriction.
 *  - A can's "equivalents" are the other cans of exactly the same colour value.
 */
class PaintCatalogue private constructor(
    /** Every can, in file order (the build sorts by brand, line, name). */
    val cans: List<Can>,
    private val brandNames: List<String>,
    private val lineNames: List<List<String>>,
    /** For each brand, the hosts its data came from. */
    val sources: Map<String, List<String>>,
) {
    /**
     * Apply device-local user swatches without altering the bundled, provenance-tracked catalogue.
     * Every index (key, GTIN, search, RGB, equivalence and matching) is rebuilt from the resulting
     * cans. Call on the unmodified catalogue when clearing an override.
     */
    fun withUserColours(colours: UserCanColours): PaintCatalogue =
        if (colours.size == 0) this
        else PaintCatalogue(cans.map(colours::applyTo), brandNames, lineNames, sources)

    /** The brands, in file order. */
    val brands: List<String> get() = brandNames

    /** The lines of [brand], in file order; empty for an unknown brand. */
    fun lines(brand: String): List<String> {
        val i = brandNames.indexOf(brand)
        return if (i < 0) emptyList() else lineNames[i]
    }

    private val brandIndex: Map<String, List<Can>> by lazy(LazyThreadSafetyMode.NONE) { cans.groupBy { it.brand } }
    private val lineIndex: Map<String, List<Can>> by lazy(LazyThreadSafetyMode.NONE) { cans.groupBy { it.brand + "|" + it.line } }

    /** The cans of [brand], or of just its [line] if given; empty if there are none. */
    fun cans(brand: String, line: String? = null): List<Can> =
        (if (line == null) brandIndex[brand] else lineIndex["$brand|$line"]) ?: emptyList()

    private val keyIndex: Map<String, Can> by lazy(LazyThreadSafetyMode.NONE) {
        val map = HashMap<String, Can>(cans.size * 2)
        for (can in cans) if (!map.containsKey(can.canKey)) map[can.canKey] = can
        map
    }

    private val gtinIndex: Map<String, Can> by lazy(LazyThreadSafetyMode.NONE) {
        val map = HashMap<String, Can>(cans.size * 2)
        for (can in cans) {
            val gtin = can.gtin ?: continue
            val key = gtin.padStart(14, '0')
            if (!map.containsKey(key)) map[key] = can
        }
        map
    }

    private val codeIndex: Map<String, List<Can>> by lazy(LazyThreadSafetyMode.NONE) {
        val map = HashMap<String, MutableList<Can>>()
        for (can in cans) {
            val code = can.code ?: continue
            map.getOrPut(PaintText.compact(code)) { ArrayList(1) }.add(can)
        }
        map
    }

    private val colourIndex: Map<Int, List<Can>> by lazy(LazyThreadSafetyMode.NONE) {
        val map = HashMap<Int, MutableList<Can>>()
        for (can in cans) can.rgb?.let { map.getOrPut(it) { ArrayList(1) }.add(can) }
        map
    }

    /** The can with [canKey] [key], or null: a key of another catalogue version may no longer exist. */
    fun byKey(key: String): Can? = keyIndex[key]

    /**
     * The can with barcode [gtin], or null if there is none or [gtin] is not a valid barcode (see
     * [GtinCheck]). A UPC-A and its EAN-13 form (a leading zero) are the same barcode. Should two
     * cans share a barcode (one product listed under two lines) the first in file order is found.
     */
    fun byGtin(gtin: String): Can? = GtinCheck.canonical(gtin)?.let { gtinIndex[it] }

    /**
     * The can a scanned [gtin] means: the can the user taught for it in [user] if it still exists
     * in this catalogue, else the catalogue's own [byGtin]. Null if neither knows it or [gtin] is
     * invalid.
     */
    fun resolve(gtin: String, user: UserBarcodes = UserBarcodes.EMPTY): Can? {
        user.keyFor(gtin)?.let { key -> keyIndex[key]?.let { return it } }
        return byGtin(gtin)
    }

    /** The cans whose code is [code], ignoring case, accents and punctuation ("RV-252" is "rv 252"). */
    fun byCode(code: String): List<Can> = codeIndex[PaintText.compact(code)] ?: emptyList()

    /** The cans of exactly the colour [rgb] (opaque ARGB), in file order. */
    fun withColour(rgb: Int): List<Can> = colourIndex[rgb or (0xFF shl 24)] ?: emptyList()

    /** The other cans of exactly [can]'s colour; empty when it has no colour value. */
    fun equivalents(can: Can): List<Can> = can.rgb?.let { rgb -> colourIndex[rgb]?.filter { it != can } } ?: emptyList()

    // --- Search ------------------------------------------------------------------------------------------

    /** The text of every can in the form [search] compares, built on first use. */
    private class SearchRows(val name: Array<String>, val code: Array<String>, val haystack: Array<String>)

    private val rows: SearchRows by lazy(LazyThreadSafetyMode.NONE) {
        val name = Array(cans.size) { PaintText.normalise(cans[it].name) }
        val code = Array(cans.size) { cans[it].code?.let(PaintText::compact).orEmpty() }
        val haystack = Array(cans.size) {
            val can = cans[it]
            " " + PaintText.normalise(can.brand + " " + can.line + " " + can.name + " " + can.code.orEmpty()) + " "
        }
        SearchRows(name, code, haystack)
    }

    /**
     * The cans matching [query], best first, at most [limit]. Case, accents and punctuation are
     * ignored everywhere, so a code matches however it is written: "rv 252", "RV-252" and "rv252" are
     * the same query. The query is looked for in the name, the code, the brand and the line, and the
     * matches are ranked:
     *  1. the query is exactly the can's code;
     *  2. the code starts with the query;
     *  3. the name starts with the query;
     *  4. every word of the query is a whole word of the brand, line, name or code;
     *  5. the query (or each of its words) appears somewhere inside them, or inside the code.
     *
     * Within a rank, file order. A blank query matches nothing.
     */
    fun search(query: String, limit: Int = 50, allowed: Set<String> = emptySet()): List<Can> {
        require(limit >= 0) { "limit cannot be negative, got $limit" }
        val q = PaintText.normalise(query)
        if (q.isEmpty() || limit == 0) return emptyList()
        val compact = q.replace(" ", "")
        val words = q.split(' ')
        val wholeWords = words.map { " $it " }
        val r = rows
        val tiers = Array(5) { ArrayList<Can>() }
        for (i in cans.indices) {
            val can = cans[i]
            if (!isAllowed(can, allowed)) continue
            val code = r.code[i]
            val haystack = r.haystack[i]
            val tier = when {
                code.isNotEmpty() && code == compact -> 0
                code.isNotEmpty() && code.startsWith(compact) -> 1
                r.name[i].startsWith(q) -> 2
                wholeWords.all { haystack.contains(it) } -> 3
                haystack.contains(q) || words.all { haystack.contains(it) } ||
                    (code.isNotEmpty() && code.contains(compact)) -> 4
                else -> continue
            }
            tiers[tier].add(can)
        }
        val out = ArrayList<Can>(minOf(limit, 64))
        for (tier in tiers) {
            for (can in tier) {
                if (out.size == limit) return out
                out.add(can)
            }
        }
        return out
    }

    private fun isAllowed(can: Can, allowed: Set<String>): Boolean =
        allowed.isEmpty() || can.brand in allowed || can.line in allowed || (can.brand + "|" + can.line) in allowed

    // --- Matching an image's colours to cans -------------------------------------------------------------

    /** The cans of one colour value among a set of candidates, in file order. */
    private class ColourGroup(val rgb: Int, val cans: MutableList<Can>)

    /**
     * For each of the colours of a clustered colour separation (the app passes the palette that
     * [ColorSeparator] found), the can to paint it with, as [PaintLayer]s in the order of
     * [separationColours]. At most the first [n] colours are used.
     *
     * The algorithm:
     *  1. The allowed cans that have a colour value are grouped by value; each group is one
     *     candidate, so no two layers can end up with the same colour (the colour separation needs
     *     them all different), and the group's cans, in file order, become the layer's [PaintLayer.cans]
     *     (the first being the pick, the rest its equivalents).
     *  2. Every colour wants its nearest candidate in Oklab. Of all the colours still without one,
     *     the (colour, candidate) pair with the smallest distance anywhere is settled first: the
     *     colour that is best served takes its can, and the candidate is then out. A colour whose
     *     nearest candidate was just taken looks again for its nearest of those that remain. This
     *     goes on until every colour has one. A tie goes to the earlier colour, then the earlier
     *     candidate in file order, so the result is the same on every run and on every platform.
     *
     * Settling the best fits first keeps a colour with a near-perfect can from losing it to a colour
     * that merely wanted it too, which taking the colours in list order would allow.
     *
     * If fewer distinct candidates exist than colours, the colours left over are dropped: the result
     * is shorter than the input. A colour with no allowed can at all gives an empty list.
     */
    fun bestCans(
        separationColours: List<Int>,
        allowed: Set<String> = emptySet(),
        n: Int = separationColours.size,
    ): List<PaintLayer> {
        require(n in 0..separationColours.size) { "n must be 0..${separationColours.size}, got $n" }
        val targets = separationColours.subList(0, n)
        val groups = LinkedHashMap<Int, ColourGroup>()
        for (can in cans) {
            val rgb = can.rgb ?: continue
            if (!isAllowed(can, allowed)) continue
            groups.getOrPut(rgb) { ColourGroup(rgb, ArrayList(1)) }.cans.add(can)
        }
        val candidates = groups.values.toList()
        val g = candidates.size
        val t = targets.size
        if (g == 0 || t == 0) return emptyList()

        val lab = DoubleArray(3)
        val candidateLab = DoubleArray(g * 3)
        for ((i, group) in candidates.withIndex()) {
            Oklab.fromRgb((group.rgb shr 16) and 0xFF, (group.rgb shr 8) and 0xFF, group.rgb and 0xFF, lab)
            lab.copyInto(candidateLab, i * 3)
        }
        val targetLab = DoubleArray(t * 3)
        for ((i, rgb) in targets.withIndex()) {
            Oklab.fromRgb((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, lab)
            lab.copyInto(targetLab, i * 3)
        }

        val taken = BooleanArray(g)
        val assigned = IntArray(t) { -1 }
        val want = IntArray(t)
        val wantDistance = DoubleArray(t)
        fun look(target: Int) {
            var best = -1
            var bestD = Double.MAX_VALUE
            for (c in 0 until g) {
                if (taken[c]) continue
                val d = PaintColour.square(targetLab[target * 3] - candidateLab[c * 3]) +
                    PaintColour.square(targetLab[target * 3 + 1] - candidateLab[c * 3 + 1]) +
                    PaintColour.square(targetLab[target * 3 + 2] - candidateLab[c * 3 + 2])
                if (d < bestD) {
                    best = c
                    bestD = d
                }
            }
            want[target] = best
            wantDistance[target] = bestD
        }
        for (i in 0 until t) look(i)
        repeat(minOf(t, g)) {
            var pick = -1
            for (i in 0 until t) {
                if (assigned[i] >= 0) continue
                if (pick < 0 || wantDistance[i] < wantDistance[pick]) pick = i
            }
            val chosen = want[pick]
            assigned[pick] = chosen
            taken[chosen] = true
            for (i in 0 until t) if (assigned[i] < 0 && want[i] == chosen) look(i)
        }
        val layers = ArrayList<PaintLayer>(t)
        for (i in 0 until t) {
            val c = assigned[i]
            if (c >= 0) layers.add(PaintLayer(candidates[c].rgb, candidates[c].cans, targets[i] or (0xFF shl 24)))
        }
        return layers
    }

    /**
     * The cans to paint [image] with in [n] colours: the image is separated into [n] sheets by
     * [ColorSeparator] (clustering its colours as usual), and the colours it finds are matched to
     * cans by [bestCans]. The layers come in the separator's paint order. An image with fewer
     * distinct colours than [n] gives fewer layers; an image with nothing to separate gives none.
     *
     * Separate again with `ColorParams(sheets = layers.size, colors = layers.map { it.color })` to
     * make the sheets from those exact paints, then ask [unusedLayers] which of them the image
     * did not need.
     */
    fun pickFor(image: RgbaImage, n: Int, allowed: Set<String> = emptySet()): List<PaintLayer> {
        require(n in 1..ColorParams.MAX_SHEETS) { "a colour stencil has 1..${ColorParams.MAX_SHEETS} sheets, not $n" }
        val result = ColorSeparator.separate(image, ColorParams(sheets = n)) ?: return emptyList()
        return bestCans(result.separation.sheets.map { it.color }, allowed)
    }

    companion object {
        /** The file name of the bundled catalogue, in `models/` on the web and in the assets on Android. */
        const val FILE_NAME = "paints.tsv"

        /** A catalogue with no cans. */
        val EMPTY = PaintCatalogue(emptyList(), emptyList(), emptyList(), emptyMap())

        /**
         * Reads a catalogue from [text] (the format is on [PaintCatalogue]). Fast and light on
         * memory: the text is scanned once in place and only the strings that end up in a [Can] are
         * made; brand and line names are shared by all their cans.
         *
         * Throws [IllegalArgumentException] naming the line number when the text is not a catalogue:
         * a missing header, a row with the wrong number of fields, an unknown line, a bad hex digit,
         * or a barcode whose check digit is wrong.
         */
        fun parse(text: String): PaintCatalogue {
            val brandNames = ArrayList<String>()
            val brandLines = ArrayList<MutableList<String>>()
            val sources = LinkedHashMap<String, List<String>>()
            val lineNames = ArrayList<String>()
            val lineBrand = ArrayList<Int>()
            val cans = ArrayList<Can>(text.length / 40)

            var seenHeader = false
            var pos = 0
            var lineNumber = 0
            val end = text.length
            while (pos < end) {
                lineNumber++
                var stop = text.indexOf('\n', pos)
                if (stop < 0) stop = end
                var lineEnd = stop
                if (lineEnd > pos && text[lineEnd - 1] == '\r') lineEnd--
                val start = pos
                pos = stop + 1
                if (lineEnd == start) continue

                if (text[start] == '#') {
                    val fields = text.substring(start, lineEnd).split('\t')
                    when (fields[0]) {
                        "#stencil-artist-paints" -> {
                            require(fields.size == 2 && fields[1] == "1") {
                                "line $lineNumber: unsupported catalogue version '${fields.drop(1).joinToString("\t")}', expected 1"
                            }
                            seenHeader = true
                        }
                        "#brand" -> {
                            require(seenHeader) { "line $lineNumber: this is not a paint catalogue (no #stencil-artist-paints header)" }
                            require(fields.size == 4 && fields[1] == brandNames.size.toString() && fields[2].isNotEmpty()) {
                                "line $lineNumber: expected '#brand<TAB>${brandNames.size}<TAB>name<TAB>hosts'"
                            }
                            brandNames.add(fields[2])
                            brandLines.add(ArrayList())
                            sources[fields[2]] = if (fields[3].isEmpty()) emptyList() else fields[3].split(',')
                        }
                        "#line" -> {
                            require(seenHeader) { "line $lineNumber: this is not a paint catalogue (no #stencil-artist-paints header)" }
                            val brand = fields.getOrNull(2)?.toIntOrNull()
                            require(fields.size == 4 && fields[1] == lineNames.size.toString() &&
                                brand != null && brand in brandNames.indices && fields[3].isNotEmpty()) {
                                "line $lineNumber: expected '#line<TAB>${lineNames.size}<TAB>known brand id<TAB>name'"
                            }
                            lineNames.add(fields[3])
                            lineBrand.add(brand)
                            brandLines[brand].add(fields[3])
                        }
                        else -> throw IllegalArgumentException("line $lineNumber: unknown header '${fields[0]}'")
                    }
                    continue
                }

                require(seenHeader) { "line $lineNumber: this is not a paint catalogue (no #stencil-artist-paints header)" }
                // Seven fields: line id, name, finish, code, hex, hex source, gtin. Find the six tabs.
                val tabs = IntArray(6)
                var from = start
                for (k in 0 until 6) {
                    val tab = text.indexOf('\t', from)
                    require(tab in from until lineEnd) { "line $lineNumber: expected 7 fields" }
                    tabs[k] = tab
                    from = tab + 1
                }
                val extra = text.indexOf('\t', from)
                require(extra < 0 || extra >= lineEnd) { "line $lineNumber: expected 7 fields, found more" }

                var line = 0
                require(tabs[0] > start) { "line $lineNumber: missing line id" }
                for (i in start until tabs[0]) {
                    val d = text[i] - '0'
                    require(d in 0..9) { "line $lineNumber: line id is not a number" }
                    line = line * 10 + d
                    require(line < lineNames.size) { "line $lineNumber: unknown line id" }
                }
                require(line < lineNames.size) { "line $lineNumber: unknown line id" }

                require(tabs[1] > tabs[0] + 1) { "line $lineNumber: a can needs a name" }
                val name = text.substring(tabs[0] + 1, tabs[1])
                val finish = if (tabs[2] > tabs[1] + 1) text.substring(tabs[1] + 1, tabs[2]) else null
                val code = if (tabs[3] > tabs[2] + 1) text.substring(tabs[2] + 1, tabs[3]) else null

                var rgb: Int? = null
                var hexSource: HexSource? = null
                val hexLength = tabs[4] - tabs[3] - 1
                val sourceLength = tabs[5] - tabs[4] - 1
                if (hexLength == 0) {
                    require(sourceLength == 0) { "line $lineNumber: a hex source without a hex" }
                } else {
                    require(hexLength == 6) { "line $lineNumber: hex must be six digits" }
                    var value = 0
                    for (i in tabs[3] + 1 until tabs[4]) {
                        val digit = hexDigit(text[i])
                        require(digit >= 0) { "line $lineNumber: '${text[i]}' is not a hex digit" }
                        value = value * 16 + digit
                    }
                    rgb = value or (0xFF shl 24)
                    hexSource = when {
                        sourceLength == 1 && text[tabs[4] + 1] == 'o' -> HexSource.OFFICIAL
                        sourceLength == 1 && text[tabs[4] + 1] == 's' -> HexSource.SAMPLED
                        else -> throw IllegalArgumentException("line $lineNumber: hex source must be o or s")
                    }
                }

                val gtin = if (lineEnd > tabs[5] + 1) text.substring(tabs[5] + 1, lineEnd) else null
                require(gtin == null || GtinCheck.isValid(gtin)) { "line $lineNumber: '$gtin' is not a valid barcode" }

                val brand = lineBrand[line]
                cans.add(Can(brandNames[brand], lineNames[line], name, finish, code, rgb, hexSource, gtin))
            }
            require(seenHeader) { "this is not a paint catalogue (no #stencil-artist-paints header)" }
            return PaintCatalogue(cans, brandNames, brandLines, sources)
        }

        private fun hexDigit(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }
    }
}

package com.hereliesaz.stencilartist.core

/** The order colour sheets are sprayed in. The first sheet of the order is the base and covers the whole subject. */
enum class ColorOrder {
    /** Dark base with lighter colours over it when the subject is dark ([Tones.isDarkByArea]), light base with darker over it otherwise. */
    AUTO,

    /** The lightest colour is the base, then ever darker ones. Lightness is Oklab L of the sheet's colour. */
    LIGHT_TO_DARK,

    /** The darkest colour is the base, then ever lighter ones. */
    DARK_TO_LIGHT,

    /** The colour covering the most of the subject is the base, then smaller and smaller areas. */
    LARGEST_FIRST,
}

/**
 * The knobs of a colour separation.
 *
 * @property sheets how many sheets (colours) to cut, 1..[MAX_SHEETS]. An image with fewer distinct
 *   colours than this gets fewer sheets, never duplicates.
 * @property smoothing side of the median filter that simplifies the colour before it is clustered;
 *   odd. Larger merges small features into their surroundings. 1 turns it off.
 * @property closing side of the square that closes each sheet's edge, in pixels. Larger fills wider
 *   notches and gaps. 1 turns it off.
 * @property order which sheet is the base and how the rest follow.
 * @property colors the exact colours of the sheets (the user's own paints), or null to find them in
 *   the image. If given there must be exactly [sheets] of them, with distinct RGB. Each pixel then
 *   goes to the nearest of them in Oklab and no clustering runs; [order] still decides which of them
 *   is sprayed first. A colour no pixel is nearest to still gets its (empty) sheet.
 */
data class ColorParams(
    val sheets: Int = 4,
    val smoothing: Int = 9,
    val closing: Int = 5,
    val order: ColorOrder = ColorOrder.AUTO,
    val colors: List<Int>? = null,
) {
    init {
        require(sheets in 1..MAX_SHEETS) { "a colour stencil has 1..$MAX_SHEETS sheets, not $sheets" }
        require(smoothing >= 1 && smoothing % 2 == 1) { "smoothing must be odd and positive, got $smoothing" }
        require(closing >= 1) { "closing must be positive, got $closing" }
        require(colors == null || colors.size == sheets) { "expected $sheets colours, got ${colors?.size}" }
        require(colors == null || colors.map { it and 0xFFFFFF }.toSet().size == colors.size) {
            "the given colours must all differ"
        }
    }

    companion object {
        /**
         * The most sheets a colour separation will make. Every sheet is a full-size mask that the
         * preview, the PNG export and the vectoriser each build, and clustering time grows with the
         * count, so an absurd count would only exhaust a phone's memory or patience. A stencil of a
         * thousand paints is already far beyond anything that can be sprayed.
         */
        const val MAX_SHEETS = 1024
    }
}

/**
 * A colour separation. [dark] is the dark-or-light call made on the subject, and [order] is the
 * order actually used, with [ColorOrder.AUTO] resolved to one of the other three.
 */
class ColorResult(val separation: Separation, val dark: Boolean, val order: ColorOrder)

/**
 * Separates an image into sheets by COLOUR: each sheet is one flat paint colour, and spraying them
 * in order reproduces the picture in as many colours as there are sheets. It makes the same
 * [Separation] as [TonalSeparator], so nothing downstream knows or cares which made it.
 *
 * The steps, all deterministic (the same image gives the same sheets on every target):
 *  1. The subject is the silhouette [TonalSeparator] uses ([Subject.silhouette]): pixels with any
 *     alpha, gaps closed, holes filled.
 *  2. The colour is smoothed so shapes are cuttable. Each of R, G and B is median-filtered with
 *     [Raster.medianBlur] on the straight (not alpha-premultiplied) colour, which keeps a soft edge
 *     pixel at its true colour instead of a dark fringe. The cut-out's transparent pixels are first
 *     given the colour of the nearest subject pixel, so the filter does not drag the subject's
 *     border towards black. A per-channel median can make a colour the image lacks; it is only the
 *     input to clustering.
 *  3. The subject's colours go into a histogram of 5-bit-per-channel cells, each cell weighted by its
 *     pixel count and located at the mean colour of its pixels, and are clustered by weighted k-means
 *     in [Oklab]. The seeds are chosen greedily, each the cell with the greatest count times squared
 *     distance from the seeds so far (k-means++ without the dice), so no random numbers are
 *     involved and distinct colours get their own seed. Lloyd iterations run until the assignment
 *     stops changing or [MAX_ITERATIONS], and an emptied cluster is reseeded at the worst-served
 *     cell. Clustering cells, not pixels, keeps a 1600 px image fast. Colours that share one cell
 *     count as one colour; if the image has fewer distinct cells than sheets asked for, there are
 *     fewer sheets.
 *  4. Every pixel is assigned exactly to its nearest cluster centre, and each centre is then moved to
 *     the mean of its pixels. Its sheet colour is that centre converted back to sRGB, opaque. A
 *     cluster that ends up with no pixel is dropped, so the sheets are never duplicates or empty.
 *  5. The sheets are put in paint order ([ColorOrder]); a pixel's index is the position of its
 *     cluster in that order.
 *  6. Each sheet is made cuttable as in the tonal stencil. The mask `index >= s` is closed with
 *     [Raster.close] (done for all sheets in one grey-scale closing of the index, see [closedRanks],
 *     which gives the same masks) and every subject pixel inside the closed mask is given index `s`, taking the
 *     highest `s` that reaches it. Because the masks `index >= s` are nested (a pixel of a later
 *     sheet is in every earlier one's mask) and closing keeps nested masks nested, the closed mask
 *     of sheet `s` is exactly the mask of the finished separation: it nests, the base is the
 *     silhouette, and (for an odd kernel) a pixel only ever moves to a LATER sheet, never to an
 *     earlier one or out of the subject. Closing fills gaps narrower than the kernel in a sheet with that sheet's colour,
 *     so a speck of an earlier colour inside a later colour's notch is absorbed by the later one.
 *     Filled-in holes and closed gaps were never seen, so they stay on the base.
 */
object ColorSeparator {

    /** The most Lloyd iterations of the clustering. */
    const val MAX_ITERATIONS = 40

    /** Returns null when the image is too small or has no visible pixel to separate. */
    fun separate(image: RgbaImage, params: ColorParams = ColorParams()): ColorResult? {
        val w = image.width
        val h = image.height
        if (w < 2 || h < 2) return null
        val size = w * h

        val observed = BooleanArray(size) { image.pixels[it] ushr 24 != 0 }
        if (observed.none { it }) return null
        val silhouette = Subject.silhouette(observed, w, h)
        val dark = Tones.isDarkByArea(Subject.lumaHistogram(image))

        val planes = smoothedPlanes(image, observed, params.smoothing)

        // Sheet colours before ordering, and the cluster of every observed pixel.
        val cluster = IntArray(size) { -1 }
        val colors: List<Int>
        val area: IntArray
        val given = params.colors
        if (given != null) {
            val centres = Centres(DoubleArray(given.size), DoubleArray(given.size), DoubleArray(given.size))
            for ((i, c) in given.withIndex()) {
                val lab = Oklab.fromArgb(c)
                centres.l[i] = lab.l; centres.a[i] = lab.a; centres.b[i] = lab.b
            }
            area = assign(planes, observed, centres, cluster, null)
            colors = given
        } else {
            val centres = cluster(planes, observed, params.sheets)
            val sum = Array(3) { DoubleArray(centres.l.size) }
            val counts = assign(planes, observed, centres, cluster, sum)
            val keep = counts.indices.filter { counts[it] > 0 }
            // Each centre moves to the mean of the pixels it won, which for a flat region is its colour exactly.
            colors = keep.map { Oklab.toArgb(sum[0][it] / counts[it], sum[1][it] / counts[it], sum[2][it] / counts[it]) }
            val renumber = IntArray(counts.size) { -1 }
            for ((n, old) in keep.withIndex()) renumber[old] = n
            for (i in 0 until size) if (cluster[i] >= 0) cluster[i] = renumber[cluster[i]]
            area = IntArray(keep.size) { counts[keep[it]] }
        }

        val order = when (params.order) {
            ColorOrder.AUTO -> if (dark) ColorOrder.DARK_TO_LIGHT else ColorOrder.LIGHT_TO_DARK
            else -> params.order
        }
        val lightness = DoubleArray(colors.size) { Oklab.fromArgb(colors[it]).l }
        val ranked = colors.indices.sortedWith(
            when (order) {
                ColorOrder.LIGHT_TO_DARK -> compareBy<Int>({ -lightness[it] }, { -area[it] }, { it })
                ColorOrder.DARK_TO_LIGHT -> compareBy<Int>({ lightness[it] }, { -area[it] }, { it })
                else -> compareBy<Int>({ -area[it] }, { -lightness[it] }, { it })
            },
        )
        val position = IntArray(colors.size)
        for ((rank, id) in ranked.withIndex()) position[id] = rank
        val sheets = ranked.map { Sheet(colors[it]) }

        // A pixel is on the sheet of its cluster; an unseen pixel inside the silhouette is on the base.
        val rank = IntArray(size) { i ->
            when {
                cluster[i] >= 0 -> position[cluster[i]]
                silhouette[i] -> 0
                else -> Separation.NONE
            }
        }
        val index = if (params.closing > 1 && sheets.size > 1) closedRanks(rank, observed, w, h, params.closing) else rank
        return ColorResult(Separation(w, h, sheets, index), dark, order)
    }

    /**
     * Closes every sheet at once. The mask `rank >= s` closed with a square is the same as the
     * grey-scale closing of the rank image (a maximum filter, then a minimum filter, over the same
     * square) thresholded at `s`, because a maximum is at least `s` exactly when some pixel is, and a
     * minimum exactly when every pixel is. So two passes over the image close all sheets, however
     * many there are, and give the same masks as [Raster.close] run on each. A pixel that was never seen
     * keeps its rank, so it stays on the base. A seen pixel takes the highest sheet whose closed mask
     * holds it, and at least the base: an odd kernel never lowers a pixel, while an even one is not
     * symmetric about its pixel and may, as in the tonal stencil, drop one to an earlier sheet.
     */
    internal fun closedRanks(rank: IntArray, observed: BooleanArray, w: Int, h: Int, kernel: Int): IntArray {
        val before = kernel / 2
        val after = kernel - 1 - before
        val queue = IntArray(maxOf(w, h))
        val dilated = IntArray(rank.size)
        slide(rank, dilated, w, h, before, after, true, queue)
        val closed = IntArray(rank.size)
        slide(dilated, closed, w, h, before, after, false, queue)
        return IntArray(rank.size) { if (observed[it]) maxOf(closed[it], 0) else rank[it] }
    }

    /**
     * One separable pass: [dst] is the maximum (or, without [max], minimum) of [src] over the window
     * of `before` pixels back and `after` forward, along every row and then every column, with
     * pixels beyond the image edge simply absent. Each line is done with a monotone queue in
     * linear time; [queue] is scratch of at least the longer side.
     */
    private fun slide(src: IntArray, dst: IntArray, w: Int, h: Int, before: Int, after: Int, max: Boolean, queue: IntArray) {
        val across = IntArray(src.size)
        for (y in 0 until h) line(src, across, y * w, 1, w, before, after, max, queue)
        for (x in 0 until w) line(across, dst, x, w, h, before, after, max, queue)
    }

    private fun line(
        src: IntArray, dst: IntArray, start: Int, stride: Int, length: Int, before: Int, after: Int, max: Boolean, queue: IntArray,
    ) {
        var head = 0
        var tail = 0
        var next = 0
        for (i in 0 until length) {
            val last = minOf(length - 1, i + after)
            while (next <= last) {
                val v = src[start + next * stride]
                while (tail > head && (if (max) src[start + queue[tail - 1] * stride] <= v else src[start + queue[tail - 1] * stride] >= v)) tail--
                queue[tail++] = next++
            }
            while (queue[head] < i - before) head++
            dst[start + i * stride] = src[start + queue[head] * stride]
        }
    }

    /**
     * Three 8-bit colour planes of the subject, smoothed, in R, G, B order. Only the subject's
     * bounding box, grown by the filter's radius, is filtered: nothing outside it is read later, and
     * every pixel inside the box sees the same neighbours it would in the whole frame.
     */
    private fun smoothedPlanes(image: RgbaImage, observed: BooleanArray, smoothing: Int): Array<ByteArray> {
        val w = image.width
        val h = image.height
        var x0 = w
        var y0 = h
        var x1 = -1
        var y1 = -1
        for (i in observed.indices) {
            if (!observed[i]) continue
            val x = i % w
            val y = i / w
            if (x < x0) x0 = x
            if (x > x1) x1 = x
            if (y < y0) y0 = y
            if (y > y1) y1 = y
        }
        val reach = smoothing / 2
        val left = maxOf(0, x0 - reach)
        val top = maxOf(0, y0 - reach)
        val cw = minOf(w - 1, x1 + reach) - left + 1
        val ch = minOf(h - 1, y1 + reach) - top + 1
        val size = cw * ch
        val seen = BooleanArray(size) { observed[(top + it / cw) * w + left + it % cw] }
        val planes = Array(3) { ByteArray(size) }
        for (i in 0 until size) {
            if (!seen[i]) continue
            val p = image.pixels[(top + i / cw) * w + left + i % cw]
            planes[0][i] = (p shr 16).toByte()
            planes[1][i] = (p shr 8).toByte()
            planes[2][i] = p.toByte()
        }
        // Breadth-first from the subject, so every other pixel takes its nearest subject pixel's colour.
        val reached = seen.copyOf()
        val queue = IntArray(size)
        var head = 0
        var tail = 0
        for (i in 0 until size) if (seen[i]) queue[tail++] = i
        while (head < tail) {
            val i = queue[head++]
            val x = i % cw
            fun spread(to: Int) {
                if (reached[to]) return
                reached[to] = true
                for (plane in planes) plane[to] = plane[i]
                queue[tail++] = to
            }
            if (x > 0) spread(i - 1)
            if (x < cw - 1) spread(i + 1)
            if (i >= cw) spread(i - cw)
            if (i < size - cw) spread(i + cw)
        }
        return Array(3) { c ->
            val smooth = Raster.medianBlur(planes[c], cw, ch, smoothing)
            val full = ByteArray(w * h)
            for (row in 0 until ch) smooth.copyInto(full, (top + row) * w + left, row * cw, (row + 1) * cw)
            full
        }
    }

    /** Cluster centres in Oklab, struct-of-arrays. */
    private class Centres(val l: DoubleArray, val a: DoubleArray, val b: DoubleArray)

    /**
     * Assigns every observed pixel in [cluster] to its nearest centre, and returns how many pixels
     * each centre won. With [sum] set, adds each winner's Oklab colour to `sum[0..2][centre]`.
     */
    private fun assign(
        planes: Array<ByteArray>, observed: BooleanArray, centres: Centres, cluster: IntArray, sum: Array<DoubleArray>?,
    ): IntArray {
        val nearest = Nearest(centres.l, centres.a, centres.b)
        val counts = IntArray(centres.l.size)
        val lab = DoubleArray(3)
        var lastRgb = -1
        var last = 0
        for (i in cluster.indices) {
            if (!observed[i]) continue
            val rgb = ((planes[0][i].toInt() and 0xFF) shl 16) or ((planes[1][i].toInt() and 0xFF) shl 8) or (planes[2][i].toInt() and 0xFF)
            if (rgb != lastRgb) {
                Oklab.fromRgb(rgb shr 16, (rgb shr 8) and 0xFF, rgb and 0xFF, lab)
                last = nearest.of(lab[0], lab[1], lab[2])
                lastRgb = rgb
            }
            cluster[i] = last
            counts[last]++
            if (sum != null) {
                sum[0][last] += lab[0]; sum[1][last] += lab[1]; sum[2][last] += lab[2]
            }
        }
        return counts
    }

    /** Weighted k-means of the subject's colour histogram; at most [wanted] centres, fewer if the image has fewer colours. */
    private fun cluster(planes: Array<ByteArray>, observed: BooleanArray, wanted: Int): Centres {
        val cells = 1 shl 15
        val count = LongArray(cells)
        val sums = Array(3) { LongArray(cells) }
        for (i in observed.indices) {
            if (!observed[i]) continue
            val r = planes[0][i].toInt() and 0xFF
            val g = planes[1][i].toInt() and 0xFF
            val b = planes[2][i].toInt() and 0xFF
            val cell = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            count[cell]++
            sums[0][cell] += r.toLong(); sums[1][cell] += g.toLong(); sums[2][cell] += b.toLong()
        }
        val used = (0 until cells).filter { count[it] > 0 }
        val n = used.size
        val pl = DoubleArray(n)
        val pa = DoubleArray(n)
        val pb = DoubleArray(n)
        val weight = DoubleArray(n)
        val lab = DoubleArray(3)
        for ((j, cell) in used.withIndex()) {
            val c = count[cell]
            // The mean colour of the cell, rounded to a level (the colour of a flat region is exact).
            Oklab.fromRgb(((sums[0][cell] * 2 + c) / (2 * c)).toInt(), ((sums[1][cell] * 2 + c) / (2 * c)).toInt(), ((sums[2][cell] * 2 + c) / (2 * c)).toInt(), lab)
            pl[j] = lab[0]; pa[j] = lab[1]; pb[j] = lab[2]
            weight[j] = c.toDouble()
        }

        // Greedy seeding: the heaviest cell, then repeatedly the cell with the most count x distance^2.
        val nearestSeed = DoubleArray(n) { Double.MAX_VALUE }
        val seeds = ArrayList<Int>()
        fun addSeed(j: Int) {
            seeds += j
            for (k in 0 until n) {
                val d = square(pl[k] - pl[j]) + square(pa[k] - pa[j]) + square(pb[k] - pb[j])
                if (d < nearestSeed[k]) nearestSeed[k] = d
            }
        }
        var first = 0
        for (j in 1 until n) if (weight[j] > weight[first]) first = j
        addSeed(first)
        while (seeds.size < wanted) {
            var best = -1
            var score = 0.0
            for (j in 0 until n) {
                val s = weight[j] * nearestSeed[j]
                if (s > score) { best = j; score = s }
            }
            if (best < 0) break
            addSeed(best)
        }

        var k = seeds.size
        val centres = Centres(DoubleArray(k) { pl[seeds[it]] }, DoubleArray(k) { pa[seeds[it]] }, DoubleArray(k) { pb[seeds[it]] })
        val owner = IntArray(n) { -1 }
        val distance = DoubleArray(n)
        for (iteration in 0 until MAX_ITERATIONS) {
            val nearest = Nearest(centres.l, centres.a, centres.b)
            var changed = false
            for (j in 0 until n) {
                val c = nearest.of(pl[j], pa[j], pb[j])
                distance[j] = nearest.distance
                if (owner[j] != c) { owner[j] = c; changed = true }
            }
            if (!changed) break
            val total = DoubleArray(k)
            val sl = DoubleArray(k)
            val sa = DoubleArray(k)
            val sb = DoubleArray(k)
            for (j in 0 until n) {
                val c = owner[j]
                total[c] += weight[j]
                sl[c] += weight[j] * pl[j]; sa[c] += weight[j] * pa[j]; sb[c] += weight[j] * pb[j]
            }
            for (c in 0 until k) {
                if (total[c] > 0.0) {
                    centres.l[c] = sl[c] / total[c]; centres.a[c] = sa[c] / total[c]; centres.b[c] = sb[c] / total[c]
                }
            }
            // An emptied centre is reseeded at the cell its centre serves worst, so no sheet is lost to a bad start.
            for (c in 0 until k) {
                if (total[c] > 0.0) continue
                var best = -1
                var score = 0.0
                for (j in 0 until n) {
                    val s = weight[j] * distance[j]
                    if (s > score) { best = j; score = s }
                }
                if (best < 0) continue
                centres.l[c] = pl[best]; centres.a[c] = pa[best]; centres.b[c] = pb[best]
                distance[best] = 0.0
            }
        }
        k = centres.l.size
        return centres
    }

    private fun square(x: Double) = x * x

    /**
     * Nearest-centre search. Centres are kept sorted by lightness, and the search walks outwards from
     * the pixel's lightness and stops once the lightness gap alone is beyond the best distance, which
     * keeps a search among hundreds of centres short. A tie goes to the lowest centre number, so the
     * answer does not depend on the sort.
     */
    private class Nearest(private val l: DoubleArray, private val a: DoubleArray, private val b: DoubleArray) {
        private val ids = l.indices.sortedWith(compareBy<Int>({ l[it] }, { it })).toIntArray()
        private val sortedL = DoubleArray(ids.size) { l[ids[it]] }

        /** Squared distance to the centre last returned by [of]. */
        var distance = 0.0
            private set

        fun of(pl: Double, pa: Double, pb: Double): Int {
            val n = ids.size
            var lo = 0
            var hi = n
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (sortedL[mid] < pl) lo = mid + 1 else hi = mid
            }
            var up = lo
            var down = lo - 1
            var best = -1
            var bestD = Double.MAX_VALUE
            while (up < n || down >= 0) {
                val gapUp = if (up < n) sortedL[up] - pl else Double.MAX_VALUE
                val gapDown = if (down >= 0) pl - sortedL[down] else Double.MAX_VALUE
                val fromUp = gapUp <= gapDown
                val gap = if (fromUp) gapUp else gapDown
                if (best >= 0 && gap * gap > bestD) break
                val at = if (fromUp) up++ else down--
                val c = ids[at]
                val d = square(l[c] - pl) + square(a[c] - pa) + square(b[c] - pb)
                if (d < bestD || (d == bestD && c < best)) { bestD = d; best = c }
            }
            distance = bestD
            return best
        }
    }
}

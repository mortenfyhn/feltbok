package io.github.mortenfyhn.feltbok

import kotlin.math.floor

/** Finds the localities whose [Locality.cullBounds] may overlap a lat/lon box, so the map doesn't
 *  scan all ~80k on every frame and tap. Each locality goes into every grid cell its bounds touch.
 *  The few huge ones (sea areas) that would fill many cells are kept apart and always returned.
 *  Results are a superset at cell granularity, so callers still test the exact bounds. They keep
 *  the input order, which is the map's draw order. */
class LocalityGrid(private val localities: List<Locality>, private val cellDeg: Double = 0.1) {
    private val cells = HashMap<Long, IntList>()
    private val huge = ArrayList<Int>()
    private var rowMin = Int.MAX_VALUE
    private var rowMax = Int.MIN_VALUE
    private var colMin = Int.MAX_VALUE
    private var colMax = Int.MIN_VALUE

    init {
        localities.forEachIndexed { i, loc ->
            val b = loc.cullBounds
            val r0 = cell(b[0]); val r1 = cell(b[1]); val c0 = cell(b[2]); val c1 = cell(b[3])
            if ((r1 - r0 + 1).toLong() * (c1 - c0 + 1) > MAX_CELLS) {
                huge += i
                return@forEachIndexed
            }
            rowMin = minOf(rowMin, r0); rowMax = maxOf(rowMax, r1)
            colMin = minOf(colMin, c0); colMax = maxOf(colMax, c1)
            for (r in r0..r1) for (c in c0..c1) cells.getOrPut(key(r, c)) { IntList() }.add(i)
        }
    }

    fun query(latMin: Double, latMax: Double, lonMin: Double, lonMax: Double): List<Locality> {
        val hits = IntList()
        huge.forEach { hits.add(it) }
        // Clamped to the filled cells, or a zoomed-out world view would walk millions of empty ones.
        for (r in maxOf(cell(latMin), rowMin)..minOf(cell(latMax), rowMax)) {
            for (c in maxOf(cell(lonMin), colMin)..minOf(cell(lonMax), colMax)) {
                cells[key(r, c)]?.let { hits.addAll(it) }
            }
        }
        // A locality spanning several cells appears once per cell; sorting puts the copies side
        // by side and restores the input order.
        val ids = hits.sorted()
        val out = ArrayList<Locality>(ids.size)
        for (k in ids.indices) if (k == 0 || ids[k] != ids[k - 1]) out += localities[ids[k]]
        return out
    }

    private fun cell(deg: Double) = floor(deg / cellDeg).toInt()

    private fun key(row: Int, col: Int) = (row.toLong() shl 32) or (col.toLong() and 0xffffffffL)

    /** Growable int array: a List<Int> boxes every index, which adds up over ~80k localities. */
    private class IntList {
        private var a = IntArray(4)
        private var n = 0
        fun add(v: Int) {
            if (n == a.size) a = a.copyOf(n * 2)
            a[n++] = v
        }
        fun addAll(o: IntList) { for (k in 0 until o.n) add(o.a[k]) }
        fun sorted(): IntArray = a.copyOf(n).also { it.sort() }
    }

    private companion object {
        const val MAX_CELLS = 64
    }
}

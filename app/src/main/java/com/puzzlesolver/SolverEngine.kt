package com.puzzlesolver

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.*

data class SolveResult(
    val tapCoords: List<Pair<Int, Int>>,  // pixel (x,y) to tap for each animal
    val n: Int,
    val status: String
)

object SolverEngine {

    private val BEIGE = floatArrayOf(237f, 228f, 215f)
    private val WHITE = floatArrayOf(255f, 255f, 255f)

    private fun pixDist(r: Int, g: Int, b: Int, ref: FloatArray): Float {
        val dr = r - ref[0]; val dg = g - ref[1]; val db = b - ref[2]
        return sqrt(dr*dr + dg*dg + db*db)
    }

    // ── Grid boundary detection ───────────────────────────────────────────────

    fun detectGrid(bmp: Bitmap): IntArray? {
        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        fun isColorful(px: Int): Boolean {
            val r = Color.red(px); val g = Color.green(px); val b = Color.blue(px)
            val mx = maxOf(r, g, b)
            return mx > 70 && mx < 248 &&
                   pixDist(r, g, b, BEIGE) > 25 &&
                   pixDist(r, g, b, WHITE) > 25
        }

        fun rowColorCount(y: Int): Int {
            var n = 0
            for (x in w / 5 until w * 4 / 5 step 3)
                if (isColorful(pixels[y * w + x])) n++
            return n
        }

        fun colColorCount(x: Int, y1: Int, y2: Int): Int {
            var n = 0
            for (y in y1 until y2 step 3)
                if (isColorful(pixels[y * w + x])) n++
            return n
        }

        val minRow = (w * 3 / 5) / 20
        var y1 = h / 5
        for (y in h / 6 until h * 2 / 3) { if (rowColorCount(y) > minRow) { y1 = y; break } }

        var y2 = h * 5 / 6
        for (y in h * 5 / 6 downTo h / 3) { if (rowColorCount(y) > minRow) { y2 = y; break } }

        val minCol = (y2 - y1) / 20
        var x1 = w / 10
        for (x in w / 10 until w / 2) { if (colColorCount(x, y1, y2) > minCol) { x1 = x; break } }

        var x2 = w * 9 / 10
        for (x in w * 9 / 10 downTo w / 2) { if (colColorCount(x, y1, y2) > minCol) { x2 = x; break } }

        if (x2 - x1 < 200 || y2 - y1 < 200) return null
        return intArrayOf(x1, y1, x2, y2)
    }

    // ── N detection (how many rows/cols) ────────────────────────────────────

    private fun scoreN(profile: FloatArray, n: Int): Float {
        val len = profile.size
        val cs = len.toFloat() / n
        if (cs < 40f) return Float.NEGATIVE_INFINITY
        var score = 0f
        for (k in 0..n) {
            val b = (k * cs).toInt().coerceIn(0, len - 1)
            score += profile[b]
            val c = ((k + 0.5f) * cs).toInt().coerceIn(0, len - 1)
            score -= profile[c]
        }
        return score
    }

    fun detectN(bmp: Bitmap, x1: Int, y1: Int, x2: Int, y2: Int): Int {
        val bw = bmp.width
        val gw = x2 - x1; val gh = y2 - y1
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bw, 0, 0, bw, bmp.height)

        val vProf = FloatArray(gw)
        for (x in 0 until gw) {
            var s = 0f
            for (y in 0 until gh) {
                val px = pixels[(y1 + y) * bw + (x1 + x)]
                s += (Color.red(px) + Color.green(px) + Color.blue(px)) / 3f
            }
            vProf[x] = s / gh
        }

        val hProf = FloatArray(gh)
        for (y in 0 until gh) {
            var s = 0f
            for (x in 0 until gw) {
                val px = pixels[(y1 + y) * bw + (x1 + x)]
                s += (Color.red(px) + Color.green(px) + Color.blue(px)) / 3f
            }
            hProf[y] = s / gw
        }

        var bestN = 9; var bestScore = Float.NEGATIVE_INFINITY
        for (n in 6..13) {
            val combined = scoreN(vProf, n) / gw + scoreN(hProf, n) / gh
            if (combined > bestScore) { bestScore = combined; bestN = n }
        }
        return bestN
    }

    // ── Color sampling & clustering ──────────────────────────────────────────

    private fun sampleCell(bmp: Bitmap, cx: Int, cy: Int, q: Int): Triple<Int,Int,Int> {
        val w = bmp.width; val h = bmp.height
        val offsets = listOf(0 to 0, -q to -q, q to -q, -q to q, q to q)
        val samples = offsets.map { (dx, dy) ->
            val px = bmp.getPixel((cx + dx).coerceIn(0, w-1), (cy + dy).coerceIn(0, h-1))
            Triple(Color.red(px), Color.green(px), Color.blue(px))
        }
        val valid = samples.filter { (r,g,b) ->
            val mx = maxOf(r,g,b)
            mx > 70 && mx < 248 &&
            pixDist(r,g,b, BEIGE) > 20 && pixDist(r,g,b, WHITE) > 20
        }
        val pool = valid.ifEmpty { samples }
        return pool.maxByOrNull { (r,g,b) -> min(pixDist(r,g,b,BEIGE), pixDist(r,g,b,WHITE)) }!!
    }

    private fun colorDist(a: Triple<Int,Int,Int>, b: Triple<Int,Int,Int>): Float {
        val dr = (a.first  - b.first ).toFloat()
        val dg = (a.second - b.second).toFloat()
        val db = (a.third  - b.third ).toFloat()
        return sqrt(dr*dr + dg*dg + db*db)
    }

    // ── Backtracking solver ──────────────────────────────────────────────────

    fun solve(grid: Array<IntArray>, n: Int): Map<Int, Pair<Int,Int>>? {
        // Build regions per color
        val regions = mutableMapOf<Int, MutableList<Pair<Int,Int>>>()
        for (r in 0 until n) for (c in 0 until n) {
            val cid = grid[r][c]; if (cid > 0)
            regions.getOrPut(cid) { mutableListOf() } += (r to c)
        }

        val solution  = mutableMapOf<Int, Pair<Int,Int>>()
        val usedRows  = mutableSetOf<Int>()
        val usedCols  = mutableSetOf<Int>()

        // Forced placements first (single-cell colors)
        for ((cid, cells) in regions) if (cells.size == 1) {
            val (r,c) = cells[0]
            solution[cid] = r to c; usedRows += r; usedCols += c
        }

        // Sort remaining by region size (smallest = most constrained first)
        val remaining = regions.keys
            .filter { it !in solution }
            .sortedBy { regions[it]!!.size }

        fun adjacent(r1: Int, c1: Int, r2: Int, c2: Int) =
            abs(r1-r2) <= 1 && abs(c1-c2) <= 1

        fun canPlace(cid: Int, r: Int, c: Int): Boolean {
            if (r in usedRows || c in usedCols) return false
            if (grid[r][c] != cid) return false
            return solution.values.none { (pr, pc) -> adjacent(r, c, pr, pc) }
        }

        fun bt(idx: Int): Boolean {
            if (idx == remaining.size) return true
            val cid = remaining[idx]
            for ((r, c) in regions[cid]!!) {
                if (canPlace(cid, r, c)) {
                    solution[cid] = r to c; usedRows += r; usedCols += c
                    if (bt(idx + 1)) return true
                    solution -= cid; usedRows -= r; usedCols -= c
                }
            }
            return false
        }

        return if (bt(0)) solution.toMap() else null
    }

    // ── Main entry: bitmap → tap coordinates ────────────────────────────────

    fun analyze(bmp: Bitmap): SolveResult {
        val bounds = detectGrid(bmp)
            ?: return SolveResult(emptyList(), 0, "Could not detect grid")

        val (gx1, gy1, gx2, gy2) = bounds.let {
            listOf(it[0], it[1], it[2], it[3])
        }

        // Find N by trying candidates: pick where uniqueColors == N
        val initN = detectN(bmp, gx1, gy1, gx2, gy2)
        var bestN = initN; var bestDiff = Int.MAX_VALUE
        for (tryN in 7..12) {
            val cell0 = (gx2 - gx1) / tryN; val q0 = cell0 / 5
            val rawC = Array(tryN) { r -> Array(tryN) { c ->
                val cx = gx1 + c * cell0 + cell0 / 2
                val cy = gy1 + r * cell0 + cell0 / 2
                sampleCell(bmp, cx, cy, q0)
            }}
            val uniq = mutableListOf<Triple<Int,Int,Int>>()
            for (row in rawC) for (col in row)
                if (uniq.none { colorDist(col, it) < 55f }) uniq += col
            val diff = kotlin.math.abs(uniq.size - tryN)
            if (diff < bestDiff) { bestDiff = diff; bestN = tryN }
            if (diff == 0) break
        }

        val n    = bestN
        val cell = (gx2 - gx1) / n
        val q    = cell / 5

        // Build grid of color IDs
        val rawColors  = Array(n) { r -> Array(n) { c ->
            val cx = gx1 + (c * cell) + cell / 2
            val cy = gy1 + (r * cell) + cell / 2
            sampleCell(bmp, cx, cy, q)
        }}

        // Cluster into color IDs
        val centers  = mutableListOf<Triple<Int,Int,Int>>()
        val colorGrid = Array(n) { r -> IntArray(n) { c ->
            val col = rawColors[r][c]
            var bestId = -1; var bestD = Float.MAX_VALUE
            centers.forEachIndexed { j, ctr ->
                val d = colorDist(col, ctr)
                if (d < bestD) { bestD = d; bestId = j }
            }
            if (bestD < 55f) {
                val cnt = centers.take(bestId + 1).count { colorDist(col, it) < 55f }
                // Simple running average for center
                val (or, og, ob) = centers[bestId]
                centers[bestId] = Triple(
                    (or + col.first) / 2, (og + col.second) / 2, (ob + col.third) / 2
                )
                bestId + 1
            } else {
                centers += col; centers.size
            }
        }}

        val solution = solve(colorGrid, n)
            ?: return SolveResult(emptyList(), n, "No solution found (N=$n, colors=${centers.size})")

        // Sort by color region size (forced/small regions first)
        val regionSizes = colorGrid.flatMap { it.toList() }
            .groupingBy { it }.eachCount()

        val taps = solution.entries
            .sortedBy { (cid, _) -> regionSizes[cid] ?: 99 }
            .map { (_, pos) ->
                val (r, c) = pos
                val px = gx1 + c * cell + cell / 2
                val py = gy1 + r * cell + cell / 2
                px to py
            }

        return SolveResult(taps, n, "OK — $n×$n, ${centers.size} colors")
    }
}

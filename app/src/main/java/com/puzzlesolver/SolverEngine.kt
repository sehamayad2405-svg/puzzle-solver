package com.puzzlesolver

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

private const val TAG = "SolverEngine"

data class SolveResult(
    val tapCoords: List<Pair<Int, Int>>,
    val n: Int,
    val status: String
)

object SolverEngine {

    private val BEIGE = doubleArrayOf(237.0, 228.0, 215.0)
    private val WHITE = doubleArrayOf(255.0, 255.0, 255.0)
    private const val COLOR_MERGE = 40.0

    private fun pixDist(r: Double, g: Double, b: Double, ref: DoubleArray): Double {
        val dr = r - ref[0]; val dg = g - ref[1]; val db = b - ref[2]
        return sqrt(dr*dr + dg*dg + db*db)
    }

    private fun isColorful(r: Double, g: Double, b: Double): Boolean {
        val mx = maxOf(r, g, b)
        return mx > 70 && mx < 248 &&
               pixDist(r, g, b, BEIGE) > 22 &&
               pixDist(r, g, b, WHITE) > 18
    }

    // Returns pixel as DoubleArray [R, G, B] from RGB Mat
    private fun px(mat: Mat, y: Int, x: Int): DoubleArray = mat.get(y, x)

    private fun bitmapToMat(bmp: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()
        return rgb
    }

    // Build 8-bit mask where colorful pixels = 255
    private fun colorfulMask(rgb: Mat): Mat {
        val h = rgb.rows(); val w = rgb.cols()
        val mask = Mat.zeros(h, w, CvType.CV_8U)
        for (y in 0 until h) for (x in 0 until w) {
            val p = px(rgb, y, x)
            if (isColorful(p[0], p[1], p[2])) mask.put(y, x, 255.0)
        }
        return mask
    }

    // Bounding box of non-zero pixels in mask (manual scan, avoids MatOfPoint issues)
    private fun maskBounds(mask: Mat): IntArray? {
        val h = mask.rows(); val w = mask.cols()
        var x0 = w; var y0 = h; var x1 = 0; var y1 = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (mask.get(y, x)[0] > 0.0) {
                if (x < x0) x0 = x; if (x > x1) x1 = x
                if (y < y0) y0 = y; if (y > y1) y1 = y
            }
        }
        return if (x1 <= x0) null else intArrayOf(x0, y0, x1, y1)
    }

    // Grid detection via contours (matches Python detect_grid)
    fun detectGrid(bmp: Bitmap): IntArray? {
        val rgb = bitmapToMat(bmp)
        val h = rgb.rows(); val w = rgb.cols()

        val mask = colorfulMask(rgb)
        for (y in 0 until (h * 0.20).toInt()) for (x in 0 until w) mask.put(y, x, 0.0)
        for (y in (h * 0.85).toInt() until h) for (x in 0 until w) mask.put(y, x, 0.0)

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, 15.0))
        val dilated = Mat()
        Imgproc.dilate(mask, dilated, kernel)

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(dilated, contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        val result: IntArray
        if (contours.isEmpty()) {
            Log.w(TAG, "detectGrid: no contours, using mask bounds fallback")
            val b = maskBounds(mask)
            result = b ?: intArrayOf(20, (h * 0.20).toInt(), w - 20, (h * 0.85).toInt())
        } else {
            val biggest = contours.maxByOrNull { Imgproc.contourArea(it) }!!
            val br = Imgproc.boundingRect(biggest)
            val PAD = 5
            result = intArrayOf(
                maxOf(0, br.x - PAD),
                maxOf(0, br.y - PAD),
                minOf(w, br.x + br.width + PAD),
                minOf(h, br.y + br.height + PAD)
            )
        }

        rgb.release(); mask.release(); dilated.release(); kernel.release(); hierarchy.release()
        Log.i(TAG, "Grid bounds: (${result[0]},${result[1]})->(${result[2]},${result[3]})  " +
              "size=${result[2]-result[0]}x${result[3]-result[1]}")

        if (result[2] - result[0] < 200 || result[3] - result[1] < 200) {
            Log.e(TAG, "Grid too small — not a puzzle screen"); return null
        }
        return result
    }

    // N detection via brightness profile scoring (matches Python detect_n_from_edges)
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

    fun detectNFromEdges(rgb: Mat, x1: Int, y1: Int, x2: Int, y2: Int): Int {
        val gw = x2 - x1; val gh = y2 - y1
        val hProf = FloatArray(gh)
        val vProf = FloatArray(gw)

        for (y in 0 until gh) {
            var s = 0f
            for (x in 0 until gw) {
                val p = px(rgb, y1 + y, x1 + x)
                s += (p[0] + p[1] + p[2]).toFloat() / 3f
            }
            hProf[y] = s / gw
        }
        for (x in 0 until gw) {
            var s = 0f
            for (y in 0 until gh) {
                val p = px(rgb, y1 + y, x1 + x)
                s += (p[0] + p[1] + p[2]).toFloat() / 3f
            }
            vProf[x] = s / gh
        }

        var bestN = 9; var bestScore = Float.NEGATIVE_INFINITY
        for (n in 6..13) {
            val combined = scoreN(vProf, n) / gw + scoreN(hProf, n) / gh
            Log.d(TAG, "detectNEdges: N=$n score=${"%.4f".format(combined)}")
            if (combined > bestScore) { bestScore = combined; bestN = n }
        }
        Log.i(TAG, "detectNEdges result: N=$bestN")
        return bestN
    }

    // N detection via HoughCircles on progress dot area (matches Python count_progress_dots)
    fun countProgressDots(rgb: Mat, gridY1: Int): Int? {
        val yLo = maxOf(30, gridY1 - 420)
        val yHi = maxOf(150, gridY1 - 20)
        if (yHi <= yLo) return null

        val roi = rgb.submat(yLo, yHi, 50, rgb.cols() - 50)
        val gray = Mat()
        Imgproc.cvtColor(roi, gray, Imgproc.COLOR_RGB2GRAY)

        val circles = Mat()
        // minRadius and maxRadius are Int in OpenCV Java bindings
        Imgproc.HoughCircles(gray, circles, Imgproc.HOUGH_GRADIENT,
            1.0, 18.0, 50.0, 18.0, 8, 26)

        if (!circles.empty()) {
            val n = circles.cols()
            Log.i(TAG, "countProgressDots HoughCircles: $n dots")
            if (n in 6..13) {
                roi.release(); gray.release(); circles.release()
                return n
            }
        }

        // Fallback: colorful blob counting
        val roiMask = colorfulMask(roi)
        val labels = Mat(); val stats = Mat(); val centroids = Mat()
        val nLab = Imgproc.connectedComponentsWithStats(roiMask, labels, stats, centroids)
        val xCoords = mutableListOf<Double>()
        for (i in 1 until nLab) {
            val area = stats.get(i, Imgproc.CC_STAT_AREA)[0]
            if (area in 40.0..900.0) xCoords += centroids.get(i, 0)[0]
        }
        xCoords.sort()
        var merged = if (xCoords.isEmpty()) 0 else 1
        for (i in 1 until xCoords.size) if (xCoords[i] - xCoords[i-1] > 15) merged++

        roi.release(); gray.release(); circles.release()
        roiMask.release(); labels.release(); stats.release(); centroids.release()

        Log.i(TAG, "countProgressDots fallback blobs: $merged")
        return if (merged in 6..13) merged else null
    }

    // Sample cell center (matches Python sample_cell)
    private fun sampleCell(rgb: Mat, cx: Int, cy: Int, q: Int): Triple<Double,Double,Double> {
        val w = rgb.cols(); val h = rgb.rows()
        val offsets = listOf(0 to 0, -q to -q, q to -q, -q to q, q to q)
        data class S(val r: Double, val g: Double, val b: Double, val score: Double)

        val samples = offsets.map { (dx, dy) ->
            val x = (cx + dx).coerceIn(0, w - 1)
            val y = (cy + dy).coerceIn(0, h - 1)
            val p = px(rgb, y, x)
            S(p[0], p[1], p[2],
              minOf(pixDist(p[0], p[1], p[2], BEIGE), pixDist(p[0], p[1], p[2], WHITE)))
        }
        val valid = samples.filter { isColorful(it.r, it.g, it.b) }
        val best = (valid.ifEmpty { samples }).maxByOrNull { it.score }!!
        return Triple(best.r, best.g, best.b)
    }

    private fun colorDist(a: Triple<Double,Double,Double>, b: Triple<Double,Double,Double>): Double {
        val dr = a.first-b.first; val dg = a.second-b.second; val db = a.third-b.third
        return sqrt(dr*dr + dg*dg + db*db)
    }

    // Cluster sampled colors (matches Python cluster_colors)
    private fun clusterColors(
        raw: Array<Array<Triple<Double,Double,Double>>>,
        n: Int,
        thresh: Double
    ): Pair<List<Triple<Double,Double,Double>>, Array<IntArray>> {
        val clusters = mutableListOf<Triple<Double,Double,Double>>()
        val grid = Array(n) { r ->
            IntArray(n) { c ->
                val col = raw[r][c]
                val idx = clusters.indexOfFirst { colorDist(col, it) < thresh }
                if (idx >= 0) {
                    val (or, og, ob) = clusters[idx]
                    clusters[idx] = Triple(
                        (or + col.first) / 2,
                        (og + col.second) / 2,
                        (ob + col.third) / 2
                    )
                    idx + 1
                } else {
                    clusters += col; clusters.size
                }
            }
        }
        return clusters to grid
    }

    // Backtracking solver with forced placements + MRV heuristic
    fun solve(grid: Array<IntArray>, n: Int): Map<Int, Pair<Int,Int>>? {
        val regions = mutableMapOf<Int, MutableList<Pair<Int,Int>>>()
        for (r in 0 until n) for (c in 0 until n) {
            val cid = grid[r][c]; if (cid > 0)
            regions.getOrPut(cid) { mutableListOf() } += (r to c)
        }
        val solution = mutableMapOf<Int, Pair<Int,Int>>()
        val usedRows = mutableSetOf<Int>()
        val usedCols = mutableSetOf<Int>()

        fun adjacent(r1: Int, c1: Int, r2: Int, c2: Int) =
            abs(r1 - r2) <= 1 && abs(c1 - c2) <= 1

        fun validCells(cid: Int): List<Pair<Int,Int>> =
            regions[cid]!!.filter { (r, c) ->
                r !in usedRows && c !in usedCols &&
                solution.values.none { (pr, pc) -> adjacent(r, c, pr, pc) }
            }

        fun bt(todo: List<Int>): Boolean {
            val remaining = todo.toMutableList()
            var changed = true
            while (changed) {
                changed = false
                for (cid in remaining.toList()) {
                    val cells = validCells(cid)
                    if (cells.isEmpty()) return false
                    if (cells.size == 1) {
                        val (r, c) = cells[0]
                        solution[cid] = r to c; usedRows += r; usedCols += c
                        remaining -= cid; changed = true
                    }
                }
            }
            if (remaining.isEmpty()) return true
            val bestCid = remaining.minByOrNull { validCells(it).size }!!
            for ((r, c) in validCells(bestCid)) {
                solution[bestCid] = r to c; usedRows += r; usedCols += c
                if (bt(remaining.filter { it != bestCid })) return true
                solution -= bestCid; usedRows -= r; usedCols -= c
            }
            return false
        }

        return if (bt(regions.keys.toList())) solution.toMap() else null
    }

    // Main entry: Bitmap → tap coordinates
    fun analyze(bmp: Bitmap): SolveResult {
        val rgb = bitmapToMat(bmp)

        val bounds = detectGrid(bmp)
            ?: run { rgb.release(); return SolveResult(emptyList(), 0, "Could not detect grid") }
        val gx1 = bounds[0]; val gy1 = bounds[1]
        val gx2 = bounds[2]
        val gw = gx2 - gx1

        val edgeN = detectNFromEdges(rgb, gx1, gy1, gx2, bounds[3])
        val dotN  = countProgressDots(rgb, gy1)
        val initN = when {
            edgeN == dotN -> edgeN
            dotN != null  -> dotN
            else          -> edgeN
        }
        Log.i(TAG, "N detection: edgeN=$edgeN  dotN=$dotN  → initN=$initN")

        var bestN = initN; var bestDiff = Int.MAX_VALUE
        for (tryN in 6..13) {
            val cs = gw / tryN; val q = cs / 5
            val raw = Array(tryN) { r -> Array(tryN) { c ->
                val cx = gx1 + c * cs + cs / 2
                val cy = gy1 + r * cs + cs / 2
                sampleCell(rgb, cx, cy, q)
            }}
            val (_, colGrid) = clusterColors(raw, tryN, COLOR_MERGE)
            val nColors = colGrid.flatMap { it.toList() }.toSet().size
            val diff = abs(nColors - tryN)
            Log.d(TAG, "tryN=$tryN  uniqueColors=$nColors  diff=$diff  cellSize=${cs}px")
            val better = diff < bestDiff ||
                (diff == bestDiff && abs(tryN - initN) < abs(bestN - initN))
            if (better) { bestDiff = diff; bestN = tryN }
        }
        Log.i(TAG, "Final N=$bestN  bestDiff=$bestDiff")

        val cs = gw / bestN; val q = cs / 5
        val raw = Array(bestN) { r -> Array(bestN) { c ->
            val cx = gx1 + c * cs + cs / 2
            val cy = gy1 + r * cs + cs / 2
            sampleCell(rgb, cx, cy, q)
        }}
        val (centers, colorGrid) = clusterColors(raw, bestN, COLOR_MERGE)
        Log.i(TAG, "Solving N=$bestN grid with ${centers.size} colors")

        val solution = solve(colorGrid, bestN)
        if (solution == null) {
            Log.e(TAG, "No solution found for N=$bestN, colors=${centers.size}")
            rgb.release()
            return SolveResult(emptyList(), bestN,
                "No solution found (N=$bestN, colors=${centers.size})")
        }

        val regionSizes = colorGrid.flatMap { it.toList() }.groupingBy { it }.eachCount()
        val taps = solution.entries
            .sortedBy { (cid, _) -> regionSizes[cid] ?: 99 }
            .map { (_, pos) ->
                val (r, c) = pos
                gx1 + c * cs + cs / 2 to gy1 + r * cs + cs / 2
            }

        rgb.release()
        return SolveResult(taps, bestN, "OK — $bestN×$bestN, ${centers.size} colors")
    }
}

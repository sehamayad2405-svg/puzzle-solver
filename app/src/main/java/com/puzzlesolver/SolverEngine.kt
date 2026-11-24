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

/**
 * One candidate board reading: a (threshold, N) pair that clustered cleanly
 * AND has a valid solution. Ranked list of these replaces the old
 * "single fixed Config.colorMerge threshold" commitment.
 */
data class SolveCandidate(
    val n: Int,
    val thresh: Int,
    val cellSize: Int,
    val nColors: Int,
    val taps: List<Pair<Int, Int>>,
    val gridBounds: IntArray
)

object SolverEngine {

    private val BEIGE = doubleArrayOf(237.0, 228.0, 215.0)
    private val WHITE = doubleArrayOf(255.0, 255.0, 255.0)

    /** Thresholds scanned, mirroring Python's `for thresh in range(28, 85, 3)`. */
    private const val THRESH_MIN = 28
    private const val THRESH_MAX = 85
    private const val THRESH_STEP = 3

    /** Python MAX_RETRIES — how many ranked candidates we hand back. */
    private const val MAX_CANDIDATES = 15

    /** Python: `if cs < 45: continue` */
    private const val MIN_CELL_SIZE = 45

    /** Last failure reason, so the thin analyze() wrapper can still report it. */
    private var lastStatus: String = ""

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

        // Enforce square grid: N×N grid has equal width and height
        val gw2 = result[2] - result[0]
        val gh2 = result[3] - result[1]
        if (gh2 > gw2 * Config.squareCropRatio) {
            // Too tall — crop height to match width (extra height = UI below grid)
            result[3] = result[1] + gw2
            Log.w(TAG, "Grid was non-square (${gw2}x${gh2}), cropped to square")
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

    // Sample cell using 3×3 grid of points, return median channel values
    // Using median avoids bird-icon pixels dominating a single-cell sample
    private fun sampleCell(rgb: Mat, cx: Int, cy: Int, q: Int): Triple<Double,Double,Double> {
        val w = rgb.cols(); val h = rgb.rows()
        // 3×3 grid at ±q/2 and ±q offsets
        val step = maxOf(1, q / 2)
        val offsets = listOf(
            -step to -step, 0 to -step, step to -step,
            -step to  0,    0 to  0,    step to  0,
            -step to  step, 0 to  step, step to  step
        )
        val rs = mutableListOf<Double>()
        val gs = mutableListOf<Double>()
        val bs = mutableListOf<Double>()
        for ((dx, dy) in offsets) {
            val x = (cx + dx).coerceIn(0, w - 1)
            val y = (cy + dy).coerceIn(0, h - 1)
            val p = px(rgb, y, x)
            if (isColorful(p[0], p[1], p[2])) {
                rs += p[0]; gs += p[1]; bs += p[2]
            }
        }
        // Fallback to all samples if none were colorful
        if (rs.isEmpty()) {
            for ((dx, dy) in offsets) {
                val x = (cx + dx).coerceIn(0, w - 1)
                val y = (cy + dy).coerceIn(0, h - 1)
                val p = px(rgb, y, x)
                rs += p[0]; gs += p[1]; bs += p[2]
            }
        }
        rs.sort(); gs.sort(); bs.sort()
        val mid = rs.size / 2
        return Triple(rs[mid], gs[mid], bs[mid])
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
        // Cluster centres are fixed at the first colour that created them and are
        // never averaged afterwards — matching Python exactly. Averaging lets a
        // centre drift toward each new member, which snowballs into merging
        // regions that Python keeps distinct.
        val clusters = mutableListOf<Triple<Double,Double,Double>>()
        val grid = Array(n) { r ->
            IntArray(n) { c ->
                val col = raw[r][c]
                val idx = clusters.indexOfFirst { colorDist(col, it) < thresh }
                if (idx >= 0) {
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
            val forced = mutableListOf<Triple<Int, Int, Int>>()   // cid, r, c (undo log)
            while (changed) {
                changed = false
                for (cid in remaining.toList()) {
                    val cells = validCells(cid)
                    if (cells.isEmpty()) {
                        // roll back this frame's forced placements before failing
                        for ((fc, fr, fcol) in forced.asReversed()) {
                            solution -= fc; usedRows -= fr; usedCols -= fcol
                        }
                        return false
                    }
                    if (cells.size == 1) {
                        val (r, c) = cells[0]
                        solution[cid] = r to c; usedRows += r; usedCols += c
                        forced += Triple(cid, r, c)
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
            // dead end: undo this frame's forced placements too
            for ((fc, fr, fcol) in forced.asReversed()) {
                solution -= fc; usedRows -= fr; usedCols -= fcol
            }
            return false
        }

        return if (bt(regions.keys.toList())) solution.toMap() else null
    }

    // ── Candidate search ────────────────────────────────────────────────────
    // Port of Python's `all_candidates` loop + config_score: search the 2D space
    // of (clustering threshold × N) instead of committing to one threshold.

    private class Cfg(
        val thresh: Int,
        val n: Int,
        val cellSize: Int,
        val nColors: Int,
        val diff: Int,
        val nDist: Int,
        val minCount: Int,
        val colorGrid: Array<IntArray>,
        val centers: Array<Array<Pair<Int, Int>>>
    )

    /**
     * Python config_score ordering:
     *   diff != 0 → (diff, nDist, thresh)
     *   diff == 0 → (0, nDist, -minCount, thresh)
     * i.e. diff asc, nDist asc, then (only when diff==0) LARGER minCount first,
     * then thresh asc.
     */
    private val CFG_ORDER = Comparator<Cfg> { a, b ->
        var r = a.diff.compareTo(b.diff)
        if (r != 0) return@Comparator r
        r = a.nDist.compareTo(b.nDist)
        if (r != 0) return@Comparator r
        if (a.diff == 0) {
            r = b.minCount.compareTo(a.minCount)   // larger minCount first
            if (r != 0) return@Comparator r
        }
        a.thresh.compareTo(b.thresh)
    }

    /**
     * Ranked list of board readings, best first, at most 15 — the Kotlin
     * equivalent of Python's `all_candidates[:MAX_RETRIES]`.
     * Each entry already has a verified solution; OverlayService taps them in
     * order and falls through to the next one when red-X verification fails.
     */
    fun analyzeAll(bmp: Bitmap): List<SolveCandidate> {
        val rgb = bitmapToMat(bmp)

        val bounds = detectGrid(bmp)
        if (bounds == null) {
            rgb.release()
            lastStatus = "Could not detect grid"
            Log.e(TAG, "analyzeAll: $lastStatus")
            return emptyList()
        }
        val gx1 = bounds[0]; val gy1 = bounds[1]
        val gx2 = bounds[2]; val gy2 = bounds[3]
        val gw = gx2 - gx1

        val edgeN = detectNFromEdges(rgb, gx1, gy1, gx2, gy2)
        val dotN  = countProgressDots(rgb, gy1)
        // Always use edgeN — dotN (HoughCircles) consistently mis-detects game icons as dots
        val initN = edgeN
        Log.i(TAG, "N detection: edgeN=$edgeN  dotN=$dotN  → initN=$initN (dotN ignored)")
        Log.i(TAG, "Config: ${Config.summary()}")
        Log.i(TAG, "Threshold sweep $THRESH_MIN..${THRESH_MAX - 1} step $THRESH_STEP " +
                   "replaces the single colorMerge=${Config.colorMerge} threshold")

        // sampleCell() depends only on N, so cache raw samples per N and reuse
        // them across all ~20 thresholds instead of re-sampling every time.
        val rawCache = HashMap<Int, Array<Array<Triple<Double,Double,Double>>>>()
        val cenCache = HashMap<Int, Array<Array<Pair<Int, Int>>>>()

        fun rawFor(tryN: Int, cs: Int): Array<Array<Triple<Double,Double,Double>>> =
            rawCache.getOrPut(tryN) {
                val q = cs / 5
                Array(tryN) { r -> Array(tryN) { c ->
                    val cx = gx1 + c * cs + cs / 2
                    val cy = gy1 + r * cs + cs / 2
                    sampleCell(rgb, cx, cy, q)
                }}
            }

        fun cenFor(tryN: Int, cs: Int): Array<Array<Pair<Int, Int>>> =
            cenCache.getOrPut(tryN) {
                Array(tryN) { r -> Array(tryN) { c ->
                    (gx1 + c * cs + cs / 2) to (gy1 + r * cs + cs / 2)
                }}
            }

        val configs = mutableListOf<Cfg>()
        var thresh = THRESH_MIN
        while (thresh < THRESH_MAX) {
            for (tryN in Config.nMin..Config.nMax) {
                val cs = gw / tryN
                if (cs < MIN_CELL_SIZE) continue

                val raw = rawFor(tryN, cs)
                val (clusters, colorGrid) = clusterColors(raw, tryN, thresh.toDouble())

                val flat = colorGrid.flatMap { it.toList() }
                val nColors = flat.toSet().size
                val diff = abs(nColors - tryN)
                val nDist = abs(tryN - initN)

                val counts = IntArray(clusters.size)
                for (v in flat) if (v in 1..clusters.size) counts[v - 1]++
                val minCount = counts.minOrNull() ?: 0

                configs += Cfg(thresh, tryN, cs, nColors, diff, nDist, minCount,
                               colorGrid, cenFor(tryN, cs))
                Log.d(TAG, "cand: thresh=$thresh N=$tryN cell=${cs}px colors=$nColors " +
                           "diff=$diff nDist=$nDist minCount=$minCount")
            }
            thresh += THRESH_STEP
        }

        configs.sortWith(CFG_ORDER)
        Log.i(TAG, "analyzeAll: ${configs.size} configs built (thresh " +
                   "$THRESH_MIN..${THRESH_MAX - 1} step $THRESH_STEP, N ${Config.nMin}..${Config.nMax})")

        // Solve in ranked order, keeping only configs that have a solution,
        // and stop once we have MAX_CANDIDATES.
        val out = mutableListOf<SolveCandidate>()
        var unsolvable = 0
        for (cfg in configs) {
            if (out.size >= MAX_CANDIDATES) break

            val solution = solve(cfg.colorGrid, cfg.n)
            if (solution == null) { unsolvable++; continue }

            val regionSizes = cfg.colorGrid.flatMap { it.toList() }
                .groupingBy { it }.eachCount()
            // Tap smallest colour region first = most forced (Python tap_order)
            val taps = solution.entries
                .sortedBy { (cid, _) -> regionSizes[cid] ?: 99 }
                .map { (_, pos) ->
                    val (r, c) = pos
                    cfg.centers[r][c]
                }

            val cand = SolveCandidate(
                n = cfg.n,
                thresh = cfg.thresh,
                cellSize = cfg.cellSize,
                nColors = cfg.nColors,
                taps = taps,
                // Python: GRID_BOUNDS = [x1, y1, x2, y1 + cs * cn]
                gridBounds = intArrayOf(gx1, gy1, gx2, gy1 + cfg.cellSize * cfg.n)
            )
            out += cand

            Log.i(TAG, "candidate #${out.size}: N=${cfg.n} thresh=${cfg.thresh} " +
                       "cell=${cfg.cellSize}px colors=${cfg.nColors} diff=${cfg.diff} " +
                       "nDist=${cfg.nDist} minCount=${cfg.minCount} taps=${taps.size}")

            if (out.size == 1) {
                // Full board dump for the best candidate only (keeps logcat readable)
                Log.i(TAG, "best candidate grid: N=${cfg.n} cellSize=${cfg.cellSize}px " +
                           "grid=(${gx1},${gy1})->(${gx2},${gy2})")
                for (r in 0 until cfg.n) {
                    val row = (0 until cfg.n).joinToString("  ") { c ->
                        val (cx, cy) = cfg.centers[r][c]
                        "c$c(${cx},${cy})=${cfg.colorGrid[r][c]}"
                    }
                    Log.i(TAG, "row[$r]: $row")
                }
                Log.i(TAG, "best taps (${taps.size}): " +
                           taps.joinToString(" → ") { "(${it.first},${it.second})" })
            }
        }

        rgb.release()

        lastStatus = if (out.isEmpty()) {
            "No solvable configuration (${configs.size} tried)"
        } else {
            "OK — ${out[0].n}×${out[0].n}, ${out[0].nColors} colors, " +
            "thresh=${out[0].thresh}, ${out.size} candidate(s)"
        }
        Log.i(TAG, "analyzeAll: ${out.size} ranked candidates, $unsolvable unsolvable skipped " +
                   "— $lastStatus")
        return out
    }

    /** Thin wrapper over analyzeAll() so existing single-shot callers keep working. */
    fun analyze(bmp: Bitmap): SolveResult {
        val candidates = analyzeAll(bmp)
        val best = candidates.firstOrNull()
            ?: return SolveResult(emptyList(), 0, lastStatus)
        return SolveResult(best.taps, best.n, lastStatus)
    }

    // ── Red-X verification (port of Python has_red_x) ───────────────────────

    private fun isRedX(r: Int, g: Int, b: Int): Boolean =
        r > 160 && g < 100 && b < 100 && r > g + 80 && r > b + 80

    /**
     * True when a red X appeared near the tap point. Scans the box
     * ±half*2 around (tapX, tapY), clamped to the grid, stepping 3px;
     * ≥10 red pixels = red X.
     */
    fun hasRedX(bmp: Bitmap, tapX: Int, tapY: Int,
                gx1: Int, gy1: Int, gx2: Int, gy2: Int): Boolean {
        val w = bmp.width; val h = bmp.height
        val half = maxOf(80, (gy2 - gy1) / 10)

        val rx1 = maxOf(gx1, tapX - half * 2).coerceIn(0, w - 1)
        val rx2 = minOf(gx2, tapX + half * 2).coerceIn(0, w)
        val ry1 = maxOf(gy1, tapY - half * 2).coerceIn(0, h - 1)
        val ry2 = minOf(gy2, tapY + half * 2).coerceIn(0, h)
        if (rx2 <= rx1 || ry2 <= ry1) {
            Log.d(TAG, "hasRedX: empty scan box at ($tapX,$tapY)")
            return false
        }

        var redCount = 0
        var y = ry1
        while (y < ry2) {
            var x = rx1
            while (x < rx2) {
                val p = bmp.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (isRedX(r, g, b)) {
                    redCount++
                    if (redCount >= 10) {
                        Log.i(TAG, "hasRedX: RED X near ($tapX,$tapY) " +
                                   "box=($rx1,$ry1)->($rx2,$ry2) half=$half")
                        return true
                    }
                }
                x += 3
            }
            y += 3
        }
        Log.d(TAG, "hasRedX: clean at ($tapX,$tapY) redCount=$redCount half=$half")
        return false
    }

    // ── Board state detection (port of Python detect_cell_state) ────────────

    /** 0 = empty, 1 = X mark, 2 = animal placed. */
    fun detectCellState(bmp: Bitmap, cx: Int, cy: Int, cellSize: Int): Int {
        val w = bmp.width; val h = bmp.height
        val q = maxOf(5, cellSize / 6)

        fun pxAt(x: Int, y: Int): IntArray {
            val p = bmp.getPixel(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1))
            return intArrayOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
        }

        val center = pxAt(cx, cy)
        val corners = listOf(
            pxAt(cx - q, cy - q),
            pxAt(cx + q, cy - q),
            pxAt(cx - q, cy + q),
            pxAt(cx + q, cy + q)
        )
        val avgR = corners.sumOf { it[0] } / 4
        val avgG = corners.sumOf { it[1] } / 4
        val avgB = corners.sumOf { it[2] } / 4

        val cMin = minOf(center[0], center[1], center[2])
        val cMax = maxOf(center[0], center[1], center[2])

        // White/near-white center over a non-white corner average = X mark
        val cornerFromWhite = pixDist(avgR.toDouble(), avgG.toDouble(), avgB.toDouble(), WHITE)
        if (cMin > 180 && cMax - cMin < 50 && cornerFromWhite > 30) return 1

        // Center far from the corner average = animal icon
        val centerFromCorner = pixDist(
            center[0].toDouble(), center[1].toDouble(), center[2].toDouble(),
            doubleArrayOf(avgR.toDouble(), avgG.toDouble(), avgB.toDouble())
        )
        if (centerFromCorner > 70) return 2

        return 0
    }

    /** Every non-empty cell as Triple(centreX, centreY, state). */
    fun getOccupiedCells(bmp: Bitmap, x1: Int, y1: Int, x2: Int, y2: Int,
                         n: Int, cellSize: Int): List<Triple<Int, Int, Int>> {
        val out = mutableListOf<Triple<Int, Int, Int>>()
        for (r in 0 until n) for (c in 0 until n) {
            val cx = x1 + c * cellSize + cellSize / 2
            val cy = y1 + r * cellSize + cellSize / 2
            val state = detectCellState(bmp, cx, cy, cellSize)
            if (state != 0) {
                out += Triple(cx, cy, state)
                Log.d(TAG, "getOccupiedCells: row=${r + 1} col=${c + 1} " +
                           "at ($cx,$cy) state=$state")
            }
        }
        Log.i(TAG, "getOccupiedCells: ${out.size} occupied of ${n * n} " +
                   "(grid ($x1,$y1)->($x2,$y2), cell=${cellSize}px)")
        return out
    }

    // ── Continue dialog (port of Python has_continue_dialog) ────────────────

    /** True when the out-of-lives Continue/Restart dialog is on screen. */
    fun hasContinueDialog(bmp: Bitmap): Boolean {
        val w = bmp.width; val h = bmp.height
        val cx1 = (w * 0.20).toInt().coerceIn(0, w - 1)
        val cx2 = (w * 0.80).toInt().coerceIn(0, w)
        val cy1 = (h * 0.25).toInt().coerceIn(0, h - 1)
        val cy2 = (h * 0.45).toInt().coerceIn(0, h)
        if (cx2 <= cx1 || cy2 <= cy1) return false

        val rowW = cx2 - cx1
        val row = IntArray(rowW)
        var orange = 0
        for (y in cy1 until cy2) {
            bmp.getPixels(row, 0, rowW, cx1, y, rowW, 1)
            for (p in row) {
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (r > 200 && g > 100 && g < 180 && b < 80) orange++
            }
        }
        val found = orange > 500
        Log.i(TAG, "hasContinueDialog: orangePixels=$orange → $found")
        return found
    }
}

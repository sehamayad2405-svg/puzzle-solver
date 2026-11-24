package com.puzzlesolver

import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.text.InputType
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean

class OverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val CHANNEL_ID = "solver_channel"
        /** Max candidate configurations to try before giving up (Python MAX_RETRIES). */
        const val MAX_RETRIES = 15
        var isRunning = false
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var statusText: TextView? = null

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private val solving = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Config.init(this)
        createNotificationChannel()
        startForeground(1, buildNotification("Puzzle Solver running"))
        addOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        }
        if (code == android.app.Activity.RESULT_OK && data != null) {
            try {
                val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val mp = mgr.getMediaProjection(code, data)
                if (mp != null) {
                    if (android.os.Build.VERSION.SDK_INT >= 34) {
                        mp.registerCallback(object : MediaProjection.Callback() {
                            override fun onStop() {
                                mediaProjection = null
                                virtualDisplay?.release(); virtualDisplay = null
                                imageReader?.close(); imageReader = null
                                setStatus("No screenshot perm!")
                            }
                        }, Handler(Looper.getMainLooper()))
                    }
                    // Create VirtualDisplay immediately — Android 14 revokes if not done within ~1s
                    val dm = resources.displayMetrics
                    val w = dm.widthPixels; val h = dm.heightPixels
                    imageReader?.close(); virtualDisplay?.release()
                    imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
                    virtualDisplay = mp.createVirtualDisplay(
                        "solver", w, h, dm.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        imageReader!!.surface, null, null
                    )
                    mediaProjection = mp
                    setStatus("Ready")
                } else {
                    setStatus("MP is null!")
                }
            } catch (e: Exception) {
                setStatus("MP err: ${e.message?.take(18)}")
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        isRunning = false
        overlayView?.let { windowManager?.removeView(it) }
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        super.onDestroy()
    }

    // ── Overlay floating button ──────────────────────────────────────────────

    private fun addOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 4, 4, 4)
        }

        // Top row: SOLVE + ⚙ settings button
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val btn = Button(this).apply {
            text = "SOLVE"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            textSize = 12f
        }

        val cfgBtn = Button(this).apply {
            text = "⚙"
            setBackgroundColor(Color.parseColor("#555555"))
            setTextColor(Color.WHITE)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        topRow.addView(btn)
        topRow.addView(cfgBtn)

        statusText = TextView(this).apply {
            text = "No screenshot perm!"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#80000000"))
            textSize = 10f
            setPadding(4, 2, 4, 2)
        }

        layout.addView(topRow)
        layout.addView(statusText)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 50
        }

        // Drag to move
        var dX = 0f; var dY = 0f
        layout.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dX = e.rawX - params.x; dY = e.rawY - params.y; false }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (e.rawX - dX).toInt()
                    params.y = (e.rawY - dY).toInt()
                    windowManager?.updateViewLayout(layout, params); true
                }
                else -> false
            }
        }

        btn.setOnClickListener {
            if (!solving.get()) triggerSolve()
        }

        cfgBtn.setOnClickListener { showConfigDialog(params, layout) }

        windowManager?.addView(layout, params)
        overlayView = layout
    }

    private fun showConfigDialog(overlayParams: WindowManager.LayoutParams, overlayView: View) {
        // Must allow focus to show dialog keyboard
        overlayParams.flags = overlayParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        windowManager?.updateViewLayout(overlayView, overlayParams)

        fun field(label: String, value: String) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 4, 8, 4)
            addView(TextView(this@OverlayService).apply {
                text = label; textSize = 13f; minWidth = 200
                setTextColor(Color.BLACK)
            })
            addView(EditText(this@OverlayService).apply {
                setText(value)
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                tag = label
                minWidth = 160
            })
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 8)
            val sv = ScrollView(this@OverlayService)
            val inner = LinearLayout(this@OverlayService).apply {
                orientation = LinearLayout.VERTICAL
                addView(field("colorMerge", Config.colorMerge.toString()))
                addView(field("nMin", Config.nMin.toString()))
                addView(field("nMax", Config.nMax.toString()))
                addView(field("squareCropRatio", Config.squareCropRatio.toString()))
                addView(field("dotNTolerance", Config.dotNTolerance.toString()))
            }
            sv.addView(inner)
            addView(sv)
        }

        // Service has no Activity window — wrap in ContextThemeWrapper so dialog renders correctly
        val themeCtx = android.view.ContextThemeWrapper(applicationContext, android.R.style.Theme_Material_Light_Dialog)
        val dialog = android.app.AlertDialog.Builder(themeCtx)
            .setTitle("Solver Config")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                fun et(label: String) = container.findViewWithTag<EditText>(label)?.text?.toString()
                et("colorMerge")?.toFloatOrNull()?.let { Config.colorMerge = it }
                et("nMin")?.toIntOrNull()?.let { Config.nMin = it }
                et("nMax")?.toIntOrNull()?.let { Config.nMax = it }
                et("squareCropRatio")?.toFloatOrNull()?.let { Config.squareCropRatio = it }
                et("dotNTolerance")?.toIntOrNull()?.let { Config.dotNTolerance = it }
                setStatus("Config saved!")
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Reset") { _, _ -> Config.reset(); setStatus("Config reset") }
            .create()

        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.setOnDismissListener {
            overlayParams.flags = overlayParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            windowManager?.updateViewLayout(overlayView, overlayParams)
        }
        dialog.show()
    }

    private fun setStatus(msg: String) {
        handler.post { statusText?.text = msg }
    }

    // ── Screenshot + solve ───────────────────────────────────────────────────

    /**
     * Full solve pass, mirroring solver.py:
     *   capture → Continue-dialog check → grid detect → clear board →
     *   fresh capture → rank all candidate configs → candidate retry loop.
     */
    private fun triggerSolve() {
        if (TapService.instance == null) {
            setStatus("Enable Accessibility!"); return
        }
        if (mediaProjection == null) {
            setStatus("No screenshot perm!"); return
        }
        solving.set(true)
        setStatus("Capturing...")

        Thread {
            try {
                // (a) capture
                val bmp = captureScreen()
                if (bmp == null) { setStatus("Screenshot failed"); solving.set(false); return@Thread }

                // (b) out of lives → tap Restart in the Continue dialog, then start over
                if (SolverEngine.hasContinueDialog(bmp)) {
                    val svc = TapService.instance
                    if (svc == null) { setStatus("Enable Accessibility!"); solving.set(false); return@Thread }
                    val dm = resources.displayMetrics
                    val rx = dm.widthPixels / 2
                    val ry = (dm.heightPixels * 0.68).toInt()
                    setStatus("Continue dialog → Restart")
                    handler.post {
                        svc.tap(rx, ry) {
                            handler.postDelayed({
                                solving.set(false)
                                triggerSolve()
                            }, 1500)
                        }
                    }
                    return@Thread
                }

                // (c) rank every candidate configuration — ONE sweep per solve.
                // analyzeAll() masks the whole frame pixel-by-pixel and runs
                // detectGrid itself, so every extra call visibly stalls the phone.
                // Its candidates already carry gridBounds, so no separate
                // detectGrid call is needed here.
                setStatus("Ranking configs...")
                val candidates = SolverEngine.analyzeAll(bmp)
                if (candidates.isEmpty()) {
                    setStatus("No candidate configs"); solving.set(false); return@Thread
                }
                val best = candidates[0]

                // (d) clear leftovers, then (e) run the candidate retry loop.
                // Tap coordinates depend only on bounds + cellSize, which clearing
                // does not change, so the ranked list stays valid afterwards.
                clearBoard(bmp, best.gridBounds, best.n) {
                    tryCandidate(candidates, 0)
                }

            } catch (e: Exception) {
                setStatus("Error: ${e.message}"); solving.set(false)
            }
        }.start()
    }

    /**
     * Remove animals / X marks left over from a previous run so the solve starts
     * from an empty grid. Per solver.py: an animal needs 1 tap to return to empty,
     * an X mark needs 2 taps (X → animal → empty).
     *
     * Must be called OFF the main thread (it inspects a bitmap).
     * [n] comes from the best candidate configuration; a guessed grid would tap
     * the wrong cells and corrupt a good board, so an untrustworthy n skips
     * clearing entirely. [onDone] is always invoked, on the main thread.
     */
    private fun clearBoard(bmp: Bitmap, bounds: IntArray, n: Int, onDone: () -> Unit) {
        val svc = TapService.instance
        if (svc == null) { handler.post { onDone() }; return }

        if (n < 2) { setStatus("Skip clear (no N)"); handler.post { onDone() }; return }

        val cellSize = (bounds[2] - bounds[0]) / n
        if (cellSize < 10) { setStatus("Skip clear (bad cell)"); handler.post { onDone() }; return }

        val occupied = SolverEngine.getOccupiedCells(
            bmp, bounds[0], bounds[1], bounds[2], bounds[3], n, cellSize
        )
        if (occupied.isEmpty()) { setStatus("Board is clean"); handler.post { onDone() }; return }

        setStatus("Clearing ${occupied.size} cells...")

        fun clearNext(i: Int) {
            if (i >= occupied.size) {
                handler.postDelayed({ onDone() }, 400)
                return
            }
            val (cx, cy, state) = occupied[i]
            val after = { handler.postDelayed({ clearNext(i + 1) }, 400) }
            if (state == 1) {
                // X mark: two taps (X → animal → empty); doubleTap is two taps 150ms apart
                svc.doubleTap(cx, cy) { after() }
            } else {
                // Animal: one tap back to empty
                svc.tap(cx, cy) { after() }
            }
        }

        handler.post { clearNext(0) }
    }

    /**
     * Try candidate [idx] of the ranked list (solver.py's
     * `for attempt, cand in enumerate(all_candidates[:MAX_RETRIES])` loop).
     *
     * Each cell is double-tapped (empty → X → animal); after every tap the screen
     * is re-read and checked for a red X. On a red X every cell placed during this
     * attempt — including the rejected one — is undone in reverse order and the
     * next candidate is tried. Gestures go on the handler, bitmap work on a
     * background thread. Must be called on the main thread.
     */
    private fun tryCandidate(candidates: List<SolveCandidate>, idx: Int) {
        if (idx >= candidates.size || idx >= MAX_RETRIES) {
            setStatus("All configs failed"); solving.set(false); return
        }
        val svc = TapService.instance
        if (svc == null) { setStatus("Enable Accessibility!"); solving.set(false); return }

        val cand = candidates[idx]
        if (cand.taps.isEmpty()) {
            handler.post { tryCandidate(candidates, idx + 1) }
            return
        }

        val gb = cand.gridBounds
        if (gb.size < 4) {
            handler.post { tryCandidate(candidates, idx + 1) }
            return
        }

        setStatus("Try ${idx + 1}: N=${cand.n} th=${cand.thresh}")

        // Cells placed during THIS attempt only (solver.py THIS_ATTEMPT_CELLS)
        val placed = mutableListOf<Pair<Int, Int>>()

        fun undoThenNext(i: Int) {
            if (i < 0) {
                placed.clear()
                handler.postDelayed({ tryCandidate(candidates, idx + 1) }, 500)
                return
            }
            val (ux, uy) = placed[i]
            // Animal → empty is a single tap
            svc.tap(ux, uy) {
                handler.postDelayed({ undoThenNext(i - 1) }, 350)
            }
        }

        fun tapNext(i: Int) {
            if (i >= cand.taps.size) {
                setStatus("Done!"); solving.set(false); return
            }
            val (x, y) = cand.taps[i]
            // empty → X → animal
            svc.doubleTap(x, y) {
                handler.postDelayed({
                    Thread {
                        var red = false
                        try {
                            val chk = captureScreen()
                            red = chk != null &&
                                SolverEngine.hasRedX(chk, x, y, gb[0], gb[1], gb[2], gb[3])
                        } catch (e: Exception) {
                            red = false
                        }
                        val sawRedX = red
                        handler.post {
                            // The rejected cell was tapped too, so it must be undone as well
                            placed.add(x to y)
                            if (sawRedX) {
                                setStatus("Red X on try ${idx + 1} — undoing")
                                undoThenNext(placed.size - 1)
                            } else {
                                tapNext(i + 1)
                            }
                        }
                    }.start()
                }, 500)
            }
        }

        handler.post { tapNext(0) }
    }

    private fun captureScreen(): Bitmap? {
        // VirtualDisplay is kept alive from onStartCommand; just drain stale frames
        val ir = imageReader ?: return null
        Thread.sleep(200)  // let current frame arrive

        val image = ir.acquireLatestImage() ?: run {
            Thread.sleep(500)
            ir.acquireLatestImage()
        } ?: return null

        val w = image.width; val h = image.height
        val planes = image.planes[0]
        val buf = planes.buffer
        val rowStride = planes.rowStride
        val pixelStride = planes.pixelStride
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rowData = ByteArray(rowStride)
        for (row in 0 until h) {
            buf.get(rowData, 0, rowStride)
            var col = 0; var pix = 0
            while (col < rowStride && pix < w) {
                val r = rowData[col].toInt() and 0xFF
                val g = rowData[col+1].toInt() and 0xFF
                val b = rowData[col+2].toInt() and 0xFF
                bmp.setPixel(pix, row, Color.rgb(r, g, b))
                col += pixelStride; pix++
            }
        }
        image.close()
        return bmp
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Puzzle Solver", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Puzzle Solver")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }
}

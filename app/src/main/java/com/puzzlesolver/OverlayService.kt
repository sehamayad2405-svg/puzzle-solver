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
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean

class OverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val CHANNEL_ID = "solver_channel"
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

        val btn = Button(this).apply {
            text = "SOLVE"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            textSize = 12f
        }

        statusText = TextView(this).apply {
            text = "No screenshot perm!"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#80000000"))
            textSize = 10f
            setPadding(4, 2, 4, 2)
        }

        layout.addView(btn)
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

        windowManager?.addView(layout, params)
        overlayView = layout
    }

    private fun setStatus(msg: String) {
        handler.post { statusText?.text = msg }
    }

    // ── Screenshot + solve ───────────────────────────────────────────────────

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
                val bmp = captureScreen()
                if (bmp == null) { setStatus("Screenshot failed"); solving.set(false); return@Thread }

                setStatus("Solving...")
                val result = SolverEngine.analyze(bmp)

                if (result.tapCoords.isEmpty()) {
                    setStatus(result.status); solving.set(false); return@Thread
                }

                setStatus("Tapping ${result.tapCoords.size} cells...")
                tapSequence(result.tapCoords) {
                    setStatus("Done! ${result.status}")
                    solving.set(false)
                    // Auto restart after 4 seconds for next level
                    handler.postDelayed({ triggerSolve() }, 4000)
                }

            } catch (e: Exception) {
                setStatus("Error: ${e.message}"); solving.set(false)
            }
        }.start()
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

    private fun tapSequence(coords: List<Pair<Int,Int>>, onFinished: () -> Unit) {
        val svc = TapService.instance ?: return
        fun tapNext(idx: Int) {
            if (idx >= coords.size) { onFinished(); return }
            val (x, y) = coords[idx]
            svc.doubleTap(x, y) {
                handler.postDelayed({ tapNext(idx + 1) }, 650)
            }
        }
        handler.post { tapNext(0) }
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

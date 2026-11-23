package com.puzzlesolver

import android.app.Activity
import android.content.*
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.opencv.android.OpenCVLoader

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_MEDIA = 1001
    }

    private lateinit var tvStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpenCVLoader.initLocal()
        Config.init(this)
        setContentView(R.layout.activity_main)

        tvStatus  = findViewById(R.id.tvStatus)
        btnStart  = findViewById(R.id.btnStart)
        btnStop   = findViewById(R.id.btnStop)

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }

        btnStart.setOnClickListener { requestScreenCapture() }
        btnStop.setOnClickListener  { stopSolver() }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val tapOk     = TapService.instance != null
        val overlayOk = Settings.canDrawOverlays(this)
        when {
            !tapOk && !overlayOk ->
                tvStatus.text = "Step 1: Enable Accessibility Service, then allow Overlay"
            !tapOk ->
                tvStatus.text = "Step 1: Enable Accessibility Service for Puzzle Solver"
            !overlayOk ->
                tvStatus.text = "Step 2: Allow Overlay Permission"
            else ->
                tvStatus.text = "Ready! Tap START SOLVER, then open your puzzle game."
        }
        btnStart.isEnabled = tapOk && overlayOk && !OverlayService.isRunning
        btnStop.visibility  = if (OverlayService.isRunning) android.view.View.VISIBLE else android.view.View.GONE
        btnStart.visibility = if (OverlayService.isRunning) android.view.View.GONE   else android.view.View.VISIBLE
    }

    private fun requestScreenCapture() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_MEDIA)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_MEDIA && resultCode == Activity.RESULT_OK && data != null) {
            val intent = Intent(this, OverlayService::class.java).apply {
                putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
                putExtra(OverlayService.EXTRA_RESULT_DATA, data)
            }
            startForegroundService(intent)
            Toast.makeText(this, "Solver started! Open your game.", Toast.LENGTH_LONG).show()
            finish()  // go back to game
        }
    }

    private fun stopSolver() {
        stopService(Intent(this, OverlayService::class.java))
        updateStatus()
    }
}

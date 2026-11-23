package com.puzzlesolver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class TapService : AccessibilityService() {

    companion object {
        var instance: TapService? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun tap(x: Int, y: Int, delayMs: Long = 0, onDone: (() -> Unit)? = null) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, delayMs, 60)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { onDone?.invoke() }
            override fun onCancelled(g: GestureDescription?) { onDone?.invoke() }
        }, null)
    }

    fun doubleTap(x: Int, y: Int, onDone: (() -> Unit)? = null) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val s1 = GestureDescription.StrokeDescription(path, 0,   60)
        val s2 = GestureDescription.StrokeDescription(path, 150, 60)
        val gesture = GestureDescription.Builder().addStroke(s1).addStroke(s2).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { onDone?.invoke() }
            override fun onCancelled(g: GestureDescription?) { onDone?.invoke() }
        }, null)
    }
}

package com.puzzlesolver

import android.content.Context
import android.content.SharedPreferences

object Config {

    private const val PREFS = "solver_config"
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    // ── Tuneable parameters ──────────────────────────────────────────────────

    /** Color clustering distance threshold (lower = more distinct colors) */
    var colorMerge: Float
        get() = prefs.getFloat("colorMerge", 40f)
        set(v) = prefs.edit().putFloat("colorMerge", v).apply()

    /** Minimum N to try */
    var nMin: Int
        get() = prefs.getInt("nMin", 6)
        set(v) = prefs.edit().putInt("nMin", v).apply()

    /** Maximum N to try */
    var nMax: Int
        get() = prefs.getInt("nMax", 13)
        set(v) = prefs.edit().putInt("nMax", v).apply()

    /** Grid height/width ratio above which height is cropped to width */
    var squareCropRatio: Float
        get() = prefs.getFloat("squareCropRatio", 1.15f)
        set(v) = prefs.edit().putFloat("squareCropRatio", v).apply()

    /** HoughCircles vs edgeN tolerance: ignore dotN if |dotN-edgeN| > this */
    var dotNTolerance: Int
        get() = prefs.getInt("dotNTolerance", 3)
        set(v) = prefs.edit().putInt("dotNTolerance", v).apply()

    fun reset() {
        prefs.edit().clear().apply()
    }

    fun summary(): String =
        "colorMerge=$colorMerge  N=$nMin..$nMax  squareCrop=$squareCropRatio  dotTol=$dotNTolerance"
}

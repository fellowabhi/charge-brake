package com.fellowabhi.chargebrake

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Activity.dpf(v: Float) = v * resources.displayMetrics.density

fun Activity.text(size: Float, color: Int = C_TXT, bold: Boolean = false, mono: Boolean = false, txt: String = "") = TextView(this).apply {
    textSize = size; setTextColor(color); text = txt
    typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    includeFontPadding = false
}

fun round(color: Int, r: Float, strokeColor: Int = 0, strokeW: Int = 0) = GradientDrawable().apply {
    setColor(color); cornerRadius = r; if (strokeW > 0) setStroke(strokeW, strokeColor)
}

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f, top: Int = 0) =
    LinearLayout.LayoutParams(w, h, weight).apply { topMargin = top }

fun LinearLayout.add(v: View, top: Int = 0, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, w: Int = ViewGroup.LayoutParams.MATCH_PARENT, weight: Float = 0f): View {
    addView(v, lp(w, h, weight, top)); return v
}

fun Activity.row(vararg vs: View, gap: Int = 8) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    vs.forEachIndexed { k, v -> addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (k > 0) leftMargin = dp(gap) }) }
}

/** Selectable chip. */
fun Activity.chip(label: String, on: Boolean, onClick: () -> Unit) = text(13f, if (on) 0xFF7FE3EE.toInt() else C_DIM, txt = label).apply {
    setPadding(dp(12), dp(7), dp(12), dp(7))
    background = if (on) round(0xFF123A40.toInt(), dpf(99f), 0xFF1F6E77.toInt(), dp(1)) else round(0, dpf(99f), C_LINE, dp(1))
    setOnClickListener { onClick() }
}

fun Activity.slider(parent: LinearLayout, label: String, min: Int, max: Int, step: Int, value: Int, fmt: (Int) -> String, set: (Int) -> Unit) {
    val tv = text(14f, C_DIM)
    val sb = SeekBar(this).apply { this.max = (max - min) / step; progress = ((value - min) / step).coerceIn(0, this.max) }
    fun upd(v: Int) { tv.text = "$label: ${fmt(v)}" }
    upd(min + sb.progress * step)
    sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) { upd(min + p * step) }
        override fun onStartTrackingTouch(s: SeekBar) {}
        override fun onStopTrackingTouch(s: SeekBar) { set(min + s.progress * step) }
    })
    parent.add(tv, dp(12)); parent.add(sb, dp(2))
}

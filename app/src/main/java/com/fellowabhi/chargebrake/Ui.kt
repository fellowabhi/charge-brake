package com.fellowabhi.chargebrake

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView

const val C_TXT = 0xFFE8EAED.toInt()
const val C_DIM = 0xFF8A8F98.toInt()
const val C_ACC = 0xFF26C6DA.toInt()
const val C_ON = 0xFFE64A19.toInt()
const val C_CARD = 0xFF1C1F26.toInt()

fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Activity.text(size: Float, color: Int = C_TXT, bold: Boolean = false, mono: Boolean = false) = TextView(this).apply {
    textSize = size; setTextColor(color)
    typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
}

fun Activity.button(label: String, bg: Int = C_CARD, onClick: () -> Unit) = Button(this).apply {
    text = label; isAllCaps = false; setTextColor(C_TXT); textSize = 15f
    background = round(bg, dp(12).toFloat())
    stateListAnimator = null
    setOnClickListener { onClick() }
}

fun round(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = r }

fun LinearLayout.add(v: View, top: Int = 0, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT): View {
    addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply { topMargin = top })
    return v
}

fun Activity.seg(parent: LinearLayout, labels: List<String>, sel: Int, onSel: (Int) -> Unit) {
    val g = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
    labels.forEachIndexed { k, s ->
        g.addView(RadioButton(this).apply { id = 100 + k; text = s; setTextColor(C_TXT); textSize = 14f; gravity = Gravity.CENTER_VERTICAL },
            RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
    g.check(100 + sel)
    g.setOnCheckedChangeListener { _, id -> onSel(id - 100) }
    parent.add(g, dp(4))
}

fun Activity.slider(parent: LinearLayout, label: String, min: Int, max: Int, step: Int, value: Int, fmt: (Int) -> String, set: (Int) -> Unit) {
    val tv = text(14f, C_DIM)
    val sb = SeekBar(this).apply { this.max = (max - min) / step; progress = ((value - min) / step).coerceIn(0, this.max) }
    fun upd(v: Int) { tv.text = "$label: ${fmt(v)}" }
    upd(min + sb.progress * step)
    sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) { val v = min + p * step; upd(v); if (user) { set(v); Monitor.save() } }
        override fun onStartTrackingTouch(s: SeekBar) {}
        override fun onStopTrackingTouch(s: SeekBar) {}
    })
    parent.add(tv, dp(10)); parent.add(sb)
}

package com.fellowabhi.chargebrake

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** The state banner: vector icon, auto-fitted one-line title + detail, round action button. Also rendered offscreen for checks. */
class BannerViews(private val a: Activity) {
    val root = GlowFrame(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; pad(a.dp(10), a.dp(9), a.dp(10), a.dp(9)) }
    val icon = ImageView(a).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(a.dp(6), a.dp(6), a.dp(6), a.dp(6)) }
    val title = FitLabel(a, 16f, 11f, bold = true)
    val sub = FitLabel(a, 12.5f, 9.5f)
    val action: TextView = a.text(14f, 0xFF1A0E08.toInt(), bold = true, txt = "Start").apply { gravity = Gravity.CENTER; maxLines = 1 }
    private var iconRes = 0
    init {
        root.addView(icon, LinearLayout.LayoutParams(a.dp(32), a.dp(32)))
        val t = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        t.addView(title, LinearLayout.LayoutParams(-1, -2)); t.addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(3) })
        root.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = a.dp(10) })
        root.addView(action, LinearLayout.LayoutParams(a.dp(56), a.dp(56)).apply { leftMargin = a.dp(8) })
    }
    /** @param glowK 0..1 how much glow the current visual level allows. */
    fun bind(st: St, m: Monitor, col: Int, pulse: Float, glowK: Float) {
        title.text = Look.title(st, m); sub.text = Look.sub(st, m)
        title.color = col; title.glowColor = withAlpha(col, 0.6f); title.glow = a.dpf(7f) * glowK
        sub.color = 0xFFD9DCE1.toInt()
        val r = Look.uiIcon(st); if (r != iconRes) { iconRes = r; icon.setImageResource(r) }
        icon.setColorFilter(col)
        icon.background = round(withAlpha(col, 0.14f + 0.12f * pulse * glowK), a.dpf(16f))
        val act = Look.action(st)
        if (act == null) { if (action.visibility != android.view.View.GONE) action.visibility = android.view.View.GONE } else {
            if (action.visibility != android.view.View.VISIBLE) action.visibility = android.view.View.VISIBLE
            if (action.text != act) { action.text = act; action.textSize = if (act.length > 5) 12f else 14f }
            val bc = if (act == "Ignore") C_GREY else C_ORANGE
            action.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(lerpColor(bc, 0xFFFFFFFF.toInt(), 0.10f * pulse * glowK)) }
        }
        root.glowColor = col; root.glow = (0.35f + 0.35f * pulse) * glowK; root.tint = 1.4f; root.stroke = 1f; root.invalidate()
    }
}

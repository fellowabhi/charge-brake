package com.fellowabhi.chargebrake

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

const val C_BG = 0xFF0B0C0F.toInt()
const val C_CARD = 0xFF16191F.toInt()
const val C_LINE = 0xFF2A2F38.toInt()
const val C_TXT = 0xFFE8EAED.toInt()
const val C_DIM = 0xFF8E949E.toInt()
const val C_FAINT = 0xFF5D636D.toInt()
const val C_ORANGE = 0xFFFF7043.toInt()
const val C_GREEN = 0xFF4CD07D.toInt()
const val C_BLUE = 0xFF4FA3FF.toInt()
const val C_AMBER = 0xFFFFB300.toInt()
const val C_RED = 0xFFEF5350.toInt()
const val C_GREY = 0xFF9AA0A6.toInt()
const val C_TEAL = 0xFF26C6DA.toInt()

fun lerpColor(a: Int, b: Int, k: Float): Int {
    fun ch(s: Int) = ((a shr s and 255) + (((b shr s and 255) - (a shr s and 255)) * k)).roundToInt().coerceIn(0, 255)
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}
fun withAlpha(c: Int, a: Float) = (c and 0xFFFFFF) or ((a.coerceIn(0f, 1f) * 255).roundToInt() shl 24)

fun numColor(mw: Float) = when { mw > 150f -> C_AMBER; mw < -150f -> C_BLUE; else -> C_GREEN }

fun fmtInt(v: Float) = String.format(Locale.US, "%,d", v.roundToInt())
fun fmtSigned(v: Float) = (if (v >= 0) "+" else "−") + fmtInt(abs(v))

/** Colour, icon and plain-words text for each state. */
object Look {
    fun color(s: St) = when (s) {
        St.BRAKING -> C_ORANGE
        St.HOLDING, St.IDLE -> C_GREEN
        St.IN_USE -> C_BLUE
        St.CHARGING_UP -> C_AMBER
        St.HOT, St.NO_READING -> C_RED
        St.FAST, St.PAUSED, St.OFF, St.UNPLUGGED -> C_GREY
    }
    fun glyph(s: St) = when (s) {
        St.BRAKING -> "‖"; St.HOLDING, St.IDLE -> "✓"; St.IN_USE -> "↓"; St.CHARGING_UP -> "↑"
        St.HOT -> "!"; St.NO_READING -> "?"; St.PAUSED -> "‖"; else -> "⏻"
    }
    fun icon(s: St) = when (s) {
        St.BRAKING -> R.drawable.ic_st_brake
        St.HOLDING, St.IDLE, St.IN_USE -> R.drawable.ic_st_hold
        St.CHARGING_UP -> R.drawable.ic_st_charge
        St.HOT, St.PAUSED, St.FAST, St.NO_READING -> R.drawable.ic_st_paused
        St.OFF, St.UNPLUGGED -> R.drawable.ic_st_off
    }
    fun title(s: St, m: Monitor) = when (s) {
        St.OFF -> "OFF"
        St.UNPLUGGED -> "UNPLUGGED"
        St.CHARGING_UP -> "CHARGING UP"
        St.BRAKING -> "BRAKING"
        St.HOLDING, St.IDLE -> "HOLDING ${m.pct}%"
        St.IN_USE -> "IN USE"
        St.HOT -> "PAUSED: TOO HOT"
        St.FAST -> "OFF: FAST CHARGING"
        St.NO_READING -> "NO POWER READING"
        St.PAUSED -> "PAUSED"
    }
    fun sub(s: St, m: Monitor): String {
        val c = m.ctl; val bmw = (m.burnMw / 10f).roundToInt() * 10
        return when (s) {
            St.OFF -> "tap Brake to hold the level while plugged in"
            St.UNPLUGGED -> "brake idle · plug in to hold the level"
            St.CHARGING_UP -> "to ${c.hold()}% target · brake idle"
            St.BRAKING -> "using ~$bmw mW to pull net to 0"
            St.HOLDING -> "brake using ~$bmw mW"
            St.IDLE -> "no surplus · brake not needed"
            St.IN_USE -> "phone uses more than the charger gives · brake backing off"
            St.HOT -> String.format(Locale.US, "%.1f °C ≥ %.0f °C cutoff · resumes below %.0f °C", m.temp, m.cfg.tempCutoffC, m.cfg.tempCutoffC - 1)
            St.FAST -> String.format(Locale.US, "detected %.1f W · retry in %d:%02d", c.fastMw / 1000f, c.fastRetryInS / 60, c.fastRetryInS % 60)
            St.NO_READING -> "current sensor gives no data · switch mode to Auto or %"
            St.PAUSED -> "paused by you · resume from the notification"
        }
    }
}

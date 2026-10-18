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
const val C_PURPLE = 0xFFB388FF.toInt()
const val C_TEAL = 0xFF26C6DA.toInt()

fun lerpColor(a: Int, b: Int, k: Float): Int {
    fun ch(s: Int) = ((a shr s and 255) + (((b shr s and 255) - (a shr s and 255)) * k)).roundToInt().coerceIn(0, 255)
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}
fun withAlpha(c: Int, a: Float) = (c and 0xFFFFFF) or ((a.coerceIn(0f, 1f) * 255).roundToInt() shl 24)

fun numColor(mw: Float) = when { mw > 100f -> C_AMBER; mw < -100f -> C_BLUE; else -> C_GREEN }

fun fmtInt(v: Float) = String.format(Locale.US, "%,d", v.roundToInt())
fun fmtSigned(v: Float) = (if (v >= 0) "+" else "−") + fmtInt(abs(v))

/** Colour, icon and plain-words text for each state. */
object Look {
    fun color(s: St) = when (s) {
        St.BRAKING -> C_ORANGE
        St.DRAINING -> C_PURPLE
        St.HOLDING, St.IDLE -> C_GREEN
        St.IN_USE -> C_BLUE
        St.CHARGING_UP -> C_AMBER
        St.HOT, St.NO_READING -> C_RED
        St.FAST, St.PAUSED, St.OFF, St.UNPLUGGED -> C_GREY
    }
    fun glyph(s: St) = when (s) {
        St.BRAKING -> "‖"; St.DRAINING -> "↘"; St.HOLDING, St.IDLE -> "✓"; St.IN_USE -> "↓"; St.CHARGING_UP -> "↑"
        St.HOT -> "!"; St.NO_READING -> "?"; St.PAUSED -> "‖"; St.FAST -> "!"; else -> "×"
    }
    fun icon(s: St) = when (s) {
        St.BRAKING -> R.drawable.ic_st_brake
        St.DRAINING -> R.drawable.ic_st_drain
        St.HOLDING, St.IDLE, St.IN_USE -> R.drawable.ic_st_hold
        St.CHARGING_UP -> R.drawable.ic_st_charge
        St.HOT, St.PAUSED, St.FAST, St.NO_READING -> R.drawable.ic_st_paused
        St.OFF, St.UNPLUGGED -> R.drawable.ic_st_off
    }
    /** mAh the hold aims at: the controller's exact target; when off in target mode, the converted target. */
    fun goalMah(m: Monitor): Float {
        val t = m.ctl.targetMah
        if (m.enabled && !t.isNaN()) return t
        if (m.enabled) return m.ctl.goal()
        if (m.cfg.holdCurrent) return Float.NaN
        if (!m.cfg.targetMahSet.isNaN()) return m.cfg.targetMahSet
        return if (m.mah.isNaN()) Float.NaN else m.mah + (m.cfg.targetPct - m.pct) * m.cfg.capacityMah / 100f
    }
    private fun goalTxt(m: Monitor) = m.mahLabel(goalMah(m))
    private fun toGo(m: Monitor, down: Boolean): String {
        val t = goalMah(m)
        return if (t.isNaN() || m.mah.isNaN()) "" else (if (down) "~" else "") + fmtInt(maxOf(0f, if (down) m.mah - t else t - m.mah)) + " mAh to go · "
    }
    /** What the brake would hold if started now. */
    fun planned(m: Monitor) = if (m.cfg.holdCurrent) "the current " + (if (m.mah.isNaN()) "${m.pct}%" else "${fmtInt(m.mah)} mAh") else goalTxt(m)

    fun title(s: St, m: Monitor): String {
        val c = m.ctl
        return when (s) {
            St.OFF -> if (m.plugged == 0) "Off · not plugged in" else "Off"
            St.UNPLUGGED -> "Unplugged · waiting"
            St.CHARGING_UP -> "Charging up to ${goalTxt(m)}"
            St.DRAINING -> "Draining to ${goalTxt(m)}"
            St.BRAKING -> "Hold ${goalTxt(m)} · braking"
            St.HOLDING, St.IDLE -> "Hold ${goalTxt(m)}"
            St.IN_USE -> "Hold ${goalTxt(m)} · phone in use"
            St.HOT -> String.format(Locale.US, "Paused: too hot (%.1f °C)", m.temp)
            St.FAST -> String.format(Locale.US, "Off: fast charging (%.1f W)", c.fastMw / 1000f)
            St.NO_READING -> "No power reading"
            St.PAUSED -> "Paused"
        }
    }
    fun sub(s: St, m: Monitor): String {
        val c = m.ctl; val bmw = (m.burnMw / 10f).roundToInt() * 10
        return when (s) {
            St.OFF -> if (m.plugged == 0) "Plug in, then Start to hold ${planned(m)}" else "Start to hold ${planned(m)} while plugged in"
            St.UNPLUGGED -> "brake idle · plug in to hold ${goalTxt(m)}"
            St.CHARGING_UP -> toGo(m, false) + "charging normally, brake idle"
            St.DRAINING -> toGo(m, true) + "${Drain.name(m.cfg.drainSpeed)} drain (${Drain.desc(m.cfg.drainSpeed)}) · then holds"
            St.BRAKING -> "brake ~$bmw mW pulls the net to 0"
            St.HOLDING -> "brake ~$bmw mW"
            St.IDLE -> "no surplus · brake not needed"
            St.IN_USE -> "phone uses more than the charger gives · brake backing off"
            St.HOT -> String.format(Locale.US, "cutoff %.0f °C · resumes below %.0f °C", m.cfg.tempCutoffC, m.cfg.tempCutoffC - 1)
            St.FAST -> String.format(Locale.US, "brake off to protect the charger · retry in %d:%02d", c.fastRetryInS / 60, c.fastRetryInS % 60)
            St.NO_READING -> "current sensor gives no data · switch mode to Auto or %"
            St.PAUSED -> "paused by you · holding ${goalTxt(m)} when resumed"
        }
    }
    /** Banner action button for this state, or null. */
    fun action(s: St) = when (s) { St.OFF -> "Start"; St.PAUSED -> "Resume"; St.FAST -> "Ignore"; else -> null }
}

fun fmtDur(min: Float): String {
    val m = min.roundToInt()
    return if (m < 60) "$m m" else "${m / 60} h ${String.format(Locale.US, "%02d", m % 60)} m"
}

/** Hold-quality tile text: value, detail line, colour. */
object Quality {
    fun value(m: Monitor): String {
        val q = m.minPerPct
        return when {
            q.isNaN() -> "measuring…"
            q.isInfinite() -> "∞ · steady"
            else -> "~${fmtDur(q)} per 1%"
        }
    }
    fun grade(q: Float) = when { q.isNaN() -> ""; q >= 90f -> "excellent"; q >= 60f -> "good"; q >= 10f -> "fair"; else -> "poor" }
    fun color(q: Float) = when { q.isNaN() -> C_GREY; q >= 60f -> C_GREEN; q >= 10f -> C_AMBER; else -> C_RED }
    fun sub(m: Monitor): String {
        val q = m.minPerPct; val d = m.driftMw
        return when {
            q.isNaN() -> "5 min average of the net · needs 1 min of holding"
            q.isInfinite() -> "drift within noise (±${fmtInt(m.driftNoise)} mW) · excellent"
            else -> (if (d > 0) "charging " else "draining ") + fmtSigned(d) + " mW (5 min avg) · " + grade(q) +
                (if (m.driftReason.isNotEmpty()) " · " + m.driftReason else "")
        }
    }
}

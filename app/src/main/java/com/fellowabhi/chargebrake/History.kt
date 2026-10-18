package com.fellowabhi.chargebrake

/** Ring buffer of recent samples for the charts. Access under synchronized(this). */
class History(val cap: Int) {
    val t = LongArray(cap); val net = FloatArray(cap); val ema = FloatArray(cap)
    val burn = FloatArray(cap); val tgt = FloatArray(cap); val pct = IntArray(cap)
    val mah = FloatArray(cap); val tmah = FloatArray(cap)
    var size = 0; private set
    private var head = 0

    fun idx(k: Int) = (head - size + k + 2 * cap) % cap
    fun first(t0: Long) = firstAfter(t0, size) { t[idx(it)] }

    @Synchronized fun add(tt: Long, n: Float, e: Float, b: Float, tg: Float, p: Int, m: Float, tm: Float) {
        t[head] = tt; net[head] = n; ema[head] = e; burn[head] = b; tgt[head] = tg; pct[head] = p; mah[head] = m; tmah[head] = tm
        head = (head + 1) % cap
        if (size < cap) size++
    }
}

/** Long, slow history for the charge graph (mAh, target mAh, %, state). */
class ChargeHistory(val cap: Int) {
    val t = LongArray(cap); val mah = FloatArray(cap); val tmah = FloatArray(cap); val pct = IntArray(cap)
    val st = arrayOfNulls<St>(cap); val net = FloatArray(cap)
    var size = 0; private set
    private var head = 0
    fun idx(k: Int) = (head - size + k + 2 * cap) % cap
    fun first(t0: Long) = firstAfter(t0, size) { t[idx(it)] }
    @Synchronized fun add(tt: Long, m: Float, tm: Float, p: Int, s: St, n: Float = 0f) {
        t[head] = tt; mah[head] = m; tmah[head] = tm; pct[head] = p; st[head] = s; net[head] = n
        head = (head + 1) % cap
        if (size < cap) size++
    }
    /** mAh source switched: move the stored curve (and its target line) by d so it joins the new scale. */
    @Synchronized fun shift(d: Float) { for (k in 0 until size) { val i = idx(k); mah[i] += d; tmah[i] += d } }
}

/** Index of the last sample before t0 (so the line starts at the left edge), by binary search. */
inline fun firstAfter(t0: Long, n: Int, t: (Int) -> Long): Int {
    var lo = 0; var hi = n
    while (lo < hi) { val m = (lo + hi) ushr 1; if (t(m) < t0) lo = m + 1 else hi = m }
    return maxOf(0, lo - 1)
}

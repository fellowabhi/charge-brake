package com.fellowabhi.chargebrake

/** Ring buffer of recent samples for the charts. Access under synchronized(this). */
class History(val cap: Int) {
    val t = LongArray(cap); val net = FloatArray(cap); val ema = FloatArray(cap)
    val burn = FloatArray(cap); val tgt = FloatArray(cap); val pct = IntArray(cap)
    val mah = FloatArray(cap); val tmah = FloatArray(cap)
    var size = 0; private set
    private var head = 0

    fun idx(k: Int) = (head - size + k + 2 * cap) % cap

    @Synchronized fun add(tt: Long, n: Float, e: Float, b: Float, tg: Float, p: Int, m: Float, tm: Float) {
        t[head] = tt; net[head] = n; ema[head] = e; burn[head] = b; tgt[head] = tg; pct[head] = p; mah[head] = m; tmah[head] = tm
        head = (head + 1) % cap
        if (size < cap) size++
    }
}

package com.ramim.homedragon

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The fire charge-up, shared by the dragon on the home screen and the Charge-up time preview.
 *
 * It starts at 9 points: the tail tip and the 8 claw tips of the two wings. Each one is a steady glowing source that sends out ONE wave during
 * the charge-up: from a claw tip along the finger bone to the wrist, then the arm bone to the shoulder; from the tail tip along the spine
 * spikes to the shoulder and on up the neck into the mouth, arriving at about 85% of the charge. Behind the wave the bones and spikes stay
 * softly lit until the breath ends, then everything fades out. The wave thickness (th, 0.5..2) widens the wave and its glow. In the middle of the open mouth an orb grows and imploding particles (straight lines, accelerating, up to 120) are pulled into
 * it; during the breath the orb shrinks steadily and is smallest when the breath ends. Short electric arcs (up to 12 gaps between the spine
 * spikes at a time, a new random set 10 times a second) run for the whole charge and breath. The Charge-up quality (cq, 0.1..1) scales the
 * number of arcs, particles and waves together. Colours come from an 8-step gradient of the flame colours (index 0 = hot core .. 7 = cool tip).
 */
class ChargeFx(private val model: DragonModel, private val p: Painter) {

    interface Painter {
        /** Soft glow in gradient colour [i] (0 hot .. 7 cool), [half] = half size in pixels. */
        fun glow(i: Int, x: Float, y: Float, half: Float, alpha: Float)
        /** Dark soft halo behind a glow, so it stays visible on bright wallpapers. */
        fun halo(x: Float, y: Float, half: Float, alpha: Float)
        /** Thin lines (pairs of points: count = number of floats) in gradient colour [colorIdx]. */
        fun lines(pts: FloatArray, count: Int, colorIdx: Int, width: Float, alpha: Float)
    }

    private val cbx = FloatArray(16); private val cby = FloatArray(16)
    private val ctx = FloatArray(16); private val cty = FloatArray(16); private val cfr = FloatArray(16)
    private val chB = FloatArray(16)
    private val wvK = FloatArray(16)
    private val scK = FloatArray(16)
    private val nearW = FloatArray(14); private val farW = FloatArray(14)
    private val orbP = FloatArray(2)
    private val tipP = FloatArray(2)
    private val arcP = FloatArray(12)
    private val trl = FloatArray(4)

    // values of the current frame, so the helpers below need short argument lists
    private var ds = 1f; private var time = 0f; private var amt = 0f
    private var build = 0f; private var rate = 0f
    private var tc = 0f                 // seconds since the charge began
    private var travel = 0.9f           // seconds the wave needs from the claw tip / tail tip to the shoulder
    private var wd = 0.13f              // half width of the wave along the path
    private var th = 1f                 // wave thickness
    private var x0w = 0f                // front of the wave

    private fun sm(a: Float, b: Float, x: Float): Float {
        val u = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }

    private fun hash(x: Int, y: Int, sd: Int): Float {
        var h = x * 374761393 + y * 668265263 + sd * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f
    }

    /** Gradient index for "heat" g: 0 = cool (claw tip / tail tip) .. 1 = hot (neck, mouth). */
    private fun gi(g: Float): Int = ((1f - g.coerceIn(0f, 1f)) * 7f + 0.5f).toInt().coerceIn(0, 7)

    /** Strength of the single wave at position x on a path (0 = claw tip / tail tip, 1 = shoulder, 1.3 = mouth). */
    private fun waveSum(x: Float): Float {
        val d = (x - tc / travel) / wd
        return if (d > 4f || d < -4f) 0f else exp(-d * d)
    }

    /**
     * u = charge progress 0..1; ft = seconds since the breath started (negative while charging); fe = seconds since the breath ended
     * (0 or negative until then); fireDur = length of the breath; charging = still charging; cq = Charge-up quality 0.1..1; thick = wave thickness 0.5..2.
     */
    fun draw(st: DragonModel.State, scale: Float, now: Float, chargeT: Float, u: Float, ft: Float, fe: Float, fireDur: Float, charging: Boolean, cq: Float, thick: Float) {
        if (chargeT <= 0.05f) return
        ds = scale; time = now
        val fade = if (charging || fe <= 0f) 1f else 1f - sm(0f, 1f, fe)         // after the breath everything fades out in one second
        amt = if (charging) sm(0f, 0.1f, u) else fade
        val sparkAmt = if (charging) (0.35f + 0.65f * u) * sm(0.1f, 0.3f, u) else fade
        if (amt <= 0.01f && sparkAmt <= 0.01f) return
        val pw = 0.75f + 0.25f * (chargeT / 1.5f).coerceIn(0f, 1.6f)           // longer charge = bigger, brighter finish
        build = 0.5f + 0.5f * u
        rate = 14f + 26f * u
        tc = if (charging) u * chargeT else chargeT + max(0f, ft)
        travel = (chargeT * 0.65f).coerceAtLeast(0.3f)                              // the wave reaches the mouth (1.3 x travel) at about 85%
        th = thick.coerceIn(0.5f, 2f)
        wd = 0.13f * th
        x0w = tc / travel
        val n = model.chargePoints(st, cbx, cby, ctx, cty, cfr)
        val hasNear = model.wingPoints(st, false, nearW)
        val hasFar = model.wingPoints(st, true, farW)

        // where the shoulders are along the spine: the waves of the tail and of the wings reach it together
        var fs = 0.8f
        if (hasNear) {
            var best = 1e9f
            for (k in 1 until n) { val d = hypot(cbx[k] - nearW[0], cby[k] - nearW[1]); if (d < best) { best = d; fs = cfr[k] } }
        }
        fs = fs.coerceIn(0.5f, 0.9f)

        // spikes: brightness (and dark halos first, so the glows are drawn on top of them)
        for (k in 0 until n) {
            val xk = if (cfr[k] <= fs) cfr[k] / fs else 1f + 0.3f * (cfr[k] - fs) / max(0.05f, 0.97f - fs)
            val wv = waveSum(xk)
            wvK[k] = wv
            val lit = if (k == 0) 1f else sm(0f, 0.08f, x0w - xk + 0.04f)
            val fl = 0.85f + 0.15f * sin(time * rate + k * 0.9f)
            var b = min(1f, lit * (0.26f + 0.18f * build) * fl + wv * (0.5f + 0.4f * build))
            if (k == 0) b = max(b, 0.62f * fl)                                   // the tail tip is a steady source
            chB[k] = b * amt
            if (chB[k] > 0.01f) p.halo(ctx[k], cty[k], 13f * ds, chB[k] * 0.55f)
        }
        // wings: halos, then glows
        if (hasNear) wingGlow(nearW, 0, 0)
        if (hasFar) wingGlow(farW, 0, 1)
        if (hasNear) wingGlow(nearW, 1, 0)
        if (hasFar) wingGlow(farW, 1, 1)

        // bloom at the 9 start points right at the beginning
        val sb = sm(0f, 0.05f, u) * (1f - sm(0.05f, 0.28f, u)) * amt
        if (sb > 0.01f && charging) {
            p.glow(6, cbx[0], cby[0], 15f * ds * (0.8f + 0.5f * sb), sb * 0.9f); p.glow(3, cbx[0], cby[0], 7f * ds, sb)
            if (hasNear) for (f in 0 until 4) { p.glow(6, nearW[6 + 2 * f], nearW[7 + 2 * f], 13f * ds * (0.8f + 0.5f * sb), sb * 0.9f) }
            if (hasFar) for (f in 0 until 4) { p.glow(6, farW[6 + 2 * f], farW[7 + 2 * f], 11f * ds * (0.8f + 0.5f * sb), sb * 0.8f) }
        }
        // every wave that arrives at the shoulders flashes there
        val mg = min(1f, waveSum(1f)) * sm(0f, 0.2f, x0w - 1f + 0.2f) * amt
        if (mg > 0.02f) {
            if (hasNear) shoulderFlash(nearW[0], nearW[1], mg, pw)
            if (hasFar) shoulderFlash(farW[0], farW[1], mg * 0.8f, pw)
        }

        // spine spikes
        for (k in 0 until n) {
            val b = chB[k]
            if (b <= 0.01f) continue
            val fk = (0.9f + 0.5f * min(1f, wvK[k])) * (1f + (th - 1f) * min(1f, wvK[k]))
            val g = cfr[k] * 0.8f + 0.1f                                           // cool at the tail, hot near the head
            p.glow(gi(g), cbx[k], cby[k], 17f * ds * fk, b * 0.38f)
            p.glow(gi(g + 0.1f), cbx[k], cby[k], 9f * ds, b * 0.8f)
            p.glow(gi(g + 0.3f), ctx[k], cty[k], 10.5f * ds * (0.7f + 0.3f * b), b * 0.95f)
            p.glow(0, ctx[k], cty[k], 5f * ds * (0.6f + 0.4f * b), b)
            if (k + 1 < n) {
                val mb = (b + chB[k + 1]) * 0.5f
                if (mb > 0.02f) {
                    val x2 = (cbx[k] + cbx[k + 1]) * 0.5f; val y2 = (cby[k] + cby[k + 1]) * 0.5f
                    p.glow(gi(g + 0.2f), x2, y2, 7f * ds, mb * 0.55f)
                    p.glow(gi(g), x2, y2, 12f * ds, mb * 0.35f)
                }
            }
        }

        // short electric arcs between neighbouring spike tips: exactly 12 of the gaps at a time, chosen at random, a new set 10 times a second
        if (sparkAmt > 0.02f && n > 1) {
            val slot = (time * 10f).toInt()
            val gaps = n - 1
            for (k in 0 until gaps) scK[k] = if (min(chB[k], chB[k + 1]) > 0.08f) hash(slot, k, 7) else 2f
            val full = max(1, (12f * cq.coerceIn(0.1f, 1f) + 0.5f).toInt())          // 12 gaps at 100%, 6 at 50%, 1 at 10%
            val want = if (!charging && fe > 0f) max(1, (full * sparkAmt).toInt()) else full
            for (k in 0 until gaps) {
                if (scK[k] >= 2f) continue
                var rank = 0
                for (j in 0 until gaps) if (scK[j] < scK[k]) rank++
                if (rank >= want) continue
                val bk = min(chB[k], chB[k + 1])
                val x0 = ctx[k]; val y0 = cty[k]; val x1 = ctx[k + 1]; val y1 = cty[k + 1]
                val dx = x1 - x0; val dy = y1 - y0; val len = max(1e-3f, hypot(dx, dy))
                val nx = -dy / len; val ny = dx / len
                val j1 = (hash(slot, k, 11) - 0.5f) * 6f * ds; val j2 = (hash(slot, k, 13) - 0.5f) * 6f * ds
                arcP[0] = x0; arcP[1] = y0
                arcP[2] = x0 + dx * 0.33f + nx * j1; arcP[3] = y0 + dy * 0.33f + ny * j1
                arcP[4] = arcP[2]; arcP[5] = arcP[3]
                arcP[6] = x0 + dx * 0.66f + nx * j2; arcP[7] = y0 + dy * 0.66f + ny * j2
                arcP[8] = arcP[6]; arcP[9] = arcP[7]
                arcP[10] = x1; arcP[11] = y1
                val ci = (hash(slot, k, 3) * 3f).toInt().coerceIn(0, 2)
                val al = min(1f, 0.6f + bk) * sparkAmt.coerceAtMost(1f)
                p.lines(arcP, 12, ci + 1, max(1f, 1.5f * ds), al)
                p.lines(arcP, 12, 0, max(0.8f, 0.6f * ds), al)
                p.glow(ci, (x0 + x1) * 0.5f, (y0 + y1) * 0.5f, 5f * ds, 0.5f * al)
            }
        }

        // the middle of the open mouth, behind the teeth: all the charge gathers here (it follows how far the mouth is open)
        model.mouthPoint(st, 26f, 3.5f + 6.5f * st.mouth.coerceIn(0f, 1f), orbP)
        model.mouthPoint(st, 36f, 3f, tipP)
        val gt = ((u - 0.45f) / 0.40f).coerceIn(0f, 1f)                        // the orb is complete at 85% of the charge-up
        val g = if (charging) sm(0f, 1f, gt) else fade
        // the orb grows while charging, then shrinks steadily through the breath: smallest when the breath ends
        val ou = if (charging) 0.15f + 0.85f * g else 1f - 0.88f * (ft / max(0.1f, fireDur)).coerceIn(0f, 1f)
        if (g > 0.01f && n > 1) {
            val arr = min(1f, waveSum(1.3f))                                       // a wave reaching the mouth makes the orb throb
            val hold = if (charging) sm(0.83f, 0.9f, u) else 0f                    // the last 15% of the charge: the finished orb holds, brighter and throbbing
            val pulse = (0.9f + (0.1f + 0.08f * hold) * sin(time * rate)) * (1f + 0.12f * arr + 0.08f * hold)
            val ox = orbP[0] + sin(time * 61f) * 0.8f * ds * ou; val oy = orbP[1] + cos(time * 53f) * 0.8f * ds * ou
            p.halo(ox, oy, 24f * ds * pw * ou, 0.6f * g)
            val lb = max(chB[n - 1], 0.5f * g)
            for (j in 0 until 4) {                                                 // the neck waves run on into the mouth
                val f = (time * 1.5f + j / 4f) % 1f
                val px = ctx[n - 1] + (ox - ctx[n - 1]) * f; val py = cty[n - 1] + (oy - cty[n - 1]) * f
                p.glow(0, px, py, 3.2f * ds, (1f - f) * 0.9f * lb * g)
                p.glow(2, px, py, 6f * ds, (1f - f) * 0.5f * lb * g)
            }
            p.halo(ox, oy, 46f * ds * pw, 0.35f * g)                               // one shared dark halo behind the whole swarm
            if (charging) implode(ox, oy, g, cq, 1f - sm(0.6f, 0.85f, u))        // particles thin out and stop arriving by 85%; none during the breath
            p.glow(4, ox, oy, 28f * ds * ou * pw * pulse, 0.55f * g)               // orb: hot white centre, gradient to the cool edge
            p.glow(2, ox, oy, 20f * ds * ou * pw * pulse, 0.7f * g)
            p.glow(1, ox, oy, 15f * ds * ou * pw * pulse, 0.85f * g)
            p.glow(0, ox, oy, 9f * ds * ou * pw, g)
        }
        // the moment the breath leaves the mouth: a flash at the mouth tip
        if (!charging && ft in 0f..0.4f) {
            val rp = ft / 0.4f; val rl = 1f - rp
            p.glow(0, tipP[0], tipP[1], (10f + 14f * rl) * ds * pw, rl)
            p.glow(1, tipP[0], tipP[1], (18f + 26f * rp) * ds * pw, rl * 0.9f)
            p.glow(3, tipP[0], tipP[1], (30f + 30f * rp) * ds * pw, rl * 0.5f)
        }
    }

    /**
     * Glowing particles pulled into the mouth: each one starts at rest on a wide circle around the orb and falls in a straight line to its
     * centre, speeding up steadily (constant pull), with a straight streak behind it that is longer the faster it goes. On arrival it
     * shrinks into the orb. 120 particles at 100% quality (cq), 12 at 10%. Coloured from the cool to the hot end of the flame gradient.
     */
    private fun implode(ox: Float, oy: Float, strength: Float, cq: Float, thin: Float) {
        if (strength <= 0.02f || thin <= 0.01f) return
        val np = ((120f * cq.coerceIn(0.1f, 1f)).roundToInt().coerceIn(12, 120) * thin).toInt()
        for (j in 0 until np) {
            val cyc = time * 1.3f + j * 0.618034f
            val ci = cyc.toInt()
            val f = cyc - ci
            val a0 = hash(ci, j, 21) * 6.2832f                                       // fixed direction: a straight line in
            val r0 = (22f + 28f * hash(ci, j, 22)) * ds
            val f2 = max(0f, f - 0.12f)
            val dx = cos(a0); val dy = sin(a0)
            val rr = r0 * (1f - f * f); val rb = r0 * (1f - f2 * f2)                  // distance from the centre: starts at rest, constant pull
            val px = ox + dx * rr; val py = oy + dy * rr
            trl[0] = ox + dx * rb; trl[1] = oy + dy * rb; trl[2] = px; trl[3] = py
            val e = max(0.4f, sm(0f, 0.1f, f)) * (1f - sm(0.9f, 1f, f)) * strength
            if (e <= 0.01f) continue
            val ci2 = (((1f - f) * 4f).toInt() + j % 3).coerceIn(0, 7)
            val sz = (5.9f + 3.9f * (1f - f)) * ds
            if (j % 3 == 0) p.halo(px, py, sz * 1.5f, e * 0.45f)
            p.lines(trl, 4, ci2, max(1.6f, 2.6f * ds), e)
            p.glow(ci2, px, py, sz, e)
            p.glow(0, px, py, sz * 0.55f, e)
        }
    }

    private fun shoulderFlash(x: Float, y: Float, mg: Float, pw: Float) {
        p.halo(x, y, 26f * ds * pw, 0.5f * mg)
        p.glow(2, x, y, (20f + 12f * mg) * ds * pw, 0.7f * mg)
        p.glow(1, x, y, (13f + 8f * mg) * ds * pw, 0.9f * mg)
        p.glow(0, x, y, 7f * ds * pw, mg)
    }

    /** One wing: from each claw tip along its finger bone to the wrist, then along the arm bone (wrist, elbow, shoulder). */
    private fun wingGlow(w: FloatArray, pass: Int, seed: Int) {
        val sx = w[0]; val sy = w[1]; val ex = w[2]; val ey = w[3]; val wx = w[4]; val wy = w[5]
        for (q in 0 until 5) {                                                       // arm bone: wrist, middle, elbow, middle, shoulder
            val px: Float; val py: Float
            when (q) {
                0 -> { px = wx; py = wy }
                1 -> { px = (wx + ex) * 0.5f; py = (wy + ey) * 0.5f }
                2 -> { px = ex; py = ey }
                3 -> { px = (ex + sx) * 0.5f; py = (ey + sy) * 0.5f }
                else -> { px = sx; py = sy }
            }
            node(px, py, 0.55f + q * 0.1125f, if (q == 0) 1.4f else 1f, pass, seed, false)
        }
        for (f in 0 until 4) {                                                       // finger bones
            val tx = w[6 + 2 * f]; val ty = w[7 + 2 * f]
            for (j in 0 until 5) {
                val q = j / 5f
                node(tx + (wx - tx) * q, ty + (wy - ty) * q, q * 0.55f, if (j == 0) 1.35f else 1f, pass, seed + f, j == 0)
            }
        }
    }

    /**
     * One glowing point on a wing bone. xc = position along the path (0 claw tip .. 1 shoulder). pass 0 = dark halo, pass 1 = glow.
     * src = the claw tip itself: a steady source that sends out the waves.
     */
    private fun node(x: Float, y: Float, xc: Float, size: Float, pass: Int, seed: Int, src: Boolean) {
        val wv = waveSum(xc)
        val lit = sm(0f, 0.08f, x0w - xc + 0.04f)
        val fl = 0.85f + 0.15f * sin(time * rate + xc * 9f + seed)
        var b = min(1f, lit * (0.2f + 0.14f * build) * fl + wv * (0.5f + 0.4f * build))
        if (src) b = max(b, 0.62f * fl)
        b *= amt
        if (b <= 0.01f) return
        if (pass == 0) { p.halo(x, y, 9f * ds * size, b * 0.4f); return }
        val g = xc * 0.85f                                                           // cool at the claw tip, hot near the shoulder
        val tk = 1f + (th - 1f) * min(1f, wv)                                        // only the wave itself gets thicker
        p.glow(gi(g), x, y, 12f * ds * size * (0.85f + 0.4f * min(1f, wv)) * tk, b * 0.5f)
        p.glow(gi(g + 0.25f), x, y, 6.5f * ds * size * tk, b * 0.9f)
        p.glow(0, x, y, 2.8f * ds * size, b)
    }
}

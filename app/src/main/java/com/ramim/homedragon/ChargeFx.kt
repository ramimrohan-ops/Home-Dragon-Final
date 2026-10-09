package com.ramim.homedragon

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The fire charge-up, shared by the dragon on the home screen and the Charge-up time preview.
 *
 * It starts at 9 points (the tail tip and the 8 claw tips of the two wings). The glow runs from each claw tip along the finger bone to the wrist,
 * then along the arm bone to the shoulder; the tail glow runs along the spine spikes to the shoulder. They all arrive together, flash, and one
 * glow goes up the neck into the mouth, where sparks and rings gather into an orb. At the breath there is a flash and a ring. Short electric
 * arcs jump between the spine spikes while it charges, keep going for the whole breath, then fade to nothing.
 * Colours come from an 8-step gradient of the flame colours (index 0 = hot core .. 7 = cool tip); the caller draws with it.
 */
class ChargeFx(private val model: DragonModel, private val p: Painter) {

    interface Painter {
        /** Soft glow in gradient colour [i] (0 hot .. 7 cool), [half] = half size in pixels. */
        fun glow(i: Int, x: Float, y: Float, half: Float, alpha: Float)
        /** Dark soft halo behind a glow, so it stays visible on bright wallpapers. */
        fun halo(x: Float, y: Float, half: Float, alpha: Float)
        /** Thin lines (pairs of points: count = number of floats) in gradient colour [colorIdx]. */
        fun lines(pts: FloatArray, count: Int, colorIdx: Int, width: Float, alpha: Float)
        fun ring(x: Float, y: Float, r: Float, colorIdx: Int, width: Float, alpha: Float)
    }

    private val cbx = FloatArray(16); private val cby = FloatArray(16)
    private val ctx = FloatArray(16); private val cty = FloatArray(16); private val cfr = FloatArray(16)
    private val chB = FloatArray(16)
    private val nearW = FloatArray(14); private val farW = FloatArray(14)
    private val orbP = FloatArray(2)
    private val arcP = FloatArray(12)
    private val strk = FloatArray(4)

    // values of the current frame, so the helpers below need short argument lists
    private var ds = 1f; private var time = 0f; private var xw = 0f; private var amt = 0f; private var soft = 0f
    private var build = 0f; private var rate = 0f

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

    /**
     * u = charge progress 0..1; ft = seconds since the breath started (negative while charging);
     * charging = still charging; spark = strength of the electric arcs after the charge (1 during the breath, then fading to 0).
     */
    fun draw(st: DragonModel.State, scale: Float, now: Float, chargeT: Float, u: Float, ft: Float, charging: Boolean, spark: Float, pq: Float) {
        if (chargeT <= 0.05f) return
        ds = scale; time = now
        amt = if (charging) sm(0f, 0.1f, u) else 1f - sm(0.1f, 0.45f, ft)
        soft = if (charging) 0f else spark * 0.42f
        val sparkAmt = if (charging) (0.35f + 0.65f * u) * sm(0.1f, 0.3f, u) else spark
        if (amt <= 0.01f && soft <= 0.01f && sparkAmt <= 0.01f) return
        val pw = 0.75f + 0.25f * (chargeT / 1.5f).coerceIn(0f, 1.6f)           // longer charge = bigger, brighter finish
        build = 0.5f + 0.5f * u
        rate = 14f + 26f * u
        val n = model.chargePoints(st, cbx, cby, ctx, cty, cfr)
        val hasNear = model.wingPoints(st, false, nearW)
        val hasFar = model.wingPoints(st, true, farW)

        // where the shoulders are along the spine: the tail glow and the wing glows reach it together
        var fs = 0.8f
        if (hasNear) {
            var best = 1e9f
            for (k in 1 until n) { val d = hypot(cbx[k] - nearW[0], cby[k] - nearW[1]); if (d < best) { best = d; fs = cfr[k] } }
        }
        fs = fs.coerceIn(0.5f, 0.9f)
        xw = (u / 0.40f).coerceIn(0f, 1f) * 1.04f                                  // wing glow: 0 claw tip .. 1 shoulder
        val fw = if (u < 0.40f) fs * (u / 0.40f) else fs + (0.97f - fs) * ((u - 0.40f) / 0.30f).coerceIn(0f, 1f)   // tail glow, then up the neck
        val gt = ((u - 0.6f) / 0.4f).coerceIn(0f, 1f)                              // gathering in the mouth (last 40%)

        // spikes: brightness (and dark halos first, so the glows are drawn on top of them)
        for (k in 0 until n) {
            val d = fw - cfr[k] + 0.04f
            val lit = sm(0f, 0.08f, d)
            val flash = sm(0f, 0.05f, d) * (1f - sm(0.05f, 0.3f, d))
            val fl = 0.85f + 0.15f * sin(time * rate + k * 0.9f)
            chB[k] = max(min(1f, lit * 0.72f * build * fl + flash * 0.95f) * amt, soft * lit * fl)
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
        // the glows from both wings and the tail meet and flash at the shoulders
        val mg = sm(0.36f, 0.42f, u) * (1f - sm(0.42f, 0.58f, u)) * amt
        if (mg > 0.01f && charging) {
            if (hasNear) shoulderFlash(nearW[0], nearW[1], mg, pw)
            if (hasFar) shoulderFlash(farW[0], farW[1], mg * 0.8f, pw)
        }

        // spine spikes
        var lastLit = 0f
        for (k in 0 until n) {
            val b = chB[k]
            if (b <= 0.01f) continue
            if (k == n - 1) lastLit = b
            val d = fw - cfr[k] + 0.04f
            val fk = 0.9f + 0.4f * sm(0f, 0.05f, d) * (1f - sm(0.05f, 0.3f, d))
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
        // bright head of the glow while it runs along the spine
        if (charging && fw > 0.01f && fw < 0.95f) {
            var k = 0
            while (k + 1 < n - 1 && cfr[k + 1] < fw) k++
            val f = ((fw - cfr[k]) / max(0.01f, cfr[k + 1] - cfr[k])).coerceIn(0f, 1f)
            val wx = cbx[k] + (cbx[k + 1] - cbx[k]) * f; val wy = cby[k] + (cby[k + 1] - cby[k]) * f
            p.halo(wx, wy, 18f * ds, 0.5f * amt)
            p.glow(gi(fw), wx, wy, 14f * ds, 0.9f * amt)
            p.glow(0, wx, wy, 6f * ds, amt)
        }

        // short electric arcs between neighbouring spike tips
        if (sparkAmt > 0.02f && n > 1) {
            val slot = (time * 24f).toInt()                                          // a new set of arcs 24 times a second
            for (k in 0 until n - 1) {
                val bk = min(chB[k], chB[k + 1])
                if (bk <= 0.08f) continue
                if (hash(slot, k, 7) > sparkAmt * (0.3f + 0.45f * pq)) continue
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
                val al = min(1f, bk * 1.4f) * sparkAmt.coerceAtMost(1f)
                p.lines(arcP, 12, ci + 1, max(1f, 1.5f * ds), al)
                p.lines(arcP, 12, 0, max(0.8f, 0.6f * ds), al)
                p.glow(ci, (x0 + x1) * 0.5f, (y0 + y1) * 0.5f, 5f * ds, 0.5f * al)
            }
        }

        // gather in the mouth
        model.mouthPoint(st, 28f, 3f, orbP)
        if (charging && gt > 0f && n > 1) {
            val g = sm(0f, 1f, gt)
            val ou = 0.15f + 0.85f * g
            val pulse = 0.88f + 0.12f * sin(time * rate)
            val ox = orbP[0] + sin(time * 61f) * 0.8f * ds * ou; val oy = orbP[1] + cos(time * 53f) * 0.8f * ds * ou
            p.halo(ox, oy, 24f * ds * pw * ou, 0.6f * g)
            for (j in 0 until 4) {                                                   // streams from the last spike into the mouth
                val f = (gt * 1.6f + j * 0.25f) % 1f
                val px = ctx[n - 1] + (ox - ctx[n - 1]) * f; val py = cty[n - 1] + (oy - cty[n - 1]) * f
                p.glow(0, px, py, 3.2f * ds, (1f - f) * 0.95f * max(lastLit, 0.6f))
                p.glow(2, px, py, 6f * ds, (1f - f) * 0.5f * max(lastLit, 0.6f))
            }
            val ns = (6 + 10 * pq).toInt()
            for (j in 0 until ns) {                                                  // sparks pulled in from all around, in different colours
                val a = j * 2.399963f + time * 0.7f
                val f = (gt * 2.2f + j * 0.37f) % 1f
                val r = (46f - 10f * (j % 3)) * ds * (1f - f)
                val sx = ox + cos(a) * r; val sy = oy + sin(a) * r
                val al = (0.3f + 0.7f * f) * g
                strk[0] = sx; strk[1] = sy; strk[2] = sx + cos(a) * 7f * ds; strk[3] = sy + sin(a) * 7f * ds
                p.lines(strk, 4, 1 + j % 4, max(1f, 1.5f * ds), al)
                p.glow(j % 3, sx, sy, 2.4f * ds, al)
            }
            for (j in 0 until 3) {                                                   // rings shrinking into the mouth
                val f = (gt * 1.8f + j / 3f) % 1f
                p.ring(ox, oy, (40f - 34f * f) * ds * pw, 1 + j * 2, max(1f, 1.5f * ds), sin(f * PI.toFloat()) * 0.65f * g)
            }
            p.glow(4, ox, oy, 28f * ds * ou * pw * pulse, 0.55f * g)                // orb: hot white centre, gradient to the cool edge
            p.glow(2, ox, oy, 20f * ds * ou * pw * pulse, 0.7f * g)
            p.glow(1, ox, oy, 15f * ds * ou * pw * pulse, 0.85f * g)
            p.glow(0, ox, oy, 9f * ds * ou * pw, g)
        }
        // release: flash and a ring that flies outwards, changing colour along the gradient
        if (!charging && ft in 0f..0.4f) {
            val rp = ft / 0.4f; val rl = 1f - rp
            p.glow(0, orbP[0], orbP[1], (10f + 14f * rl) * ds * pw, rl)
            p.glow(1, orbP[0], orbP[1], (18f + 26f * rp) * ds * pw, rl * 0.9f)
            p.glow(3, orbP[0], orbP[1], (30f + 30f * rp) * ds * pw, rl * 0.5f)
            p.ring(orbP[0], orbP[1], (8f + 52f * rp) * ds * pw, (rp * 5f).toInt(), max(1.5f, 2.4f * ds * rl), rl * 0.85f)
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
            node(px, py, 0.55f + q * 0.1125f, if (q == 0) 1.4f else 1f, pass, seed)
        }
        for (f in 0 until 4) {                                                       // finger bones
            val tx = w[6 + 2 * f]; val ty = w[7 + 2 * f]
            for (j in 0 until 5) {
                val q = j / 5f
                node(tx + (wx - tx) * q, ty + (wy - ty) * q, q * 0.55f, if (j == 0) 1.35f else 1f, pass, seed + f)
            }
        }
    }

    /** One glowing point on a wing bone. xc = position along the path (0 claw tip .. 1 shoulder). pass 0 = dark halo, pass 1 = glow. */
    private fun node(x: Float, y: Float, xc: Float, size: Float, pass: Int, seed: Int) {
        val d = xw - xc + 0.04f
        val lit = sm(0f, 0.08f, d)
        val flash = sm(0f, 0.05f, d) * (1f - sm(0.05f, 0.3f, d))
        val fl = 0.85f + 0.15f * sin(time * rate + xc * 9f + seed)
        val b = max(min(1f, lit * 0.72f * build * fl + flash * 0.95f) * amt, soft * lit * fl)
        if (b <= 0.01f) return
        if (pass == 0) { p.halo(x, y, 9f * ds * size, b * 0.4f); return }
        val g = xc * 0.85f                                                           // cool at the claw tip, hot near the shoulder
        p.glow(gi(g), x, y, 12f * ds * size * (0.85f + 0.3f * flash), b * 0.5f)
        p.glow(gi(g + 0.25f), x, y, 6.5f * ds * size, b * 0.9f)
        p.glow(0, x, y, 2.8f * ds * size, b)
    }
}

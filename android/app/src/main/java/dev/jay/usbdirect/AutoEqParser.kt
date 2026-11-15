// SPDX-License-Identifier: GPL-3.0-or-later
package dev.jay.usbdirect

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Result of an AutoEQ / EqualizerAPO text import. */
data class AutoEqResult(
    val preampDb: Double?,
    val bands: List<DspBand>,
    /** Filters that did not fit the 10 PEQ + HPF + LPF rack. */
    val ignored: Int
)

/**
 * Pure-Kotlin parser for AutoEQ / EqualizerAPO text presets.
 * No Android types are used so this can run under JVM unit tests.
 */
object AutoEqParser {

    private val preampRe = Regex(
        """preamp\s*:\s*(-?\d+(?:[.,]\d+)?)\s*db""",
        RegexOption.IGNORE_CASE
    )
    private val headRe = Regex(
        """^\s*filter\s+\d+\s*:\s*(on|off)\s+([a-z]+)""",
        RegexOption.IGNORE_CASE
    )
    private val fcRe = Regex(
        """\bfc\s+(\d+(?:[.,]\d+)?)\s*hz""",
        RegexOption.IGNORE_CASE
    )
    private val gainRe = Regex(
        """\bgain\s+(-?\d+(?:[.,]\d+)?)\s*db""",
        RegexOption.IGNORE_CASE
    )
    private val qRe = Regex(
        """\bq\s+(\d+(?:[.,]\d+)?)""",
        RegexOption.IGNORE_CASE
    )
    private val bwRe = Regex(
        """\bbw\s+oct\s+(\d+(?:[.,]\d+)?)""",
        RegexOption.IGNORE_CASE
    )

    private fun num(s: String): Double? =
        s.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    /** Q from bandwidth in octaves (RBJ). */
    private fun bwToQ(bw: Double): Double {
        if (bw <= 0.0) return 0.707
        val p = 2.0.pow(bw)
        return sqrt(p) / (p - 1.0)
    }

    fun parse(text: String): AutoEqResult {
        var preamp: Double? = null
        val peq = ArrayList<DspBand>(10)
        var hpf: DspBand? = null
        var lpf: DspBand? = null
        var ignored = 0

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            preampRe.find(line)?.let { m ->
                num(m.groupValues[1])?.let { preamp = it }
            }

            val head = headRe.find(line) ?: continue
            if (!head.groupValues[1].equals("on", true)) continue

            val alias = head.groupValues[2].lowercase()
            val type = when (alias) {
                "pk" -> 0
                "lsc", "ls" -> 1
                "hsc", "hs" -> 2
                "hp", "hpf" -> 3
                "lp", "lpf" -> 4
                else -> continue
            }
            val fc = fcRe.find(line)?.groupValues?.get(1)?.let(::num) ?: continue
            val gain = gainRe.find(line)?.groupValues?.get(1)?.let(::num) ?: 0.0
            val q = qRe.find(line)?.groupValues?.get(1)?.let(::num)
                ?: bwRe.find(line)?.groupValues?.get(1)?.let(::num)?.let(::bwToQ)
                ?: 0.707

            val band = DspBand(type, fc, q, gain, true)
            when (type) {
                3 -> if (hpf == null) hpf = band else ignored++
                4 -> if (lpf == null) lpf = band else ignored++
                else -> if (peq.size < 10) peq.add(band) else ignored++
            }
        }

        val bands = ArrayList<DspBand>(peq.size + 2)
        bands.addAll(peq)
        hpf?.let { bands.add(it) }
        lpf?.let { bands.add(it) }
        return AutoEqResult(preamp, bands, ignored)
    }
}

/* ------------------------------------------------------------------ math */

data class Biquad(
    val b0: Double, val b1: Double, val b2: Double,
    val a1: Double, val a2: Double
)

/** Kotlin mirror of the native RBJ compile path, used by the EQ curve. */
object DspMath {

    private fun clamp(v: Double, lo: Double, hi: Double) =
        if (v < lo) lo else if (v > hi) hi else v

    fun biquad(band: DspBand, rate: Double): Biquad? {
        if (!band.enabled || rate < 8000.0) return null
        val fc = band.fc
        if (!fc.isFinite() || fc <= 0.0 || fc >= rate * 0.45) return null
        val q = clamp(if (band.q.isFinite()) band.q else 0.707, 0.05, 20.0)
        val gain = clamp(if (band.gainDb.isFinite()) band.gainDb else 0.0, -24.0, 24.0)
        val a = 10.0.pow(gain / 40.0)
        val w0 = 2.0 * Math.PI * fc / rate
        val cw = cos(w0)
        val sw = sin(w0)
        val alpha = sw / (2.0 * q)

        var b0: Double
        var b1: Double
        var b2: Double
        var a0: Double
        var a1: Double
        var a2: Double

        when (band.type) {
            0 -> {
                b0 = 1.0 + alpha * a
                b1 = -2.0 * cw
                b2 = 1.0 - alpha * a
                a0 = 1.0 + alpha / a
                a1 = -2.0 * cw
                a2 = 1.0 - alpha / a
            }
            1 -> {
                val sa = sqrt(a)
                b0 = a * ((a + 1.0) - (a - 1.0) * cw + 2.0 * sa * alpha)
                b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cw)
                b2 = a * ((a + 1.0) - (a - 1.0) * cw - 2.0 * sa * alpha)
                a0 = (a + 1.0) + (a - 1.0) * cw + 2.0 * sa * alpha
                a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cw)
                a2 = (a + 1.0) + (a - 1.0) * cw - 2.0 * sa * alpha
            }
            2 -> {
                val sa = sqrt(a)
                b0 = a * ((a + 1.0) + (a - 1.0) * cw + 2.0 * sa * alpha)
                b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cw)
                b2 = a * ((a + 1.0) + (a - 1.0) * cw - 2.0 * sa * alpha)
                a0 = (a + 1.0) - (a - 1.0) * cw + 2.0 * sa * alpha
                a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cw)
                a2 = (a + 1.0) - (a - 1.0) * cw - 2.0 * sa * alpha
            }
            3 -> {
                b0 = (1.0 + cw) * 0.5
                b1 = -(1.0 + cw)
                b2 = (1.0 + cw) * 0.5
                a0 = 1.0 + alpha
                a1 = -2.0 * cw
                a2 = 1.0 - alpha
            }
            4 -> {
                b0 = (1.0 - cw) * 0.5
                b1 = 1.0 - cw
                b2 = (1.0 - cw) * 0.5
                a0 = 1.0 + alpha
                a1 = -2.0 * cw
                a2 = 1.0 - alpha
            }
            else -> return null
        }
        if (!a0.isFinite() || abs(a0) < 1e-12) return null
        return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    fun magnitudeDb(q: Biquad, rate: Double, freq: Double): Double {
        if (rate <= 0.0) return 0.0
        val w = 2.0 * Math.PI * freq / rate
        val c1 = cos(w)
        val s1 = -sin(w)
        val c2 = cos(2.0 * w)
        val s2 = -sin(2.0 * w)
        val nr = q.b0 + q.b1 * c1 + q.b2 * c2
        val ni = q.b1 * s1 + q.b2 * s2
        val dr = 1.0 + q.a1 * c1 + q.a2 * c2
        val di = q.a1 * s1 + q.a2 * s2
        val den = hypot(dr, di)
        if (den < 1e-15) return 0.0
        return 20.0 * log10(hypot(nr, ni) / den)
    }

    fun maxBoostDb(config: DspConfig, rate: Double): Double {
        val bands = config.bands.filter { it.enabled }
        if (bands.isEmpty() || rate <= 0.0) return 0.0
        val fmin = 20.0
        val fmax = min(20000.0, rate * 0.45)
        if (fmax <= fmin * 1.001) return 0.0
        var maxBoost = 0.0
        for (band in bands) {
            val q = biquad(band, rate) ?: continue
            var bandMax = -1e9
            for (k in 0 until 256) {
                val f = fmin * (fmax / fmin).pow(k / 255.0)
                val db = magnitudeDb(q, rate, f)
                if (db > bandMax) bandMax = db
            }
            if (bandMax > maxBoost) maxBoost = bandMax
        }
        return if (maxBoost > 0.0) maxBoost else 0.0
    }

    fun effectivePreampDb(config: DspConfig, rate: Double): Double {
        val boost = if (config.autoHeadroom) maxBoostDb(config, rate) else 0.0
        return config.preampDb - boost
    }

    fun responseDb(config: DspConfig, rate: Double, freq: Double): Double {
        var db = effectivePreampDb(config, rate)
        for (band in config.bands) {
            if (!band.enabled) continue
            biquad(band, rate)?.let { db += magnitudeDb(it, rate, freq) }
        }
        return db
    }

    fun logFreq(frac: Double, min: Double = 20.0, max: Double = 20000.0): Double =
        min * (max / min).pow(frac)
}

/** Effective signal-path verdict for the BIT-PERFECT / PROCESSED badges. */
data class SignalVerdict(val perfect: Boolean, val reasons: List<String>)

fun signalVerdict(
    usingMixer: Boolean,
    dspEnabled: Boolean,
    resampled: Boolean,
    hardwareVolume: Boolean,
    volume: Float,
    sourceBits: Int,
    dacBits: Int,
    ditherOn: Boolean
): SignalVerdict {
    val reasons = ArrayList<String>(4)
    if (usingMixer) reasons.add("Android mixer, not bit-perfect")
    if (dspEnabled) reasons.add("EQ")
    if (resampled) reasons.add("Resampled")
    if (sourceBits > 0 && dacBits > 0 && sourceBits > dacBits) {
        reasons.add(
            "Bit-depth reduction ($sourceBits>$dacBits)" +
            if (!ditherOn) " undithered" else ""
        )
    }
    // Dither is auto-skipped at unity volume / DSP off / no resample / no
    // bit-depth reduction, so it is not a processed-path reason here.
    if (!hardwareVolume && volume < 0.999f) reasons.add("SW vol")
    return SignalVerdict(reasons.isEmpty(), reasons)
}

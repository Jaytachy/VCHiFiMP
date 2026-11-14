package dev.jay.usbdirect

import java.nio.FloatBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real-time spectrum + level meter, tapped from the rendered audio.
 *
 * The tap is post-volume but pre-dither, so the meters show what the DAC is
 * about to receive. Analysis runs on its own thread against a lock-free ring:
 * nothing here may ever block the audio thread.
 */
class AudioAnalyzer {

    data class Frame(
        val bands: FloatArray,   // dBFS per band, already smoothed
        val peakDb: Float,
        val rmsDb: Float,
        val peakHoldDb: Float,
        val clipping: Boolean
    )

    companion object {
        const val BANDS = 64
        private const val FFT_N = 8192
        private const val RING = 1 shl 16
        private const val TICK_MS = 8L
        // The meter uses a much shorter window than the FFT: an 8192-point
        // window is 170 ms, which is far too slow to show a peak.
        private const val METER_N = 2048
    }

    private val _frame = MutableStateFlow(
        Frame(FloatArray(BANDS) { -100f }, -100f, -100f, -100f, false)
    )
    val frame: StateFlow<Frame> = _frame.asStateFlow()

    private val ring = FloatArray(RING)
    @Volatile private var writeIdx = 0L
    @Volatile private var running = false
    private var worker: Thread? = null

    // analysis scratch, owned by the worker thread only
    private val real = FloatArray(FFT_N)
    private val imag = FloatArray(FFT_N)
    private val window = FloatArray(FFT_N) {
        0.5f - 0.5f * cos(2.0 * Math.PI * it / (FFT_N - 1)).toFloat()
    }
    private val smoothed = FloatArray(BANDS) { -100f }
    private val bandLo = IntArray(BANDS)
    private val bandHi = IntArray(BANDS)
    private var peakHold = -100f
    private var lastPeakHoldTick = 0L
    private var clipLatchUntil = 0L

    init {
        // log-spaced band edges, 20 Hz .. 20 kHz
        val nyquist = 24000f
        for (b in 0 until BANDS) {
            val f0 = 20f * Math.pow(20000.0 / 20.0, b.toDouble() / BANDS).toFloat()
            val f1 = 20f * Math.pow(20000.0 / 20.0, (b + 1).toDouble() / BANDS).toFloat()
            bandLo[b] = ((f0 / nyquist) * (FFT_N / 2)).toInt().coerceIn(1, FFT_N / 2 - 1)
            bandHi[b] = ((f1 / nyquist) * (FFT_N / 2)).toInt().coerceIn(bandLo[b] + 1, FFT_N / 2)
        }
    }

    /** Called from the audio thread. Must stay allocation-free and non-blocking. */
    fun offer(buf: FloatBuffer, frames: Int, gain: Float) {
        if (!running) return
        var w = writeIdx
        var i = 0
        while (i < frames) {
            val l = buf.get(i * 2)
            val r = buf.get(i * 2 + 1)
            ring[(w and (RING - 1).toLong()).toInt()] = (l + r) * 0.5f * gain
            w++
            i++
        }
        writeIdx = w
    }

    fun start() {
        if (running) return
        running = true
        worker = Thread {
            while (running) {
                val t0 = System.nanoTime()
                try { analyse() } catch (t: Throwable) { /* never kill the worker */ }
                val spent = (System.nanoTime() - t0) / 1_000_000
                val sleep = TICK_MS - spent
                if (sleep > 0) Thread.sleep(sleep)
            }
        }.also { it.isDaemon = true; it.priority = Thread.NORM_PRIORITY; it.start() }
    }

    fun stop() {
        running = false
        worker?.join(200)
        worker = null
    }

    private fun analyse() {
        val w = writeIdx
        if (w < FFT_N) return
        // most recent FFT_N samples
        for (n in 0 until FFT_N) {
            val idx = ((w - FFT_N + n) and (RING - 1).toLong()).toInt()
            real[n] = ring[idx] * window[n]
            imag[n] = 0f
        }

        var peak = 0f
        var sumsq = 0f
        for (n in 0 until METER_N) {
            val v = ring[((w - METER_N + n) and (RING - 1).toLong()).toInt()]
            val a = if (v < 0) -v else v
            if (a > peak) peak = a
            sumsq += v * v
        }

        fft(real, imag)

        // magnitude -> dB, scaled so a full-scale sine reads 0 dB
        val scale = 4.0f / FFT_N
        for (b in 0 until BANDS) {
            var m = 0f
            for (k in bandLo[b] until bandHi[b]) {
                val re = real[k]; val im = imag[k]
                val mag = sqrt(re * re + im * im) * scale
                if (mag > m) m = mag
            }
            val db = if (m <= 1e-7f) -100f else max(-100f, 20f * log10(m))
            // fast attack, slow release
            // per-tick coefficients: ~120 updates/s now, so the release is
            // shorter per frame to keep the same visible decay time
            smoothed[b] = if (db > smoothed[b]) db else smoothed[b] - (smoothed[b] - db) * 0.13f
        }

        val peakDb = if (peak <= 1e-5f) -100f else 20f * log10(peak)
        val rmsDb = if (sumsq <= 1e-10f) -100f
                    else 20f * log10(sqrt(sumsq / METER_N))

        val now = System.currentTimeMillis()
        if (peakDb > peakHold || now - lastPeakHoldTick > 1200) {
            peakHold = max(peakDb, -100f)
            lastPeakHoldTick = now
        }
        if (peak >= 0.999f) clipLatchUntil = now + 1500

        _frame.value = Frame(
            bands = smoothed.copyOf(),
            peakDb = peakDb,
            rmsDb = rmsDb,
            peakHoldDb = peakHold,
            clipping = now < clipLatchUntil
        )
    }

    /** In-place iterative radix-2 FFT. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = FFT_N
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f
                var curIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}

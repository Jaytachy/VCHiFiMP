// SPDX-License-Identifier: GPL-3.0-or-later
package dev.jay.usbdirect

import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicInteger
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
        private const val RING = 1 shl 18   // must hold the full decode buffer (~2.7 s)
        // 30 Hz is enough for the 64-band curve and the peak/rms meter; the
        // UI interpolates between frames at display refresh.
        private const val TICK_MS = 33L
        private const val SNAPSHOTS = 4
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
    /** Frames to lag the analysis by so it matches what is actually audible. */
    @Volatile var delayFrames: Long = 0
    @Volatile private var running = false
    @Volatile private var worker: Thread? = null
    /** Number of composed, visible UI consumers (spectrum/level/clip). */
    private val consumers = AtomicInteger(0)
    @Volatile private var tickCount = 0L

    // analysis scratch, owned by the worker thread only
    private val real = FloatArray(FFT_N)
    private val imag = FloatArray(FFT_N)
    private val window = FloatArray(FFT_N) {
        0.5f - 0.5f * cos(2.0 * Math.PI * it / (FFT_N - 1)).toFloat()
    }
    private val smoothed = FloatArray(BANDS) { -100f }
    private val raw = FloatArray(BANDS)
    // Published band arrays rotate so the worker never allocates per tick and
    // a frame already handed to a collector is not overwritten immediately.
    private val snapshots = Array(SNAPSHOTS) { FloatArray(BANDS) { -100f } }
    private var snapshotSlot = 0
    private val bandLo = IntArray(BANDS)
    private val bandHi = IntArray(BANDS)
    private var bandRate = 0
    @Volatile private var sampleRate = 48000
    private var peakHold = -100f
    private var lastPeakHoldTick = 0L
    private var clipLatchUntil = 0L

    /** Must follow the device rate: the band edges are in Hz, not bins. */
    fun setSampleRate(rate: Int) {
        if (rate > 0) sampleRate = rate
    }

    private fun rebuildBands(rate: Int) {
        val nyq = rate / 2f
        val half = FFT_N / 2
        for (b in 0 until BANDS) {
            val f0 = 20f * Math.pow(1000.0, b.toDouble() / BANDS).toFloat()
            val f1 = 20f * Math.pow(1000.0, (b + 1).toDouble() / BANDS).toFloat()
            bandLo[b] = ((f0 / nyq) * half).toInt().coerceIn(1, half - 1)
            bandHi[b] = ((f1 / nyq) * half).toInt().coerceIn(bandLo[b] + 1, half)
        }
        bandRate = rate
    }

    /** Called from the audio thread. Must stay allocation-free and non-blocking. */
    fun offer(buf: FloatBuffer, frames: Int, gain: Float) {
        // No consumer is looking: stay O(1) and leave the ring untouched.
        if (!running || consumers.get() <= 0) return
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

    /** A composed, visible consumer is now reading frames. */
    fun acquireConsumer() {
        consumers.incrementAndGet()
        maybeStartWorker()
    }

    /** That consumer is gone or its screen went off; stop waking when idle. */
    fun releaseConsumer() {
        if (consumers.decrementAndGet() <= 0) {
            consumers.set(0)
            worker?.interrupt()
        }
    }

    fun start() {
        if (running) return
        running = true
        maybeStartWorker()
    }

    fun stop() {
        running = false
        val w = worker
        if (w != null) {
            w.interrupt()
            try { w.join(200) } catch (_: InterruptedException) { }
        }
        worker = null
    }

    /** Run the worker only while playback is live and a consumer is visible. */
    private fun maybeStartWorker() {
        if (!running || consumers.get() <= 0) return
        synchronized(this) {
            if (!running || consumers.get() <= 0) return
            val live = worker
            if (live != null && live.isAlive) return
            worker = Thread {
                while (running && consumers.get() > 0) {
                    tickCount++
                    val t0 = System.nanoTime()
                    try { analyse() } catch (t: Throwable) { /* never kill the worker */ }
                    val spent = (System.nanoTime() - t0) / 1_000_000
                    val sleep = TICK_MS - spent
                    if (sleep > 0) {
                        try { Thread.sleep(sleep) } catch (_: InterruptedException) { break }
                    }
                }
            }.also { it.isDaemon = true; it.priority = Thread.NORM_PRIORITY; it.start() }
        }
    }

    internal fun consumerCountForTest(): Int = consumers.get()
    internal fun workerAliveForTest(): Boolean = worker?.isAlive == true
    internal fun tickCountForTest(): Long = tickCount
    internal fun awaitWorkerExitForTest(timeoutMs: Long): Boolean {
        val w = worker ?: return true
        return try { w.join(timeoutMs); !w.isAlive } catch (_: InterruptedException) { false }
    }

    private fun analyse() {
        // The tap sits on the producer side of the ring, so the newest samples
        // are future audio. Analyse at (writeIdx - buffered) instead.
        val d = delayFrames.coerceIn(0, (RING - FFT_N).toLong())
        val w = writeIdx - d
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

        // band edges depend on the device rate, so rebuild when it changes
        if (bandRate != sampleRate) rebuildBands(sampleRate)

        // magnitude -> dB, scaled so a full-scale sine reads 0 dB
        val scale = 4.0f / FFT_N
        for (b in 0 until BANDS) {
            // RMS across the band instead of max: at 5.9 Hz resolution the
            // bass bands hold only one or two bins, and max made them jump.
            var sum = 0f
            for (k in bandLo[b] until bandHi[b]) {
                val re = real[k]; val im = imag[k]
                val mag = sqrt(re * re + im * im) * scale
                sum += mag * mag
            }
            val count = (bandHi[b] - bandLo[b]).coerceAtLeast(1)
            val m = sqrt(sum / count)
            raw[b] = if (m <= 1e-7f) -100f else max(-100f, 20f * log10(m))
        }
        for (b in 0 until BANDS) {
            // 1-2-1 spatial smoothing so a kick does not spike a single band
            val lo = raw[(b - 1).coerceAtLeast(0)]
            val hi = raw[(b + 1).coerceAtMost(BANDS - 1)]
            val db = (lo + 2f * raw[b] + hi) / 4f
            smoothed[b] = if (db > smoothed[b]) {
                smoothed[b] + (db - smoothed[b]) * 0.55f
            } else {
                smoothed[b] - (smoothed[b] - db) * 0.13f
            }
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

        val bands = snapshots[snapshotSlot]
        snapshotSlot = (snapshotSlot + 1) % SNAPSHOTS
        smoothed.copyInto(bands)
        _frame.value = Frame(
            bands = bands,
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

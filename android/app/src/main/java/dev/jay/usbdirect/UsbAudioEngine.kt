// SPDX-License-Identifier: GPL-3.0-or-later
package dev.jay.usbdirect

import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock

/** One RBJ biquad slot in the native rack. type: 0=peak,1=lowshelf,2=highshelf,3=HPF,4=LPF. */
data class DspBand(
    val type: Int,
    val fc: Double,
    val q: Double,
    val gainDb: Double = 0.0,
    val enabled: Boolean = true
)

/** Complete DSP rack configuration; held across engine restarts. */
data class DspConfig(
    val bands: List<DspBand> = emptyList(),
    val preampDb: Double = 0.0,
    val autoHeadroom: Boolean = true
)

/**
 * Thin Kotlin wrapper over the native raw-usbfs engine.
 *
 * The engine owns the isochronous URBs. We hand it decoded interleaved
 * float32 PCM; it applies software volume, TPDF dither and S16/S24 packing.
 */
class UsbAudioEngine private constructor(h: Long) : AutoCloseable {

    /**
     * Lifetime guard: ordinary native calls hold the read side; close() takes
     * the write side before clearing the handle and freeing the engine.
     * abort() deliberately does not use this lock: it has its own tiny lock so
     * it can wake a writer blocked inside nativeWrite even while close() is
     * queued for the write side.
     */
    private val lifeLock = ReentrantReadWriteLock()
    private val abortLock = Any()
    private val handle = AtomicLong(h)

    @Volatile private var aborting = false
    @Volatile private var closed = false

    private inline fun <T> withHandle(fallback: T, block: (Long) -> T): T {
        val read = lifeLock.readLock()
        read.lock()
        try {
            val h = handle.get()
            return if (h == 0L) fallback else block(h)
        } finally {
            read.unlock()
        }
    }

    val isOk: Boolean
        get() = withHandle(false) { nativeOk(it) }

    val error: String
        get() = withHandle("engine closed") { nativeError(it) }

    val isAborting: Boolean
        get() = aborting || closed

    /** Blocking. Returns frames accepted, -1, or UAE_ERR_CLOSING/RESET. */
    fun write(buffer: FloatBuffer, frames: Int): Int =
        withHandle(ERR_CLOSING) { nativeWrite(it, buffer, frames) }

    /** Wake native producers and the pump; does not wait for either. */
    fun abort() {
        synchronized(abortLock) {
            val h = handle.get()
            if (h != 0L) {
                aborting = true
                nativeAbort(h)
            }
        }
    }

    /** Clear the transient abort gate after the decode thread has joined. */
    fun resume() {
        withHandle(Unit) {
            nativeResume(it)
            aborting = false
        }
    }

    fun setVolume(v: Float) {
        withHandle(Unit) { nativeSetVolume(it, v.coerceIn(0f, 1f)) }
    }

    fun setPpm(ppm: Float) {
        withHandle(Unit) { nativeSetPpm(it, ppm) }
    }

    fun setDither(on: Boolean) {
        withHandle(Unit) { nativeSetDither(it, on) }
    }

    /** Tell the packer whether the producer is resampling into this rate. */
    fun setResampled(on: Boolean) {
        withHandle(Unit) { nativeSetResampled(it, on) }
    }

    /** Decoded source depth; drives dither on 24→16 reductions. */
    fun setSourceBits(bits: Int) {
        withHandle(Unit) { nativeSetSourceBits(it, bits) }
    }

    /** Enable/disable the native biquad rack (ramped wet/dry in C). */
    fun setDspEnabled(on: Boolean) {
        withHandle(Unit) { nativeDspEnable(it, on) }
    }

    /** Publish band parameters. Coefficients are compiled natively per rate. */
    fun setDspConfig(config: DspConfig) {
        val bands = config.bands.take(12)
        val params = DoubleArray(bands.size * 5)
        for (i in bands.indices) {
            val b = bands[i]
            val o = i * 5
            params[o] = b.type.toDouble()
            params[o + 1] = b.fc
            params[o + 2] = b.q
            params[o + 3] = b.gainDb
            params[o + 4] = if (b.enabled) 1.0 else 0.0
        }
        withHandle(Unit) {
            nativeDspSetBands(it, params, bands.size, config.preampDb,
                              config.autoHeadroom)
        }
    }

    /** Clear DF-II history; coefficients are kept. */
    fun resetDsp() {
        withHandle(Unit) { nativeDspReset(it) }
    }

    /** Fixed-order snapshot: urbs, errors, underruns, ring%, rate, bits, alt,
     *  maxpkt, sync, latencyMs, jitter min/avg/max us, framesDone/Packed, filled. */
    fun telemetry(): LongArray =
        withHandle(LongArray(0)) { nativeTelemetry(it) ?: LongArray(0) }

    fun summary(): String = withHandle("") { nativeSummary(it) ?: "" }

    /** int[]{min,max,res,cur} in 1/256 dB, or null when the DAC has no Feature Unit volume. */
    fun hwVolumeInfo(): IntArray? = withHandle<IntArray?>(null) {
        val out = IntArray(4)
        if (nativeHwVolInfo(it, out) == 1) out else null
    }

    fun setHwVolume(raw: Int): Boolean =
        withHandle(false) { nativeHwVolSet(it, raw) }

    /** Re-target the live session (rate + bit depth) without releasing it. */
    fun switchFormat(rate: Int, bits: Int): Int =
        withHandle(-1) { nativeSwitchFormat(it, rate, bits) }

    /** Frames the device has actually consumed (completed URBs), not queued. */
    fun framesPlayed(): Long = withHandle(0L) { nativeFramesPlayed(it) }

    /** Frames decoded but not yet heard: ring occupancy + URBs in flight. */
    fun bufferedFrames(): Long = withHandle(0L) { nativeBufferedFrames(it) }

    /** Tell the engine the producer is done, so it can fade out cleanly. */
    fun eos() {
        withHandle(Unit) { nativeEos(it) }
    }

    override fun close() {
        // Every close path is safe without an earlier stop(): wake writers
        // first, then take the write side and let nativeClose drain them.
        abort()
        val write = lifeLock.writeLock()
        write.lock()
        try {
            val h: Long
            synchronized(abortLock) {
                h = handle.getAndSet(0L)
                closed = true
            }
            if (h != 0L) nativeClose(h)
        } finally {
            write.unlock()
        }
    }

    private external fun nativeOk(h: Long): Boolean
    private external fun nativeError(h: Long): String
    private external fun nativeWrite(h: Long, buffer: FloatBuffer, frames: Int): Int
    private external fun nativeSetVolume(h: Long, v: Float)
    private external fun nativeSetPpm(h: Long, ppm: Float)
    private external fun nativeSetDither(h: Long, on: Boolean)
    private external fun nativeSetResampled(h: Long, on: Boolean)
    private external fun nativeSetSourceBits(h: Long, bits: Int)
    private external fun nativeDspEnable(h: Long, on: Boolean)
    private external fun nativeDspSetBands(
        h: Long, params: DoubleArray, nbands: Int,
        preampDb: Double, autoHeadroom: Boolean
    )
    private external fun nativeDspReset(h: Long)
    private external fun nativeEos(h: Long)
    private external fun nativeFramesPlayed(h: Long): Long
    private external fun nativeBufferedFrames(h: Long): Long
    private external fun nativeSwitchFormat(h: Long, rate: Int, bits: Int): Int
    private external fun nativeTelemetry(h: Long): LongArray?
    private external fun nativeSummary(h: Long): String?
    private external fun nativeHwVolInfo(h: Long, out: IntArray): Int
    private external fun nativeHwVolSet(h: Long, raw: Int): Boolean
    private external fun nativeAbort(h: Long)
    private external fun nativeResume(h: Long)
    private external fun nativeClose(h: Long)

    companion object {
        const val ERR_CLOSING = -1000
        const val ERR_RESET = -1001

        init {
            System.loadLibrary("usbdirect")
        }

        /**
         * @param fd      usbfs descriptor from UsbDeviceConnection.fileDescriptor
         * @param rate    44100 / 48000 / 96000
         * @param bits    16 or 24, selects the UAC1 alt setting
         */
        fun open(fd: Int, rate: Int, bits: Int): UsbAudioEngine {
            val h = nativeOpen(fd, rate, bits)
            return UsbAudioEngine(h)
        }

        @JvmStatic
        private external fun nativeOpen(fd: Int, rate: Int, bits: Int): Long
    }
}

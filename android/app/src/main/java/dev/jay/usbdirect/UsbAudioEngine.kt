package dev.jay.usbdirect

import java.nio.FloatBuffer

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
class UsbAudioEngine private constructor(internal var handle: Long) : AutoCloseable {

    val isOk: Boolean
        get() = handle != 0L && nativeOk(handle)

    val error: String
        get() = if (handle == 0L) "engine closed" else nativeError(handle)

    /** Blocking. Returns frames accepted, or -1 if the engine failed. */
    fun write(buffer: FloatBuffer, frames: Int): Int =
        if (handle == 0L) -1 else nativeWrite(handle, buffer, frames)

    fun setVolume(v: Float) {
        if (handle != 0L) nativeSetVolume(handle, v.coerceIn(0f, 1f))
    }

    fun setPpm(ppm: Float) {
        if (handle != 0L) nativeSetPpm(handle, ppm)
    }

    fun setDither(on: Boolean) {
        if (handle != 0L) nativeSetDither(handle, on)
    }

    /** Enable/disable the native biquad rack (ramped wet/dry in C). */
    fun setDspEnabled(on: Boolean) {
        if (handle != 0L) nativeDspEnable(handle, on)
    }

    /** Publish band parameters. Coefficients are compiled natively per rate. */
    fun setDspConfig(config: DspConfig) {
        if (handle == 0L) return
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
        nativeDspSetBands(handle, params, bands.size, config.preampDb,
                          config.autoHeadroom)
    }

    /** Clear DF-II history; coefficients are kept. */
    fun resetDsp() {
        if (handle != 0L) nativeDspReset(handle)
    }

    /** Fixed-order snapshot: urbs, errors, underruns, ring%, rate, bits, alt,
     *  maxpkt, sync, latencyMs, jitter min/avg/max us, framesDone/Packed, filled. */
    fun telemetry(): LongArray = if (handle != 0L) nativeTelemetry(handle) ?: LongArray(0)
                                 else LongArray(0)

    fun summary(): String = if (handle != 0L) nativeSummary(handle) ?: "" else ""

    /** int[]{min,max,res,cur} in 1/256 dB, or null when the DAC has no Feature Unit volume. */
    fun hwVolumeInfo(): IntArray? {
        if (handle == 0L) return null
        val out = IntArray(4)
        return if (nativeHwVolInfo(handle, out) == 1) out else null
    }

    fun setHwVolume(raw: Int): Boolean =
        handle != 0L && nativeHwVolSet(handle, raw)

    /** Re-target the live session (rate + bit depth) without releasing it. */
    fun switchFormat(rate: Int, bits: Int): Int =
        if (handle != 0L) nativeSwitchFormat(handle, rate, bits) else -1

    /** Frames the device has actually consumed (completed URBs), not queued. */
    fun framesPlayed(): Long = if (handle != 0L) nativeFramesPlayed(handle) else 0L

    /** Frames decoded but not yet heard: ring occupancy + URBs in flight. */
    fun bufferedFrames(): Long = if (handle != 0L) nativeBufferedFrames(handle) else 0L

    /** Tell the engine the producer is done, so it can fade out cleanly. */
    fun eos() {
        if (handle != 0L) nativeEos(handle)
    }

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) nativeClose(h)
    }

    private external fun nativeOk(h: Long): Boolean
    private external fun nativeError(h: Long): String
    private external fun nativeWrite(h: Long, buffer: FloatBuffer, frames: Int): Int
    private external fun nativeSetVolume(h: Long, v: Float)
    private external fun nativeSetPpm(h: Long, ppm: Float)
    private external fun nativeSetDither(h: Long, on: Boolean)
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
    private external fun nativeClose(h: Long)

    companion object {
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

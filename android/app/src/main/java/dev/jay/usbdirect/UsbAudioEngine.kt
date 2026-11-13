package dev.jay.usbdirect

import java.nio.FloatBuffer

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

package dev.jay.usbdirect

import java.nio.FloatBuffer

/**
 * libsamplerate (SRC_SINC_MEDIUM_QUALITY) behind JNI.
 * Only used when the DAC cannot take the track's rate natively.
 */
class NativeResampler(
    channels: Int,
    ratio: Double,
    quality: Int = QUALITY_MEDIUM
) : AutoCloseable {

    private var handle: Long = nativeNew(channels, ratio, quality)

    val isOk: Boolean get() = handle != 0L

    fun process(input: FloatBuffer, inFrames: Int, output: FloatBuffer, outMaxFrames: Int): Int =
        if (handle == 0L) -1 else nativeProcess(handle, input, inFrames, output, outMaxFrames)

    fun flush(output: FloatBuffer, outMaxFrames: Int): Int =
        if (handle == 0L) -1 else nativeFlush(handle, output, outMaxFrames)

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) nativeDelete(h)
    }

    companion object {
        init { System.loadLibrary("usbdirect") }

        /** libsamplerate converter types. */
        const val QUALITY_BEST = 0
        const val QUALITY_MEDIUM = 1
        const val QUALITY_FASTEST = 2

        @JvmStatic private external fun nativeNew(
            channels: Int, ratio: Double, quality: Int
        ): Long
        @JvmStatic private external fun nativeProcess(
            h: Long, input: FloatBuffer, inFrames: Int,
            output: FloatBuffer, outMaxFrames: Int
        ): Int
        @JvmStatic private external fun nativeFlush(
            h: Long, output: FloatBuffer, outMaxFrames: Int
        ): Int
        @JvmStatic private external fun nativeDelete(h: Long)
    }
}

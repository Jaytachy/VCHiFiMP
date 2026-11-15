// SPDX-License-Identifier: GPL-3.0-or-later
package dev.jay.usbdirect

import java.nio.FloatBuffer

/**
 * Mixer/headphone EQ: a second native uae_dsp rack owned by the AudioTrack
 * sink rather than the USB engine.
 *
 * Control calls ([setConfig], [setEnabled], [reset]) may come from the UI
 * thread; [process] runs only on the mixer writer thread, immediately before
 * AudioTrack.write. While disabled, [process] returns without touching the
 * buffer, so the bypass is literally zero DSP code on the sample path.
 */
class MixerDsp(rate: Int) {

    private val lock = Any()
    private var handle: Long = nativeMixerDspCreate(rate.toDouble())
    private val block = MixerBlock(CHANNELS)

    /**
     * True while the native rack must run. It stays true through the 480-frame
     * fade-out after the user turns EQ off, then clears when the ramp ends.
     */
    @Volatile var engaged = false
        private set

    val isOpen: Boolean get() = handle != 0L

    /** Publish the same DspConfig used by the USB rack (seqlock in native). */
    fun setConfig(config: DspConfig) {
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
        synchronized(lock) {
            val h = handle
            if (h != 0L) {
                nativeMixerDspSetConfig(h, params, bands.size, config.preampDb,
                                        config.autoHeadroom)
            }
        }
    }

    /** Toggle with the native 480-frame wet/dry ramp (no click). */
    fun setEnabled(on: Boolean) {
        synchronized(lock) {
            val h = handle
            if (h == 0L) return
            nativeMixerDspEnable(h, on)
            if (on) engaged = true
        }
    }

    /** Clear filter history at the next processed block (seek/track change). */
    fun reset() {
        if (!engaged) return          // bypassed: no DSP call at all
        synchronized(lock) {
            val h = handle
            if (h != 0L) nativeMixerDspReset(h)
        }
    }

    /**
     * In-place EQ. Returns true when a native call was made, false when the
     * rack is bypassed. Caller must be on the mixer writer thread.
     */
    fun process(buf: FloatBuffer, frames: Int): Boolean {
        if (!engaged) return false
        synchronized(lock) {
            val h = handle
            if (h == 0L || !engaged) return false

            // Absolute bulk access only: the caller's shared buffer position,
            // limit and byte order must be exactly as received.
            val active = block.process(buf, frames) { s, f, c ->
                nativeMixerDspProcess(h, s, f, c)
            }
            if (!active) engaged = false
            return true
        }
    }

    fun close() {
        synchronized(lock) {
            val h = handle
            handle = 0L
            engaged = false
            if (h != 0L) nativeMixerDspDestroy(h)
        }
    }

    private companion object {
        const val CHANNELS = 2

        init {
            System.loadLibrary("usbdirect")
        }

        @JvmStatic
        private external fun nativeMixerDspCreate(rate: Double): Long

        @JvmStatic
        private external fun nativeMixerDspDestroy(h: Long)

        @JvmStatic
        private external fun nativeMixerDspSetConfig(
            h: Long, params: DoubleArray, nbands: Int,
            preampDb: Double, autoHeadroom: Boolean
        )

        @JvmStatic
        private external fun nativeMixerDspEnable(h: Long, on: Boolean)

        @JvmStatic
        private external fun nativeMixerDspProcess(
            h: Long, buf: FloatArray, frames: Int, channels: Int
        ): Int

        @JvmStatic
        private external fun nativeMixerDspReset(h: Long)
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package dev.jay.usbdirect

import java.nio.FloatBuffer

/**
 * Absolute bulk transfer for the mixer EQ.
 *
 * The writer's FloatBuffer is shared with PcmSink, which keeps appending to it
 * with relative puts. [process] therefore must not move the caller's position
 * or limit: an earlier relative get() left the position at the limit and the
 * next sink emit threw BufferOverflowException.
 */
internal class MixerBlock(private val channels: Int = 2) {

    private var scratch = FloatArray(PREROLL * channels)

    /** Returns true when [nativeProcess] reports the rack still active. */
    fun process(
        buf: FloatBuffer,
        frames: Int,
        nativeProcess: (FloatArray, Int, Int) -> Int
    ): Boolean {
        val n = frames * channels
        if (scratch.size < n) scratch = FloatArray(n)

        buf.get(0, scratch, 0, n)       // absolute: position/limit untouched
        val active = nativeProcess(scratch, frames, channels)
        buf.put(0, scratch, 0, n)       // absolute: position/limit untouched
        return active != 0
    }

    private companion object {
        /** Covers OUT_MAX (6144) frames without allocating per block. */
        const val PREROLL = 6144
    }
}

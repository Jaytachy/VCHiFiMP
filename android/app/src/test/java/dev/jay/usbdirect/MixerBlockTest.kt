// SPDX-License-Identifier: GPL-3.0-or-later
package dev.jay.usbdirect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Regression for the beta.1 mixer crash: MixerDsp.process() used a relative
 * get(), which moved PcmSink's shared outBuf position to its limit and made the
 * next emit() throw BufferOverflowException.
 */
class MixerBlockTest {

    @Test
    fun directBufferPositionAndLimitPreserved() {
        val frames = 2048
        val buf = ByteBuffer.allocateDirect(frames * 2 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        checkBufferContract(buf, frames)
    }

    @Test
    fun heapBufferPositionAndLimitPreserved() {
        val frames = 2048
        checkBufferContract(FloatBuffer.allocate(frames * 2), frames)
    }

    private fun checkBufferContract(buf: FloatBuffer, frames: Int) {
        val samples = frames * 2
        buf.position(0)
        buf.limit(samples)
        val pos = buf.position()
        val lim = buf.limit()
        val order = buf.order()

        val block = MixerBlock(2)
        var calls = 0
        val active = block.process(buf, frames) { scratch, f, c ->
            assertEquals(frames, f)
            assertEquals(2, c)
            assertTrue(scratch.size >= f * c)
            for (i in 0 until f * c) scratch[i] *= 0.5f
            calls++
            1
        }

        assertTrue(active)
        assertEquals(1, calls)
        assertEquals("position must not move", pos, buf.position())
        assertEquals("limit must not move", lim, buf.limit())
        assertEquals("byte order must not change", order, buf.order())

        // PcmSink's next relative append of a full block must not overflow.
        buf.position(0)
        repeat(samples) { buf.put(0.25f) }
        assertEquals(samples, buf.position())
    }

    @Test
    fun eqOnOffOnAcross1000BlocksKeepsBufferUsable() {
        val frames = 512
        val samples = frames * 2
        val buf = ByteBuffer.allocateDirect(samples * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buf.position(0)
        buf.limit(samples)

        val block = MixerBlock(2)
        var engaged = true
        var processed = 0

        repeat(1000) { i ->
            when (i) {
                300 -> engaged = false   // EQ off
                600 -> engaged = true    // EQ on again
            }
            if (engaged) {
                val pos = buf.position()
                val lim = buf.limit()
                assertTrue(block.process(buf, frames) { s, f, c ->
                    for (j in 0 until f * c) s[j] *= 0.99f
                    1
                })
                assertEquals(pos, buf.position())
                assertEquals(lim, buf.limit())
                processed++
            }
            // Same shared buffer reused for every block, as PcmSink does.
            buf.position(0)
            repeat(samples) { buf.put(0.1f) }
        }

        assertEquals(700, processed)
    }
}

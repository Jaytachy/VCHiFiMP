// SPDX-License-Identifier: GPL-3.0-or-later
package dev.jay.usbdirect

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.FloatBuffer

/**
 * Regression for the beta.2 gapless crash: flushOut() shrank outBuf's limit to
 * the partial block size and never restored it. The next track's relative
 * emit() then threw BufferOverflowException before the flush threshold.
 */
class PcmSinkBoundaryTest {

    /** Consumes the window like the real sinks and records frame order. */
    private class RecordingTarget : UsbDirectPlayer.PcmTarget {
        val left = ArrayList<Float>()
        val right = ArrayList<Float>()
        var written = 0L

        override fun write(buf: FloatBuffer, bytes: ByteBuffer, frames: Int): Int {
            assertEquals("write window", frames * 2, buf.limit())
            for (i in 0 until frames) {
                left.add(buf.get(i * 2))
                right.add(buf.get(i * 2 + 1))
            }
            written += frames
            return frames
        }

        override fun eos() {}
        override fun playedFrames(): Long = written
        override fun bufferedFrames(): Long = 0L
    }

    private var nextFrame = 0L

    private fun newSink(
        target: RecordingTarget,
        onBoundary: ((Int) -> Unit)? = null
    ) = UsbDirectPlayer.PcmSink(
        target = target,
        srcRate = 48000,
        outRate = 48000,      // passthrough: exercises emit()'s relative puts
        srcCh = 2,
        startMs = 0L,
        sessionAtStart = 0,
        currentSession = { 0 },
        progress = null,
        tap = null,
        onBoundary = onBoundary,
        quality = 1
    )

    private fun push(sink: UsbDirectPlayer.PcmSink, frames: Int) {
        val block = FloatBuffer.allocate(frames * 2)
        repeat(frames) {
            val v = nextFrame.toFloat()
            block.put(v)
            block.put(-v)
            nextFrame++
        }
        block.flip()
        sink.pushFloat(block)
    }

    private fun assertSequence(target: RecordingTarget) {
        assertEquals(nextFrame, target.written)
        assertEquals(nextFrame.toInt(), target.left.size)
        for (i in 0 until target.left.size) {
            assertEquals("left frame $i", i.toFloat(), target.left[i], 0f)
            assertEquals("right frame $i", -i.toFloat(), target.right[i], 0f)
        }
    }

    @Test
    fun partialFlushThenBoundaryThenNextTrackFullBlock() {
        val target = RecordingTarget()
        var boundaries = 0
        val sink = newSink(target) { boundaries++ }

        push(sink, 1000)                 // partial tail of track 1
        sink.commitBoundary(1, 24)       // flushOut() at a partial size
        assertEquals(1000L, target.written)

        push(sink, 6144)                 // full OUT_MAX block of track 2
        assertEquals(7144L, target.written)
        assertEquals(1, boundaries)

        push(sink, 250)
        sink.flush()
        assertEquals(7394L, target.written)
        assertSequence(target)
    }

    @Test
    fun thousandBoundariesKeepBufferUsable() {
        val target = RecordingTarget()
        val seen = ArrayList<Int>()
        val sink = newSink(target) { seen.add(it) }

        repeat(1000) { i ->
            push(sink, 37)
            sink.commitBoundary(i, 24)
        }
        push(sink, 1)
        sink.flush()

        assertEquals(1000 * 37 + 1, target.written.toInt())
        assertEquals((0 until 1000).toList(), seen)
        assertSequence(target)
    }
}

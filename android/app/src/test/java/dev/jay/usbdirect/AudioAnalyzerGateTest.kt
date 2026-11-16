// SPDX-License-Identifier: GPL-3.0-or-later
package dev.jay.usbdirect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The analyzer worker may only exist while playback is live and at least one
 * UI consumer (the Output spectrum/level panel) is composed and visible.
 * While idle there must be no worker thread and no ticks at all.
 */
class AudioAnalyzerGateTest {

    @Test
    fun startedWithoutConsumerHasNoWorkerAndNoTicks() {
        val a = AudioAnalyzer()
        try {
            a.start()
            Thread.sleep(120)
            assertEquals(0, a.consumerCountForTest())
            assertEquals(0L, a.tickCountForTest())
            assertFalse(a.workerAliveForTest())
        } finally {
            a.stop()
        }
    }

    @Test
    fun acquireStartsWorkerAndReleaseStopsIt() {
        val a = AudioAnalyzer()
        try {
            a.start()
            a.acquireConsumer()
            await("first tick") { a.tickCountForTest() > 0 }
            assertTrue(a.workerAliveForTest())

            a.releaseConsumer()
            assertTrue("worker exits when last consumer leaves",
                a.awaitWorkerExitForTest(2000))
            val idleTicks = a.tickCountForTest()
            Thread.sleep(150)
            assertEquals("no ticks while idle", idleTicks, a.tickCountForTest())
        } finally {
            a.stop()
        }
    }

    @Test
    fun secondConsumerKeepsWorkerRunning() {
        val a = AudioAnalyzer()
        try {
            a.start()
            a.acquireConsumer()
            a.acquireConsumer()
            await("first tick") { a.tickCountForTest() > 0 }

            a.releaseConsumer()
            Thread.sleep(150)
            assertTrue("one consumer still holds the worker", a.workerAliveForTest())
            val before = a.tickCountForTest()
            Thread.sleep(150)
            assertTrue("worker keeps ticking", a.tickCountForTest() > before)

            a.releaseConsumer()
            assertTrue("worker exits after last consumer",
                a.awaitWorkerExitForTest(2000))
        } finally {
            a.stop()
        }
    }

    @Test
    fun stopJoinsWorkerAndLeavesNoThread() {
        val a = AudioAnalyzer()
        try {
            a.acquireConsumer()
            a.start()
            await("first tick") { a.tickCountForTest() > 0 }
            assertTrue(a.workerAliveForTest())

            a.stop()
            assertFalse("stop leaves no live worker", a.workerAliveForTest())
            val stoppedTicks = a.tickCountForTest()
            Thread.sleep(150)
            assertEquals("no ticks after stop", stoppedTicks, a.tickCountForTest())
        } finally {
            a.releaseConsumer()
            a.stop()
        }
    }

    private fun await(what: String, timeoutMs: Long = 2000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(5)
        }
        assertTrue("timeout waiting for $what", cond())
    }
}

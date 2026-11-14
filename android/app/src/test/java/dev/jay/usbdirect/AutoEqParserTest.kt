package dev.jay.usbdirect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AutoEqParserTest {

    @Test
    fun parsesPeqShelfAndHpLp() {
        val text = """
            Preamp: -6.2 dB
            Filter 1: ON LSC Fc 105 Hz Gain 6.0 dB Q 0.70
            Filter 2: ON PK Fc 1000 Hz Gain -2.5 dB Q 1.41
            Filter 3: ON HSC Fc 10000 Hz Gain 3.0 dB Q 0.70
            Filter 4: ON HP Fc 30 Hz
        """.trimIndent()

        val r = AutoEqParser.parse(text)
        assertEquals(-6.2, r.preampDb ?: 0.0, 1e-9)
        assertEquals(0, r.ignored)
        assertEquals(4, r.bands.size)
        assertEquals(1, r.bands[0].type)   // LSC
        assertEquals(0, r.bands[1].type)   // PK
        assertEquals(2, r.bands[2].type)   // HSC
        assertEquals(3, r.bands[3].type)   // HPF
        assertEquals(105.0, r.bands[0].fc, 1e-9)
        assertEquals(6.0, r.bands[0].gainDb, 1e-9)
        assertEquals(1000.0, r.bands[1].fc, 1e-9)
        assertEquals(3.0, r.bands[2].gainDb, 1e-9)
    }

    @Test
    fun acceptsCommaDecimalsBwAndOffLines() {
        val text = """
            Preamp: -4,5 dB
            Filter 1: OFF PK Fc 31 Hz Gain 2.5 dB Q 1.41
            Filter 2: on pk fc 200 Hz gain -1,5 db bw oct 1.5
            Filter 3: ON HP Fc 20 Hz Q 0.707
            Filter 4: ON LP Fc 18000 Hz Q 0.707
        """.trimIndent()

        val r = AutoEqParser.parse(text)
        assertEquals(-4.5, r.preampDb ?: 0.0, 1e-9)
        assertEquals(3, r.bands.size)
        assertEquals(0, r.bands[0].type)
        assertEquals(200.0, r.bands[0].fc, 1e-9)
        assertEquals(-1.5, r.bands[0].gainDb, 1e-9)
        // Q = sqrt(2^BW) / (2^BW - 1) for BW = 1.5 octaves.
        assertTrue(abs(r.bands[0].q - 0.9204) < 0.01)
        assertEquals(3, r.bands[1].type)
        assertEquals(4, r.bands[2].type)
    }

    @Test
    fun capsPeqAtTenAndWarnsAboutIgnoredFilters() {
        val text = buildString {
            appendLine("Preamp: -8.0 dB")
            for (i in 1..12) {
                appendLine("Filter $i: ON PK Fc ${100 + i * 50} Hz Gain -1.0 dB Q 1.0")
            }
            appendLine("Filter 13: ON HP Fc 30 Hz Q 0.707")
            appendLine("Filter 14: ON LP Fc 18000 Hz Q 0.707")
        }

        val r = AutoEqParser.parse(text)
        assertEquals(2, r.ignored)
        assertEquals(12, r.bands.size)   // 10 PEQ + HPF + LPF
        assertEquals(10, r.bands.count { it.type == 0 })
        assertEquals(1, r.bands.count { it.type == 3 })
        assertEquals(1, r.bands.count { it.type == 4 })
    }
}

package dev.jay.usbdirect

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val EMPTY_FRAME = AudioAnalyzer.Frame(
    FloatArray(AudioAnalyzer.BANDS) { -100f }, -100f, -100f, -100f, false
)

@Composable
fun AnalyzerScreen(analyzer: AudioAnalyzer?, onClose: () -> Unit) {
    val frame = analyzer?.frame?.collectAsState(EMPTY_FRAME)?.value ?: EMPTY_FRAME

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("‹ Back") }
            Spacer(Modifier.width(4.dp))
            Text("Spectrum", style = MaterialTheme.typography.titleLarge)
        }

        Spacer(Modifier.height(8.dp))

        // ---- spectrum ----
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF101014))
        ) {
            val n = AudioAnalyzer.BANDS
            val gap = 2f
            val bw = (size.width - gap * (n - 1)) / n
            for (i in 0 until n) {
                val db = frame.bands[i]
                val norm = ((db + 90f) / 90f).coerceIn(0f, 1f)
                val h = norm * size.height
                if (h < 1f) continue
                val frac = i.toFloat() / (n - 1)
                val color = Brush.verticalGradient(
                    0f to Color(0xFF7CE7C3),
                    0.55f to Color(0xFF6E8BFF),
                    1f to Color(0xFFB06BFF)
                )
                drawRect(
                    brush = color,
                    topLeft = Offset(i * (bw + gap), size.height - h),
                    size = Size(bw, h)
                )
            }
            // -12 dB reference line
            val y = size.height * (1f - (78f / 90f))
            drawLine(
                color = Color(0x33FFFFFF),
                start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = 1f
            )
        }

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("20 Hz", style = MaterialTheme.typography.labelSmall)
            Text("1 k", style = MaterialTheme.typography.labelSmall)
            Text("10 k", style = MaterialTheme.typography.labelSmall)
            Text("20 k", style = MaterialTheme.typography.labelSmall)
        }

        Spacer(Modifier.height(20.dp))

        // ---- level meter ----
        LevelMeter(
            peakDb = frame.peakDb,
            rmsDb = frame.rmsDb,
            holdDb = frame.peakHoldDb,
            clipping = frame.clipping
        )

        Spacer(Modifier.height(12.dp))
        Text(
            "peak ${dbText(frame.peakDb)}   rms ${dbText(frame.rmsDb)}   " +
            "hold ${dbText(frame.peakHoldDb)}",
            style = MaterialTheme.typography.bodySmall
        )
        if (frame.clipping) {
            Spacer(Modifier.height(6.dp))
            Text("CLIPPING — samples are hitting full scale",
                 color = Color(0xFFFF5252),
                 style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun dbText(db: Float): String =
    if (db <= -99.9f) "-inf dB" else "%.1f dB".format(db)

@Composable
private fun LevelMeter(peakDb: Float, rmsDb: Float, holdDb: Float, clipping: Boolean) {
    // -100 .. +6 dB
    val lo = -100f
    val hi = 6f
    fun frac(db: Float) = ((db - lo) / (hi - lo)).coerceIn(0f, 1f)

    Column {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF15151A))
        ) {
            val w = size.width
            // scale shading: green up to -12, yellow to 0, red above
            val gEnd = w * frac(-12f)
            val yEnd = w * frac(0f)
            drawRect(Color(0xFF1E3A2E), size = Size(gEnd, size.height))
            drawRect(Color(0xFF3A3720), topLeft = Offset(gEnd, 0f),
                     size = Size((yEnd - gEnd).coerceAtLeast(0f), size.height))
            drawRect(Color(0xFF3A1E1E), topLeft = Offset(yEnd, 0f),
                     size = Size((w - yEnd).coerceAtLeast(0f), size.height))

            // rms body
            drawRect(
                Brush.horizontalGradient(
                    listOf(Color(0xFF31C48D), Color(0xFFF5C542), Color(0xFFEF5350))
                ),
                size = Size(w * frac(rmsDb), size.height)
            )
            // peak marker
            val px = w * frac(peakDb)
            drawLine(Color.White, Offset(px, 0f), Offset(px, size.height), strokeWidth = 2f)
            // hold marker
            val hx = w * frac(holdDb)
            drawLine(Color(0xAAFFFFFF), Offset(hx, 0f), Offset(hx, size.height), strokeWidth = 1f)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("-100", style = MaterialTheme.typography.labelSmall)
            Text("-60", style = MaterialTheme.typography.labelSmall)
            Text("-30", style = MaterialTheme.typography.labelSmall)
            Text("-12", style = MaterialTheme.typography.labelSmall)
            Text("0", style = MaterialTheme.typography.labelSmall)
            Text("+6", style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (clipping) Color(0xFFFF5252) else Color(0xFF2A2A31))
            )
            Text("clip", style = MaterialTheme.typography.labelSmall)
        }
    }
}

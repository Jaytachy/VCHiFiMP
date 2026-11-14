package dev.jay.usbdirect

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

private val EMPTY_FRAME = AudioAnalyzer.Frame(
    FloatArray(AudioAnalyzer.BANDS) { -100f }, -100f, -100f, -100f, false
)

private val DB_FLOOR = -90f

@Composable
fun SpectrumPanel(
    analyzer: AudioAnalyzer?,
    modifier: Modifier = Modifier,
    accent: Color? = null
) {
    val n = AudioAnalyzer.BANDS
    val target = remember { FloatArray(n) { DB_FLOOR } }
    val display = remember { FloatArray(n) { DB_FLOOR } }
    var tick by remember { mutableIntStateOf(0) }
    var levels by remember { mutableStateOf(EMPTY_FRAME) }

    LaunchedEffect(analyzer) {
        if (analyzer == null) return@LaunchedEffect
        launch {
            analyzer.frame.collect { f ->
                f.bands.copyInto(target)
                levels = f
            }
        }
        // interpolate toward the newest frame at display refresh: frames arrive
        // every 8 ms, the screen redraws every ~8 ms too, and chasing rather
        // than snapping is what makes the curve glide instead of step
        while (true) {
            withFrameNanos { }
            for (i in 0 until n) display[i] += (target[i] - display[i]) * 0.35f
            tick++
        }
    }

    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF101014))
        ) {
            tick  // read so every animation step redraws

            val floorY = size.height
            fun norm(db: Float) = ((db - DB_FLOOR) / -DB_FLOOR).coerceIn(0f, 1f)

            val pts = Array(n) { i ->
                Offset(
                    x = i * size.width / (n - 1),
                    y = floorY - norm(display[i]) * floorY
                )
            }

            // Catmull-Rom -> cubic bezier, so the curve is continuous
            val line = Path()
            line.moveTo(pts[0].x, pts[0].y)
            for (i in 0 until n - 1) {
                val p0 = pts[max(0, i - 1)]
                val p1 = pts[i]
                val p2 = pts[i + 1]
                val p3 = pts[min(n - 1, i + 2)]
                line.cubicTo(
                    p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
                    p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
                    p2.x, p2.y
                )
            }

            // -12 dB reference
            val refY = floorY - norm(-12f) * floorY
            drawLine(Color(0x22FFFFFF), Offset(0f, refY), Offset(size.width, refY), 1f)

            val area = Path().apply {
                addPath(line)
                lineTo(size.width, floorY)
                lineTo(0f, floorY)
                close()
            }
            val a = accent ?: Color(0xFF7CE7C3)
            val a2 = accent?.copy(alpha = 1f) ?: Color(0xFF6E8BFF)
            drawPath(
                area,
                Brush.verticalGradient(
                    0f to a.copy(alpha = 0.60f),
                    0.45f to a2.copy(alpha = 0.38f),
                    1f to a2.copy(alpha = 0.05f)
                )
            )
            // soft outer glow then the crisp line
            drawPath(line, a.copy(alpha = 0.25f), style = Stroke(width = 8f))
            drawPath(line, accent?.copy(alpha = 0.95f) ?: Color(0xFF9FF3D6),
                     style = Stroke(width = 2.5f))
        }

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("20 Hz", style = MaterialTheme.typography.labelSmall)
            Text("1 k", style = MaterialTheme.typography.labelSmall)
            Text("10 k", style = MaterialTheme.typography.labelSmall)
            Text("20 k", style = MaterialTheme.typography.labelSmall)
        }

        Spacer(Modifier.height(20.dp))

        LevelMeter(
            peakDb = levels.peakDb,
            rmsDb = levels.rmsDb,
            holdDb = levels.peakHoldDb,
            clipping = levels.clipping
        )

        Spacer(Modifier.height(12.dp))
        Text(
            "peak ${dbText(levels.peakDb)}   rms ${dbText(levels.rmsDb)}   " +
            "hold ${dbText(levels.peakHoldDb)}",
            style = MaterialTheme.typography.bodySmall
        )
        if (levels.clipping) {
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
fun LevelMeter(peakDb: Float, rmsDb: Float, holdDb: Float, clipping: Boolean) {
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
            val gEnd = w * frac(-12f)
            val yEnd = w * frac(0f)
            drawRect(Color(0xFF1E3A2E), size = Size(gEnd, size.height))
            drawRect(Color(0xFF3A3720), topLeft = Offset(gEnd, 0f),
                     size = Size((yEnd - gEnd).coerceAtLeast(0f), size.height))
            drawRect(Color(0xFF3A1E1E), topLeft = Offset(yEnd, 0f),
                     size = Size((w - yEnd).coerceAtLeast(0f), size.height))

            drawRect(
                Brush.horizontalGradient(
                    listOf(Color(0xFF31C48D), Color(0xFFF5C542), Color(0xFFEF5350))
                ),
                size = Size(w * frac(rmsDb), size.height)
            )
            val px = w * frac(peakDb)
            drawLine(Color.White, Offset(px, 0f), Offset(px, size.height), 2f)
            val hx = w * frac(holdDb)
            drawLine(Color(0xAAFFFFFF), Offset(hx, 0f), Offset(hx, size.height), 1f)
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

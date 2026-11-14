package dev.jay.usbdirect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Accent pair pulled out of the active cover art. */
data class ArtAccent(val primary: Color, val secondary: Color)

/**
 * Dominant/vibrant colour extraction from the cached album art.
 *
 * The bitmap is downscaled to 32x32, pixels are binned by hue and scored by
 * saturation times brightness, so a small vivid detail beats a large grey
 * background. Results are memoised per album.
 */
object ArtPalette {

    private val cache = HashMap<String, ArtAccent>()
    private fun key(album: String, artist: String) = album + "\u0000" + artist

    fun peek(album: String, artist: String): ArtAccent? =
        synchronized(cache) { cache[key(album, artist)] }

    suspend fun load(context: Context, album: String, artist: String): ArtAccent? =
        withContext(Dispatchers.IO) {
            if (album.isEmpty() && artist.isEmpty()) return@withContext null
            val k = key(album, artist)
            synchronized(cache) { cache[k] }?.let { return@withContext it }

            val file = ArtStore.existing(context, album, artist) ?: return@withContext null
            val src = BitmapFactory.decodeFile(file.absolutePath) ?: return@withContext null
            val small = Bitmap.createScaledBitmap(src, 32, 32, true)
            if (small !== src) src.recycle()

            val px = IntArray(32 * 32)
            small.getPixels(px, 0, 32, 0, 0, 32, 32)
            small.recycle()

            val bins = 24
            val score = FloatArray(bins)
            val sumR = FloatArray(bins)
            val sumG = FloatArray(bins)
            val sumB = FloatArray(bins)
            val hsv = FloatArray(3)

            for (p in px) {
                if ((p ushr 24) and 0xFF < 128) continue
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                android.graphics.Color.RGBToHSV(r, g, b, hsv)
                // skip near-black, blown-out and washed-out pixels
                if (hsv[2] < 0.10f || hsv[1] < 0.15f) continue
                val bin = (hsv[0] / (360f / bins)).toInt().coerceIn(0, bins - 1)
                val w = hsv[1] * (0.35f + hsv[2])
                score[bin] += w
                sumR[bin] += r * w
                sumG[bin] += g * w
                sumB[bin] += b * w
            }

            var best = -1
            var bestScore = 0f
            for (i in 0 until bins) if (score[i] > bestScore) { bestScore = score[i]; best = i }
            if (best < 0 || bestScore <= 0f) return@withContext null

            val w = score[best]
            val r = (sumR[best] / w).toInt().coerceIn(0, 255)
            val g = (sumG[best] / w).toInt().coerceIn(0, 255)
            val b = (sumB[best] / w).toInt().coerceIn(0, 255)

            android.graphics.Color.RGBToHSV(r, g, b, hsv)
            // push saturation up a little: covers are often muted by design
            val accent = Color.hsv(
                hsv[0],
                (hsv[1] * 1.15f).coerceIn(0.35f, 0.9f),
                hsv[2].coerceIn(0.55f, 0.95f)
            )
            val secondary = Color.hsv(
                (hsv[0] + 26f) % 360f,
                (hsv[1] * 1.05f).coerceIn(0.25f, 0.85f),
                (hsv[2] * 0.85f).coerceIn(0.35f, 0.8f)
            )

            ArtAccent(accent, secondary).also { synchronized(cache) { cache[k] = it } }
        }
}

/** Accent for the current track, or null when dynamic colours are disabled. */
@Composable
fun rememberArtAccent(album: String, artist: String, enabled: Boolean): ArtAccent? {
    val ctx = LocalContext.current
    var accent by remember(album, artist, enabled) {
        mutableStateOf(if (enabled) ArtPalette.peek(album, artist) else null)
    }
    LaunchedEffect(album, artist, enabled) {
        accent = if (enabled) ArtPalette.load(ctx, album, artist) else null
    }
    return accent
}

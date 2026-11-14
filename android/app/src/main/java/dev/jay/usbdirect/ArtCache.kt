package dev.jay.usbdirect

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decoded artwork, held in memory and decoded off the main thread.
 *
 * Decoding inside composition was the cause of the grid stutter: every card
 * entering the viewport blocked a frame on BitmapFactory. The cache is sized in
 * bytes because 512x512 ARGB is ~1 MB per album.
 */
object ArtCache {

    private val cache = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    private fun key(album: String, artist: String) = album + "\u0000" + artist

    fun peek(album: String, artist: String): ImageBitmap? = cache.get(key(album, artist))

    suspend fun load(context: Context, album: String, artist: String): ImageBitmap? =
        withContext(Dispatchers.IO) {
            val k = key(album, artist)
            cache.get(k)?.let { return@withContext it }
            val f = ArtStore.existing(context, album, artist) ?: return@withContext null
            val opts = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
            val bmp = BitmapFactory.decodeFile(f.absolutePath, opts)?.asImageBitmap()
                ?: return@withContext null
            cache.put(k, bmp)
            bmp
        }
}

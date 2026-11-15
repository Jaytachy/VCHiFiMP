// SPDX-License-Identifier: GPL-3.0-or-later
package dev.jay.usbdirect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Album artwork cache.
 *
 * Embedded art is often 1500x1500 PNG and re-decoding it while scrolling is
 * fatal for smoothness, so each album's first track is decoded once, scaled
 * to 512 px and written to the app's files dir.
 */
object ArtStore {

    private const val TARGET = 512

    private fun dir(context: Context) = File(context.filesDir, "art").apply { mkdirs() }

    fun fileFor(context: Context, album: String, artist: String): File =
        File(dir(context), key(album, artist) + ".jpg")

    fun existing(context: Context, album: String, artist: String): File? =
        fileFor(context, album, artist).takeIf { it.exists() }

    /** Extract + downscale once. Returns the cached file, or null if no art. */
    fun extract(context: Context, album: String, artist: String, trackUri: Uri): File? {
        val out = fileFor(context, album, artist)
        if (out.exists()) return out
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, trackUri)
            val bytes = r.embeddedPicture ?: return null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            var sample = 1
            while (opts.outWidth / (sample * 2) >= TARGET) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return null
            val scaled = if (bmp.width > TARGET) {
                Bitmap.createScaledBitmap(
                    bmp, TARGET, (bmp.height * TARGET / bmp.width).coerceAtLeast(1), true
                )
            } else bmp
            File(context.filesDir, "art.tmp").outputStream().use {
                scaled.compress(Bitmap.CompressFormat.JPEG, 88, it)
            }
            File(context.filesDir, "art.tmp").renameTo(out)
            if (scaled !== bmp) bmp.recycle()
            scaled.recycle()
            out
        } catch (t: Throwable) {
            Log.w("VCProbe", "art failed for $album: ${t.message}")
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun key(album: String, artist: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val h = md.digest((album + "\u0000" + artist).toByteArray())
        return h.joinToString("") { "%02x".format(it) }.take(16)
    }
}

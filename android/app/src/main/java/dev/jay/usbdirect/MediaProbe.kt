package dev.jay.usbdirect

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File

/** Cheap metadata lookup used to build a Track without decoding anything. */
object MediaProbe {

    data class Info(
        val sampleRate: Int,
        val channels: Int,
        val durationMs: Long,
        val mime: String
    )

    fun of(context: Context, uri: Uri): Info? {
        val ex = MediaExtractor()
        return try {
            if (uri.scheme == "file") {
                ex.setDataSource(uri.path!!)
            } else {
                context.contentResolver.openFileDescriptor(uri, "r")?.use {
                    ex.setDataSource(it.fileDescriptor)
                } ?: return null
            }
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                val durUs = if (f.containsKey(MediaFormat.KEY_DURATION))
                    f.getLong(MediaFormat.KEY_DURATION) else 0L
                return Info(
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                    durationMs = durUs / 1000,
                    mime = mime
                )
            }
            null
        } catch (t: Throwable) {
            null
        } finally {
            ex.release()
        }
    }

    fun titleOf(uri: Uri): String =
        uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
            ?: File(uri.path ?: "").nameWithoutExtension.ifEmpty { "track" }
}

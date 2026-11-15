// SPDX-License-Identifier: GPL-3.0-or-later
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
        val mime: String,
        /** 16/24 when the container reports it, else 0. */
        val bitDepth: Int = 0
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
                val bits = runCatching {
                    if (f.containsKey("bits-per-sample")) f.getInteger("bits-per-sample")
                    else 0
                }.getOrDefault(0)
                return Info(
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                    durationMs = durUs / 1000,
                    mime = mime,
                    bitDepth = bits
                )
            }
            null
        } catch (t: Throwable) {
            null
        } finally {
            ex.release()
        }
    }

    /** Fallback codec label from the file extension. */
    fun codecOf(uri: Uri): String {
        val name = uri.lastPathSegment ?: uri.path ?: return ""
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "flac" -> "FLAC"
            "wav", "aiff", "aif" -> "PCM"
            "mp3" -> "MP3"
            "m4a", "alac" -> "ALAC"
            "aac" -> "AAC"
            "ogg" -> "OGG"
            "opus" -> "OPUS"
            "dsf", "dff" -> "DSD"
            else -> ext.uppercase()
        }
    }

    fun titleOf(uri: Uri): String =
        uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
            ?: File(uri.path ?: "").nameWithoutExtension.ifEmpty { "track" }
}

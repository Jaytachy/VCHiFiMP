package dev.jay.usbdirect

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

data class LibTrack(
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val trackNo: Int,
    val durationMs: Long,
    val sampleRate: Int,
    val bitDepth: Int = 0,
    val codec: String = ""
) {
    fun toQueueTrack() = Track(uri, title, durationMs, artist, album, codec)
}

data class LibAlbum(
    val name: String,
    val artist: String,
    val tracks: List<LibTrack>
) {
    val durationMs: Long get() = tracks.sumOf { it.durationMs }
}

/**
 * Walks a SAF tree (the folder the user picked) and reads tags.
 *
 * Everything is blocking document I/O, so it runs off the main thread and
 * reports progress; a large library is thousands of ContentResolver round
 * trips and cannot be done on the UI thread.
 */
object LibraryScanner {

    private val AUDIO_EXT = setOf(
        "flac", "wav", "mp3", "m4a", "aac", "ogg", "opus", "dsf", "dff", "aiff", "aif", "alac"
    )

    fun scan(
        context: Context,
        tree: Uri,
        onProgress: (found: Int, current: String) -> Unit,
        cancel: AtomicBoolean = AtomicBoolean(false)
    ): List<LibTrack> {
        val out = ArrayList<LibTrack>()
        try {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            walk(context, tree, rootId, out, onProgress, cancel)
        } catch (t: Throwable) {
            Log.e("VCProbe", "scan failed: ${t.message}", t)
        }
        Log.i("VCProbe", "scan: ${out.size} tracks")
        return out
    }

    private fun walk(
        context: Context,
        tree: Uri,
        documentId: String,
        out: MutableList<LibTrack>,
        onProgress: (Int, String) -> Unit,
        cancel: AtomicBoolean
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val resolver = context.contentResolver
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        resolver.query(childrenUri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (cancel.get()) return
                val id = c.getString(0)
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                val child = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(context, tree, id, out, onProgress, cancel)
                } else {
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext in AUDIO_EXT || mime.startsWith("audio/")) {
                        val t = tagOf(context, child, name)
                        if (t != null) {
                            out.add(t)
                            onProgress(out.size, name)
                            if (out.size % 25 == 0)
                                Log.i("VCProbe", "scan: ${out.size} tracks, at $name")
                        }
                    }
                }
            }
        }
    }

    private fun tagOf(context: Context, uri: Uri, filename: String): LibTrack? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            fun meta(key: Int) = r.extractMetadata(key)?.trim().orEmpty()
            val title = meta(MediaMetadataRetriever.METADATA_KEY_TITLE)
                .ifEmpty { filename.substringBeforeLast('.') }
            val artist = meta(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                .ifEmpty { meta(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) }
                .ifEmpty { "Unknown artist" }
            val album = meta(MediaMetadataRetriever.METADATA_KEY_ALBUM).ifEmpty { "Unknown album" }
            val trackNo = meta(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                .substringBefore('/').toIntOrNull() ?: 0
            val durMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0L
            val bits = meta(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE).toIntOrNull() ?: 0
            val rate = meta(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE).toIntOrNull() ?: 0
            if (rate == 0 && bits == 0) {
                // retriever does not always report these; leave 0 and let
                // playback probe the real value
            }
            val ext = filename.substringAfterLast('.', "").lowercase()
            LibTrack(uri, title, artist, album, trackNo, durMs, rate, bits, codecName(ext))
        } catch (t: Throwable) {
            Log.w("VCProbe", "tag failed for $uri: ${t.message}")
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun codecName(ext: String): String = when (ext) {
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

    fun albums(tracks: List<LibTrack>): List<LibAlbum> =
        tracks.groupBy { it.album to it.artist }
            .map { (key, list) ->
                LibAlbum(key.first, key.second, list.sortedBy { it.trackNo })
            }
            .sortedBy { it.name.lowercase() }
}

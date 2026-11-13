package dev.jay.usbdirect

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The library is a few hundred ContentResolver round trips to build, so it is
 * cached after the first scan and reloaded on launch. The cache is keyed by
 * the chosen folder, so switching folders does not show a stale library.
 */
object LibraryStore {

    private const val FILE = "library_cache.json"
    private const val VERSION = 1

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun load(context: Context, tree: Uri): List<LibTrack>? {
        val f = file(context)
        if (!f.exists()) return null
        return try {
            val root = JSONObject(f.readText())
            if (root.optInt("version") != VERSION) return null
            if (root.optString("tree") != tree.toString()) return null
            val arr = root.optJSONArray("tracks") ?: return null
            val out = ArrayList<LibTrack>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    LibTrack(
                        uri = Uri.parse(o.getString("uri")),
                        title = o.getString("title"),
                        artist = o.getString("artist"),
                        album = o.getString("album"),
                        trackNo = o.optInt("trackNo"),
                        durationMs = o.optLong("durationMs"),
                        sampleRate = o.optInt("sampleRate")
                    )
                )
            }
            Log.i("VCProbe", "cache hit: ${out.size} tracks")
            out
        } catch (t: Throwable) {
            Log.w("VCProbe", "cache load failed: ${t.message}")
            null
        }
    }

    fun save(context: Context, tree: Uri, tracks: List<LibTrack>) {
        try {
            val arr = JSONArray()
            for (t in tracks) {
                arr.put(
                    JSONObject().apply {
                        put("uri", t.uri.toString())
                        put("title", t.title)
                        put("artist", t.artist)
                        put("album", t.album)
                        put("trackNo", t.trackNo)
                        put("durationMs", t.durationMs)
                        put("sampleRate", t.sampleRate)
                    }
                )
            }
            val root = JSONObject().apply {
                put("version", VERSION)
                put("tree", tree.toString())
                put("savedAt", System.currentTimeMillis())
                put("tracks", arr)
            }
            // write to a temp file first so a crash mid-write cannot leave a
            // truncated cache that would be parsed as an empty library
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file(context))) {
                tmp.delete()
            }
            Log.i("VCProbe", "cache saved: ${tracks.size} tracks")
        } catch (t: Throwable) {
            Log.w("VCProbe", "cache save failed: ${t.message}")
        }
    }

    fun clear(context: Context) {
        file(context).delete()
    }
}

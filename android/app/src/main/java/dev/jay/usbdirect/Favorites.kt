package dev.jay.usbdirect

import android.content.Context

/** Persisted favourite track URIs. */
object Favorites {
    private const val PREFS = "vc"
    private const val KEY = "favoriteUris"

    fun load(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    fun save(context: Context, uris: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY, uris).apply()
    }
}

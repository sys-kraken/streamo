package com.streamo.app.data

import android.content.Context

/** Saves the library in the app's "library" SharedPreferences, as Streamo always has. */
class SharedPrefsStorage(context: Context) : KeyValueStorage {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

fun LibraryStore.init(context: Context) = init(SharedPrefsStorage(context))

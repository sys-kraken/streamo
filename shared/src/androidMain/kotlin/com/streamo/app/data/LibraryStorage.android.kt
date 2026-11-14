package com.streamo.app.data

import android.content.Context

/** Saves in one of the app's SharedPreferences files ("library", "downloads"), as Streamo always has. */
class SharedPrefsStorage(context: Context, name: String = "library") : KeyValueStorage {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

fun LibraryStore.init(context: Context) = init(SharedPrefsStorage(context))

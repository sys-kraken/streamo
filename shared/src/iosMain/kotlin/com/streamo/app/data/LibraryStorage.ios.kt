package com.streamo.app.data

import platform.Foundation.NSUserDefaults

/** Saves the library in the app's standard NSUserDefaults, under "library.<key>". */
class UserDefaultsStorage : KeyValueStorage {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun get(key: String): String? = defaults.stringForKey("library.$key")

    override fun put(key: String, value: String) {
        defaults.setObject(value, forKey = "library.$key")
    }
}

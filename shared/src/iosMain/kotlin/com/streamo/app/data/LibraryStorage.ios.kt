package com.streamo.app.data

import platform.Foundation.NSUserDefaults

/** Saves in the app's standard NSUserDefaults, under "<name>.<key>". */
class UserDefaultsStorage(private val name: String = "library") : KeyValueStorage {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun get(key: String): String? = defaults.stringForKey("$name.$key")

    override fun put(key: String, value: String) {
        defaults.setObject(value, forKey = "$name.$key")
    }
}

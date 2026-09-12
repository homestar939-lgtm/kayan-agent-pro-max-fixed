package com.kayan.x.files

import android.content.Context
import android.net.Uri

/**
 * Persists SAF tree URI roots across reboots.
 * Uses SharedPreferences (tiny key-value store) — no Database needed for this data.
 */
class PersistedUriStore(context: Context) {

    private val prefs = context.getSharedPreferences("saf_roots", Context.MODE_PRIVATE)

    fun saveRoot(name: String, uri: Uri) {
        prefs.edit().putString("root_$name", uri.toString()).apply()
    }

    fun loadRoot(name: String): Uri? =
        prefs.getString("root_$name", null)?.let { Uri.parse(it) }

    fun loadRoots(): Map<String, Uri> {
        val all = prefs.all
        return all
            .filterKeys { it.startsWith("root_") }
            .mapKeys { it.key.removePrefix("root_") }
            .mapValues { Uri.parse(it.value as String) }
    }

    fun removeRoot(name: String) {
        prefs.edit().remove("root_$name").apply()
    }

    fun hasRoot(name: String): Boolean = prefs.contains("root_$name")
}

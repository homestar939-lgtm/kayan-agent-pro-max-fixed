package com.kayan.x.files

import android.net.Uri

/**
 * Opaque, SAF-safe file reference used by the Agent.
 * The Agent NEVER handles raw /sdcard paths — only [DocumentHandle] instances.
 */
data class DocumentHandle(
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean,
    val isFile: Boolean
)

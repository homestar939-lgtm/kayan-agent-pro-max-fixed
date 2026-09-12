package com.kayan.x.model

data class ModelInfo(
    val uri: String,
    val displayName: String,
    val sizeBytes: Long,
    val addedAt: Long
) {
    val sizeMb: Float get() = sizeBytes / 1_048_576f
    val sizeLabel: String get() = "${"%.1f".format(sizeMb)} MB"
}

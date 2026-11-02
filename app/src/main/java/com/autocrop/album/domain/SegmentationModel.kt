package com.autocrop.album.domain

/** Stable preference keys and model-specific mask semantics. */
enum class SegmentationModel(val key: String, val assetPath: String, val displayName: String) {
    DEEPLAB("deeplab", "deeplab_v3.tflite", "DeepLab"),
    SELFIE("selfie", "selfie_segmenter.tflite", "Selfie"),
    MULTICLASS("multiclass", "selfie_multiclass_256x256.tflite", "Multiclass");

    fun normalizeCategories(categories: ByteArray): ByteArray {
        if (this == DEEPLAB) return categories
        return ByteArray(categories.size) { i ->
            val category = categories[i].toInt() and 255
            if (if (this == MULTICLASS) category in 1..5 else category == 1) 1 else 0
        }
    }

    companion object {
        fun fromKey(key: String?): SegmentationModel = values().firstOrNull { it.key == key } ?: MULTICLASS
        fun personFromConfidence(score: Float): Byte = if (score >= 0.5f) 1 else 0
    }
}

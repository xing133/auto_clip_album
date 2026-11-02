package com.autocrop.album.domain

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import java.nio.ByteOrder

class InteractivePersonSelector(context: Context) {
    private val context = context.applicationContext
    fun select(bitmap: Bitmap, x: Float, y: Float): ByteArray {
        require(x >= 0 && x < bitmap.width && y >= 0 && y < bitmap.height)
        val task = InteractiveSegmenter.createFromOptions(context,
            InteractiveSegmenter.InteractiveSegmenterOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("magic_touch.tflite").build())
                .setOutputCategoryMask(false).setOutputConfidenceMasks(true).build())
        try {
            BitmapImageBuilder(checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))).build().use { input ->
                // Verified for pinned SDK 0.10.9: convertToRenderData omits normalized=true.
                // Despite the keypoint type name, this SDK consumes input-image PIXEL coordinates.
                val result = task.segment(input, InteractiveSegmenter.RegionOfInterest.create(NormalizedKeypoint.create(x, y)))
                val masks = result.confidenceMasks().orElseThrow { IllegalStateException("No selection mask") }
                try {
                    check(masks.isNotEmpty())
                    val mask = masks.last()
                    val scores = ByteBufferExtractor.extract(mask).duplicate().order(ByteOrder.nativeOrder())
                        .apply { rewind() }.asFloatBuffer()
                    val selected = ByteArray(bitmap.width * bitmap.height)
                    for (py in 0 until bitmap.height) for (px in 0 until bitmap.width) {
                        val mx = ((px + 0.5) * mask.width / bitmap.width).toInt().coerceAtMost(mask.width - 1)
                        val my = ((py + 0.5) * mask.height / bitmap.height).toInt().coerceAtMost(mask.height - 1)
                        if (scores.get(my * mask.width + mx) >= 0.5f) selected[py * bitmap.width + px] = 1
                    }
                    android.util.Log.d("BystanderEditor", "point=($x,$y) selected=${selected.count { it.toInt() != 0 }}")
                    return selected
                } finally { masks.forEach { it.close() } }
            }
        } finally { task.close() }
    }
}

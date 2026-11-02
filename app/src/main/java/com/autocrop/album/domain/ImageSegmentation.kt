package com.autocrop.album.domain

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.nio.ByteOrder

/** Each CPU task owns its native graph and a copy of its input; the caller retains its bitmap. */
class ImageSegmentation(context: Context) {
    private val context = context.applicationContext

    fun segment(bitmap: Bitmap, model: SegmentationModel): SegmentationRegions {
        val options = ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(model.assetPath).build())
            .setRunningMode(RunningMode.IMAGE)
            .setOutputCategoryMask(model != SegmentationModel.SELFIE)
            .setOutputConfidenceMasks(model == SegmentationModel.SELFIE)
            .build()
        val segmenter = ImageSegmenter.createFromOptions(context, options)
        try {
            // MediaPipe 0.10.9 recycles a wrapped Bitmap when its MPImage is closed.
            BitmapImageBuilder(checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))).build().use { image ->
                val result = segmenter.segment(image)
                val regions = if (model == SegmentationModel.SELFIE) {
                    val masks = result.confidenceMasks().orElseThrow { IllegalStateException("No confidence mask") }
                    try {
                        check(masks.size == 1) { "Expected a single person-confidence channel" }
                        val mask = masks.single()
                        val scores = ByteBufferExtractor.extract(mask).duplicate().order(ByteOrder.nativeOrder())
                            .apply { rewind() }.asFloatBuffer()
                        val categories = ByteArray(mask.width * mask.height) {
                            SegmentationModel.personFromConfidence(scores.get(it))
                        }
                        SegmentationRegions.fromCategories(categories, mask.width, mask.height)
                    } finally { masks.forEach { it.close() } }
                } else {
                    check(result.categoryMask().isPresent) { "Segmenter returned no category mask" }
                    result.categoryMask().get().use { mask ->
                        val buffer = ByteBufferExtractor.extract(mask).duplicate().apply { rewind() }
                        val categories = ByteArray(mask.width * mask.height)
                        check(buffer.remaining() >= categories.size) { "Incomplete segmentation mask" }
                        buffer.get(categories)
                        SegmentationRegions.fromCategories(model.normalizeCategories(categories), mask.width, mask.height)
                    }
                }
                android.util.Log.d("ImageSegmentation", "model=${model.key}, mask=${regions.width}x${regions.height}, regions=${regions.regions.size}")
                return regions
            }
        } finally { segmenter.close() }
    }
}

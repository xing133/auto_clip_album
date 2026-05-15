package com.autocrop.album.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.autocrop.album.data.models.DetectedPerson
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MediaPipe 人物检测器封装
 *
 * 使用 Object Detector（EfficientDet-Lite，COCO 80 类）只保留 person 类，
 * 直接返回包含整个可见人物（含头发、衣服）的 bounding box。
 */
class PersonDetector(private val context: Context) {

    private var objectDetector: ObjectDetector? = null
    private var isInitialized = false

    /**
     * 初始化 MediaPipe Object Detector
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext

        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET_PATH)
                .build()

            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setCategoryAllowlist(listOf(PERSON_CATEGORY))  // 只检测人物
                .setMaxResults(MAX_PERSONS)                     // 最多检测两个人
                .setScoreThreshold(DetectedPerson.MIN_CONFIDENCE)
                .build()

            objectDetector = ObjectDetector.createFromOptions(context, options)
            isInitialized = true
        } catch (e: Exception) {
            e.printStackTrace()
            throw IllegalStateException("Failed to initialize PersonDetector: ${e.message}")
        }
    }

    /**
     * 检测图片中的人物
     * @param bitmap 输入图片
     * @return 检测到的人物列表（按置信度从高到低排序）
     */
    suspend fun detectPersons(bitmap: Bitmap): List<DetectedPerson> = withContext(Dispatchers.Default) {
        if (!isInitialized) {
            initialize()
        }

        val detector = objectDetector ?: return@withContext emptyList()

        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result: ObjectDetectorResult = detector.detect(mpImage)

            result.detections()
                .mapNotNull { detection ->
                    // boundingBox() 已是像素坐标；categories() 因 allowlist 只含 person
                    val category = detection.categories().firstOrNull() ?: return@mapNotNull null
                    val box = detection.boundingBox()
                    if (box.width() <= 0 || box.height() <= 0) return@mapNotNull null
                    Pair(RectF(box), category.score())
                }
                .sortedByDescending { it.second }
                .mapIndexed { index, (box, score) ->
                    DetectedPerson(
                        personId = index,
                        boundingBox = box,
                        confidence = score
                    )
                }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * 释放资源
     */
    fun close() {
        objectDetector?.close()
        objectDetector = null
        isInitialized = false
    }

    companion object {
        private const val MODEL_ASSET_PATH = "efficientdet_lite0.tflite"
        private const val PERSON_CATEGORY = "person"
        private const val MAX_PERSONS = 2
    }
}

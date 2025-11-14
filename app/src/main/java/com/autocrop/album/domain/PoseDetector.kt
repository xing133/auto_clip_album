package com.autocrop.album.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import com.autocrop.album.data.models.DetectedPerson
import com.autocrop.album.data.models.LandmarkType
import com.autocrop.album.data.models.PoseLandmark
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MediaPipe 姿态检测器封装
 */
class PoseDetector(private val context: Context) {

    private var poseLandmarker: PoseLandmarker? = null
    private var isInitialized = false

    /**
     * 初始化 MediaPipe Pose Landmarker
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext

        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("pose_landmarker_heavy.task")
                .build()

            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumPoses(5)  // 最多检测5个人
                .setMinPoseDetectionConfidence(DetectedPerson.MIN_CONFIDENCE)
                .setMinPosePresenceConfidence(DetectedPerson.MIN_CONFIDENCE)
                .setMinTrackingConfidence(DetectedPerson.MIN_CONFIDENCE)
                .build()

            poseLandmarker = PoseLandmarker.createFromOptions(context, options)
            isInitialized = true
        } catch (e: Exception) {
            e.printStackTrace()
            throw IllegalStateException("Failed to initialize PoseDetector: ${e.message}")
        }
    }

    /**
     * 检测图片中的人物姿态
     * @param bitmap 输入图片
     * @return 检测到的人物列表
     */
    suspend fun detectPose(bitmap: Bitmap): List<DetectedPerson> = withContext(Dispatchers.Default) {
        if (!isInitialized) {
            initialize()
        }

        val landmarker = poseLandmarker ?: return@withContext emptyList()

        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result: PoseLandmarkerResult = landmarker.detect(mpImage)

            val detectedPersons = mutableListOf<DetectedPerson>()

            result.landmarks().forEachIndexed { index, normalizedLandmarks ->
                val landmarks = mutableListOf<PoseLandmark>()
                var minX = Float.MAX_VALUE
                var minY = Float.MAX_VALUE
                var maxX = Float.MIN_VALUE
                var maxY = Float.MIN_VALUE

                normalizedLandmarks.forEachIndexed { landmarkIndex, landmark ->
                    // MediaPipe 返回的是归一化坐标 [0, 1]，需要转换为像素坐标
                    val x = landmark.x() * bitmap.width
                    val y = landmark.y() * bitmap.height
                    val maskCount = result.segmentationMasks().map { it.size }.orElse(0)
                    val visibility = if (index < maskCount) {
                        landmark.visibility().orElse(1.0f)
                    } else {
                        1.0f
                    }
                    val presence = landmark.presence().orElse(1.0f)

                    val landmarkType = LandmarkType.fromIndex(landmarkIndex)
                    if (landmarkType != null) {
                        landmarks.add(
                            PoseLandmark(
                                type = landmarkType,
                                position = PointF(x, y),
                                visibility = visibility,
                                presence = presence
                            )
                        )

                        // 计算边界框：只使用在图片范围内的骨骼点
                        // 过滤掉 MediaPipe 预测的、超出图片边界的部分
                        if (visibility > DetectedPerson.VISIBILITY_THRESHOLD &&
                            x >= 0 && x <= bitmap.width &&
                            y >= 0 && y <= bitmap.height) {
                            minX = minOf(minX, x)
                            minY = minOf(minY, y)
                            maxX = maxOf(maxX, x)
                            maxY = maxOf(maxY, y)
                        }
                    }
                }

                // 确保边界框有效
                if (minX < maxX && minY < maxY) {
                    val boundingBox = RectF(minX, minY, maxX, maxY)

                    // 计算平均置信度
                    val avgConfidence = landmarks
                        .filter { it.visibility > DetectedPerson.VISIBILITY_THRESHOLD }
                        .map { it.presence }
                        .average()
                        .toFloat()

                    detectedPersons.add(
                        DetectedPerson(
                            personId = index,
                            landmarks = landmarks,
                            boundingBox = boundingBox,
                            confidence = avgConfidence
                        )
                    )
                }
            }

            // 按置信度排序（从高到低）
            detectedPersons.sortedByDescending { it.confidence }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * 释放资源
     */
    fun close() {
        poseLandmarker?.close()
        poseLandmarker = null
        isInitialized = false
    }

    companion object {
        /**
         * 骨骼连接关系（用于绘制骨骼线条）
         */
        val POSE_CONNECTIONS = listOf(
            // 面部
            Pair(LandmarkType.LEFT_EYE, LandmarkType.RIGHT_EYE),
            Pair(LandmarkType.LEFT_EYE, LandmarkType.NOSE),
            Pair(LandmarkType.RIGHT_EYE, LandmarkType.NOSE),
            Pair(LandmarkType.LEFT_EYE, LandmarkType.LEFT_EAR),
            Pair(LandmarkType.RIGHT_EYE, LandmarkType.RIGHT_EAR),
            Pair(LandmarkType.MOUTH_LEFT, LandmarkType.MOUTH_RIGHT),

            // 躯干
            Pair(LandmarkType.LEFT_SHOULDER, LandmarkType.RIGHT_SHOULDER),
            Pair(LandmarkType.LEFT_SHOULDER, LandmarkType.LEFT_HIP),
            Pair(LandmarkType.RIGHT_SHOULDER, LandmarkType.RIGHT_HIP),
            Pair(LandmarkType.LEFT_HIP, LandmarkType.RIGHT_HIP),

            // 左臂
            Pair(LandmarkType.LEFT_SHOULDER, LandmarkType.LEFT_ELBOW),
            Pair(LandmarkType.LEFT_ELBOW, LandmarkType.LEFT_WRIST),
            Pair(LandmarkType.LEFT_WRIST, LandmarkType.LEFT_PINKY),
            Pair(LandmarkType.LEFT_WRIST, LandmarkType.LEFT_INDEX),
            Pair(LandmarkType.LEFT_WRIST, LandmarkType.LEFT_THUMB),
            Pair(LandmarkType.LEFT_PINKY, LandmarkType.LEFT_INDEX),

            // 右臂
            Pair(LandmarkType.RIGHT_SHOULDER, LandmarkType.RIGHT_ELBOW),
            Pair(LandmarkType.RIGHT_ELBOW, LandmarkType.RIGHT_WRIST),
            Pair(LandmarkType.RIGHT_WRIST, LandmarkType.RIGHT_PINKY),
            Pair(LandmarkType.RIGHT_WRIST, LandmarkType.RIGHT_INDEX),
            Pair(LandmarkType.RIGHT_WRIST, LandmarkType.RIGHT_THUMB),
            Pair(LandmarkType.RIGHT_PINKY, LandmarkType.RIGHT_INDEX),

            // 左腿
            Pair(LandmarkType.LEFT_HIP, LandmarkType.LEFT_KNEE),
            Pair(LandmarkType.LEFT_KNEE, LandmarkType.LEFT_ANKLE),
            Pair(LandmarkType.LEFT_ANKLE, LandmarkType.LEFT_HEEL),
            Pair(LandmarkType.LEFT_ANKLE, LandmarkType.LEFT_FOOT_INDEX),
            Pair(LandmarkType.LEFT_HEEL, LandmarkType.LEFT_FOOT_INDEX),

            // 右腿
            Pair(LandmarkType.RIGHT_HIP, LandmarkType.RIGHT_KNEE),
            Pair(LandmarkType.RIGHT_KNEE, LandmarkType.RIGHT_ANKLE),
            Pair(LandmarkType.RIGHT_ANKLE, LandmarkType.RIGHT_HEEL),
            Pair(LandmarkType.RIGHT_ANKLE, LandmarkType.RIGHT_FOOT_INDEX),
            Pair(LandmarkType.RIGHT_HEEL, LandmarkType.RIGHT_FOOT_INDEX)
        )
    }
}

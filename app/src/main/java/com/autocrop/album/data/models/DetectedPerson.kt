package com.autocrop.album.data.models

import android.graphics.PointF
import android.graphics.RectF

/**
 * 检测到的人物数据模型
 */
data class DetectedPerson(
    val personId: Int,
    val landmarks: List<PoseLandmark>,
    val boundingBox: RectF,
    val confidence: Float
) {
    /**
     * 获取所有可见的关键点
     */
    fun getVisibleLandmarks(): List<PoseLandmark> {
        return landmarks.filter { it.visibility > VISIBILITY_THRESHOLD }
    }

    /**
     * 计算人物的中心点
     */
    fun getCenterPoint(): PointF {
        return PointF(
            boundingBox.centerX(),
            boundingBox.centerY()
        )
    }

    companion object {
        const val VISIBILITY_THRESHOLD = 0.5f
        const val MIN_CONFIDENCE = 0.3f
    }
}

/**
 * 姿态关键点
 */
data class PoseLandmark(
    val type: LandmarkType,
    val position: PointF,
    val visibility: Float,
    val presence: Float
)

/**
 * 关键点类型（简化版，基于 MediaPipe Pose）
 */
enum class LandmarkType(val index: Int) {
    NOSE(0),
    LEFT_EYE_INNER(1),
    LEFT_EYE(2),
    LEFT_EYE_OUTER(3),
    RIGHT_EYE_INNER(4),
    RIGHT_EYE(5),
    RIGHT_EYE_OUTER(6),
    LEFT_EAR(7),
    RIGHT_EAR(8),
    MOUTH_LEFT(9),
    MOUTH_RIGHT(10),
    LEFT_SHOULDER(11),
    RIGHT_SHOULDER(12),
    LEFT_ELBOW(13),
    RIGHT_ELBOW(14),
    LEFT_WRIST(15),
    RIGHT_WRIST(16),
    LEFT_PINKY(17),
    RIGHT_PINKY(18),
    LEFT_INDEX(19),
    RIGHT_INDEX(20),
    LEFT_THUMB(21),
    RIGHT_THUMB(22),
    LEFT_HIP(23),
    RIGHT_HIP(24),
    LEFT_KNEE(25),
    RIGHT_KNEE(26),
    LEFT_ANKLE(27),
    RIGHT_ANKLE(28),
    LEFT_HEEL(29),
    RIGHT_HEEL(30),
    LEFT_FOOT_INDEX(31),
    RIGHT_FOOT_INDEX(32);

    companion object {
        fun fromIndex(index: Int): LandmarkType? {
            return values().find { it.index == index }
        }
    }
}

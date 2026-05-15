package com.autocrop.album.data.models

import android.graphics.PointF
import android.graphics.RectF

/**
 * 检测到的人物数据模型
 * 来源：MediaPipe Object Detector（EfficientDet）返回的 person bounding box
 */
data class DetectedPerson(
    val personId: Int,
    val boundingBox: RectF,
    val confidence: Float
) {
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
        const val MIN_CONFIDENCE = 0.3f
    }
}

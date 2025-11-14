package com.autocrop.album.data.models

import android.graphics.RectF

/**
 * 裁剪框数据模型
 */
data class CropBox(
    val id: Int,
    var rect: RectF,
    val personId: Int? = null,  // null 表示手动创建的裁剪框
    var isSelected: Boolean = false,
    val confidence: Float = 1.0f
) {
    /**
     * 获取宽高比
     */
    fun getAspectRatio(): Float {
        val width = rect.width()
        val height = rect.height()
        return if (height > 0) width / height else 1f
    }

    /**
     * 检查宽高比是否在合理范围内（避免细长条）
     * 范围：1:3 到 3:1
     */
    fun isAspectRatioValid(): Boolean {
        val ratio = getAspectRatio()
        return ratio in MIN_ASPECT_RATIO..MAX_ASPECT_RATIO
    }

    /**
     * 复制并调整矩形
     */
    fun copyWithRect(newRect: RectF): CropBox {
        return copy(rect = RectF(newRect))
    }

    companion object {
        const val MIN_ASPECT_RATIO = 1f / 3f  // 1:3
        const val MAX_ASPECT_RATIO = 3f / 1f  // 3:1
        const val MIN_SIZE_DP = 50f  // 最小尺寸（DP）
    }
}

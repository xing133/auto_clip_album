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
    val confidence: Float = 1.0f,
    // Segmentation crops retain the detected non-border area through edits and zoom.
    val contentBounds: RectF? = null
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
     * 复制并调整矩形
     */
    fun copyWithRect(newRect: RectF): CropBox {
        return copy(rect = RectF(newRect), contentBounds = contentBounds?.let { RectF(it) })
    }

    companion object {
        const val MIN_SIZE_DP = 50f  // 最小尺寸（DP）
    }
}

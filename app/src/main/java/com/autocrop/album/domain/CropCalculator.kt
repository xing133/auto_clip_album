package com.autocrop.album.domain

import android.graphics.RectF
import com.autocrop.album.data.models.CropBox
import com.autocrop.album.data.models.DetectedPerson
import kotlin.math.max

/**
 * 裁剪框计算器
 * 基于检测到的人物 bounding box 生成裁剪框
 */
object CropCalculator {

    // ========== 可调参数 ==========

    /**
     * 默认外扩系数：在人物检测框外围统一扩展的比例
     * 0.08 = 上下左右各扩展 检测框长边的 8%
     * 建议范围：0.0 ~ 0.3
     */
    const val DEFAULT_EXPANSION_RATIO = 0.08f

    /**
     * 为检测到的人物生成裁剪框
     * @param persons 检测到的人物列表
     * @param imageWidth 图片宽度
     * @param imageHeight 图片高度
     * @param letterboxResult 黑边检测结果（可选），用于确保裁剪框不包含黑边
     * @param expansionRatio 外扩系数（可选），默认 [DEFAULT_EXPANSION_RATIO]
     * @return 裁剪框列表
     */
    fun generateCropBoxes(
        persons: List<DetectedPerson>,
        imageWidth: Int,
        imageHeight: Int,
        letterboxResult: LetterboxDetector.DetectionResult? = null,
        expansionRatio: Float = DEFAULT_EXPANSION_RATIO,
        maxAspectNumerator: Int
    ): List<CropBox> {
        return persons.mapIndexed { index, person ->
            val rect = calculateCropRect(person, imageWidth, imageHeight, letterboxResult, expansionRatio, maxAspectNumerator)
            CropBox(
                id = index,
                rect = rect,
                personId = person.personId,
                isSelected = index == 0,  // 默认选中第一个
                confidence = person.confidence,
                contentBounds = letterboxResult?.bounds?.let { RectF(it) }
            )
        }
    }

    /**
     * 计算单个人物的裁剪矩形
     */
    private fun calculateCropRect(
        person: DetectedPerson,
        imageWidth: Int,
        imageHeight: Int,
        letterboxResult: LetterboxDetector.DetectionResult? = null,
        expansionRatio: Float = DEFAULT_EXPANSION_RATIO,
        maxAspectNumerator: Int
    ): RectF {
        val rect = RectF(person.boundingBox)

        // 1. 统一外扩：四边各扩展 检测框长边 × expansionRatio
        val padding = max(rect.width(), rect.height()) * expansionRatio
        rect.left -= padding
        rect.top -= padding
        rect.right += padding
        rect.bottom += padding

        val available = letterboxResult?.bounds?.let { RectF(it) }
            ?: RectF(0f, 0f, imageWidth.toFloat(), imageHeight.toFloat())
        return constrainCropAspect(rect, available, maxAspectNumerator)
    }

    /** Shared final constraint for generated, manually edited and viewport-clipped crop boxes. */
    fun constrainCropAspect(rect: RectF, bounds: RectF, maxAspectNumerator: Int): RectF {
        if (bounds.isEmpty) return RectF()
        val result = CropAspectRatio.fit(
            CropAspectRatio.Box(rect.left, rect.top, rect.right, rect.bottom),
            CropAspectRatio.Box(bounds.left, bounds.top, bounds.right, bounds.bottom), maxAspectNumerator)
        return RectF(result.left, result.top, result.right, result.bottom)
    }

    /**
     * 调整裁剪框大小（用户拖动时）
     * @param cropBox 当前裁剪框
     * @param newRect 新的矩形
     * @param imageWidth 图片宽度
     * @param imageHeight 图片高度
     * @return 调整后的裁剪框
     */
    fun adjustCropBox(
        cropBox: CropBox,
        newRect: RectF,
        imageWidth: Int,
        imageHeight: Int,
        maxAspectNumerator: Int
    ): CropBox {
        val adjustedRect = RectF(newRect)

        // 确保最小尺寸
        val minSize = CropBox.MIN_SIZE_DP
        if (adjustedRect.width() < minSize) {
            adjustedRect.right = adjustedRect.left + minSize
        }
        if (adjustedRect.height() < minSize) {
            adjustedRect.bottom = adjustedRect.top + minSize
        }

        val available = RectF(0f, 0f, imageWidth.toFloat(), imageHeight.toFloat())
        if (cropBox.contentBounds?.let { !available.intersect(it) } == true) return cropBox
        return cropBox.copyWithRect(constrainCropAspect(adjustedRect, available, maxAspectNumerator))
    }

    /**
     * 创建手动裁剪框（默认在图片中心）
     */
    fun createManualCropBox(
        id: Int,
        imageWidth: Int,
        imageHeight: Int,
        maxAspectNumerator: Int,
        contentBounds: RectF? = null
    ): CropBox {
        // 创建一个居中的裁剪框，大小为图片的50%
        val width = imageWidth * 0.5f
        val height = imageHeight * 0.5f
        val left = (imageWidth - width) / 2
        val top = (imageHeight - height) / 2

        val rect = RectF(left, top, left + width, top + height)

        return CropBox(
            id = id,
            rect = constrainCropAspect(rect, contentBounds ?: RectF(0f, 0f, imageWidth.toFloat(), imageHeight.toFloat()), maxAspectNumerator),
            personId = null,  // 手动创建
            isSelected = true,
            confidence = 1.0f,
            contentBounds = contentBounds?.let { RectF(it) }
        )
    }
}

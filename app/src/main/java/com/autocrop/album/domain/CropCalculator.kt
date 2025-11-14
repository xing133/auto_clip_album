package com.autocrop.album.domain

import android.graphics.RectF
import com.autocrop.album.data.models.CropBox
import com.autocrop.album.data.models.DetectedPerson
import kotlin.math.max
import kotlin.math.min

/**
 * 裁剪框计算器
 * 基于检测到的人物姿态生成裁剪框
 */
object CropCalculator {

    private const val PADDING_RATIO = 0.2f  // 边距比例 20%
    private const val MIN_ASPECT_RATIO = 1f / 3f  // 最小宽高比 1:3
    private const val MAX_ASPECT_RATIO = 3f / 1f  // 最大宽高比 3:1

    /**
     * 为检测到的人物生成裁剪框
     * @param persons 检测到的人物列表
     * @param imageWidth 图片宽度
     * @param imageHeight 图片高度
     * @return 裁剪框列表
     */
    fun generateCropBoxes(
        persons: List<DetectedPerson>,
        imageWidth: Int,
        imageHeight: Int
    ): List<CropBox> {
        return persons.mapIndexed { index, person ->
            val rect = calculateCropRect(person, imageWidth, imageHeight)
            CropBox(
                id = index,
                rect = rect,
                personId = person.personId,
                isSelected = index == 0,  // 默认选中第一个
                confidence = person.confidence
            )
        }
    }

    /**
     * 计算单个人物的裁剪矩形
     */
    private fun calculateCropRect(
        person: DetectedPerson,
        imageWidth: Int,
        imageHeight: Int
    ): RectF {
        var rect = RectF(person.boundingBox)

        // 1. 添加边距
        val paddingX = rect.width() * PADDING_RATIO
        val paddingY = rect.height() * PADDING_RATIO
        rect.left -= paddingX
        rect.right += paddingX
        rect.top -= paddingY
        rect.bottom += paddingY

        // 2. 检查并修正宽高比
        rect = adjustAspectRatio(rect)

        // 3. 确保不超出图片边界
        rect = constrainToImageBounds(rect, imageWidth, imageHeight)

        return rect
    }

    /**
     * 调整宽高比，避免细长条
     */
    private fun adjustAspectRatio(rect: RectF): RectF {
        val width = rect.width()
        val height = rect.height()
        val ratio = width / height

        return when {
            ratio < MIN_ASPECT_RATIO -> {
                // 太窄，扩展宽度
                val targetWidth = height * MIN_ASPECT_RATIO
                val widthDiff = targetWidth - width
                RectF(
                    rect.left - widthDiff / 2,
                    rect.top,
                    rect.right + widthDiff / 2,
                    rect.bottom
                )
            }
            ratio > MAX_ASPECT_RATIO -> {
                // 太宽，扩展高度
                val targetHeight = width / MAX_ASPECT_RATIO
                val heightDiff = targetHeight - height
                RectF(
                    rect.left,
                    rect.top - heightDiff / 2,
                    rect.right,
                    rect.bottom + heightDiff / 2
                )
            }
            else -> rect
        }
    }

    /**
     * 约束到图片边界内
     */
    private fun constrainToImageBounds(
        rect: RectF,
        imageWidth: Int,
        imageHeight: Int
    ): RectF {
        val result = RectF(rect)

        // 如果裁剪框超出边界，进行平移或缩放
        if (result.width() > imageWidth || result.height() > imageHeight) {
            // 裁剪框比图片还大，缩放到图片大小
            val scaleX = if (result.width() > imageWidth) imageWidth / result.width() else 1f
            val scaleY = if (result.height() > imageHeight) imageHeight / result.height() else 1f
            val scale = min(scaleX, scaleY)

            val centerX = result.centerX()
            val centerY = result.centerY()
            val newWidth = result.width() * scale
            val newHeight = result.height() * scale

            result.set(
                centerX - newWidth / 2,
                centerY - newHeight / 2,
                centerX + newWidth / 2,
                centerY + newHeight / 2
            )
        }

        // 平移到边界内
        if (result.left < 0) {
            result.offset(-result.left, 0f)
        }
        if (result.top < 0) {
            result.offset(0f, -result.top)
        }
        if (result.right > imageWidth) {
            result.offset(imageWidth - result.right, 0f)
        }
        if (result.bottom > imageHeight) {
            result.offset(0f, imageHeight - result.bottom)
        }

        // 最终裁剪到边界
        result.left = max(0f, result.left)
        result.top = max(0f, result.top)
        result.right = min(imageWidth.toFloat(), result.right)
        result.bottom = min(imageHeight.toFloat(), result.bottom)

        return result
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
        imageHeight: Int
    ): CropBox {
        var adjustedRect = RectF(newRect)

        // 确保最小尺寸
        val minSize = CropBox.MIN_SIZE_DP
        if (adjustedRect.width() < minSize) {
            adjustedRect.right = adjustedRect.left + minSize
        }
        if (adjustedRect.height() < minSize) {
            adjustedRect.bottom = adjustedRect.top + minSize
        }

        // 约束到图片边界
        adjustedRect = constrainToImageBounds(adjustedRect, imageWidth, imageHeight)

        return cropBox.copyWithRect(adjustedRect)
    }

    /**
     * 创建手动裁剪框（默认在图片中心）
     */
    fun createManualCropBox(
        id: Int,
        imageWidth: Int,
        imageHeight: Int
    ): CropBox {
        // 创建一个居中的裁剪框，大小为图片的50%
        val width = imageWidth * 0.5f
        val height = imageHeight * 0.5f
        val left = (imageWidth - width) / 2
        val top = (imageHeight - height) / 2

        val rect = RectF(left, top, left + width, top + height)

        return CropBox(
            id = id,
            rect = rect,
            personId = null,  // 手动创建
            isSelected = true,
            confidence = 1.0f
        )
    }
}

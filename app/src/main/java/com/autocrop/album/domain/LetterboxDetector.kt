package com.autocrop.album.domain

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 黑边检测器
 * 用于检测并去除视频截图中的 letterbox（上下黑边）或 pillarbox（左右黑边）
 *
 * 算法：基于缩放后的亮度均值 + 方差判定
 * 1. 将图片缩放到较小尺寸，避免大图遍历开销
 * 2. 计算每行/列的亮度均值与方差，判定低亮度且低方差区域
 * 3. 允许少量亮色 UI 元素穿插，避免因控制条/弹幕等导致误判
 * 4. 将检测到的裁剪比例映射回原图尺寸
 */
object LetterboxDetector {

    private const val TAG = "LetterboxDetector"
    private val DEBUG_LOGS_ENABLED = true
    private val DEBUG_OVERLAY_ENABLED = true

    // ========== 可调参数 ==========

    /**
     * 暗区域亮度比例阈值
     * 如果某行的亮度 < 图片平均亮度 * 此比例，认为是暗区域
     * 建议范围：0.4 ~ 0.7
     */
    private const val DARK_RATIO_THRESHOLD = 0.65f

    /**
     * 最大裁剪比例
     * 单边最多裁剪掉图片的百分比（防止过度裁剪）
     * 0.4 = 最多裁剪 40%
     */
    private const val MAX_CROP_RATIO = 0.4f

    /**
     * 最小裁剪阈值
     * 只有裁剪掉的部分超过此比例，才执行裁剪
     * 0.03 = 至少裁剪 3%
     */
    private const val MIN_CROP_THRESHOLD = 0.03f

    /**
     * 分析时的最大尺寸，超过则缩放以降低计算量
     */
    private const val ANALYSIS_MAX_SIZE = 640

    /**
     * 判定“纯黑边”所允许的亮度方差，越小对文字/图标越不敏感
     */
    private const val VARIANCE_THRESHOLD = 35f

    /**
     * 最小需要连续检测到的黑边高度/宽度占比
     */
    private const val MIN_RUN_RATIO = 0.02f

    /**
     * 允许的亮行/亮列间隙，用于容忍黑边里偶尔出现的控制按钮、弹幕输入框等
     */
    private const val GAP_TOLERANCE = 6

    /**
     * 单个像素被视为“黑色”的亮度阈值（0~255）
     */
    private const val DARK_PIXEL_VALUE = 35f

    /**
     * 某行/列中黑色像素占比超过此值，则认为该行/列属于黑边
     */
    private const val MIN_DARK_FRACTION = 0.78f

    /**
     * 检测并裁剪黑边
     * @param bitmap 原始图片
     * @return 裁剪后的图片，如果没有检测到黑边则返回原图
     */
    fun detectAndCrop(
        bitmap: Bitmap,
        enableDebugOverlay: Boolean = DEBUG_OVERLAY_ENABLED
    ): Bitmap {
        val result = detectContentBounds(bitmap)
        val bounds = result.bounds

        // 检查裁剪区域是否有效
        val croppedWidth = bounds.width()
        val croppedHeight = bounds.height()

        // 计算裁剪比例
        val cropRatioTop = result.trimmedRatios.top
        val cropRatioBottom = result.trimmedRatios.bottom
        val cropRatioLeft = result.trimmedRatios.left
        val cropRatioRight = result.trimmedRatios.right

        // 只有裁剪掉的部分足够多，且置信度足够，才执行裁剪
        val hasMeaningfulCrop = result.confidence >= MIN_CROP_THRESHOLD ||
            cropRatioTop >= MIN_CROP_THRESHOLD ||
            cropRatioBottom >= MIN_CROP_THRESHOLD ||
            cropRatioLeft >= MIN_CROP_THRESHOLD ||
            cropRatioRight >= MIN_CROP_THRESHOLD

        if (DEBUG_LOGS_ENABLED) {
            Log.d(
                TAG,
                "detect result: bounds=$bounds ratios=$cropRatioTop/$cropRatioBottom/$cropRatioLeft/$cropRatioRight " +
                    "confidence=${result.confidence} image=${bitmap.width}x${bitmap.height}"
            )
        }

        // 如果裁剪后的区域太小，或者没有明显变化，就返回原图
        if (!hasMeaningfulCrop ||
            croppedWidth < bitmap.width * 0.5f ||
            croppedHeight < bitmap.height * 0.5f) {
            return bitmap
        }

        var cropSource = bitmap
        var overlayBitmap: Bitmap? = null
        if (enableDebugOverlay && (cropRatioTop > 0f || cropRatioBottom > 0f ||
                cropRatioLeft > 0f || cropRatioRight > 0f)) {
            overlayBitmap = highlightCropArea(bitmap, bounds)
            cropSource = overlayBitmap ?: bitmap
        }

        // 执行裁剪
        val cropped = try {
            Bitmap.createBitmap(
                cropSource,
                bounds.left,
                bounds.top,
                croppedWidth,
                croppedHeight
            )
        } catch (e: Exception) {
            e.printStackTrace()
            overlayBitmap?.takeIf { it != bitmap }?.recycle()
            return bitmap  // 裁剪失败返回原图
        }

        if (overlayBitmap != null && overlayBitmap != bitmap) {
            overlayBitmap.recycle()
        }

        return cropped
    }

    /**
     * 检测内容边界（不包含黑边的区域）
     */
    private fun detectContentBounds(bitmap: Bitmap): DetectionResult {
        val analysis = prepareAnalysisBitmap(bitmap)
        val rowStats = calculateRowStats(analysis.bitmap)
        val colStats = calculateColumnStats(analysis.bitmap)

        val avgRowBrightness = rowStats.sumOf { it.mean.toDouble() }.toFloat() / rowStats.size
        val avgColBrightness = colStats.sumOf { it.mean.toDouble() }.toFloat() / colStats.size

        val trimTop = detectTrim(rowStats, avgRowBrightness, true)
        val trimBottom = detectTrim(rowStats, avgRowBrightness, false)
        val trimLeft = detectTrim(colStats, avgColBrightness, true)
        val trimRight = detectTrim(colStats, avgColBrightness, false)

        val scaledRect = Rect(
            trimLeft,
            trimTop,
            analysis.bitmap.width - trimRight,
            analysis.bitmap.height - trimBottom
        )

        val bounds = Rect(
            (scaledRect.left * analysis.scaleX).roundToInt().coerceIn(0, bitmap.width),
            (scaledRect.top * analysis.scaleY).roundToInt().coerceIn(0, bitmap.height),
            (scaledRect.right * analysis.scaleX).roundToInt().coerceIn(0, bitmap.width),
            (scaledRect.bottom * analysis.scaleY).roundToInt().coerceIn(0, bitmap.height)
        )

        if (analysis.shouldRecycle) {
            analysis.bitmap.recycle()
        }

        val trimmedRatios = TrimmedRatios(
            top = bounds.top.toFloat() / bitmap.height,
            bottom = (bitmap.height - bounds.bottom).toFloat() / bitmap.height,
            left = bounds.left.toFloat() / bitmap.width,
            right = (bitmap.width - bounds.right).toFloat() / bitmap.width
        )

        val confidence = maxOf(
            trimmedRatios.top,
            trimmedRatios.bottom,
            trimmedRatios.left,
            trimmedRatios.right
        )

        return DetectionResult(bounds, trimmedRatios, confidence)
    }

    private data class StripStats(
        val mean: Float,
        val variance: Float,
        val darkFraction: Float
    )

    private data class AnalysisBitmap(
        val bitmap: Bitmap,
        val scaleX: Float,
        val scaleY: Float,
        val shouldRecycle: Boolean
    )

    private data class DetectionResult(
        val bounds: Rect,
        val trimmedRatios: TrimmedRatios,
        val confidence: Float
    )

    private data class TrimmedRatios(
        val top: Float,
        val bottom: Float,
        val left: Float,
        val right: Float
    )

    private fun prepareAnalysisBitmap(source: Bitmap): AnalysisBitmap {
        val maxEdge = max(source.width, source.height)
        if (maxEdge <= ANALYSIS_MAX_SIZE) {
            return AnalysisBitmap(source, 1f, 1f, false)
        }

        val scale = ANALYSIS_MAX_SIZE.toFloat() / maxEdge
        val width = max(1, (source.width * scale).roundToInt())
        val height = max(1, (source.height * scale).roundToInt())
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        val scaleX = source.width.toFloat() / scaled.width
        val scaleY = source.height.toFloat() / scaled.height
        return AnalysisBitmap(scaled, scaleX, scaleY, true)
    }

    private fun calculateRowStats(bitmap: Bitmap): Array<StripStats> {
        val stats = Array(bitmap.height) { StripStats(0f, 0f, 0f) }
        val pixels = IntArray(bitmap.width)

        for (y in 0 until bitmap.height) {
            bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
            var sum = 0f
            var sumSq = 0f
            var darkCount = 0
            for (pixel in pixels) {
                val luma = rgbToLuma(pixel)
                sum += luma
                sumSq += luma * luma
                if (luma <= DARK_PIXEL_VALUE) {
                    darkCount++
                }
            }

            val mean = sum / bitmap.width
            val variance = (sumSq / bitmap.width) - (mean * mean)
            val darkFraction = darkCount.toFloat() / bitmap.width
            stats[y] = StripStats(mean, max(0f, variance), darkFraction)
        }

        return stats
    }

    private fun calculateColumnStats(bitmap: Bitmap): Array<StripStats> {
        val stats = Array(bitmap.width) { StripStats(0f, 0f, 0f) }

        for (x in 0 until bitmap.width) {
            var sum = 0f
            var sumSq = 0f
            var darkCount = 0
            for (y in 0 until bitmap.height) {
                val pixel = bitmap.getPixel(x, y)
                val luma = rgbToLuma(pixel)
                sum += luma
                sumSq += luma * luma
                if (luma <= DARK_PIXEL_VALUE) {
                    darkCount++
                }
            }

            val mean = sum / bitmap.height
            val variance = (sumSq / bitmap.height) - (mean * mean)
            val darkFraction = darkCount.toFloat() / bitmap.height
            stats[x] = StripStats(mean, max(0f, variance), darkFraction)
        }

        return stats
    }

    private fun rgbToLuma(pixel: Int): Float {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    private fun detectTrim(
        stats: Array<StripStats>,
        avgBrightness: Float,
        fromStart: Boolean
    ): Int {
        if (stats.isEmpty()) return 0

        val length = stats.size
        val maxCrop = (length * MAX_CROP_RATIO).roundToInt().coerceAtLeast(0)
        val minRun = max((length * MIN_RUN_RATIO).roundToInt(), 4)
        val darkThreshold = avgBrightness * DARK_RATIO_THRESHOLD

        var darkCount = 0
        var trailingLight = 0

        for (step in 0 until maxCrop) {
            val index = if (fromStart) step else length - 1 - step
            if (index !in stats.indices) break

            val strip = stats[index]
            val isDark = (strip.mean <= darkThreshold && strip.variance <= VARIANCE_THRESHOLD) ||
                strip.darkFraction >= MIN_DARK_FRACTION

            if (isDark) {
                darkCount++
                trailingLight = 0
            } else {
                if (darkCount == 0) break
                trailingLight++
                if (trailingLight > GAP_TOLERANCE) break
            }
        }

        val effectiveDark = (darkCount - trailingLight).coerceAtLeast(0)
        if (effectiveDark < minRun) {
            return 0
        }

        return effectiveDark
    }

    private fun highlightCropArea(source: Bitmap, bounds: Rect): Bitmap? {
        val target = if (source.isMutable) {
            source
        } else {
            try {
                source.copy(Bitmap.Config.ARGB_8888, true)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to copy bitmap for debug overlay", e)
                return null
            }
        }

        val canvas = Canvas(target)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 255, 64, 129)
            style = Paint.Style.STROKE
            strokeWidth = max(4f, min(target.width, target.height) * 0.02f)
        }
        val inset = paint.strokeWidth / 2f
        val rectF = RectF(bounds)
        rectF.inset(inset, inset)
        canvas.drawRect(rectF, paint)

        if (DEBUG_LOGS_ENABLED) {
            Log.d(TAG, "Overlay drawn for bounds=$bounds stroke=${paint.strokeWidth}")
        }

        return target
    }
}

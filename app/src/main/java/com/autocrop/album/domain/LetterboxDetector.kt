package com.autocrop.album.domain

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 黑边检测器 - 改进版
 *
 * 结合两种检测策略：
 * 1. 基于亮度统计（原有逻辑）- 适用于纯黑边
 * 2. 基于边缘检测（新逻辑）- 适用于有UI元素的黑边
 *
 * 新算法思路：
 * - 黑边和有效内容之间通常有明显的分界线
 * - 使用 Sobel 算子检测边缘强度
 * - 从图片四边向内扫描，找到第一条明显的边界线
 */
object LetterboxDetector {

    private const val TAG = "LetterboxDetector"
    private val DEBUG_LOGS_ENABLED = true

    /**
     * 详细调试模式 - 输出所有中间计算数据
     * 用于调试特定图片的检测问题
     */
    var VERBOSE_DEBUG_MODE = false

    // ========== 亮度检测参数（原有逻辑）==========
    private const val DARK_RATIO_THRESHOLD = 0.65f
    private const val MAX_CROP_RATIO = 0.4f
    private const val MIN_CROP_THRESHOLD = 0.03f
    private const val ANALYSIS_MAX_SIZE = 640
    private const val VARIANCE_THRESHOLD = 35f
    private const val MIN_RUN_RATIO = 0.02f
    private const val GAP_TOLERANCE = 6
    private const val DARK_PIXEL_VALUE = 35f
    private const val MIN_DARK_FRACTION = 0.78f

    // ========== 边缘检测参数（新逻辑）==========

    /**
     * 搜索边界的最大范围（占图片尺寸的比例）
     */
    private const val EDGE_SEARCH_MAX_RATIO = 0.45f

    /**
     * Sobel 算子检测的边缘强度最小值（降低以检测更多边缘）
     */
    private const val MIN_EDGE_MAGNITUDE = 30f

    /**
     * 判定为分界线所需的边缘像素覆盖率
     * 一行中至少有这个比例的像素是边缘，才认为是分界线
     */
    private const val EDGE_COVERAGE_THRESHOLD = 0.5f

    /**
     * 验证黑边区域的平均亮度阈值
     * 真正的黑边区域平均亮度应该低于此值（0-255）
     * 降低此值可以更严格地验证，避免把暗色内容误判为黑边
     */
    private const val LETTERBOX_BRIGHTNESS_THRESHOLD = 25f

    /**
     * 单个像素被判定为边缘的最小梯度值
     */
    private const val EDGE_GRADIENT_THRESHOLD = 40f

    /**
     * 检测结果数据类
     */
    data class DetectionResult(
        val bounds: Rect,              // 内容区域边界
        val trimmedRatios: TrimmedRatios,  // 裁剪比例
        val confidence: Float,         // 置信度
        val letterboxRegions: List<Rect> = emptyList()  // 检测到的黑边区域（用于debug可视化）
    )

    data class TrimmedRatios(
        val top: Float,
        val bottom: Float,
        val left: Float,
        val right: Float
    )

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

    /**
     * 仅检测黑边，返回检测结果（不执行裁剪）
     */
    fun detect(bitmap: Bitmap): DetectionResult {
        return detectContentBounds(bitmap)
    }

    /**
     * 检测并裁剪黑边
     */
    fun detectAndCrop(bitmap: Bitmap): Bitmap {
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
                "detect result: bounds=$bounds ratios=T:$cropRatioTop B:$cropRatioBottom L:$cropRatioLeft R:$cropRatioRight " +
                    "confidence=${result.confidence} image=${bitmap.width}x${bitmap.height} letterboxRegions=${result.letterboxRegions.size}"
            )
        }

        // 如果裁剪后的区域太小，或者没有明显变化，就返回原图
        if (!hasMeaningfulCrop ||
            croppedWidth < bitmap.width * 0.5f ||
            croppedHeight < bitmap.height * 0.5f) {
            return bitmap
        }

        // 执行裁剪
        val cropped = try {
            Bitmap.createBitmap(
                bitmap,
                bounds.left,
                bounds.top,
                croppedWidth,
                croppedHeight
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return bitmap  // 裁剪失败返回原图
        }

        return cropped
    }

    /**
     * 检测内容边界（结合亮度统计和边缘检测）
     */
    private fun detectContentBounds(bitmap: Bitmap): DetectionResult {
        // 方法1：基于亮度统计（原有逻辑）
        val brightnessResult = detectByBrightness(bitmap)

        // 方法2：基于边缘检测（新逻辑）
        val edgeResult = detectByEdges(bitmap)

        // 结合两种方法：对于每个方向，取裁剪更多的结果
        val combinedTop = max(brightnessResult.trimmedRatios.top, edgeResult.trimmedRatios.top)
        val combinedBottom = max(brightnessResult.trimmedRatios.bottom, edgeResult.trimmedRatios.bottom)
        val combinedLeft = max(brightnessResult.trimmedRatios.left, edgeResult.trimmedRatios.left)
        val combinedRight = max(brightnessResult.trimmedRatios.right, edgeResult.trimmedRatios.right)

        // 计算最终边界
        val finalBounds = Rect(
            (bitmap.width * combinedLeft).roundToInt(),
            (bitmap.height * combinedTop).roundToInt(),
            (bitmap.width * (1f - combinedRight)).roundToInt(),
            (bitmap.height * (1f - combinedBottom)).roundToInt()
        )

        val finalRatios = TrimmedRatios(
            top = combinedTop,
            bottom = combinedBottom,
            left = combinedLeft,
            right = combinedRight
        )

        val confidence = maxOf(combinedTop, combinedBottom, combinedLeft, combinedRight)

        // 生成黑边区域用于debug可视化
        val letterboxRegions = mutableListOf<Rect>()
        if (combinedTop > MIN_CROP_THRESHOLD) {
            letterboxRegions.add(Rect(0, 0, bitmap.width, (bitmap.height * combinedTop).roundToInt()))
        }
        if (combinedBottom > MIN_CROP_THRESHOLD) {
            val bottomStart = (bitmap.height * (1f - combinedBottom)).roundToInt()
            letterboxRegions.add(Rect(0, bottomStart, bitmap.width, bitmap.height))
        }
        if (combinedLeft > MIN_CROP_THRESHOLD) {
            letterboxRegions.add(Rect(0, 0, (bitmap.width * combinedLeft).roundToInt(), bitmap.height))
        }
        if (combinedRight > MIN_CROP_THRESHOLD) {
            val rightStart = (bitmap.width * (1f - combinedRight)).roundToInt()
            letterboxRegions.add(Rect(rightStart, 0, bitmap.width, bitmap.height))
        }

        if (DEBUG_LOGS_ENABLED) {
            Log.d(TAG, "Brightness: T:${brightnessResult.trimmedRatios.top} B:${brightnessResult.trimmedRatios.bottom} L:${brightnessResult.trimmedRatios.left} R:${brightnessResult.trimmedRatios.right}")
            Log.d(TAG, "Edge: T:${edgeResult.trimmedRatios.top} B:${edgeResult.trimmedRatios.bottom} L:${edgeResult.trimmedRatios.left} R:${edgeResult.trimmedRatios.right}")
            Log.d(TAG, "Combined: T:$combinedTop B:$combinedBottom L:$combinedLeft R:$combinedRight")
        }

        return DetectionResult(finalBounds, finalRatios, confidence, letterboxRegions)
    }

    /**
     * 基于亮度统计的检测（原有逻辑）
     */
    private fun detectByBrightness(bitmap: Bitmap): DetectionResult {
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

    /**
     * 基于边缘检测的新算法（使用 Sobel 算子）
     */
    private fun detectByEdges(bitmap: Bitmap): DetectionResult {
        try {
            // 准备分析用的图片（缩小以提高性能）
            val analysis = prepareAnalysisBitmap(bitmap)
            val analysisBitmap = analysis.bitmap

            // 转换为灰度图并计算边缘
            val edgeMap = computeEdgeMap(analysisBitmap)

            val height = edgeMap.size
            val width = edgeMap[0].size

            // 检测四个方向的边界（返回的是从中心开始的步数）
            val trimTopStep = detectEdgeBoundary(edgeMap, analysisBitmap, Direction.TOP)
            val trimBottomStep = detectEdgeBoundary(edgeMap, analysisBitmap, Direction.BOTTOM)
            val trimLeftStep = detectEdgeBoundary(edgeMap, analysisBitmap, Direction.LEFT)
            val trimRightStep = detectEdgeBoundary(edgeMap, analysisBitmap, Direction.RIGHT)

            // 将步数转换为实际的边缘位置（像素数）
            val centerY = height / 2
            val centerX = width / 2

            // 对于TOP：从中心向上搜索，step表示距离中心的距离，实际边缘在 (centerY - step)
            // 所以需要裁剪的是从0到(centerY - step)的部分，即 (centerY - step) 个像素
            val trimTopPixels = if (trimTopStep > 0) centerY - trimTopStep else 0

            // 对于BOTTOM：从中心向下搜索，实际边缘在 (centerY + step)
            // 需要裁剪的是从(centerY + step)到height的部分，即 (height - (centerY + step)) 个像素
            val trimBottomPixels = if (trimBottomStep > 0) height - (centerY + trimBottomStep) else 0

            val trimLeftPixels = if (trimLeftStep > 0) centerX - trimLeftStep else 0
            val trimRightPixels = if (trimRightStep > 0) width - (centerX + trimRightStep) else 0

            if (analysis.shouldRecycle) {
                analysisBitmap.recycle()
            }

            if (DEBUG_LOGS_ENABLED) {
                Log.d(TAG, "detectByEdges: steps T:$trimTopStep B:$trimBottomStep L:$trimLeftStep R:$trimRightStep")
                Log.d(TAG, "detectByEdges: pixels T:$trimTopPixels B:$trimBottomPixels L:$trimLeftPixels R:$trimRightPixels")
            }

            // 映射回原图尺寸
            val trimmedRatios = TrimmedRatios(
                top = trimTopPixels.toFloat() / height,
                bottom = trimBottomPixels.toFloat() / height,
                left = trimLeftPixels.toFloat() / width,
                right = trimRightPixels.toFloat() / width
            )

            val bounds = Rect(
                (bitmap.width * trimmedRatios.left).roundToInt(),
                (bitmap.height * trimmedRatios.top).roundToInt(),
                (bitmap.width * (1f - trimmedRatios.right)).roundToInt(),
                (bitmap.height * (1f - trimmedRatios.bottom)).roundToInt()
            )

            val confidence = maxOf(
                trimmedRatios.top,
                trimmedRatios.bottom,
                trimmedRatios.left,
                trimmedRatios.right
            )

            return DetectionResult(bounds, trimmedRatios, confidence)

        } catch (e: Exception) {
            Log.e(TAG, "Edge detection failed, falling back to brightness detection", e)
            // 如果边缘检测失败，返回空结果
            return DetectionResult(
                Rect(0, 0, bitmap.width, bitmap.height),
                TrimmedRatios(0f, 0f, 0f, 0f),
                0f
            )
        }
    }

    private enum class Direction {
        TOP, BOTTOM, LEFT, RIGHT
    }

    /**
     * 使用 Sobel 算子计算边缘图
     */
    private fun computeEdgeMap(bitmap: Bitmap): Array<FloatArray> {
        val width = bitmap.width
        val height = bitmap.height
        val edgeMap = Array(height) { FloatArray(width) }

        // Sobel 算子核
        val sobelX = arrayOf(
            intArrayOf(-1, 0, 1),
            intArrayOf(-2, 0, 2),
            intArrayOf(-1, 0, 1)
        )
        val sobelY = arrayOf(
            intArrayOf(-1, -2, -1),
            intArrayOf(0, 0, 0),
            intArrayOf(1, 2, 1)
        )

        var totalEdgePixels = 0
        var maxMagnitude = 0f

        // 遍历图片计算边缘强度
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var gx = 0f
                var gy = 0f

                // 应用 Sobel 算子
                for (ky in -1..1) {
                    for (kx in -1..1) {
                        val pixel = bitmap.getPixel(x + kx, y + ky)
                        val luma = rgbToLuma(pixel)
                        gx += luma * sobelX[ky + 1][kx + 1]
                        gy += luma * sobelY[ky + 1][kx + 1]
                    }
                }

                // 计算梯度幅值
                val magnitude = sqrt(gx * gx + gy * gy)
                edgeMap[y][x] = magnitude

                if (magnitude > MIN_EDGE_MAGNITUDE) {
                    totalEdgePixels++
                    if (magnitude > maxMagnitude) {
                        maxMagnitude = magnitude
                    }
                }
            }
        }

        if (DEBUG_LOGS_ENABLED) {
            val totalPixels = (width - 2) * (height - 2)
            val edgeRatio = totalEdgePixels.toFloat() / totalPixels
            Log.d(TAG, "computeEdgeMap: size=${width}x${height}, edgePixels=$totalEdgePixels/${totalPixels} (${(edgeRatio * 100).roundToInt()}%), maxMag=$maxMagnitude")
        }

        return edgeMap
    }

    /**
     * 检测某个方向的边界（新版：从中心向外搜索，忽略中心20%区域，验证亮度）
     */
    private fun detectEdgeBoundary(edgeMap: Array<FloatArray>, bitmap: Bitmap, direction: Direction): Int {
        val height = edgeMap.size
        val width = edgeMap[0].size

        // 中心安全区域：占图片的20%（单边10%）
        val centerSafeRatio = 0.1f

        // 最大搜索范围：从中心到边缘（50%）
        val maxSearchRatio = 0.5f

        val (centerPos, skipSteps, maxSearchSteps) = when (direction) {
            Direction.TOP -> Triple(
                height / 2,
                (height * centerSafeRatio).roundToInt(),
                (height * maxSearchRatio).roundToInt()
            )
            Direction.BOTTOM -> Triple(
                height / 2,
                (height * centerSafeRatio).roundToInt(),
                (height * maxSearchRatio).roundToInt()
            )
            Direction.LEFT -> Triple(
                width / 2,
                (width * centerSafeRatio).roundToInt(),
                (width * maxSearchRatio).roundToInt()
            )
            Direction.RIGHT -> Triple(
                width / 2,
                (width * centerSafeRatio).roundToInt(),
                (width * maxSearchRatio).roundToInt()
            )
        }

        var bestBoundary = 0
        var bestScore = 0f

        // 用于详细调试的数据
        val coverageData = mutableListOf<Pair<Int, Float>>()

        if (VERBOSE_DEBUG_MODE) {
            Log.d(TAG, "detectEdgeBoundary: $direction centerPos=$centerPos, skipSteps=$skipSteps, maxSearchSteps=$maxSearchSteps")
        }

        // 从中心向外扫描，跳过中心安全区域
        for (step in 0 until maxSearchSteps) {
            // 跳过中心安全区域
            if (step < skipSteps) {
                if (VERBOSE_DEBUG_MODE) {
                    coverageData.add(Pair(step, -1f)) // -1 表示跳过
                }
                continue
            }

            // 计算实际扫描位置
            val actualPos = when (direction) {
                Direction.TOP -> centerPos - step
                Direction.BOTTOM -> centerPos + step
                Direction.LEFT -> centerPos - step
                Direction.RIGHT -> centerPos + step
            }

            // 检查边界
            val coverage = when (direction) {
                Direction.TOP, Direction.BOTTOM -> {
                    if (actualPos < 0 || actualPos >= height) 0f
                    else calculateHorizontalEdgeCoverage(edgeMap, actualPos)
                }
                Direction.LEFT, Direction.RIGHT -> {
                    if (actualPos < 0 || actualPos >= width) 0f
                    else calculateVerticalEdgeCoverage(edgeMap, actualPos)
                }
            }

            if (VERBOSE_DEBUG_MODE) {
                coverageData.add(Pair(step, coverage))
            }

            // 如果这一行的边缘覆盖率超过阈值，验证外侧区域的亮度
            if (coverage >= EDGE_COVERAGE_THRESHOLD) {
                // 验证外侧区域是否足够暗（真正的黑边）
                val isValidLetterbox = verifyLetterboxBrightness(bitmap, direction, actualPos, width, height)

                if (isValidLetterbox) {
                    if (DEBUG_LOGS_ENABLED) {
                        Log.d(TAG, "detectEdgeBoundary: $direction at step=$step (actualPos=$actualPos), coverage=$coverage, brightness verified")
                    }

                    if (VERBOSE_DEBUG_MODE) {
                        logCoverageData(direction, coverageData, step)
                    }

                    return step
                } else if (VERBOSE_DEBUG_MODE) {
                    Log.d(TAG, "detectEdgeBoundary: $direction at step=$step rejected (too bright for letterbox)")
                }
            }

            // 同时记录得分最高的位置（即使没超过阈值）
            if (coverage > bestScore) {
                bestScore = coverage
                bestBoundary = step
            }
        }

        // 如果没找到明显的分界线，但有一个得分较高的位置（>30%），验证亮度后返回
        if (bestScore > 0.3f) {
            val actualPos = when (direction) {
                Direction.TOP -> centerPos - bestBoundary
                Direction.BOTTOM -> centerPos + bestBoundary
                Direction.LEFT -> centerPos - bestBoundary
                Direction.RIGHT -> centerPos + bestBoundary
            }

            val isValidLetterbox = verifyLetterboxBrightness(bitmap, direction, actualPos, width, height)

            if (isValidLetterbox) {
                if (DEBUG_LOGS_ENABLED) {
                    Log.d(TAG, "detectEdgeBoundary: $direction using best score at $bestBoundary, coverage=$bestScore, brightness verified")
                }

                if (VERBOSE_DEBUG_MODE) {
                    logCoverageData(direction, coverageData, bestBoundary)
                }

                return bestBoundary
            } else if (DEBUG_LOGS_ENABLED) {
                Log.d(TAG, "detectEdgeBoundary: $direction best score at $bestBoundary rejected (too bright)")
            }
        }

        if (VERBOSE_DEBUG_MODE) {
            logCoverageData(direction, coverageData, 0)
        }

        return 0
    }

    /**
     * 验证检测到的区域外侧是否真的是黑边（足够暗）
     */
    private fun verifyLetterboxBrightness(
        bitmap: Bitmap,
        direction: Direction,
        boundaryPos: Int,
        width: Int,
        height: Int
    ): Boolean {
        // 定义要采样的区域（边界外侧的一小块区域）
        val sampleSize = 20  // 采样区域大小

        data class SampleRegion(val startX: Int, val endX: Int, val startY: Int, val endY: Int)

        val region = when (direction) {
            Direction.TOP -> {
                // 检查边界上方的区域
                val y1 = maxOf(0, boundaryPos - sampleSize)
                val y2 = maxOf(0, boundaryPos)
                SampleRegion(0, width, y1, y2)
            }
            Direction.BOTTOM -> {
                // 检查边界下方的区域
                val y1 = minOf(height, boundaryPos)
                val y2 = minOf(height, boundaryPos + sampleSize)
                SampleRegion(0, width, y1, y2)
            }
            Direction.LEFT -> {
                // 检查边界左侧的区域
                val x1 = maxOf(0, boundaryPos - sampleSize)
                val x2 = maxOf(0, boundaryPos)
                SampleRegion(x1, x2, 0, height)
            }
            Direction.RIGHT -> {
                // 检查边界右侧的区域
                val x1 = minOf(width, boundaryPos)
                val x2 = minOf(width, boundaryPos + sampleSize)
                SampleRegion(x1, x2, 0, height)
            }
        }

        val startX = region.startX
        val endX = region.endX
        val startY = region.startY
        val endY = region.endY

        // 计算采样区域的平均亮度
        var totalBrightness = 0f
        var pixelCount = 0

        for (y in startY until endY) {
            for (x in startX until endX) {
                if (x >= 0 && x < width && y >= 0 && y < height) {
                    val pixel = bitmap.getPixel(x, y)
                    totalBrightness += rgbToLuma(pixel)
                    pixelCount++
                }
            }
        }

        if (pixelCount == 0) return false

        val avgBrightness = totalBrightness / pixelCount

        if (VERBOSE_DEBUG_MODE) {
            Log.d(TAG, "verifyLetterboxBrightness: $direction at pos=$boundaryPos, avgBrightness=$avgBrightness, threshold=$LETTERBOX_BRIGHTNESS_THRESHOLD")
        }

        // 如果平均亮度低于阈值，认为是真正的黑边
        return avgBrightness < LETTERBOX_BRIGHTNESS_THRESHOLD
    }

    /**
     * 输出详细的覆盖率数据（用于调试）
     */
    private fun logCoverageData(direction: Direction, data: List<Pair<Int, Float>>, finalBoundary: Int) {
        Log.d(TAG, "========== Edge Coverage Data for $direction ==========")
        Log.d(TAG, "Final boundary: $finalBoundary")
        Log.d(TAG, "Threshold: $EDGE_COVERAGE_THRESHOLD")

        // 输出前100行的数据
        val limit = minOf(100, data.size)
        for (i in 0 until limit) {
            val (step, coverage) = data[i]
            val marker = if (step == finalBoundary) " <- SELECTED" else if (coverage >= EDGE_COVERAGE_THRESHOLD) " <- THRESHOLD" else ""
            Log.d(TAG, "  Step $step: coverage=${String.format("%.3f", coverage)}$marker")
        }

        if (data.size > limit) {
            Log.d(TAG, "  ... (${data.size - limit} more rows)")
        }

        Log.d(TAG, "==================================================")
    }

    /**
     * 计算某一行的边缘像素覆盖率
     * 返回：该行中有多少比例的像素是边缘（横跨整个宽度的分界线）
     */
    private fun calculateHorizontalEdgeCoverage(edgeMap: Array<FloatArray>, row: Int): Float {
        if (row < 0 || row >= edgeMap.size) return 0f

        val width = edgeMap[0].size
        var edgeCount = 0

        for (x in 0 until width) {
            if (edgeMap[row][x] >= EDGE_GRADIENT_THRESHOLD) {
                edgeCount++
            }
        }

        return edgeCount.toFloat() / width
    }

    /**
     * 计算某一列的边缘像素覆盖率
     * 返回：该列中有多少比例的像素是边缘（横跨整个高度的分界线）
     */
    private fun calculateVerticalEdgeCoverage(edgeMap: Array<FloatArray>, col: Int): Float {
        if (col < 0 || col >= edgeMap[0].size) return 0f

        val height = edgeMap.size
        var edgeCount = 0

        for (y in 0 until height) {
            if (edgeMap[y][col] >= EDGE_GRADIENT_THRESHOLD) {
                edgeCount++
            }
        }

        return edgeCount.toFloat() / height
    }

    // ========== 以下是原有的亮度检测辅助方法 ==========

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
}

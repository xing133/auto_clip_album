package com.autocrop.album.ui.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.autocrop.album.R
import com.autocrop.album.data.models.CropBox
import com.autocrop.album.data.models.DetectedPerson
import com.autocrop.album.domain.CropCalculator
import com.autocrop.album.domain.PoseDetector
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 裁剪框覆盖视图
 * 负责绘制：
 * 1. 黑边 debug 可视化（蓝色半透明矩形）
 * 2. 骨骼关键点和连接线
 * 3. 裁剪框（支持多个）
 * 4. 处理拖动、缩放交互
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 检测到的人物列表
    private var detectedPersons: List<DetectedPerson> = emptyList()

    // 裁剪框列表
    private var cropBoxes: MutableList<CropBox> = mutableListOf()

    // 当前选中的裁剪框
    private var selectedCropBox: CropBox? = null

    // 黑边区域列表（用于debug可视化）
    private var letterboxRegions: List<Rect> = emptyList()

    // 图片尺寸（用于坐标转换）
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0
    private var imageOffsetX: Float = 0f
    private var imageOffsetY: Float = 0f
    private var imageScale: Float = 1f

    // 触摸状态
    private var touchMode = TouchMode.NONE
    private val touchStart = PointF()
    private var touchingCorner: Corner? = null
    private var originalCropRect = RectF()

    // 画笔
    private val landmarkPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.pose_landmark_point)
        style = Paint.Style.FILL
        strokeWidth = 8f
    }

    private val landmarkConnectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.pose_landmark_connection)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val cropBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.crop_box_border)
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val cropBoxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.crop_box_fill)
        style = Paint.Style.FILL
    }

    private val cropBoxSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.crop_box_border_selected)
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.crop_box_outside_dimmed)
        style = Paint.Style.FILL
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.crop_box_corner)
        style = Paint.Style.FILL
    }

    private val personLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.pose_landmark_selected)
        textSize = 40f
        style = Paint.Style.FILL
    }

    private val letterboxDebugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // 半透明蓝色填充
        color = Color.argb(120, 0, 100, 255)
        style = Paint.Style.FILL
    }

    // 黑边可视化开关（默认显示）
    var showLetterboxDebug: Boolean = true

    enum class TouchMode {
        NONE, DRAG, RESIZE
    }

    enum class Corner {
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, NONE
    }

    /**
     * 设置检测到的人物数据
     */
    fun setDetectedPersons(persons: List<DetectedPerson>) {
        detectedPersons = persons
        invalidate()
    }

    /**
     * 设置裁剪框列表
     */
    fun setCropBoxes(boxes: List<CropBox>) {
        cropBoxes = boxes.toMutableList()
        selectedCropBox = boxes.firstOrNull { it.isSelected }
        invalidate()
    }

    /**
     * 设置黑边区域（用于debug可视化）
     */
    fun setLetterboxRegions(regions: List<Rect>) {
        letterboxRegions = regions
        invalidate()
    }

    /**
     * 添加裁剪框
     */
    fun addCropBox(box: CropBox) {
        cropBoxes.add(box)
        selectedCropBox = box
        invalidate()
    }

    /**
     * 获取当前选中的裁剪框
     */
    fun getSelectedCropBox(): CropBox? = selectedCropBox

    /**
     * 设置图片尺寸和变换信息（用于坐标转换）
     */
    fun setImageTransform(width: Int, height: Int, offsetX: Float, offsetY: Float, scale: Float) {
        imageWidth = width
        imageHeight = height
        imageOffsetX = offsetX
        imageOffsetY = offsetY
        imageScale = scale
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (imageWidth == 0 || imageHeight == 0) return

        // 1. 绘制黑边 debug 可视化（最先绘制，在底层）
        drawLetterboxDebug(canvas)

        // 2. 绘制骨骼连接线
        drawPoseConnections(canvas)

        // 3. 绘制骨骼关键点
        drawPoseLandmarks(canvas)

        // 4. 绘制裁剪框
        drawCropBoxes(canvas)

        // 5. 绘制人物编号
        drawPersonLabels(canvas)
    }

    /**
     * 绘制黑边 debug 可视化
     */
    private fun drawLetterboxDebug(canvas: Canvas) {
        if (!showLetterboxDebug || letterboxRegions.isEmpty()) return

        letterboxRegions.forEach { region ->
            val viewRect = imageRectToViewRect(region)
            canvas.drawRect(viewRect, letterboxDebugPaint)
        }
    }

    /**
     * 将图片坐标系的Rect转换为视图坐标系的RectF
     */
    private fun imageRectToViewRect(rect: Rect): RectF {
        return RectF(
            rect.left * imageScale + imageOffsetX,
            rect.top * imageScale + imageOffsetY,
            rect.right * imageScale + imageOffsetX,
            rect.bottom * imageScale + imageOffsetY
        )
    }

    private fun drawPoseConnections(canvas: Canvas) {
        detectedPersons.forEach { person ->
            val landmarkMap = person.landmarks.associateBy { it.type }

            PoseDetector.POSE_CONNECTIONS.forEach { (start, end) ->
                val startLandmark = landmarkMap[start]
                val endLandmark = landmarkMap[end]

                if (startLandmark != null && endLandmark != null &&
                    startLandmark.visibility > DetectedPerson.VISIBILITY_THRESHOLD &&
                    endLandmark.visibility > DetectedPerson.VISIBILITY_THRESHOLD
                ) {
                    // 只绘制两个端点都在图片范围内的连线
                    if (isPointInImageBounds(startLandmark.position) &&
                        isPointInImageBounds(endLandmark.position)) {
                        val startPoint = imageToViewCoordinates(startLandmark.position)
                        val endPoint = imageToViewCoordinates(endLandmark.position)

                        canvas.drawLine(
                            startPoint.x, startPoint.y,
                            endPoint.x, endPoint.y,
                            landmarkConnectionPaint
                        )
                    }
                }
            }
        }
    }

    private fun drawPoseLandmarks(canvas: Canvas) {
        detectedPersons.forEach { person ->
            person.landmarks.forEach { landmark ->
                if (landmark.visibility > DetectedPerson.VISIBILITY_THRESHOLD &&
                    isPointInImageBounds(landmark.position)) {
                    // 只绘制在图片范围内的骨骼点
                    val point = imageToViewCoordinates(landmark.position)
                    canvas.drawCircle(point.x, point.y, 8f, landmarkPointPaint)
                }
            }
        }
    }

    private fun drawCropBoxes(canvas: Canvas) {
        cropBoxes.forEach { cropBox ->
            val rect = imageRectToViewRect(cropBox.rect)
            val isSelected = cropBox == selectedCropBox

            // 绘制填充
            if (isSelected) {
                // 绘制外部遮罩（变暗）
                canvas.save()
                canvas.clipRect(rect, android.graphics.Region.Op.DIFFERENCE)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
                canvas.restore()

                // 绘制裁剪框填充
                canvas.drawRect(rect, cropBoxFillPaint)
            }

            // 绘制边框
            val paint = if (isSelected) cropBoxSelectedPaint else cropBoxPaint
            canvas.drawRect(rect, paint)

            // 绘制角点（仅选中时）
            if (isSelected) {
                val cornerRadius = 20f
                canvas.drawCircle(rect.left, rect.top, cornerRadius, cornerPaint)
                canvas.drawCircle(rect.right, rect.top, cornerRadius, cornerPaint)
                canvas.drawCircle(rect.left, rect.bottom, cornerRadius, cornerPaint)
                canvas.drawCircle(rect.right, rect.bottom, cornerRadius, cornerPaint)
            }
        }
    }

    private fun drawPersonLabels(canvas: Canvas) {
        cropBoxes.forEach { cropBox ->
            if (cropBox.personId != null) {
                val rect = imageRectToViewRect(cropBox.rect)
                val label = "Person ${cropBox.personId + 1}"
                val textWidth = personLabelPaint.measureText(label)
                val textX = rect.left + 10f
                val textY = rect.top - 10f

                // 绘制背景
                canvas.drawRect(
                    textX - 5f, textY - personLabelPaint.textSize - 5f,
                    textX + textWidth + 5f, textY + 5f,
                    dimPaint
                )

                // 绘制文字
                canvas.drawText(label, textX, textY, personLabelPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val touchPoint = PointF(event.x, event.y)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStart.set(touchPoint)

                // 查找触摸的裁剪框
                val touchedBox = findTouchedCropBox(touchPoint)
                if (touchedBox != null) {
                    selectCropBox(touchedBox)
                    val viewRect = imageRectToViewRect(touchedBox.rect)

                    // 检查是否触摸角点
                    touchingCorner = findTouchedCorner(touchPoint, viewRect)
                    touchMode = if (touchingCorner != Corner.NONE) {
                        TouchMode.RESIZE
                    } else if (viewRect.contains(touchPoint.x, touchPoint.y)) {
                        TouchMode.DRAG
                    } else {
                        TouchMode.NONE
                    }

                    originalCropRect.set(touchedBox.rect)
                    return true
                } else {
                    // 没有触摸到裁剪框，创建新的手动裁剪框
                    performClick()
                    return false
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (selectedCropBox != null && touchMode != TouchMode.NONE) {
                    val dx = (touchPoint.x - touchStart.x) / imageScale
                    val dy = (touchPoint.y - touchStart.y) / imageScale

                    val newRect = when (touchMode) {
                        TouchMode.DRAG -> {
                            RectF(originalCropRect).apply {
                                offset(dx, dy)
                            }
                        }
                        TouchMode.RESIZE -> {
                            resizeCropRect(originalCropRect, touchingCorner!!, dx, dy)
                        }
                        else -> originalCropRect
                    }

                    // 更新裁剪框
                    val adjustedBox = CropCalculator.adjustCropBox(
                        selectedCropBox!!,
                        newRect,
                        imageWidth,
                        imageHeight
                    )

                    val index = cropBoxes.indexOf(selectedCropBox)
                    if (index >= 0) {
                        cropBoxes[index] = adjustedBox
                        selectedCropBox = adjustedBox
                        invalidate()
                    }

                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchMode = TouchMode.NONE
                touchingCorner = null
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        // 双击创建手动裁剪框的逻辑在 EditorActivity 中处理
        return true
    }

    private fun findTouchedCropBox(point: PointF): CropBox? {
        // 从后往前查找（优先选择最上层的）
        return cropBoxes.asReversed().firstOrNull { cropBox ->
            val viewRect = imageRectToViewRect(cropBox.rect)
            // 扩大触摸区域
            viewRect.inset(-20f, -20f)
            viewRect.contains(point.x, point.y)
        }
    }

    private fun selectCropBox(cropBox: CropBox) {
        cropBoxes.forEach { it.isSelected = false }
        val index = cropBoxes.indexOf(cropBox)
        if (index >= 0) {
            cropBoxes[index] = cropBox.copy(isSelected = true)
            selectedCropBox = cropBoxes[index]
            invalidate()
        }
    }

    private fun findTouchedCorner(point: PointF, rect: RectF): Corner {
        val touchRadius = 60f

        return when {
            distance(point, PointF(rect.left, rect.top)) < touchRadius -> Corner.TOP_LEFT
            distance(point, PointF(rect.right, rect.top)) < touchRadius -> Corner.TOP_RIGHT
            distance(point, PointF(rect.left, rect.bottom)) < touchRadius -> Corner.BOTTOM_LEFT
            distance(point, PointF(rect.right, rect.bottom)) < touchRadius -> Corner.BOTTOM_RIGHT
            else -> Corner.NONE
        }
    }

    private fun resizeCropRect(original: RectF, corner: Corner, dx: Float, dy: Float): RectF {
        return RectF(original).apply {
            when (corner) {
                Corner.TOP_LEFT -> {
                    left += dx
                    top += dy
                }
                Corner.TOP_RIGHT -> {
                    right += dx
                    top += dy
                }
                Corner.BOTTOM_LEFT -> {
                    left += dx
                    bottom += dy
                }
                Corner.BOTTOM_RIGHT -> {
                    right += dx
                    bottom += dy
                }
                Corner.NONE -> {}
            }
        }
    }

    private fun distance(p1: PointF, p2: PointF): Float {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * 检查点是否在图片范围内
     */
    private fun isPointInImageBounds(point: PointF): Boolean {
        return point.x >= 0f && point.x <= imageWidth &&
               point.y >= 0f && point.y <= imageHeight
    }

    /**
     * 图片坐标转换为视图坐标
     */
    private fun imageToViewCoordinates(point: PointF): PointF {
        return PointF(
            point.x * imageScale + imageOffsetX,
            point.y * imageScale + imageOffsetY
        )
    }

    /**
     * 图片矩形转换为视图矩形
     */
    private fun imageRectToViewRect(rect: RectF): RectF {
        return RectF(
            rect.left * imageScale + imageOffsetX,
            rect.top * imageScale + imageOffsetY,
            rect.right * imageScale + imageOffsetX,
            rect.bottom * imageScale + imageOffsetY
        )
    }
}

package com.autocrop.album.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import com.autocrop.album.R
import com.autocrop.album.data.models.CropBox
import com.autocrop.album.domain.CropCalculator
import com.autocrop.album.data.SettingsManager
import com.autocrop.album.domain.SegmentationRegions
import kotlin.math.sqrt

/**
 * 裁剪框覆盖视图
 * 负责绘制：
 * 1. 黑边 debug 可视化（蓝色半透明矩形）
 * 2. 裁剪框（支持多个）
 * 3. 处理拖动、缩放交互
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

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

    private val viewport = ImageViewport()
    private val settingsManager = SettingsManager.getInstance(context)
    var externalMaskEditing = false
        set(value) { field = value; touchMode = TouchMode.NONE; invalidate() }
    fun panImageBy(dx: Float, dy: Float) { viewport.pan(dx, dy); applyViewport() }
    var onImageTransformChanged: ((Int, Int, Float, Float, Float) -> Unit)? = null
    val isZoomed: Boolean get() = viewport.zoom > 1.001f
    private var multiTouchGesture = false
    private var panning = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                viewport.zoomAt(detector.scaleFactor, detector.focusX, detector.focusY)
                applyViewport()
                return true
            }
        }).apply {
            // Double-tap remains the editor's manual-crop gesture.
            isQuickScaleEnabled = false
        }

    private var segmentation: SegmentationRegions? = null
    private var segmentationBounds: SegmentationRegions.Bounds? = null
    private var segmentationBitmap: Bitmap? = null
    private val segmentationPaint = Paint()
    private var segmentationTap = false
    private val segmentationDown = PointF()
    private val segmentationLast = PointF()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    val isShowingSegmentation: Boolean get() = segmentation != null
    var onSegmentationRegionSelected: ((RectF) -> Unit)? = null
    var onSegmentationModeChanged: ((Boolean) -> Unit)? = null

    fun showSegmentation(regions: SegmentationRegions, imageBounds: RectF) {
        clearSegmentation()
        val palette = intArrayOf(0x7063e6be, 0x70ff6b6b, 0x704dabf7, 0x70ffd43b,
            0x70da77f2, 0x70ff922b, 0x7066d9e8, 0x70a9e34b)
        segmentationBitmap = Bitmap.createBitmap(regions.colorPixels(palette), regions.width,
            regions.height, Bitmap.Config.ARGB_8888)
        segmentation = regions
        segmentationBounds = SegmentationRegions.Bounds(imageBounds.left, imageBounds.top, imageBounds.right, imageBounds.bottom)
        touchMode = TouchMode.NONE
        onSegmentationModeChanged?.invoke(true)
        invalidate()
    }

    fun clearSegmentation() {
        val wasShowing = isShowingSegmentation
        segmentation = null
        segmentationBounds = null
        // A hardware display list may still reference the previous bitmap; let GC release it safely.
        segmentationBitmap = null
        segmentationTap = false
        if (wasShowing) onSegmentationModeChanged?.invoke(false)
        invalidate()
    }

    private fun drawSegmentation(canvas: Canvas) {
        val bitmap = segmentationBitmap ?: return
        val bounds = segmentationBounds ?: return
        val target = imageRectToViewRect(RectF(bounds.left, bounds.top, bounds.right, bounds.bottom))
        canvas.drawBitmap(bitmap, null, target, segmentationPaint)
    }

    private fun handleSegmentationTouch(event: MotionEvent, point: PointF): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                segmentationDown.set(point)
                segmentationLast.set(point)
                segmentationTap = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (distance(point, segmentationDown) > touchSlop) segmentationTap = false
                if (isZoomed) {
                    viewport.pan(point.x - segmentationLast.x, point.y - segmentationLast.y)
                    applyViewport()
                }
                segmentationLast.set(point)
            }
            MotionEvent.ACTION_UP -> {
                if (segmentationTap && distance(point, segmentationDown) <= touchSlop) {
                    val regions = segmentation ?: return true
                    val image = segmentationBounds ?: return true
                    val region = regions.regionAtImage(viewport.imageX(point.x), viewport.imageY(point.y), image)
                    if (region != null) {
                        val bounds = regions.boundsInImage(region, image)
                        val rect = RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
                        clearSegmentation()
                        onSegmentationRegionSelected?.invoke(rect)
                        performClick()
                    }
                }
                segmentationTap = false
            }
            MotionEvent.ACTION_CANCEL -> segmentationTap = false
        }
        return true
    }

    // 触摸状态
    private var touchMode = TouchMode.NONE
    private val touchStart = PointF()
    private var touchingCorner: Corner? = null
    private var originalCropRect = RectF()

    // 画笔
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
        // 角点
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
        // 边缘
        TOP, BOTTOM, LEFT, RIGHT,
        // 无
        NONE
    }

    /**
     * 设置裁剪框列表
     */
    fun setCropBoxes(boxes: List<CropBox>) {
        clearSegmentation()
        cropBoxes = boxes.map { it.copyWithRect(visibleCropRect(it)) }.toMutableList()
        selectedCropBox = cropBoxes.firstOrNull { it.isSelected }
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
        val constrainedBox = box.copyWithRect(visibleCropRect(box))
        cropBoxes.add(constrainedBox)
        selectedCropBox = constrainedBox
        invalidate()
    }

    /**
     * 获取当前选中的裁剪框
     */
    fun getSelectedCropBox(): CropBox? = selectedCropBox?.let {
        val visible = visibleCropRect(it)
        if (visible.isEmpty) null else it.copyWithRect(visible)
    }

    fun getVisibleImageRect(): RectF = viewport.visibleRegion().let {
        RectF(it.left, it.top, it.right, it.bottom)
    }

    var interactionEnabled = true

    /** The same limits are used for drawing, interaction and full-resolution export. */
    fun getAvailableCropBounds(box: CropBox): RectF? {
        val available = getVisibleImageRect()
        if (box.contentBounds?.let { !available.intersect(it) } == true || available.isEmpty) return null
        return available
    }

    private fun visibleCropRect(box: CropBox): RectF {
        if (imageWidth <= 0 || imageHeight <= 0) return RectF(box.rect)
        val rect = box.rect
        val available = getAvailableCropBounds(box) ?: return RectF()
        val region = ImageViewport.Region(available.left, available.top, available.right, available.bottom).constrain(
            ImageViewport.Region(rect.left, rect.top, rect.right, rect.bottom))
        return CropCalculator.constrainCropAspect(
            RectF(region.left, region.top, region.right, region.bottom), available, settingsManager.getCropAspectNumerator())
    }

    /**
     * 设置图片尺寸和变换信息（用于坐标转换）
     */
    fun setImageTransform(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || this.width <= 0 || this.height <= 0) return
        if (imageWidth != width || imageHeight != height) {
            imageWidth = width
            imageHeight = height
            viewport.reset(width.toFloat(), height.toFloat(), this.width.toFloat(), this.height.toFloat())
        }
        applyViewport()
    }

    fun resetImage() {
        clearSegmentation()
        externalMaskEditing = false
        imageWidth = 0
        imageHeight = 0
        cropBoxes.clear()
        selectedCropBox = null
        letterboxRegions = emptyList()
        touchMode = TouchMode.NONE
        multiTouchGesture = false
        panning = false
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (imageWidth > 0 && imageHeight > 0 && w > 0 && h > 0) {
            viewport.reset(imageWidth.toFloat(), imageHeight.toFloat(), w.toFloat(), h.toFloat())
            applyViewport()
        }
    }

    private fun applyViewport() {
        imageScale = viewport.scale
        imageOffsetX = viewport.offsetX
        imageOffsetY = viewport.offsetY
        onImageTransformChanged?.invoke(imageWidth, imageHeight, imageScale, imageOffsetX, imageOffsetY)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (imageWidth == 0 || imageHeight == 0 || externalMaskEditing) return
        if (isShowingSegmentation) {
            drawSegmentation(canvas)
            return
        }

        // 1. 绘制黑边 debug 可视化（最先绘制，在底层）
        drawLetterboxDebug(canvas)

        // 2. 绘制裁剪框（含遮罩）
        drawCropBoxes(canvas)

        // 3. 绘制人物编号
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

    private fun drawCropBoxes(canvas: Canvas) {
        cropBoxes.forEach { cropBox ->
            val visible = visibleCropRect(cropBox)
            if (visible.isEmpty) return@forEach
            val rect = imageRectToViewRect(visible)
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
            val border = RectF(rect).apply { inset(paint.strokeWidth / 2f, paint.strokeWidth / 2f) }
            canvas.drawRect(border, paint)

            // 绘制角点（仅选中时）
            if (isSelected) {
                val cornerRadius = 20f
                fun drawHandle(x: Float, y: Float) {
                    val radius = minOf(cornerRadius, width / 2f, height / 2f)
                    canvas.drawCircle(x.coerceIn(radius, width - radius),
                        y.coerceIn(radius, height - radius), radius, cornerPaint)
                }
                drawHandle(rect.left, rect.top)
                drawHandle(rect.right, rect.top)
                drawHandle(rect.left, rect.bottom)
                drawHandle(rect.right, rect.bottom)
            }
        }
    }

    private fun drawPersonLabels(canvas: Canvas) {
        cropBoxes.forEach { cropBox ->
            if (cropBox.personId != null) {
                val visible = visibleCropRect(cropBox)
                if (visible.isEmpty) return@forEach
                val rect = imageRectToViewRect(visible)
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
        if (!interactionEnabled) return true
        if (imageWidth <= 0 || imageHeight <= 0) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            multiTouchGesture = false
            panning = false
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            multiTouchGesture = true
            segmentationTap = false
            touchMode = TouchMode.NONE
            touchingCorner = null
        }
        // Two fingers can pan even when a crop box covers the entire viewport.
        if (event.pointerCount >= 2) {
            val activePointers = (0 until event.pointerCount).filter {
                event.actionMasked != MotionEvent.ACTION_POINTER_UP || it != event.actionIndex
            }
            val focusX = activePointers.map { event.getX(it) }.average().toFloat()
            val focusY = activePointers.map { event.getY(it) }.average().toFloat()
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                viewport.pan(focusX - lastFocusX, focusY - lastFocusY)
                applyViewport()
            }
            lastFocusX = focusX
            lastFocusY = focusY
        }
        scaleDetector.onTouchEvent(event)
        if (multiTouchGesture) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                multiTouchGesture = false
            }
            return true
        }
        val touchPoint = PointF(event.x, event.y)
        if (externalMaskEditing) return true
        if (isShowingSegmentation) return handleSegmentationTouch(event, touchPoint)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStart.set(touchPoint)

                // 查找触摸的裁剪框
                val touchedBox = findTouchedCropBox(touchPoint)
                if (touchedBox != null) {
                    selectCropBox(touchedBox)
                    val viewRect = imageRectToViewRect(visibleCropRect(touchedBox))

                    // 检查是否触摸角点
                    touchingCorner = findTouchedCorner(touchPoint, viewRect)
                    touchMode = if (touchingCorner != Corner.NONE) {
                        TouchMode.RESIZE
                    } else if (viewRect.contains(touchPoint.x, touchPoint.y)) {
                        TouchMode.DRAG
                    } else {
                        TouchMode.NONE
                    }

                    originalCropRect.set(visibleCropRect(touchedBox))
                    return true
                } else {
                    // Consume the stream so a second finger can start zooming anywhere.
                    touchMode = TouchMode.NONE
                    panning = isZoomed
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (panning) {
                    viewport.pan(touchPoint.x - touchStart.x, touchPoint.y - touchStart.y)
                    touchStart.set(touchPoint)
                    applyViewport()
                    return true
                }
                if (selectedCropBox != null && touchMode != TouchMode.NONE) {
                    val dx = viewport.imageX(touchPoint.x) - viewport.imageX(touchStart.x)
                    val dy = viewport.imageY(touchPoint.y) - viewport.imageY(touchStart.y)

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
                        imageHeight,
                        settingsManager.getCropAspectNumerator()
                    )

                    val index = cropBoxes.indexOf(selectedCropBox)
                    if (index >= 0) {
                        val visibleBox = adjustedBox.copyWithRect(visibleCropRect(adjustedBox))
                        cropBoxes[index] = visibleBox
                        selectedCropBox = visibleBox
                        invalidate()
                    }

                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchMode = TouchMode.NONE
                touchingCorner = null
                panning = false
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

    /** Coordinates are local to this view; include the resize handles in the protected area. */
    fun isNearCropBox(x: Float, y: Float): Boolean {
        val point = PointF(x, y)
        val margin = 24f * resources.displayMetrics.density
        return cropBoxes.any { box ->
            val visible = visibleCropRect(box)
            if (visible.isEmpty) return@any false
            val rect = imageRectToViewRect(visible)
            val expanded = RectF(rect).apply { inset(-margin, -margin) }
            expanded.contains(x, y) || findTouchedCorner(point, rect) != Corner.NONE
        }
    }

    private fun findTouchedCropBox(point: PointF): CropBox? {
        // 从后往前查找（优先选择最上层的）
        return cropBoxes.asReversed().firstOrNull { cropBox ->
            val visible = visibleCropRect(cropBox)
            if (visible.isEmpty) return@firstOrNull false
            val viewRect = imageRectToViewRect(visible)
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
        val cornerTouchRadius = 60f
        val edgeTouchDistance = 40f

        // 首先检查角点（优先级更高）
        when {
            distance(point, PointF(rect.left, rect.top)) < cornerTouchRadius -> return Corner.TOP_LEFT
            distance(point, PointF(rect.right, rect.top)) < cornerTouchRadius -> return Corner.TOP_RIGHT
            distance(point, PointF(rect.left, rect.bottom)) < cornerTouchRadius -> return Corner.BOTTOM_LEFT
            distance(point, PointF(rect.right, rect.bottom)) < cornerTouchRadius -> return Corner.BOTTOM_RIGHT
        }

        // 然后检查边缘
        return when {
            // 上边缘
            point.y >= rect.top - edgeTouchDistance &&
            point.y <= rect.top + edgeTouchDistance &&
            point.x >= rect.left && point.x <= rect.right -> Corner.TOP

            // 下边缘
            point.y >= rect.bottom - edgeTouchDistance &&
            point.y <= rect.bottom + edgeTouchDistance &&
            point.x >= rect.left && point.x <= rect.right -> Corner.BOTTOM

            // 左边缘
            point.x >= rect.left - edgeTouchDistance &&
            point.x <= rect.left + edgeTouchDistance &&
            point.y >= rect.top && point.y <= rect.bottom -> Corner.LEFT

            // 右边缘
            point.x >= rect.right - edgeTouchDistance &&
            point.x <= rect.right + edgeTouchDistance &&
            point.y >= rect.top && point.y <= rect.bottom -> Corner.RIGHT

            else -> Corner.NONE
        }
    }

    private fun resizeCropRect(original: RectF, corner: Corner, dx: Float, dy: Float): RectF {
        return RectF(original).apply {
            when (corner) {
                // 角点：同时调整两条边
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
                // 边缘：只调整一条边
                Corner.TOP -> {
                    top += dy
                }
                Corner.BOTTOM -> {
                    bottom += dy
                }
                Corner.LEFT -> {
                    left += dx
                }
                Corner.RIGHT -> {
                    right += dx
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

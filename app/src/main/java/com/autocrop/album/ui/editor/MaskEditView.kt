package com.autocrop.album.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.autocrop.album.domain.EditableMask
import kotlin.math.hypot

/** Shares the crop viewport transform, but stores brush strokes in image coordinates. */
class MaskEditView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    enum class Tool { SELECT, ADD, ERASE }
    var viewport: CropOverlayView? = null
    var onSelect: ((Float, Float) -> Unit)? = null
    var onMaskChanged: (() -> Unit)? = null
    var mask: EditableMask? = null
        set(value) { field = value; refresh() }
    var tool = Tool.SELECT
    var preview = false
        set(value) { field = value; invalidate() }
    var brushRadiusDp = 12f
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var maskBitmap: Bitmap? = null
    private val paint = Paint()
    private var colors = IntArray(0)
    private var multi = false
    private var tap = false
    private var stroke = false
    private val down = PointF()
    private val lastView = PointF()
    private val lastImage = PointF()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    fun setImageTransform(scale: Float, x: Float, y: Float) {
        this.scale = scale; offsetX = x; offsetY = y; invalidate()
    }
    fun refresh() {
        val m = mask
        if (m == null) { maskBitmap = null; colors = IntArray(0); invalidate(); return }
        val bytes = m.copyPixels()
        if (colors.size != bytes.size) colors = IntArray(bytes.size)
        for (i in bytes.indices) colors[i] = if (bytes[i].toInt() == 0) 0 else 0x8000ff66.toInt()
        val bitmap = maskBitmap?.takeIf { it.width == m.width && it.height == m.height }
            ?: Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888).also { maskBitmap = it }
        bitmap.setPixels(colors, 0, m.width, 0, 0, m.width, m.height)
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val m = mask ?: return
        if (!preview) maskBitmap?.let {
            canvas.drawBitmap(it, null, RectF(offsetX, offsetY, offsetX + m.width * scale, offsetY + m.height * scale), paint)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) { tap = false; return true }
        val m = mask ?: return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { multi = false; tap = true; down.set(event.x, event.y); lastView.set(down) }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            multi = true; tap = false
            if (stroke) { m.cancelStroke(); stroke = false; refresh(); onMaskChanged?.invoke() }
        }
        viewport?.onTouchEvent(event)
        if (multi) return true
        val x = (event.x - offsetX) / scale
        val y = (event.y - offsetY) / scale
        val radius = brushRadiusDp * resources.displayMetrics.density / scale
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastImage.set(x, y)
                if (!preview && tool != Tool.SELECT) {
                    m.beginStroke(); stroke = true
                    m.paintLine(x, y, x, y, radius, tool == Tool.ADD); refresh()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(event.x - down.x, event.y - down.y) > slop) tap = false
                if (stroke) {
                    m.paintLine(lastImage.x, lastImage.y, x, y, radius, tool == Tool.ADD)
                    lastImage.set(x, y); refresh()
                } else if (viewport?.isZoomed == true) viewport?.panImageBy(event.x - lastView.x, event.y - lastView.y)
                lastView.set(event.x, event.y)
            }
            MotionEvent.ACTION_UP -> {
                if (stroke) { m.endStroke(); stroke = false; onMaskChanged?.invoke() }
                else if (!preview && tap && hypot(event.x - down.x, event.y - down.y) <= slop &&
                    x >= 0 && y >= 0 && x < m.width && y < m.height) { onSelect?.invoke(x, y); performClick() }
            }
            MotionEvent.ACTION_CANCEL -> if (stroke) { m.cancelStroke(); stroke = false; refresh(); onMaskChanged?.invoke() }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}

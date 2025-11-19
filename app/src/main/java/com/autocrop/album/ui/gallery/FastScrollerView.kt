package com.autocrop.album.ui.gallery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.autocrop.album.R

/**
 * 快速滚动条 - 可拖动的滚动条
 */
class FastScrollerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var recyclerView: RecyclerView? = null
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var thumbRect = RectF()
    private var trackRect = RectF()

    private var isDragging = false
    private var thumbHeight = 0f
    private var thumbPosition = 0f

    private val minThumbHeight = 48f
    private val scrollerWidth = 24f
    private val thumbWidth = 8f

    init {
        // 设置颜色
        thumbPaint.color = ContextCompat.getColor(context, R.color.md_theme_dark_primary)
        trackPaint.color = 0x30FFFFFF.toInt()

        // 设置圆角
        thumbPaint.style = Paint.Style.FILL
        trackPaint.style = Paint.Style.FILL
    }

    fun attachToRecyclerView(rv: RecyclerView) {
        recyclerView = rv
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!isDragging) {
                    updateThumbPosition()
                }
            }
        })
    }

    private fun updateThumbPosition() {
        recyclerView?.let { rv ->
            val offset = rv.computeVerticalScrollOffset()
            val range = rv.computeVerticalScrollRange()
            val extent = rv.computeVerticalScrollExtent()

            if (range <= extent) {
                // 内容不需要滚动
                visibility = GONE
                return
            }

            visibility = VISIBLE

            // 计算滑块高度（与可见内容比例成正比）
            thumbHeight = (height * extent.toFloat() / range).coerceAtLeast(minThumbHeight)

            // 计算滑块位置
            val scrollableHeight = height - thumbHeight
            thumbPosition = (offset.toFloat() / (range - extent)) * scrollableHeight

            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (visibility != VISIBLE) return

        val right = width.toFloat()
        val left = right - thumbWidth

        // 绘制轨道
        trackRect.set(left, 0f, right, height.toFloat())
        canvas.drawRoundRect(trackRect, 4f, 4f, trackPaint)

        // 绘制滑块
        thumbRect.set(left, thumbPosition, right, thumbPosition + thumbHeight)
        canvas.drawRoundRect(thumbRect, 4f, 4f, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (event.x >= width - scrollerWidth) {
                    isDragging = true
                    handleDrag(event.y)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    handleDrag(event.y)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleDrag(y: Float) {
        recyclerView?.let { rv ->
            val itemCount = rv.adapter?.itemCount ?: return
            if (itemCount == 0) return

            // 计算目标位置比例
            val scrollableHeight = height - thumbHeight
            val ratio = (y - thumbHeight / 2).coerceIn(0f, scrollableHeight) / scrollableHeight
            val targetPosition = (ratio * itemCount).toInt().coerceIn(0, itemCount - 1)

            // 滚动到目标位置
            rv.scrollToPosition(targetPosition)

            // 更新滑块位置
            thumbPosition = (y - thumbHeight / 2).coerceIn(0f, scrollableHeight)
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateThumbPosition()
    }
}

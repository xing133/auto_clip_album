package com.autocrop.album.ui.editor

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/** Uses the overlay's preview coordinate system even when Glide loads a different resolution. */
class EditorImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {
    private var previewWidth = 0
    private var previewHeight = 0
    private var previewScale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    fun resetPreviewTransform() {
        previewWidth = 0
        previewHeight = 0
        scaleType = ScaleType.FIT_CENTER
        imageMatrix = Matrix()
    }

    fun setPreviewTransform(width: Int, height: Int, scale: Float, x: Float, y: Float) {
        previewWidth = width
        previewHeight = height
        previewScale = scale
        offsetX = x
        offsetY = y
        updateMatrix()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        updateMatrix()
    }

    private fun updateMatrix() {
        val image = drawable ?: return
        if (previewWidth <= 0 || previewHeight <= 0 || image.intrinsicWidth <= 0 || image.intrinsicHeight <= 0) return
        scaleType = ScaleType.MATRIX
        imageMatrix = Matrix().apply {
            setScale(previewScale * previewWidth / image.intrinsicWidth,
                previewScale * previewHeight / image.intrinsicHeight)
            postTranslate(offsetX, offsetY)
        }
    }
}

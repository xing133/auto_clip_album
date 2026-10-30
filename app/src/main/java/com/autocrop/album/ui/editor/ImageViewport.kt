package com.autocrop.album.ui.editor

/** Maps preview-image coordinates to the viewport. Crop geometry never lives here. */
internal class ImageViewport {
    var imageWidth = 0f
        private set
    var imageHeight = 0f
        private set
    private var viewWidth = 0f
    private var viewHeight = 0f
    private var fitScale = 1f
    var zoom = 1f
        private set
    val scale: Float get() = fitScale * zoom
    var offsetX = 0f
        private set
    var offsetY = 0f
        private set

    fun reset(imageWidth: Float, imageHeight: Float, viewWidth: Float, viewHeight: Float) {
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        this.viewWidth = viewWidth
        this.viewHeight = viewHeight
        fitScale = minOf(viewWidth / imageWidth, viewHeight / imageHeight)
        zoom = 1f
        constrain()
    }

    fun zoomAt(factor: Float, focusX: Float, focusY: Float) {
        val newZoom = (zoom * factor).coerceIn(1f, 8f)
        val ratio = newZoom / zoom
        offsetX = focusX - (focusX - offsetX) * ratio
        offsetY = focusY - (focusY - offsetY) * ratio
        zoom = newZoom
        constrain()
    }

    fun pan(dx: Float, dy: Float) {
        offsetX += dx
        offsetY += dy
        constrain()
    }

    fun imageX(viewX: Float): Float = (viewX - offsetX) / scale
    fun imageY(viewY: Float): Float = (viewY - offsetY) / scale

    data class Region(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top

        /** Intersect overlapping boxes; bring fully offscreen boxes back to the nearest edge. */
        fun constrain(box: Region): Region {
            fun axis(start: Float, end: Float, low: Float, high: Float): Pair<Float, Float> {
                val size = (end - start).coerceIn(0f, high - low)
                return when {
                    end <= low -> low to low + size
                    start >= high -> high - size to high
                    else -> maxOf(start, low) to minOf(end, high)
                }
            }
            val (left, right) = axis(box.left, box.right, this.left, this.right)
            val (top, bottom) = axis(box.top, box.bottom, this.top, this.bottom)
            return Region(left, top, right, bottom)
        }

        fun fromLocal(box: Region) = Region(box.left + left, box.top + top, box.right + left, box.bottom + top)
    }

    fun visibleRegion() = Region(
        imageX(0f).coerceIn(0f, imageWidth),
        imageY(0f).coerceIn(0f, imageHeight),
        imageX(viewWidth).coerceIn(0f, imageWidth),
        imageY(viewHeight).coerceIn(0f, imageHeight)
    )

    private fun constrain() {
        val width = imageWidth * scale
        val height = imageHeight * scale
        offsetX = if (width <= viewWidth) (viewWidth - width) / 2f
            else offsetX.coerceIn(viewWidth - width, 0f)
        offsetY = if (height <= viewHeight) (viewHeight - height) / 2f
            else offsetY.coerceIn(viewHeight - height, 0f)
    }
}

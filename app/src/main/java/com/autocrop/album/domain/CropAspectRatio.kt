package com.autocrop.album.domain

/** Ratio limits apply equally to landscape and portrait crops. */
internal object CropAspectRatio {
    private const val MAX_LONG_TO_SHORT = 20.0 / 9.0
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** Expand the short side, then uniformly fit and shift inside the available image/window. */
    fun fit(rect: Box, bounds: Box): Box {
        val availableWidth = (bounds.right - bounds.left).toDouble()
        val availableHeight = (bounds.bottom - bounds.top).toDouble()
        require(availableWidth > 0 && availableHeight > 0)
        var width = (rect.right.toDouble() - rect.left).coerceAtLeast(0.001)
        var height = (rect.bottom.toDouble() - rect.top).coerceAtLeast(0.001)
        if (width > height * MAX_LONG_TO_SHORT) height = width / MAX_LONG_TO_SHORT
        if (height > width * MAX_LONG_TO_SHORT) width = height / MAX_LONG_TO_SHORT
        val scale = minOf(1.0, availableWidth / width, availableHeight / height)
        width *= scale
        height *= scale
        val centerX = (rect.left.toDouble() + rect.right) / 2
        val centerY = (rect.top.toDouble() + rect.bottom) / 2
        val left = (centerX - width / 2).coerceIn(bounds.left.toDouble(),
            maxOf(bounds.left.toDouble(), bounds.right - width))
        val top = (centerY - height / 2).coerceIn(bounds.top.toDouble(),
            maxOf(bounds.top.toDouble(), bounds.bottom - height))
        return Box(left.toFloat(), top.toFloat(), (left + width).toFloat(), (top + height).toFloat())
    }
}

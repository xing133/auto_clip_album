package com.autocrop.album.domain

import kotlin.math.ceil
import kotlin.math.floor

/** Ratio limits apply equally to landscape and portrait crops. */
internal object CropAspectRatio {
    const val SHORT_SIDE = 9
    const val DEFAULT_NUMERATOR = 20
    const val MIN_NUMERATOR = SHORT_SIDE
    const val MAX_NUMERATOR = 1000
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)
    data class Pixels(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /** Expand the short side, then uniformly fit and shift inside the available image/window. */
    fun fit(rect: Box, bounds: Box, numerator: Int = DEFAULT_NUMERATOR): Box {
        require(numerator in MIN_NUMERATOR..MAX_NUMERATOR)
        val maxLongToShort = numerator.toDouble() / SHORT_SIDE
        val availableWidth = (bounds.right - bounds.left).toDouble()
        val availableHeight = (bounds.bottom - bounds.top).toDouble()
        require(availableWidth > 0 && availableHeight > 0)
        var width = (rect.right.toDouble() - rect.left).coerceAtLeast(0.001)
        var height = (rect.bottom.toDouble() - rect.top).coerceAtLeast(0.001)
        if (width > height * maxLongToShort) height = width / maxLongToShort
        if (height > width * maxLongToShort) width = height / maxLongToShort
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

    /** Pixel rounding must not break the ratio or include pixels outside the available content. */
    fun fitPixels(rect: Box, bounds: Box, numerator: Int = DEFAULT_NUMERATOR): Pixels? {
        val leftBound = ceil(bounds.left.toDouble()).toInt()
        val topBound = ceil(bounds.top.toDouble()).toInt()
        val rightBound = floor(bounds.right.toDouble()).toInt()
        val bottomBound = floor(bounds.bottom.toDouble()).toInt()
        if (rightBound <= leftBound || bottomBound <= topBound) return null
        val fitted = fit(rect, Box(leftBound.toFloat(), topBound.toFloat(), rightBound.toFloat(), bottomBound.toFloat()), numerator)
        val left = ceil(fitted.left.toDouble()).toInt().coerceIn(leftBound, rightBound - 1)
        val top = ceil(fitted.top.toDouble()).toInt().coerceIn(topBound, bottomBound - 1)
        val right = floor(fitted.right.toDouble()).toInt().coerceIn(left + 1, rightBound)
        val bottom = floor(fitted.bottom.toDouble()).toInt().coerceIn(top + 1, bottomBound)
        var width = right - left
        var height = bottom - top
        if (width.toLong() * SHORT_SIDE > height.toLong() * numerator) width = (height.toLong() * numerator / SHORT_SIDE).toInt()
        if (height.toLong() * SHORT_SIDE > width.toLong() * numerator) height = (width.toLong() * numerator / SHORT_SIDE).toInt()
        val x = left + (right - left - width) / 2
        val y = top + (bottom - top - height) / 2
        return Pixels(x, y, x + width, y + height)
    }
}

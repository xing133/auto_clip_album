package com.autocrop.album.domain

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/** Binary mask in full preview-image coordinates. History groups one stroke/selection per undo. */
class EditableMask(val width: Int, val height: Int) {
    private var pixels = ByteArray(width * height)
    private val history = ArrayDeque<ByteArray>()
    private var strokeStart: ByteArray? = null
    init { require(width > 0 && height > 0) }
    val hasSelection: Boolean get() = pixels.any { it.toInt() != 0 }
    val canUndo: Boolean get() = history.isNotEmpty()
    fun copyPixels(): ByteArray = pixels.copyOf()
    fun selected(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height && pixels[y * width + x].toInt() != 0
    private fun remember(before: ByteArray) {
        if (!before.contentEquals(pixels)) {
            if (history.size >= 12) history.removeFirst()
            history.addLast(before)
        }
    }
    fun addSelection(mask: ByteArray) {
        require(mask.size == pixels.size)
        val before = pixels.copyOf()
        for (i in pixels.indices) if (mask[i].toInt() != 0) pixels[i] = 1
        remember(before)
    }
    fun beginStroke() { check(strokeStart == null); strokeStart = pixels.copyOf() }
    fun paintLine(x0: Float, y0: Float, x1: Float, y1: Float, radius: Float, add: Boolean) {
        require(radius.isFinite() && radius > 0)
        val steps = ceil(hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()) / (radius * 0.4f).coerceAtLeast(1f)).toInt().coerceAtLeast(1)
        for (step in 0..steps) {
            val t = step.toFloat() / steps
            val x = x0 + (x1 - x0) * t; val y = y0 + (y1 - y0) * t
            val left = floor(x - radius).toInt().coerceAtLeast(0)
            val right = ceil(x + radius).toInt().coerceAtMost(width - 1)
            val top = floor(y - radius).toInt().coerceAtLeast(0)
            val bottom = ceil(y + radius).toInt().coerceAtMost(height - 1)
            for (py in top..bottom) for (px in left..right) {
                val dx = px + 0.5f - x; val dy = py + 0.5f - y
                if (dx * dx + dy * dy <= radius * radius) pixels[py * width + px] = if (add) 1 else 0
            }
        }
    }
    fun endStroke() { strokeStart?.let { remember(it) }; strokeStart = null }
    fun cancelStroke() { strokeStart?.let { pixels = it }; strokeStart = null }
    fun undo(): Boolean {
        cancelStroke()
        if (history.isEmpty()) return false
        pixels = history.removeLast()
        return true
    }
}

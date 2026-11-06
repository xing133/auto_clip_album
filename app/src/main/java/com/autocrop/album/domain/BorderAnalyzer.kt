package com.autocrop.album.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pixel-only border analysis: independent of Android, so detection can be regression tested.
 *
 * A border is accepted only when all three hold:
 *  1. an edge runs across the image at the proposed depth (a real, straight boundary),
 *  2. everything outside it is neutral padding (black/grey/white, sparse UI allowed),
 *  3. the strip just inside it carries real picture detail (many colours).
 * The innermost accepted edge wins, so stacked padding (e.g. navigation bar + control bar)
 * is removed as one block instead of stopping at the first inner divider.
 */
internal object BorderAnalyzer {
    /** Neutral padding is black, grey or white; a saturated strip is content, e.g. sky. */
    private const val NEUTRAL_TOLERANCE = 18
    private const val MAX_BORDER_RATIO = 0.4f
    /** Ignore side-mounted controls when judging a horizontal band. */
    private const val EDGE_INSET_RATIO = 0.15f

    /** Pixels this far apart mean the boundary really changes colour. */
    private const val JUMP_DISTANCE = 12
    /** An edge only counts when it spans the image, not when it is a local detail. */
    private const val MIN_JUMP_FRACTION = 0.25f
    private const val MIN_MEAN_JUMP = 18f
    /** Neutral share of the whole outer region, sparse icons included. */
    private const val MIN_OUTSIDE_NEUTRAL = 0.90f
    /** Real content carries many colours; toolbars and navigation bars do not. */
    private const val MIN_INSIDE_COLORS = 12
    private const val INSIDE_SUPPORT_RATIO = 0.06f
    private const val MIN_TRIM_RATIO = 0.008f

    data class Insets(val top: Int = 0, val bottom: Int = 0, val left: Int = 0, val right: Int = 0)

    fun detect(pixels: IntArray, width: Int, height: Int): Insets {
        require(width > 0 && height > 0 && pixels.size == width * height)
        if (width < 16 || height < 16) return Insets()
        return Insets(
            scan(pixels, width, height, horizontal = true, reverse = false),
            scan(pixels, width, height, horizontal = true, reverse = true),
            scan(pixels, width, height, horizontal = false, reverse = false),
            scan(pixels, width, height, horizontal = false, reverse = true)
        )
    }

    private fun red(pixel: Int) = pixel ushr 16 and 255
    private fun green(pixel: Int) = pixel ushr 8 and 255
    private fun blue(pixel: Int) = pixel and 255

    private fun distance(a: Int, b: Int) =
        maxOf(abs(red(a) - red(b)), abs(green(a) - green(b)), abs(blue(a) - blue(b)))

    private fun isNeutral(pixel: Int): Boolean {
        val r = red(pixel)
        val g = green(pixel)
        val b = blue(pixel)
        return maxOf(r, g, b) - minOf(r, g, b) <= NEUTRAL_TOLERANCE
    }

    private fun scan(pixels: IntArray, width: Int, height: Int, horizontal: Boolean, reverse: Boolean): Int {
        val depth = if (horizontal) height else width
        val span = if (horizontal) width else height
        val inset = (span * EDGE_INSET_RATIO).toInt()
        val start = inset
        val end = span - inset
        val sample = end - start
        if (sample < 8) return 0

        fun at(d: Int, p: Int): Int {
            val position = if (reverse) depth - d - 1 else d
            return if (horizontal) pixels[position * width + p] else pixels[p * width + position]
        }

        val maxTrim = (depth * MAX_BORDER_RATIO).toInt().coerceAtLeast(2)
        val support = max(6, (depth * INSIDE_SUPPORT_RATIO).toInt())
        val scanDepth = min(depth, maxTrim + support + 2)

        val jumpFraction = FloatArray(scanDepth)
        val meanJump = FloatArray(scanDepth)
        val neutralFraction = FloatArray(scanDepth)
        val colorCount = IntArray(scanDepth)
        val seen = IntArray(4096)

        for (d in 0 until scanDepth) {
            seen.fill(0)
            var distinct = 0
            var neutral = 0
            for (p in start until end) {
                val c = at(d, p)
                if (isNeutral(c)) neutral++
                val key = (red(c) shr 4 shl 8) or (green(c) shr 4 shl 4) or (blue(c) shr 4)
                if (seen[key] == 0) {
                    seen[key] = 1
                    distinct++
                }
            }
            colorCount[d] = distinct
            neutralFraction[d] = neutral.toFloat() / sample
        }

        for (d in 1 until scanDepth) {
            var jumped = 0
            var total = 0
            for (p in start until end) {
                val delta = distance(at(d, p), at(d - 1, p))
                total += delta
                if (delta >= JUMP_DISTANCE) jumped++
            }
            jumpFraction[d] = jumped.toFloat() / sample
            meanJump[d] = total.toFloat() / sample
        }

        val neutralPrefix = DoubleArray(scanDepth + 1)
        for (d in 0 until scanDepth) neutralPrefix[d + 1] = neutralPrefix[d] + neutralFraction[d]

        val minTrim = max(2, (depth * MIN_TRIM_RATIO).toInt())
        val lastTrim = min(maxTrim, scanDepth - 1)
        for (d in minTrim..lastTrim) {
            if (jumpFraction[d] < MIN_JUMP_FRACTION || meanJump[d] < MIN_MEAN_JUMP) continue
            if (neutralPrefix[d + 1] / (d + 1) < MIN_OUTSIDE_NEUTRAL) continue
            val insideEnd = min(scanDepth, d + support)
            if (insideEnd - d < 3) continue
            val colors = IntArray(insideEnd - d) { colorCount[d + it] }
            colors.sort()
            if (colors[colors.size / 2] < MIN_INSIDE_COLORS) continue
            // First edge that starts real content; snap to the sharpest row nearby (scaled bitmaps blur it).
            var boundary = d
            for (k in max(minTrim, d - 1)..min(lastTrim, d + 1)) {
                if (meanJump[k] > meanJump[boundary]) boundary = k
            }
            return boundary
        }
        return 0
    }
}

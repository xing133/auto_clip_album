package com.autocrop.album.domain

import kotlin.math.abs
import kotlin.math.max

/** Pixel-only border analysis: independent of Android, so detection can be regression tested. */
internal object BorderAnalyzer {
    private const val BACKGROUND_TOLERANCE = 12
    private const val MAX_BORDER_RATIO = 0.4f

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
    private fun distance(a: Int, b: Int) = maxOf(abs(red(a) - red(b)), abs(green(a) - green(b)), abs(blue(a) - blue(b)))

    private fun scan(pixels: IntArray, width: Int, height: Int, horizontal: Boolean, reverse: Boolean): Int {
        val depth = if (horizontal) height else width
        val span = if (horizontal) width else height
        // Exclude corners and side-mounted controls when judging a horizontal/vertical band.
        val start = (span * 0.15f).toInt()
        val end = span - start
        fun pixel(d: Int, p: Int): Int {
            val position = if (reverse) depth - d - 1 else d
            return if (horizontal) pixels[position * width + p] else pixels[p * width + position]
        }

        // A quantized mode resists text/icons; averaging the strip would mix UI into the background.
        val histogram = IntArray(4096)
        val seedDepth = max(2, (depth * 0.025f).toInt())
        for (d in 0 until seedDepth) for (p in start until end) {
            val c = pixel(d, p)
            histogram[(red(c) shr 4 shl 8) or (green(c) shr 4 shl 4) or (blue(c) shr 4)]++
        }
        val bin = histogram.indices.maxByOrNull { histogram[it] } ?: return 0
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0
        for (d in 0 until seedDepth) for (p in start until end) {
            val c = pixel(d, p)
            val key = (red(c) shr 4 shl 8) or (green(c) shr 4 shl 4) or (blue(c) shr 4)
            if (key == bin) {
                r += red(c); g += green(c); b += blue(c); count++
            }
        }
        if (count == 0) return 0
        val background = ((r / count).toInt() shl 16) or ((g / count).toInt() shl 8) or (b / count).toInt()
        // Deliberately limit this path to neutral UI backgrounds, rather than treating foliage/sky as padding.
        if (maxOf(red(background), green(background), blue(background)) -
            minOf(red(background), green(background), blue(background)) > 18) return 0

        val maxTrim = (depth * MAX_BORDER_RATIO).toInt()
        val support = max(6, (depth * 0.08f).toInt())
        val analysisDepth = minOf(depth, maxTrim + support + 3)
        val fraction = FloatArray(analysisDepth)
        val prefix = FloatArray(analysisDepth + 1)
        for (d in 0 until analysisDepth) {
            var matching = 0
            for (p in start until end) if (distance(pixel(d, p), background) <= BACKGROUND_TOLERANCE) matching++
            fraction[d] = matching.toFloat() / (end - start)
            prefix[d + 1] = prefix[d] + fraction[d]
        }
        if (prefix[seedDepth] / seedDepth < 0.60f) return 0

        val minTrim = max(3, (depth * 0.02f).toInt())
        var boundary = 0
        for (d in minTrim..maxTrim) {
            // Most of the proposed outer region must still be the background, with room for UI islands.
            if (prefix[d] / d < 0.65f || fraction[d] > 0.80f) continue
            val before = max(0, d - 3)
            val outsideMean = (prefix[d] - prefix[before]) / (d - before)
            if (outsideMean < 0.65f || outsideMean - fraction[d] < 0.10f) continue
            val insideEnd = minOf(analysisDepth, d + support)
            val insideMean = (prefix[insideEnd] - prefix[d]) / (insideEnd - d)
            if (insideMean > 0.55f) continue
            var contentRows = 0
            for (i in d until insideEnd) if (fraction[i] <= 0.62f) contentRows++
            if (contentRows.toFloat() / (insideEnd - d) < 0.70f) continue
            // Require an actual sharp transition: a gradual grey gradient is not a border.
            var changed = 0
            for (p in start until end) {
                if (distance(pixel(before, p), pixel(minOf(d + 1, analysisDepth - 1), p)) >= 12) changed++
            }
            if (changed.toFloat() / (end - start) < 0.25f) continue
            // A later transition can be the photo after a toolbar or reply box in the margin.
            if (boundary == 0 || d - boundary > 3) boundary = d
        }
        return boundary
    }
}

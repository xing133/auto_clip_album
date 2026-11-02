package com.autocrop.album.domain

import kotlin.math.floor
import kotlin.math.max

/** Connected foreground regions in a category mask. Pixel bounds use exclusive right/bottom edges. */
class SegmentationRegions private constructor(
    val width: Int,
    val height: Int,
    private val regionIds: IntArray,
    val regions: List<Region>
) {
    data class Region(val id: Int, val category: Int, val left: Int, val top: Int,
                      val right: Int, val bottom: Int, val pixelCount: Int)
    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

    fun regionAt(x: Float, y: Float): Region? {
        if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0 || x >= width || y >= height) return null
        val id = regionIds[floor(y).toInt() * width + floor(x).toInt()]
        return if (id > 0) regions[id - 1] else null
    }

    fun boundsInImage(region: Region, image: Bounds): Bounds = Bounds(
        image.left + region.left * (image.right - image.left) / width,
        image.top + region.top * (image.bottom - image.top) / height,
        image.left + region.right * (image.right - image.left) / width,
        image.top + region.bottom * (image.bottom - image.top) / height
    )

    fun regionAtImage(x: Float, y: Float, image: Bounds): Region? {
        if (image.right <= image.left || image.bottom <= image.top) return null
        return regionAt((x - image.left) * width / (image.right - image.left),
            (y - image.top) * height / (image.bottom - image.top))
    }

    fun colorPixels(palette: IntArray): IntArray {
        require(palette.isNotEmpty())
        return IntArray(regionIds.size) { index ->
            val id = regionIds[index]
            if (id == 0) 0 else palette[(id - 1) % palette.size]
        }
    }

    companion object {
        /** Ignore background (category zero) and tiny speckles. Separate disconnected same-class objects. */
        fun fromCategories(categories: ByteArray, width: Int, height: Int,
                           minPixels: Int = max(16, categories.size / 2000)): SegmentationRegions {
            require(width > 0 && height > 0 && width.toLong() * height == categories.size.toLong())
            require(minPixels > 0)
            val ids = IntArray(categories.size)
            val queue = IntArray(categories.size)
            val regions = mutableListOf<Region>()
            for (start in categories.indices) {
                val category = categories[start].toInt() and 255
                if (category == 0 || ids[start] != 0) continue
                var head = 0
                var tail = 1
                queue[0] = start
                ids[start] = -1
                var left = width
                var top = height
                var right = 0
                var bottom = 0
                fun enqueue(index: Int) {
                    if (ids[index] == 0 && categories[index] == categories[start]) {
                        ids[index] = -1
                        queue[tail++] = index
                    }
                }
                while (head < tail) {
                    val index = queue[head++]
                    val x = index % width
                    val y = index / width
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x + 1)
                    bottom = maxOf(bottom, y + 1)
                    if (x > 0) enqueue(index - 1)
                    if (x + 1 < width) enqueue(index + 1)
                    if (y > 0) enqueue(index - width)
                    if (y + 1 < height) enqueue(index + width)
                }
                if (tail >= minPixels) {
                    val id = regions.size + 1
                    for (i in 0 until tail) ids[queue[i]] = id
                    regions.add(Region(id, category, left, top, right, bottom, tail))
                }
            }
            for (i in ids.indices) if (ids[i] < 0) ids[i] = 0
            return SegmentationRegions(width, height, ids, regions)
        }
    }
}

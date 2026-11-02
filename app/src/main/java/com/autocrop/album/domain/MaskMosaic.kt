package com.autocrop.album.domain

/** Pixel-center mask mapping is shared by the preview and full-resolution export. */
object MaskMosaic {
    fun selected(mask: ByteArray, maskWidth: Int, maskHeight: Int, x: Int, y: Int, width: Int, height: Int): Boolean {
        val mx = (((x.toLong() * 2 + 1) * maskWidth) / (width.toLong() * 2)).toInt().coerceIn(0, maskWidth - 1)
        val my = (((y.toLong() * 2 + 1) * maskHeight) / (height.toLong() * 2)).toInt().coerceIn(0, maskHeight - 1)
        return mask[my * maskWidth + mx].toInt() != 0
    }
    fun renderTile(pixels: IntArray, tileWidth: Int, tileHeight: Int, x0: Int, y0: Int,
                   width: Int, height: Int, mask: ByteArray, maskWidth: Int, maskHeight: Int) {
        val count = tileWidth * tileHeight
        var red = 0L; var green = 0L; var blue = 0L
        for (i in 0 until count) { val p = pixels[i]; red += p ushr 16 and 255; green += p ushr 8 and 255; blue += p and 255 }
        val color = (255 shl 24) or ((red / count).toInt() shl 16) or ((green / count).toInt() shl 8) or (blue / count).toInt()
        for (y in 0 until tileHeight) for (x in 0 until tileWidth) {
            val i = y * tileWidth + x
            if (selected(mask, maskWidth, maskHeight, x0 + x, y0 + y, width, height)) {
                pixels[i] = (pixels[i] and 0xff000000.toInt()) or (color and 0x00ffffff)
            }
        }
    }
}

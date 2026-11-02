package com.autocrop.album.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class BorderAnalyzerTest {
    private val w = 240
    private val h = 360
    private val expected = BorderAnalyzer.Insets(70, 90, 24, 36)
    private fun photo(background: Int): IntArray = IntArray(w * h) { index ->
        val x = index % w
        val y = index / w
        if (x < 24 || x >= w - 36 || y < 70 || y >= h - 90) background
        else {
            val r = 40 + (x * 13 + y * 17) % 170
            val g = 45 + (x * 7 + y * 11) % 160
            val b = 50 + (x * 3 + y * 19) % 150
            (r shl 16) or (g shl 8) or b
        }
    }
    private fun fill(p: IntArray, left: Int, top: Int, right: Int, bottom: Int, color: Int) {
        for (y in top until bottom) for (x in left until right) p[y * w + x] = color
    }

    @Test fun pureBlackBorders() { assertEquals(expected, BorderAnalyzer.detect(photo(0), w, h)) }
    @Test fun whiteBorders() { assertEquals(expected, BorderAnalyzer.detect(photo(0xffffff), w, h)) }
    @Test fun middleGreyBorders() { assertEquals(expected, BorderAnalyzer.detect(photo(0x808080), w, h)) }
    @Test fun lightGreyBorders() { assertEquals(expected, BorderAnalyzer.detect(photo(0xe8e8e8), w, h)) }

    @Test fun blackBordersWithTextIconsAndWideReplyBox() {
        val p = photo(0)
        for (x in 12 until 210 step 18) fill(p, x, 7, x + 7, 15, 0xffffff)
        fill(p, 10, 23, 18, 34, 0xffffff)
        fill(p, 8, 321, 232, 344, 0x333333)
        for (x in 12 until 160 step 14) fill(p, x, 288, x + 5, 298, 0xffffff)
        fill(p, 212, 300, 228, 310, 0xffffff)
        assertEquals(expected, BorderAnalyzer.detect(p, w, h))
    }

    @Test fun whiteBordersWithDarkUi() {
        val p = photo(0xffffff)
        for (x in 12 until 210 step 18) fill(p, x, 7, x + 7, 15, 0x202020)
        fill(p, 8, 321, 232, 344, 0xdddddd)
        for (x in 12 until 160 step 14) fill(p, x, 288, x + 5, 298, 0x222222)
        assertEquals(expected, BorderAnalyzer.detect(p, w, h))
    }

    @Test fun greyBordersWithWhiteTextAndDarkButtons() {
        val p = photo(0x808080)
        for (x in 12 until 210 step 18) fill(p, x, 7, x + 7, 15, 0xffffff)
        fill(p, 8, 321, 232, 344, 0x404040)
        assertEquals(expected, BorderAnalyzer.detect(p, w, h))
    }

    @Test fun eachSideCanHaveADifferentBackgroundColor() {
        val p = photo(0)
        fill(p, 0, h - 90, w, h, 0xffffff)
        fill(p, 0, 70, 24, h - 90, 0x808080)
        fill(p, w - 36, 70, w, h - 90, 0xe8e8e8)
        assertEquals(expected, BorderAnalyzer.detect(p, w, h))
    }

    @Test fun greyBordersWithCompressionNoise() {
        val p = photo(0x808080)
        for (i in p.indices) if (p[i] == 0x808080) {
            val v = 125 + (i * 13 % 7)
            p[i] = (v shl 16) or (v shl 8) or v
        }
        assertEquals(expected, BorderAnalyzer.detect(p, w, h))
    }

    @Test fun darkPhotoBeginningWithMostlyBlackPixelsKeepsItsBoundary() {
        val p = photo(0)
        // A dark scene can still resemble its black frame along much of the first content row.
        fill(p, 24, 70, 140, 79, 0x080808)
        assertEquals(expected, BorderAnalyzer.detect(p, w, h))
    }

    @Test fun aThinSeparatorIsNotAContentBoundary() {
        val p = photo(0x808080)
        fill(p, 0, 22, w, 23, 0xffffff)
        assertEquals(expected, BorderAnalyzer.detect(p, w, h))
    }

    @Test fun uniformImagesAreNotBorders() {
        for (color in listOf(0, 0x808080, 0xffffff))
            assertEquals(BorderAnalyzer.Insets(), BorderAnalyzer.detect(IntArray(w * h) { color }, w, h))
    }

    @Test fun gentleGreyGradientIsNotABorder() {
        val p = IntArray(w * h) { i ->
            val v = 40 + (i / w) * 150 / h
            (v shl 16) or (v shl 8) or v
        }
        assertEquals(BorderAnalyzer.Insets(), BorderAnalyzer.detect(p, w, h))
    }

    @Test fun texturedEdgeToEdgePhotoIsNotTrimmed() {
        val p = IntArray(w * h) { i -> ((i * 31 % 210 + 20) shl 16) or ((i * 17 % 200 + 20) shl 8) or (i * 7 % 190 + 20) }
        assertEquals(BorderAnalyzer.Insets(), BorderAnalyzer.detect(p, w, h))
    }

    @Test fun blueSkyLikeBackgroundIsNotNeutralPadding() {
        assertEquals(BorderAnalyzer.Insets(), BorderAnalyzer.detect(photo(0x70b0e0), w, h))
    }

    @Test fun tinyImagesAreUnchanged() {
        assertEquals(BorderAnalyzer.Insets(), BorderAnalyzer.detect(IntArray(9) { 0 }, 3, 3))
    }
}

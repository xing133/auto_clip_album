package com.autocrop.album.domain

import org.junit.Assert.*
import org.junit.Test

class SegmentationRegionsTest {
    private fun mask(vararg rows: String): SegmentationRegions {
        val bytes = rows.joinToString("").map { (it - '0').toByte() }.toByteArray()
        return SegmentationRegions.fromCategories(bytes, rows[0].length, rows.size, minPixels = 1)
    }

    @Test fun rectangleBoundsIncludeLastPixelButExcludeNextPixel() {
        val result = mask("00000", "01110", "01110", "00000")
        assertEquals(SegmentationRegions.Region(1, 1, 1, 1, 4, 3, 6), result.regions.single())
        assertEquals(result.regions[0], result.regionAt(3.99f, 2.99f))
        assertNull(result.regionAt(4f, 3f))
    }

    @Test fun disconnectedSameClassObjectsRemainSeparatelySelectable() {
        val result = mask("11011", "11011")
        assertEquals(2, result.regions.size)
        assertNotEquals(result.regionAt(0f, 0f), result.regionAt(4f, 0f))
        assertEquals(2, result.regions[0].right)
        assertEquals(3, result.regions[1].left)
    }

    @Test fun adjacentDifferentClassesDoNotMerge() {
        val result = mask("1122", "1122")
        assertEquals(listOf(1, 2), result.regions.map { it.category })
        assertEquals(listOf(4, 4), result.regions.map { it.pixelCount })
    }

    @Test fun diagonalPixelsDoNotConnectOrWrapAcrossRows() {
        val result = mask("001", "010", "100")
        assertEquals(3, result.regions.size)
        assertEquals(1, result.regions[0].pixelCount)
    }

    @Test fun backgroundIsTransparentAndNotSelectable() {
        val result = mask("000", "000")
        assertTrue(result.regions.isEmpty())
        assertNull(result.regionAt(1f, 1f))
        assertArrayEquals(IntArray(6), result.colorPixels(intArrayOf(0x70123456)))
    }

    @Test fun rejectsTinySpecklesWithoutJoiningUnrelatedRegions() {
        val result = SegmentationRegions.fromCategories(byteArrayOf(1, 0, 1, 1, 1, 0), 6, 1, minPixels = 2)
        assertEquals(1, result.regions.size)
        assertNull(result.regionAt(0f, 0f))
        assertEquals(2, result.regions.single().left)
    }

    @Test fun maskCoordinatesMapToZoomedWindowAndOriginalImage() {
        val result = mask("0000", "0110", "0110", "0000")
        val window = SegmentationRegions.Bounds(100f, 200f, 300f, 600f)
        val selected = result.regionAtImage(175f, 350f, window)!!
        val bounds = result.boundsInImage(selected, window)
        assertEquals(SegmentationRegions.Bounds(150f, 300f, 250f, 500f), bounds)
        // A 4x larger original gets (600,1200)-(1000,2000), independent of view zoom/pan.
        assertEquals(600f, bounds.left * 4f, 0.001f)
        assertEquals(2000f, bounds.bottom * 4f, 0.001f)
        assertNull(result.regionAtImage(301f, 350f, window))
    }

    @Test fun invalidTouchCoordinatesNeverSelectAnEdgePixel() {
        val result = mask("11", "11")
        for (coordinate in listOf(-0.01f, 2f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertNull(result.regionAt(coordinate, 1f))
            assertNull(result.regionAt(1f, coordinate))
        }
    }

    @Test fun categoriesAreUnsignedAndSourceMaskIsNotModified() {
        val source = byteArrayOf(-1, -1, 0)
        val result = SegmentationRegions.fromCategories(source, 3, 1, minPixels = 1)
        assertEquals(255, result.regions.single().category)
        assertArrayEquals(byteArrayOf(-1, -1, 0), source)
    }

    @Test fun paletteCyclesAcrossSeparateRegionsAndKeepsBackgroundClear() {
        val result = mask("10203")
        assertArrayEquals(intArrayOf(10, 0, 20, 0, 10), result.colorPixels(intArrayOf(10, 20)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedMaskIsRejected() { SegmentationRegions.fromCategories(byteArrayOf(1), 2, 2) }
}

package com.autocrop.album.domain

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class CropAspectRatioTest {
    private val bounds = CropAspectRatio.Box(0f, 0f, 1000f, 1000f)
    private fun valid(box: CropAspectRatio.Box, bounds: CropAspectRatio.Box = this.bounds) {
        val w = box.right - box.left
        val h = box.bottom - box.top
        assertTrue("$box", w > 0f && h > 0f)
        assertTrue("$box", w / h <= 20f / 9f + 0.0001f)
        assertTrue("$box", h / w <= 20f / 9f + 0.0001f)
        assertTrue(box.left >= bounds.left - 0.001f && box.right <= bounds.right + 0.001f)
        assertTrue(box.top >= bounds.top - 0.001f && box.bottom <= bounds.bottom + 0.001f)
    }

    @Test fun expandsThinPortraitWithoutCuttingItsSubject() {
        val source = CropAspectRatio.Box(450f, 100f, 500f, 900f)
        val result = CropAspectRatio.fit(source, bounds)
        valid(result)
        assertEquals(source.top, result.top, 0.001f)
        assertEquals(source.bottom, result.bottom, 0.001f)
        assertEquals(360f, result.right - result.left, 0.001f)
    }

    @Test fun expandsThinLandscape() {
        val result = CropAspectRatio.fit(CropAspectRatio.Box(100f, 450f, 900f, 500f), bounds)
        valid(result)
        assertEquals(360f, result.bottom - result.top, 0.001f)
    }

    @Test fun legalRatiosIncludingTwentyToNineRemainUnchanged() {
        for (box in listOf(CropAspectRatio.Box(10f, 10f, 210f, 100f),
            CropAspectRatio.Box(10f, 10f, 100f, 210f), CropAspectRatio.Box(100f, 100f, 500f, 500f))) {
            assertEquals(box, CropAspectRatio.fit(box, bounds))
        }
    }

    @Test fun nearImageEdgeShiftsExpansionInsideImage() {
        val source = CropAspectRatio.Box(0f, 100f, 20f, 900f)
        val result = CropAspectRatio.fit(source, bounds)
        valid(result)
        assertEquals(0f, result.left, 0.001f)
        assertEquals(360f, result.right, 0.001f)
    }

    @Test fun veryNarrowWindowStillHonorsRatioByReducingLongSide() {
        val window = CropAspectRatio.Box(400f, 0f, 500f, 1000f)
        val result = CropAspectRatio.fit(window, window)
        valid(result, window)
        assertEquals(100f * 20f / 9f, result.bottom - result.top, 0.001f)
    }

    @Test fun offscreenSliverAfterZoomIsExpandedInsideCurrentWindow() {
        val window = CropAspectRatio.Box(300f, 200f, 600f, 700f)
        val result = CropAspectRatio.fit(CropAspectRatio.Box(599f, 250f, 600f, 650f), window)
        valid(result, window)
        assertEquals(600f, result.right, 0.001f)
        assertEquals(180f, result.right - result.left, 0.001f)
    }

    @Test fun randomBoxesAndWindowSizesAlwaysRespectBothLimits() {
        val random = Random(20)
        repeat(2000) {
            val width = random.nextFloat() * 1900f + 100f
            val height = random.nextFloat() * 1900f + 100f
            val window = CropAspectRatio.Box(200f, 300f, 200f + width, 300f + height)
            val x = 200f + random.nextFloat() * width
            val y = 300f + random.nextFloat() * height
            val box = CropAspectRatio.Box(x, y, x + random.nextFloat() * width + 0.1f,
                y + random.nextFloat() * height + 0.1f)
            valid(CropAspectRatio.fit(box, window), window)
        }
    }
}

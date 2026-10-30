package com.autocrop.album.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageViewportTest {
    private fun viewport() = ImageViewport().apply { reset(1000f, 800f, 500f, 400f) }
    private fun equal(expected: Float, actual: Float) = assertEquals(expected, actual, 0.001f)

    @Test fun fitCentersPortraitImage() {
        val v = ImageViewport().apply { reset(400f, 800f, 500f, 400f) }
        equal(0.5f, v.scale)
        equal(150f, v.offsetX)
        equal(0f, v.offsetY)
    }

    @Test fun zoomKeepsImagePointUnderFingers() {
        val v = viewport()
        val x = v.imageX(200f)
        val y = v.imageY(150f)
        v.zoomAt(3f, 200f, 150f)
        equal(x, v.imageX(200f))
        equal(y, v.imageY(150f))
    }

    @Test fun panAndZoomRoundTripCropCoordinatesIncludingOffscreenEdges() {
        val v = viewport()
        v.zoomAt(4f, 250f, 200f)
        v.pan(-100f, 50f)
        // Inverse mapping must remain valid even before applying the window constraint.
        for (x in listOf(100f, 900f)) {
            val screen = x * v.scale + v.offsetX
            equal(x, v.imageX(screen))
            equal(x * 4f, v.imageX(screen) * 4000f / 1000f)
        }
        for (y in listOf(80f, 720f)) {
            equal(y, v.imageY(y * v.scale + v.offsetY))
        }
    }

    @Test fun dragUsesZoomedScaleRatherThanFitScale() {
        val v = viewport()
        v.zoomAt(4f, 250f, 200f)
        equal(20f, v.imageX(240f) - v.imageX(200f))
    }

    @Test fun panIsConstrainedToImageBounds() {
        val v = viewport()
        v.zoomAt(2f, 250f, 200f)
        v.pan(10000f, 10000f)
        equal(0f, v.offsetX)
        equal(0f, v.offsetY)
        v.pan(-10000f, -10000f)
        equal(-500f, v.offsetX)
        equal(-400f, v.offsetY)
    }

    @Test fun zoomOutRestoresFitAndZoomHasUpperBound() {
        val v = viewport()
        v.zoomAt(100f, 250f, 200f)
        equal(8f, v.zoom)
        v.zoomAt(0.001f, 250f, 200f)
        equal(1f, v.zoom)
        equal(0f, v.offsetX)
        equal(0f, v.offsetY)
    }

    @Test fun narrowImageStaysCenteredUntilItFillsViewport() {
        val v = ImageViewport().apply { reset(200f, 800f, 500f, 400f) }
        v.zoomAt(2f, 250f, 200f)
        v.pan(10000f, 0f)
        equal(150f, v.offsetX)
    }

    @Test fun newImageResetsPreviousZoomAndPan() {
        val v = viewport()
        v.zoomAt(5f, 200f, 150f)
        v.pan(-100f, -100f)
        v.reset(800f, 400f, 500f, 400f)
        equal(1f, v.zoom)
        equal(0f, v.offsetX)
        equal(75f, v.offsetY)
    }

    @Test fun visibleRegionExcludesScreenLetterboxing() {
        val v = ImageViewport().apply { reset(400f, 800f, 500f, 400f) }
        assertEquals(ImageViewport.Region(0f, 0f, 400f, 800f), v.visibleRegion())
    }

    @Test fun oversizedCropBecomesViewportBoundsAndMapsToOriginalPixels() {
        val v = viewport()
        v.zoomAt(4f, 250f, 200f)
        val crop = v.visibleRegion().constrain(ImageViewport.Region(0f, 0f, 1000f, 800f))
        assertEquals(ImageViewport.Region(375f, 300f, 625f, 500f), crop)
        equal(0f, crop.left * v.scale + v.offsetX)
        equal(500f, crop.right * v.scale + v.offsetX)
        equal(0f, crop.top * v.scale + v.offsetY)
        equal(400f, crop.bottom * v.scale + v.offsetY)
        // Saving a 4000x3200 original must crop to this window, not the whole image.
        equal(1500f, crop.left * 4f)
        equal(2500f, crop.right * 4f)
    }

    @Test fun partialOverflowOnlyClampsOverflowingEdges() {
        val bounds = ImageViewport.Region(100f, 200f, 400f, 600f)
        assertEquals(ImageViewport.Region(100f, 250f, 300f, 600f),
            bounds.constrain(ImageViewport.Region(0f, 250f, 300f, 700f)))
    }

    @Test fun completelyOffscreenCropRemainsEditableInsideWindow() {
        val bounds = ImageViewport.Region(300f, 200f, 600f, 500f)
        assertEquals(ImageViewport.Region(300f, 200f, 400f, 300f),
            bounds.constrain(ImageViewport.Region(0f, 0f, 100f, 100f)))
    }

    @Test fun windowDetectionCoordinatesReturnToPreviewAfterPanAndZoom() {
        val v = viewport()
        v.zoomAt(2f, 250f, 200f)
        v.pan(-100f, -50f)
        val window = v.visibleRegion()
        val detected = window.fromLocal(ImageViewport.Region(20f, 30f, 100f, 180f))
        equal(20f, detected.left * v.scale + v.offsetX)
        equal(30f, detected.top * v.scale + v.offsetY)
        equal(window.left + 100f, detected.right)
        equal(window.top + 180f, detected.bottom)
    }
}

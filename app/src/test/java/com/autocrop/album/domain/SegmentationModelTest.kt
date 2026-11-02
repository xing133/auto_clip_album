package com.autocrop.album.domain

import org.junit.Assert.*
import org.junit.Test

class SegmentationModelTest {
    @Test fun savedKeysRoundTripAndUnknownSettingsUsePortraitDefault() {
        for (model in SegmentationModel.values()) assertEquals(model, SegmentationModel.fromKey(model.key))
        assertEquals(SegmentationModel.MULTICLASS, SegmentationModel.fromKey(null))
        assertEquals(SegmentationModel.MULTICLASS, SegmentationModel.fromKey("removed-model"))
    }

    @Test fun multiclassHairFaceSkinAndClothesFormOnePersonRegion() {
        val mask = SegmentationModel.MULTICLASS.normalizeCategories(byteArrayOf(0, 1, 3, 2, 4, 5, 0))
        assertArrayEquals(byteArrayOf(0, 1, 1, 1, 1, 1, 0), mask)
        val regions = SegmentationRegions.fromCategories(mask, 7, 1, minPixels = 1)
        assertEquals(1, regions.regions.size)
        assertEquals(1, regions.regions.single().left)
        assertEquals(6, regions.regions.single().right)
    }

    @Test fun deeplabKeepsDistinctSemanticCategories() {
        val mask = SegmentationModel.DEEPLAB.normalizeCategories(byteArrayOf(0, 15, 15, 9, 9))
        assertArrayEquals(byteArrayOf(0, 15, 15, 9, 9), mask)
        assertEquals(2, SegmentationRegions.fromCategories(mask, 5, 1, minPixels = 1).regions.size)
    }

    @Test fun selfieConfidenceThresholdIsAppliedToItsSingleFloatChannel() {
        val scores = listOf(0f, 0.49f, 0.5f, 0.99f, 1f, Float.NaN)
        assertArrayEquals(byteArrayOf(0, 0, 1, 1, 1, 0),
            scores.map { SegmentationModel.personFromConfidence(it) }.toByteArray())
    }
}

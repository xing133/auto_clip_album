package com.autocrop.album.data.models

import org.junit.Assert.assertEquals
import org.junit.Test

class PageWindowTest {
    @Test fun ninetyThousandPhotosAreNinetyPages() {
        val page = PageWindow(90000, 1000, 11)
        assertEquals(90, page.pageCount)
        assertEquals(11000, page.offset)
        assertEquals(12000, page.endExclusive)
    }

    @Test fun lastPageContainsOnlyRemainingPhotos() {
        val page = PageWindow(89321, 1000, 89)
        assertEquals(90, page.pageCount)
        assertEquals(89000, page.offset)
        assertEquals(89321, page.endExclusive)
    }

    @Test fun emptyFolderHasNoPagesOrItems() {
        val page = PageWindow(0, 1000, 8)
        assertEquals(0, page.pageCount)
        assertEquals(0, page.pageIndex)
        assertEquals(0, page.offset)
        assertEquals(0, page.endExclusive)
    }

    @Test fun exactMultipleDoesNotCreateAnExtraPage() {
        assertEquals(2, PageWindow(2000, 1000, 0).pageCount)
    }

    @Test fun deletingLastPhotoOfLastPageClampsToPreviousPage() {
        assertEquals(2, PageWindow(2001, 1000, 2).pageIndex)
        val afterDeletion = PageWindow(2000, 1000, 2)
        assertEquals(1, afterDeletion.pageIndex)
        assertEquals(1000, afterDeletion.offset)
    }

    @Test fun outOfRangeRequestsAreClamped() {
        assertEquals(0, PageWindow(90000, 1000, -1).pageIndex)
        assertEquals(89, PageWindow(90000, 1000, Int.MAX_VALUE).pageIndex)
    }

    @Test fun pagesCoverEntireFolderWithoutGapsOrOverlap() {
        var previousEnd = 0
        for (index in 0 until 90) {
            val page = PageWindow(89321, 1000, index)
            assertEquals(previousEnd, page.offset)
            previousEnd = page.endExclusive
        }
        assertEquals(89321, previousEnd)
    }

    @Test fun largeCountsCannotOverflowEndOrPageCount() {
        val page = PageWindow(Int.MAX_VALUE, 1000, Int.MAX_VALUE)
        assertEquals(2147484, page.pageCount)
        assertEquals(2147483000, page.offset)
        assertEquals(Int.MAX_VALUE, page.endExclusive)
        assertEquals(Int.MAX_VALUE, PageWindow(Int.MAX_VALUE, 1, 0).pageCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectZeroPageSize() { PageWindow(90000, 0, 0) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectNegativeCount() { PageWindow(-1, 1000, 0) }
}

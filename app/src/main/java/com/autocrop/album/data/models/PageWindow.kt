package com.autocrop.album.data.models

/** Zero-based page arithmetic, including an empty collection and a deleted last page. */
data class PageWindow(val totalCount: Int, val pageSize: Int, val requestedPage: Int) {
    init {
        require(totalCount >= 0)
        require(pageSize > 0)
    }
    val pageCount: Int = if (totalCount == 0) 0 else (totalCount - 1) / pageSize + 1
    val pageIndex: Int = requestedPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    val offset: Int = pageIndex * pageSize
    val endExclusive: Int = minOf(offset.toLong() + pageSize, totalCount.toLong()).toInt()
}

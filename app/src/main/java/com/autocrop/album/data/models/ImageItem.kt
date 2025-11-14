package com.autocrop.album.data.models

import android.net.Uri
import java.util.Date

/**
 * 图片项数据模型
 */
data class ImageItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val dateAdded: Date,
    val dateModified: Date,
    val dateTaken: Date?,
    val size: Long,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val path: String
) {
    companion object {
        /**
         * 排序类型
         */
        enum class SortOrder {
            NAME_ASC,
            NAME_DESC,
            DATE_MODIFIED_ASC,
            DATE_MODIFIED_DESC,
            DATE_TAKEN_ASC,
            DATE_TAKEN_DESC
        }
    }
}

package com.autocrop.album.data

import android.net.Uri

/**
 * 用于在 Activity 间共享图片列表，避免通过 Intent 传递大量数据
 */
object ImageDataHolder {
    private var imageUris: List<Uri> = emptyList()

    fun setImageUris(uris: List<Uri>) {
        imageUris = uris
    }

    fun getImageUris(): List<Uri> {
        return imageUris
    }

    fun clear() {
        imageUris = emptyList()
    }
}

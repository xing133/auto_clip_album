package com.autocrop.album.data

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.autocrop.album.data.models.ImageItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Date

/**
 * MediaStore 数据访问层
 */
class MediaStoreRepository(private val context: Context) {

    sealed class SaveImageResult {
        data class Success(val uri: Uri) : SaveImageResult()
        data class RequiresPermission(val intentSender: IntentSender) : SaveImageResult()
        data class Error(val throwable: Throwable? = null) : SaveImageResult()
    }

    sealed class DeleteImageResult {
        object Success : DeleteImageResult()
        data class RequiresPermission(val intentSender: IntentSender) : DeleteImageResult()
        data class Error(val throwable: Throwable? = null) : DeleteImageResult()
    }

    private val contentResolver: ContentResolver = context.contentResolver

    /**
     * 查询所有可用的目录
     */
    suspend fun queryFolders(): List<String> = withContext(Dispatchers.IO) {
        val folders = mutableSetOf<String>()
        val projection = arrayOf(MediaStore.Images.Media.DATA)

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            while (cursor.moveToNext()) {
                val path = cursor.getString(dataColumn)
                path?.let {
                    val folder = File(it).parent
                    folder?.let { f -> folders.add(f) }
                }
            }
        }

        folders.sorted()
    }

    /**
     * 查询所有图片
     * @param folderPath 可选的目录路径，如果指定则只显示该目录下的图片
     */
    suspend fun queryImages(
        sortOrder: ImageItem.Companion.SortOrder = ImageItem.Companion.SortOrder.DATE_MODIFIED_DESC,
        folderPath: String? = null
    ): List<ImageItem> =
        withContext(Dispatchers.IO) {
            val images = mutableListOf<ImageItem>()
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
                MediaStore.Images.Media.DATA
            )

            val selection = folderPath?.let { "${MediaStore.Images.Media.DATA} LIKE ?" }
            val selectionArgs = folderPath?.let { arrayOf("$it/%") }
            val orderBy = when (sortOrder) {
                ImageItem.Companion.SortOrder.NAME_ASC -> "${MediaStore.Images.Media.DISPLAY_NAME} ASC"
                ImageItem.Companion.SortOrder.NAME_DESC -> "${MediaStore.Images.Media.DISPLAY_NAME} DESC"
                ImageItem.Companion.SortOrder.DATE_MODIFIED_ASC -> "${MediaStore.Images.Media.DATE_MODIFIED} ASC"
                ImageItem.Companion.SortOrder.DATE_MODIFIED_DESC -> "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
                ImageItem.Companion.SortOrder.DATE_TAKEN_ASC -> "${MediaStore.Images.Media.DATE_TAKEN} ASC"
                ImageItem.Companion.SortOrder.DATE_TAKEN_DESC -> "${MediaStore.Images.Media.DATE_TAKEN} DESC"
            }

            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                orderBy
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val mimeTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn)
                    val dateAdded = Date(cursor.getLong(dateAddedColumn) * 1000)
                    val dateModified = Date(cursor.getLong(dateModifiedColumn) * 1000)
                    val dateTaken = cursor.getLong(dateTakenColumn).let {
                        if (it > 0) Date(it) else null
                    }
                    val size = cursor.getLong(sizeColumn)
                    val mimeType = cursor.getString(mimeTypeColumn) ?: "image/jpeg"
                    val width = cursor.getInt(widthColumn)
                    val height = cursor.getInt(heightColumn)
                    val path = cursor.getString(dataColumn)

                    val uri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id.toString()
                    )

                    images.add(
                        ImageItem(
                            id = id,
                            uri = uri,
                            displayName = name,
                            dateAdded = dateAdded,
                            dateModified = dateModified,
                            dateTaken = dateTaken,
                            size = size,
                            mimeType = mimeType,
                            width = width,
                            height = height,
                            path = path
                        )
                    )
                }
            }

            images
        }

    /**
     * 保存裁剪后的图片
     * @param sourceUri 原图 URI
     * @param croppedBitmap 裁剪后的 Bitmap（由调用方提供）
     * @param overwriteOriginal 是否覆盖原图
     * @return 保存后的 URI，失败返回 null
     */
    suspend fun saveCroppedImage(
        sourceUri: Uri,
        outputFile: File,
        overwriteOriginal: Boolean
    ): SaveImageResult = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("MediaStoreRepository", "saveCroppedImage: sourceUri=$sourceUri, outputFile=${outputFile.absolutePath}, overwriteOriginal=$overwriteOriginal")
            android.util.Log.d("MediaStoreRepository", "saveCroppedImage: temp file exists=${outputFile.exists()}, size=${outputFile.length()}")

            if (overwriteOriginal) {
                android.util.Log.d("MediaStoreRepository", "saveCroppedImage: overwrite mode")
                android.util.Log.d("MediaStoreRepository", "saveCroppedImage: calling overwriteImageUri...")
                when (val result = overwriteImageUri(sourceUri, outputFile)) {
                    is OverwriteOutcome.Success -> {
                        android.util.Log.d("MediaStoreRepository", "saveCroppedImage: overwrite success")
                        // 不需要手动通知 MediaStore，ContentResolver 会自动处理
                        SaveImageResult.Success(sourceUri)
                    }
                    is OverwriteOutcome.RequiresPermission -> {
                        android.util.Log.d("MediaStoreRepository", "saveCroppedImage: requires permission")
                        SaveImageResult.RequiresPermission(result.intentSender)
                    }
                    is OverwriteOutcome.Failure -> {
                        android.util.Log.e("MediaStoreRepository", "saveCroppedImage: overwrite failed")
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                            // 只在旧版本 Android 上尝试直接文件操作
                            android.util.Log.d("MediaStoreRepository", "saveCroppedImage: trying legacy file copy")
                            try {
                                val sourcePath = getPathFromUri(sourceUri)
                                if (sourcePath != null) {
                                    val sourceFile = File(sourcePath)
                                    outputFile.copyTo(sourceFile, overwrite = true)
                                    notifyMediaStoreUpdated(sourceFile.absolutePath)
                                    SaveImageResult.Success(sourceUri)
                                } else {
                                    android.util.Log.e("MediaStoreRepository", "saveCroppedImage: failed to get path for legacy copy")
                                    SaveImageResult.Error(IOException("Unable to get file path"))
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("MediaStoreRepository", "saveCroppedImage: legacy copy failed", e)
                                SaveImageResult.Error(e)
                            }
                        } else {
                            android.util.Log.e("MediaStoreRepository", "saveCroppedImage: unable to overwrite without permission on Android Q+")
                            SaveImageResult.Error(IOException("Unable to overwrite image without permission"))
                        }
                    }
                }
            } else {
                android.util.Log.d("MediaStoreRepository", "saveCroppedImage: save as new file mode")
                // 新建文件
                val insertedUri = insertImageToMediaStore(outputFile)
                if (insertedUri != null) {
                    android.util.Log.d("MediaStoreRepository", "saveCroppedImage: insert success, uri=$insertedUri")
                    SaveImageResult.Success(insertedUri)
                } else {
                    android.util.Log.e("MediaStoreRepository", "saveCroppedImage: insert failed, returned null")
                    SaveImageResult.Error()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaStoreRepository", "saveCroppedImage: exception", e)
            e.printStackTrace()
            SaveImageResult.Error(e)
        }
    }

    private fun overwriteImageUri(targetUri: Uri, tempFile: File): OverwriteOutcome {
        return try {
            android.util.Log.d("MediaStoreRepository", "overwriteImageUri: targetUri=$targetUri, tempFile=${tempFile.absolutePath}")
            android.util.Log.d("MediaStoreRepository", "overwriteImageUri: temp file exists=${tempFile.exists()}, size=${tempFile.length()}")

            val outputStream = contentResolver.openOutputStream(targetUri, "wt")
            if (outputStream == null) {
                android.util.Log.e("MediaStoreRepository", "overwriteImageUri: failed to open output stream")
                return OverwriteOutcome.Failure
            }

            outputStream.use { out ->
                tempFile.inputStream().use { inputStream ->
                    val bytesCopied = inputStream.copyTo(out)
                    android.util.Log.d("MediaStoreRepository", "overwriteImageUri: copied $bytesCopied bytes")
                }
            }
            android.util.Log.d("MediaStoreRepository", "overwriteImageUri: success")
            OverwriteOutcome.Success
        } catch (e: RecoverableSecurityException) {
            android.util.Log.d("MediaStoreRepository", "overwriteImageUri: RecoverableSecurityException, requires permission")
            OverwriteOutcome.RequiresPermission(e.userAction.actionIntent.intentSender)
        } catch (e: Exception) {
            android.util.Log.e("MediaStoreRepository", "overwriteImageUri: exception", e)
            e.printStackTrace()
            OverwriteOutcome.Failure
        }
    }

    private fun notifyMediaStoreUpdated(path: String) {
        try {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI.also { uri ->
                contentResolver.update(
                    uri,
                    ContentValues(),
                    "${MediaStore.Images.Media.DATA}=?",
                    arrayOf(path)
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 将文件插入到 MediaStore
     */
    private fun insertImageToMediaStore(file: File): Uri? {
        try {
            android.util.Log.d("MediaStoreRepository", "insertImageToMediaStore: file=${file.absolutePath}")
            android.util.Log.d("MediaStoreRepository", "insertImageToMediaStore: file exists=${file.exists()}, size=${file.length()}")

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Images.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                put(MediaStore.Images.Media.SIZE, file.length())

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AutoCropAlbum")
                    android.util.Log.d("MediaStoreRepository", "insertImageToMediaStore: using RELATIVE_PATH (Android Q+)")
                } else {
                    put(MediaStore.Images.Media.DATA, file.absolutePath)
                    android.util.Log.d("MediaStoreRepository", "insertImageToMediaStore: using DATA path (legacy)")
                }
            }

            val insertedUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (insertedUri == null) {
                android.util.Log.e("MediaStoreRepository", "insertImageToMediaStore: insert returned null")
                return null
            }
            android.util.Log.d("MediaStoreRepository", "insertImageToMediaStore: inserted URI=$insertedUri")

            // 对于 Android 10+，需要写入文件内容
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.util.Log.d("MediaStoreRepository", "insertImageToMediaStore: copying file content to MediaStore")
                contentResolver.openOutputStream(insertedUri)?.use { outputStream ->
                    file.inputStream().use { inputStream ->
                        val bytesCopied = inputStream.copyTo(outputStream)
                        android.util.Log.d("MediaStoreRepository", "insertImageToMediaStore: copied $bytesCopied bytes")
                    }
                } ?: run {
                    android.util.Log.e("MediaStoreRepository", "insertImageToMediaStore: failed to open output stream")
                    return null
                }
            }

            android.util.Log.d("MediaStoreRepository", "insertImageToMediaStore: success")
            return insertedUri
        } catch (e: Exception) {
            android.util.Log.e("MediaStoreRepository", "insertImageToMediaStore: exception", e)
            e.printStackTrace()
            return null
        }
    }

    /**
     * 删除图片
     * @param uri 要删除的图片 URI
     * @return 删除结果
     */
    suspend fun deleteImage(uri: Uri): DeleteImageResult = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("MediaStoreRepository", "deleteImage: uri=$uri")

            val deletedRows = contentResolver.delete(uri, null, null)

            if (deletedRows > 0) {
                android.util.Log.d("MediaStoreRepository", "deleteImage: success, deleted $deletedRows rows")
                DeleteImageResult.Success
            } else {
                android.util.Log.e("MediaStoreRepository", "deleteImage: failed, no rows deleted")
                DeleteImageResult.Error(IOException("No rows deleted"))
            }
        } catch (e: RecoverableSecurityException) {
            android.util.Log.d("MediaStoreRepository", "deleteImage: RecoverableSecurityException, requires permission")
            DeleteImageResult.RequiresPermission(e.userAction.actionIntent.intentSender)
        } catch (e: Exception) {
            android.util.Log.e("MediaStoreRepository", "deleteImage: exception", e)
            DeleteImageResult.Error(e)
        }
    }

    /**
     * 从 URI 获取文件路径
     */
    private fun getPathFromUri(uri: Uri): String? {
        val projection = arrayOf(MediaStore.Images.Media.DATA)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            cursor.moveToFirst()
            return cursor.getString(columnIndex)
        }
        return null
    }

    private sealed class OverwriteOutcome {
        object Success : OverwriteOutcome()
        data class RequiresPermission(val intentSender: IntentSender) : OverwriteOutcome()
        object Failure : OverwriteOutcome()
    }
}

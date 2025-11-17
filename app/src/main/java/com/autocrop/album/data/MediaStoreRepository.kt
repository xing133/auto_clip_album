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

    private val contentResolver: ContentResolver = context.contentResolver

    /**
     * 查询所有图片
     */
    suspend fun queryImages(sortOrder: ImageItem.Companion.SortOrder = ImageItem.Companion.SortOrder.DATE_MODIFIED_DESC): List<ImageItem> =
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

            val selection = null
            val selectionArgs = null
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
            if (overwriteOriginal) {
                val sourcePath = getPathFromUri(sourceUri) ?: return@withContext SaveImageResult.Error()
                val sourceFile = File(sourcePath)
                when (val result = overwriteImageUri(sourceUri, outputFile)) {
                    is OverwriteOutcome.Success -> {
                        notifyMediaStoreUpdated(sourceFile.absolutePath)
                        SaveImageResult.Success(sourceUri)
                    }
                    is OverwriteOutcome.RequiresPermission -> SaveImageResult.RequiresPermission(result.intentSender)
                    is OverwriteOutcome.Failure -> {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                            outputFile.copyTo(sourceFile, overwrite = true)
                            notifyMediaStoreUpdated(sourceFile.absolutePath)
                            SaveImageResult.Success(sourceUri)
                        } else {
                            SaveImageResult.Error(IOException("Unable to overwrite image without permission"))
                        }
                    }
                }
            } else {
                // 新建文件
                insertImageToMediaStore(outputFile)?.let { insertedUri ->
                    SaveImageResult.Success(insertedUri)
                } ?: SaveImageResult.Error()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            SaveImageResult.Error(e)
        }
    }

    private fun overwriteImageUri(targetUri: Uri, tempFile: File): OverwriteOutcome {
        return try {
            contentResolver.openOutputStream(targetUri, "wt")?.use { outputStream ->
                tempFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: return OverwriteOutcome.Failure
            OverwriteOutcome.Success
        } catch (e: RecoverableSecurityException) {
            OverwriteOutcome.RequiresPermission(e.userAction.actionIntent.intentSender)
        } catch (e: Exception) {
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
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Images.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
            put(MediaStore.Images.Media.SIZE, file.length())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 0)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AutoCropAlbum")
            } else {
                put(MediaStore.Images.Media.DATA, file.absolutePath)
            }
        }

        return contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.also { uri ->
            // 对于 Android 10+，需要写入文件内容
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    file.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }
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

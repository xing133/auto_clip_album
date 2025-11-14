package com.autocrop.album.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * 图片处理器
 * 负责图片的加载、裁剪、保存等操作
 */
class ImageProcessor(private val context: Context) {

    /**
     * 从 URI 加载 Bitmap
     */
    suspend fun loadBitmap(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 裁剪图片
     * @param bitmap 原始图片
     * @param cropRect 裁剪区域（像素坐标）
     * @return 裁剪后的 Bitmap
     */
    suspend fun cropBitmap(bitmap: Bitmap, cropRect: RectF): Bitmap? = withContext(Dispatchers.Default) {
        try {
            // 确保裁剪区域在图片范围内
            val left = cropRect.left.toInt().coerceIn(0, bitmap.width)
            val top = cropRect.top.toInt().coerceIn(0, bitmap.height)
            val right = cropRect.right.toInt().coerceIn(0, bitmap.width)
            val bottom = cropRect.bottom.toInt().coerceIn(0, bitmap.height)

            val width = right - left
            val height = bottom - top

            if (width <= 0 || height <= 0) {
                return@withContext null
            }

            Bitmap.createBitmap(bitmap, left, top, width, height)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 保存 Bitmap 到文件
     * @param bitmap 要保存的图片
     * @param outputFile 输出文件
     * @param quality 图片质量 (0-100)
     */
    suspend fun saveBitmap(
        bitmap: Bitmap,
        outputFile: File,
        quality: Int = 95
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 裁剪并保存图片
     * @param sourceUri 源图片 URI
     * @param cropRect 裁剪区域
     * @param targetFile 目标文件
     * @return 是否成功
     */
    suspend fun cropAndSave(
        sourceUri: Uri,
        cropRect: RectF,
        targetFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // 加载原图
            val bitmap = loadBitmap(sourceUri) ?: return@withContext false

            // 裁剪
            val croppedBitmap = cropBitmap(bitmap, cropRect) ?: return@withContext false

            // 保存
            val saved = saveBitmap(croppedBitmap, targetFile)

            // 复制 EXIF 信息
            if (saved) {
                copyExifData(sourceUri, targetFile)
            }

            // 释放资源
            if (croppedBitmap != bitmap) {
                croppedBitmap.recycle()
            }
            bitmap.recycle()

            saved
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 复制 EXIF 数据
     */
    private fun copyExifData(sourceUri: Uri, targetFile: File) {
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                val sourceExif = ExifInterface(inputStream)
                val targetExif = ExifInterface(targetFile.absolutePath)

                // 复制常用的 EXIF 标签
                val tags = arrayOf(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.TAG_DATETIME,
                    ExifInterface.TAG_DATETIME_ORIGINAL,
                    ExifInterface.TAG_DATETIME_DIGITIZED,
                    ExifInterface.TAG_MAKE,
                    ExifInterface.TAG_MODEL,
                    ExifInterface.TAG_SOFTWARE,
                    ExifInterface.TAG_ARTIST,
                    ExifInterface.TAG_COPYRIGHT,
                    ExifInterface.TAG_GPS_LATITUDE,
                    ExifInterface.TAG_GPS_LONGITUDE,
                    ExifInterface.TAG_GPS_ALTITUDE,
                    ExifInterface.TAG_GPS_TIMESTAMP,
                    ExifInterface.TAG_GPS_DATESTAMP,
                    ExifInterface.TAG_IMAGE_WIDTH,
                    ExifInterface.TAG_IMAGE_LENGTH,
                    ExifInterface.TAG_FOCAL_LENGTH,
                    ExifInterface.TAG_EXPOSURE_TIME,
                    ExifInterface.TAG_F_NUMBER,
                    ExifInterface.TAG_ISO_SPEED_RATINGS,
                    ExifInterface.TAG_WHITE_BALANCE
                )

                tags.forEach { tag ->
                    val value = sourceExif.getAttribute(tag)
                    if (value != null) {
                        targetExif.setAttribute(tag, value)
                    }
                }

                targetExif.saveAttributes()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 获取图片的旋转角度（基于 EXIF）
     */
    fun getImageRotation(uri: Uri): Int {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val exif = ExifInterface(inputStream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (e: Exception) {
            e.printStackTrace()
            0
        }
    }

    /**
     * 根据 EXIF 旋转图片
     */
    fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap

        val matrix = Matrix()
        matrix.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * 生成裁剪后的文件名
     */
    fun generateCroppedFileName(originalPath: String): String {
        val timestamp = System.currentTimeMillis()
        val file = File(originalPath)
        val name = file.nameWithoutExtension
        val ext = file.extension.ifEmpty { "jpg" }
        return "${name}_cropped_${timestamp}.${ext}"
    }

    /**
     * 获取临时文件路径
     */
    fun getTempFile(fileName: String): File {
        val tempDir = File(context.cacheDir, "cropped")
        tempDir.mkdirs()
        return File(tempDir, fileName)
    }
}

package com.autocrop.album.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.bumptech.glide.Glide
import com.bumptech.glide.request.FutureTarget
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
     * 带有黑边检测信息的Bitmap数据类
     */
    data class BitmapWithLetterboxInfo(
        val bitmap: Bitmap,
        val letterboxResult: LetterboxDetector.DetectionResult?
    )

    /**
     * 从 URI 加载 Bitmap
     */
    /**
     * 加载图片，限制最大尺寸以避免内存问题
     * @param uri 图片 URI
     * @param maxDimension 最大边长（像素），默认 2048
     * @param autoRemoveLetterbox 是否自动去除黑边，默认 true
     * @return Bitmap 或 null
     */
    suspend fun loadBitmap(
        uri: Uri,
        maxDimension: Int = 2048,
        autoRemoveLetterbox: Boolean = true
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("ImageProcessor", "loadBitmap: uri=$uri, maxDimension=$maxDimension")

            // 使用 Glide 加载图片（避免 Scoped Storage 权限问题）
            val futureTarget: FutureTarget<Bitmap> = Glide.with(context)
                .asBitmap()
                .load(uri)
                .submit()

            var bitmap = try {
                futureTarget.get()
            } catch (e: Exception) {
                android.util.Log.e("ImageProcessor", "loadBitmap: Glide failed to load", e)
                null
            } finally {
                Glide.with(context).clear(futureTarget)
            }

            if (bitmap == null) {
                android.util.Log.e("ImageProcessor", "loadBitmap: Failed to load bitmap")
                return@withContext null
            }

            android.util.Log.d("ImageProcessor", "loadBitmap: original size=${bitmap.width}x${bitmap.height}")

            // 创建一个副本，避免影响 Glide 的 bitmap 池
            var workingBitmap = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)

            // 如果需要缩放
            if (maxOf(workingBitmap.width, workingBitmap.height) > maxDimension) {
                val scale = maxDimension.toFloat() / maxOf(workingBitmap.width, workingBitmap.height)
                val newWidth = (workingBitmap.width * scale).toInt()
                val newHeight = (workingBitmap.height * scale).toInt()
                android.util.Log.d("ImageProcessor", "loadBitmap: scaling to ${newWidth}x${newHeight}")

                val scaledBitmap = Bitmap.createScaledBitmap(workingBitmap, newWidth, newHeight, true)
                if (scaledBitmap != workingBitmap) {
                    workingBitmap.recycle()
                    workingBitmap = scaledBitmap
                }
            }

            // 自动去除黑边（视频截图常见）
            if (autoRemoveLetterbox) {
                android.util.Log.d("ImageProcessor", "loadBitmap: detecting letterbox...")
                val croppedBitmap = LetterboxDetector.detectAndCrop(workingBitmap)
                android.util.Log.d("ImageProcessor", "loadBitmap: letterbox detection done, cropped=${croppedBitmap != workingBitmap}")
                if (croppedBitmap != workingBitmap) {
                    // 如果裁剪了黑边，释放原图
                    workingBitmap.recycle()
                    workingBitmap = croppedBitmap
                }
            }

            android.util.Log.d("ImageProcessor", "loadBitmap: final bitmap size=${workingBitmap.width}x${workingBitmap.height}")
            workingBitmap
        } catch (e: Exception) {
            android.util.Log.e("ImageProcessor", "loadBitmap: Exception", e)
            e.printStackTrace()
            null
        }
    }

    /**
     * 加载图片并返回黑边检测信息（用于预览和debug）
     * @param uri 图片 URI
     * @param maxDimension 最大边长（像素），默认 2048
     * @return BitmapWithLetterboxInfo 或 null
     */
    suspend fun loadBitmapWithLetterboxInfo(
        uri: Uri,
        maxDimension: Int = 2048
    ): BitmapWithLetterboxInfo? = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("ImageProcessor", "loadBitmapWithLetterboxInfo: uri=$uri")

            // 使用 Glide 加载图片
            val futureTarget: FutureTarget<Bitmap> = Glide.with(context)
                .asBitmap()
                .load(uri)
                .submit()

            var bitmap = try {
                futureTarget.get()
            } catch (e: Exception) {
                android.util.Log.e("ImageProcessor", "loadBitmapWithLetterboxInfo: Glide failed", e)
                null
            } finally {
                Glide.with(context).clear(futureTarget)
            }

            if (bitmap == null) {
                return@withContext null
            }

            // 创建副本
            var workingBitmap = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)

            // 缩放（如果需要）
            if (maxOf(workingBitmap.width, workingBitmap.height) > maxDimension) {
                val scale = maxDimension.toFloat() / maxOf(workingBitmap.width, workingBitmap.height)
                val newWidth = (workingBitmap.width * scale).toInt()
                val newHeight = (workingBitmap.height * scale).toInt()
                val scaledBitmap = Bitmap.createScaledBitmap(workingBitmap, newWidth, newHeight, true)
                if (scaledBitmap != workingBitmap) {
                    workingBitmap.recycle()
                    workingBitmap = scaledBitmap
                }
            }

            // 检测黑边（但不裁剪）
            val detectionResult = LetterboxDetector.detect(workingBitmap)
            android.util.Log.d("ImageProcessor", "loadBitmapWithLetterboxInfo: detected ${detectionResult.letterboxRegions.size} letterbox regions")

            BitmapWithLetterboxInfo(workingBitmap, detectionResult)
        } catch (e: Exception) {
            android.util.Log.e("ImageProcessor", "loadBitmapWithLetterboxInfo: Exception", e)
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

            android.util.Log.d("ImageProcessor", "cropBitmap: bitmap size=${bitmap.width}x${bitmap.height}")
            android.util.Log.d("ImageProcessor", "cropBitmap: crop region: left=$left, top=$top, right=$right, bottom=$bottom")
            android.util.Log.d("ImageProcessor", "cropBitmap: crop size: ${width}x${height}")

            if (width <= 0 || height <= 0) {
                android.util.Log.e("ImageProcessor", "cropBitmap: invalid crop size!")
                return@withContext null
            }

            val result = Bitmap.createBitmap(bitmap, left, top, width, height)
            android.util.Log.d("ImageProcessor", "cropBitmap: result size=${result.width}x${result.height}")
            result
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
    /**
     * 裁剪并保存图片
     * @param sourceUri 源图片 URI
     * @param cropRect 裁剪区域（基于预览图片的像素坐标）
     * @param targetFile 目标文件
     * @param previewWidth 预览图片宽度（用于计算缩放比例）
     * @param previewHeight 预览图片高度（用于计算缩放比例）
     * @return 是否成功
     */
    suspend fun cropAndSave(
        sourceUri: Uri,
        cropRect: RectF,
        targetFile: File,
        previewWidth: Int = 0,
        previewHeight: Int = 0
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // 加载原图（不限制尺寸，保持黑边以确保和预览图坐标系一致）
            val bitmap = loadBitmap(sourceUri, maxDimension = Int.MAX_VALUE, autoRemoveLetterbox = false) ?: return@withContext false

            android.util.Log.d("ImageProcessor", "cropAndSave: original bitmap size=${bitmap.width}x${bitmap.height}")
            android.util.Log.d("ImageProcessor", "cropAndSave: preview size=${previewWidth}x${previewHeight}")
            android.util.Log.d("ImageProcessor", "cropAndSave: cropRect before scale=$cropRect")

            // 如果提供了预览尺寸，需要将裁剪坐标按比例放大到原图
            val actualCropRect = if (previewWidth > 0 && previewHeight > 0) {
                val scaleX = bitmap.width.toFloat() / previewWidth
                val scaleY = bitmap.height.toFloat() / previewHeight
                android.util.Log.d("ImageProcessor", "cropAndSave: scale factors: scaleX=$scaleX, scaleY=$scaleY")
                RectF(
                    cropRect.left * scaleX,
                    cropRect.top * scaleY,
                    cropRect.right * scaleX,
                    cropRect.bottom * scaleY
                )
            } else {
                cropRect
            }

            android.util.Log.d("ImageProcessor", "cropAndSave: actualCropRect=$actualCropRect")

            // 裁剪
            val croppedBitmap = cropBitmap(bitmap, actualCropRect) ?: return@withContext false

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

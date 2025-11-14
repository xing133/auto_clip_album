package com.autocrop.album.ui.editor

import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.autocrop.album.R
import com.autocrop.album.data.ImageDataHolder
import com.autocrop.album.data.MediaStoreRepository
import com.autocrop.album.data.models.CropBox
import com.autocrop.album.databinding.ActivityEditorBinding
import com.autocrop.album.databinding.PageEditorImageBinding
import com.autocrop.album.domain.CropCalculator
import com.autocrop.album.domain.ImageProcessor
import com.autocrop.album.domain.PoseDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 编辑器 Activity - 全屏图片编辑和裁剪
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var adapter: ImagePagerAdapter
    private lateinit var poseDetector: PoseDetector
    private lateinit var imageProcessor: ImageProcessor
    private lateinit var repository: MediaStoreRepository

    private var imageUris: List<Uri> = emptyList()
    private var currentPosition: Int = 0
    private var currentCropBoxes: List<CropBox> = emptyList()
    private var currentBitmap: Bitmap? = null

    private lateinit var gestureDetector: GestureDetectorCompat
    private var isProcessing = false
    private var isPoseDetectorReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化
        poseDetector = PoseDetector(this)
        imageProcessor = ImageProcessor(this)
        repository = MediaStoreRepository(this)

        // 从 ImageDataHolder 获取图片列表，避免 Intent 传递大数据
        imageUris = ImageDataHolder.getImageUris()
        currentPosition = intent.getIntExtra(EXTRA_IMAGE_POSITION, 0)

        if (imageUris.isEmpty()) {
            finish()
            return
        }

        setupViewPager()
        setupButtons()
        setupGestureDetector()

        // 初始化 PoseDetector（必须先初始化完成后再处理图片）
        lifecycleScope.launch {
            try {
                showLoading(true, "Initializing pose detector...")
                poseDetector.initialize()
                isPoseDetectorReady = true
                showLoading(false)

                // 初始化完成后处理当前图片
                val viewHolder = (binding.viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
                    ?.findViewHolderForAdapterPosition(currentPosition) as? ImagePagerAdapter.ImagePageViewHolder
                viewHolder?.getBinding()?.let { pageBinding ->
                    processCurrentImage(pageBinding)
                }
            } catch (e: Exception) {
                showError("Failed to initialize pose detector: ${e.message}")
                isPoseDetectorReady = false
            }
        }
    }

    private fun setupViewPager() {
        adapter = ImagePagerAdapter(imageUris) { position, pageBinding ->
            // 不在这里处理，等初始化完成后再处理
        }

        binding.viewPager.adapter = adapter
        binding.viewPager.setCurrentItem(currentPosition, false)

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPosition = position
                currentCropBoxes = emptyList()
                currentBitmap?.recycle()
                currentBitmap = null

                // 只有在初始化完成后才处理新图片
                if (isPoseDetectorReady) {
                    val viewHolder = (binding.viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
                        ?.findViewHolderForAdapterPosition(position) as? ImagePagerAdapter.ImagePageViewHolder

                    viewHolder?.getBinding()?.let { pageBinding ->
                        processCurrentImage(pageBinding)
                    }
                }
            }
        })
    }

    private fun setupButtons() {
        binding.btnCrop.setOnClickListener {
            performCrop()
        }

        binding.btnCancel.setOnClickListener {
            finish()
        }
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                // 双击创建手动裁剪框
                createManualCropBox()
                return true
            }
        })
    }

    private fun processCurrentImage(pageBinding: PageEditorImageBinding) {
        if (isProcessing) return
        if (!isPoseDetectorReady) {
            showStatus("Waiting for pose detector to initialize...")
            return
        }

        lifecycleScope.launch {
            isProcessing = true
            showLoading(true, getString(R.string.detecting_pose))

            try {
                val uri = imageUris[currentPosition]

                // 加载图片（降低到 1024 以减少内存压力）
                val bitmap = withContext(Dispatchers.IO) {
                    imageProcessor.loadBitmap(uri, maxDimension = 1024)
                } ?: run {
                    showError("Failed to load image")
                    return@launch
                }

                currentBitmap = bitmap

                // 检测姿态
                val persons = poseDetector.detectPose(bitmap)

                // 生成裁剪框
                currentCropBoxes = if (persons.isNotEmpty()) {
                    CropCalculator.generateCropBoxes(persons, bitmap.width, bitmap.height)
                } else {
                    // 没有检测到人物，显示提示
                    showStatus(getString(R.string.no_person_detected))
                    emptyList()
                }

                // 更新 UI
                withContext(Dispatchers.Main) {
                    updateOverlayView(pageBinding, persons)

                    if (persons.isEmpty()) {
                        // 自动创建一个手动裁剪框
                        createManualCropBox()
                    } else {
                        showStatus(getString(R.string.multiple_persons_detected, persons.size))
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                showError("Error processing image: ${e.message}")
            } finally {
                isProcessing = false
                showLoading(false)
            }
        }
    }

    private fun updateOverlayView(pageBinding: PageEditorImageBinding, persons: List<Any>) {
        val imageView = pageBinding.imageView
        val overlayView = pageBinding.cropOverlayView

        // 使用检测时的 bitmap 尺寸（而非 drawable 尺寸）确保坐标一致
        val detectionBitmap = currentBitmap
        if (detectionBitmap == null) return

        // 计算图片在 ImageView 中的位置和缩放
        imageView.post {
            val imageWidth = detectionBitmap.width
            val imageHeight = detectionBitmap.height

            if (imageWidth == 0 || imageHeight == 0) return@post

            val viewWidth = imageView.width.toFloat()
            val viewHeight = imageView.height.toFloat()

            val scale = minOf(
                viewWidth / imageWidth,
                viewHeight / imageHeight
            )

            val scaledWidth = imageWidth * scale
            val scaledHeight = imageHeight * scale

            val offsetX = (viewWidth - scaledWidth) / 2
            val offsetY = (viewHeight - scaledHeight) / 2

            // 设置变换信息
            overlayView.setImageTransform(imageWidth, imageHeight, offsetX, offsetY, scale)

            // 设置检测数据
            if (persons.isNotEmpty()) {
                overlayView.setDetectedPersons(persons as List<com.autocrop.album.data.models.DetectedPerson>)
            }

            // 设置裁剪框
            if (currentCropBoxes.isNotEmpty()) {
                overlayView.setCropBoxes(currentCropBoxes)
            }
        }
    }

    private fun createManualCropBox() {
        val bitmap = currentBitmap ?: return

        val newBox = CropCalculator.createManualCropBox(
            currentCropBoxes.size,
            bitmap.width,
            bitmap.height
        )

        currentCropBoxes = currentCropBoxes + newBox

        // 更新当前页面的 OverlayView
        val viewHolder = (binding.viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
            ?.findViewHolderForAdapterPosition(currentPosition) as? ImagePagerAdapter.ImagePageViewHolder

        viewHolder?.getBinding()?.cropOverlayView?.apply {
            addCropBox(newBox)
        }

        showStatus(getString(R.string.manual_crop))
    }

    private fun performCrop() {
        if (isProcessing) return

        val viewHolder = (binding.viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
            ?.findViewHolderForAdapterPosition(currentPosition) as? ImagePagerAdapter.ImagePageViewHolder

        val selectedBox = viewHolder?.getBinding()?.cropOverlayView?.getSelectedCropBox()

        if (selectedBox == null) {
            Toast.makeText(this, "Please select a crop box", Toast.LENGTH_SHORT).show()
            return
        }

        val overwriteOriginal = binding.saveOptionGroup.checkedButtonId == R.id.btnOverwrite

        lifecycleScope.launch {
            isProcessing = true
            showLoading(true, getString(R.string.cropping_image))

            try {
                val uri = imageUris[currentPosition]
                val originalPath = getPathFromUri(uri) ?: run {
                    showError("Failed to get file path")
                    return@launch
                }

                // 生成输出文件
                val fileName = if (overwriteOriginal) {
                    File(originalPath).name
                } else {
                    imageProcessor.generateCroppedFileName(originalPath)
                }

                val tempFile = imageProcessor.getTempFile(fileName)

                // 裁剪并保存（传递预览图片尺寸用于坐标缩放）
                val previewBitmap = currentBitmap
                val success = if (previewBitmap != null) {
                    imageProcessor.cropAndSave(
                        uri,
                        selectedBox.rect,
                        tempFile,
                        previewBitmap.width,
                        previewBitmap.height
                    )
                } else {
                    imageProcessor.cropAndSave(uri, selectedBox.rect, tempFile)
                }

                if (success) {
                    // 保存到媒体库
                    val savedUri = repository.saveCroppedImage(uri, tempFile, overwriteOriginal)

                    if (savedUri != null) {
                        withContext(Dispatchers.Main) {
                            showStatus(getString(R.string.crop_success))
                            Toast.makeText(
                                this@EditorActivity,
                                getString(R.string.crop_success),
                                Toast.LENGTH_SHORT
                            ).show()

                            // 重新加载当前图片
                            if (overwriteOriginal) {
                                viewHolder?.getBinding()?.let { processCurrentImage(it) }
                            }
                        }
                    } else {
                        showError(getString(R.string.crop_failed))
                    }
                } else {
                    showError(getString(R.string.crop_failed))
                }

            } catch (e: Exception) {
                e.printStackTrace()
                showError("Crop error: ${e.message}")
            } finally {
                isProcessing = false
                showLoading(false)
            }
        }
    }

    private fun getPathFromUri(uri: Uri): String? {
        val projection = arrayOf(android.provider.MediaStore.Images.Media.DATA)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val columnIndex = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DATA)
            cursor.moveToFirst()
            return cursor.getString(columnIndex)
        }
        return null
    }

    private fun showLoading(show: Boolean, message: String? = null) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        if (show && message != null) {
            showStatus(message)
        } else if (!show) {
            binding.statusText.visibility = View.GONE
        }
    }

    private fun showStatus(message: String) {
        binding.statusText.text = message
        binding.statusText.visibility = View.VISIBLE

        // 3秒后自动隐藏
        binding.statusText.postDelayed({
            binding.statusText.visibility = View.GONE
        }, 3000)
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        poseDetector.close()
        currentBitmap?.recycle()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    companion object {
        const val EXTRA_IMAGE_URIS = "extra_image_uris"
        const val EXTRA_IMAGE_POSITION = "extra_image_position"
    }
}

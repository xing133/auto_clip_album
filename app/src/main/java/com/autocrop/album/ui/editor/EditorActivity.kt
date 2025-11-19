package com.autocrop.album.ui.editor

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.autocrop.album.R
import com.autocrop.album.data.ImageDataHolder
import com.autocrop.album.data.MediaStoreRepository
import com.autocrop.album.data.models.CropBox
import com.autocrop.album.data.MediaStoreRepository.SaveImageResult
import com.autocrop.album.data.MediaStoreRepository.DeleteImageResult
import com.autocrop.album.databinding.ActivityEditorBinding
import com.autocrop.album.databinding.PageEditorImageBinding
import com.autocrop.album.domain.CropCalculator
import com.autocrop.album.domain.ImageProcessor
import com.autocrop.album.domain.PoseDetector
import com.autocrop.album.ui.editor.ImagePagerAdapter
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

    // 标记图片是否被修改（保存/删除），用于返回结果给 Gallery
    private var hasModified: Boolean = false
    private var currentBitmap: Bitmap? = null

    private lateinit var gestureDetector: GestureDetectorCompat
    private var isProcessing = false
    private var isPoseDetectorReady = false
    private lateinit var editPermissionLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var deletePermissionLauncher: ActivityResultLauncher<IntentSenderRequest>
    private var pendingSaveRequest: PendingSaveRequest? = null
    private var pendingDeleteUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化
        poseDetector = PoseDetector(this)
        imageProcessor = ImageProcessor(this)
        repository = MediaStoreRepository(this)
        registerEditPermissionLauncher()
        registerDeletePermissionLauncher()

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

        // 禁用滑动功能，使用按钮切换图片
        binding.viewPager.isUserInputEnabled = false

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPosition = position
                currentCropBoxes = emptyList()
                currentBitmap?.recycle()
                currentBitmap = null

                // 更新导航按钮的可见性
                updateNavigationButtons()

                // 只有在初始化完成后才处理新图片
                if (isPoseDetectorReady) {
                    // 使用 post 确保 ViewHolder 已经创建完成
                    binding.viewPager.post {
                        val viewHolder = (binding.viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
                            ?.findViewHolderForAdapterPosition(position) as? ImagePagerAdapter.ImagePageViewHolder

                        viewHolder?.getBinding()?.let { pageBinding ->
                            processCurrentImage(pageBinding)
                        }
                    }
                }
            }
        })

        // 初始化导航按钮
        updateNavigationButtons()
    }

    private fun setupButtons() {
        binding.btnCrop.setOnClickListener {
            performCrop()
        }

        binding.btnCancel.setOnClickListener {
            deleteCurrentImage()
        }

        // 展开/收起保存选项
        binding.btnExpandOptions.setOnClickListener {
            toggleSaveOptions()
        }

        // 导航按钮
        binding.btnPrevImage.setOnClickListener {
            if (currentPosition > 0) {
                binding.viewPager.setCurrentItem(currentPosition - 1, true)
            }
        }

        binding.btnNextImage.setOnClickListener {
            if (currentPosition < imageUris.size - 1) {
                binding.viewPager.setCurrentItem(currentPosition + 1, true)
            }
        }
    }

    private fun toggleSaveOptions() {
        val isVisible = binding.saveOptionsPanel.visibility == View.VISIBLE
        binding.saveOptionsPanel.visibility = if (isVisible) View.GONE else View.VISIBLE

        // 更新图标方向
        binding.btnExpandOptions.setImageResource(
            if (isVisible) android.R.drawable.arrow_up_float
            else android.R.drawable.arrow_down_float
        )
    }

    private fun updateNavigationButtons() {
        // 第一张图片时隐藏"上一张"按钮
        binding.btnPrevImage.visibility = if (currentPosition > 0) View.VISIBLE else View.GONE

        // 最后一张图片时隐藏"下一张"按钮
        binding.btnNextImage.visibility = if (currentPosition < imageUris.size - 1) View.VISIBLE else View.GONE
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

                // 加载图片并获取黑边检测信息（降低到 1024 以减少内存压力）
                android.util.Log.d("EditorActivity", "Loading image from URI: $uri")
                val bitmapWithInfo = withContext(Dispatchers.IO) {
                    imageProcessor.loadBitmapWithLetterboxInfo(uri, maxDimension = 1024)
                } ?: run {
                    android.util.Log.e("EditorActivity", "Failed to load bitmap from URI: $uri")
                    showError("Failed to load image")
                    return@launch
                }

                val bitmap = bitmapWithInfo.bitmap
                val letterboxResult = bitmapWithInfo.letterboxResult

                android.util.Log.d("EditorActivity", "Image loaded successfully: ${bitmap.width}x${bitmap.height}")
                android.util.Log.d("EditorActivity", "Letterbox regions: ${letterboxResult?.letterboxRegions?.size ?: 0}")

                currentBitmap = bitmap

                // 检测姿态
                val persons = poseDetector.detectPose(bitmap)

                // 生成裁剪框（传入黑边信息，确保裁剪框不包含黑边）
                currentCropBoxes = if (persons.isNotEmpty()) {
                    CropCalculator.generateCropBoxes(persons, bitmap.width, bitmap.height, letterboxResult)
                } else {
                    // 没有检测到人物，显示提示
                    showStatus(getString(R.string.no_person_detected))
                    emptyList()
                }

                // 更新 UI
                withContext(Dispatchers.Main) {
                    updateOverlayView(pageBinding, persons, letterboxResult?.letterboxRegions ?: emptyList())

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

    private fun updateOverlayView(
        pageBinding: PageEditorImageBinding,
        persons: List<Any>,
        letterboxRegions: List<android.graphics.Rect> = emptyList()
    ) {
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

            // 设置黑边区域（debug可视化）
            overlayView.setLetterboxRegions(letterboxRegions)

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
                    when (val saveResult = repository.saveCroppedImage(uri, tempFile, overwriteOriginal)) {
                        is SaveImageResult.Success -> {
                            pendingSaveRequest = null
                            handleSaveSuccess(overwriteOriginal)
                        }
                        is SaveImageResult.RequiresPermission -> {
                            pendingSaveRequest = PendingSaveRequest(uri, tempFile, overwriteOriginal)
                            showLoading(false)
                            isProcessing = false
                            requestWritePermission(saveResult.intentSender)
                            return@launch
                        }
                        is SaveImageResult.Error -> {
                            pendingSaveRequest = null
                            showError(getString(R.string.crop_failed))
                        }
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

    private fun handleSaveSuccess(overwriteOriginal: Boolean) {
        showStatus(getString(R.string.crop_success))
        Toast.makeText(this@EditorActivity, getString(R.string.crop_success), Toast.LENGTH_SHORT).show()

        // 标记图片已修改
        hasModified = true

        if (overwriteOriginal) {
            reloadCurrentPreview()
        }
    }

    private fun reloadCurrentPreview() {
        val uri = imageUris[currentPosition]

        // 清除 Glide 缓存，确保重新加载图片
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                // 清除内存缓存
                com.bumptech.glide.Glide.get(this@EditorActivity).clearMemory()

                // 清除磁盘缓存（需要在后台线程）
                withContext(Dispatchers.IO) {
                    com.bumptech.glide.Glide.get(this@EditorActivity).clearDiskCache()
                }

                // 重新加载图片
                val recyclerView = binding.viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return@launch
                val holder = recyclerView.findViewHolderForAdapterPosition(currentPosition) as? ImagePagerAdapter.ImagePageViewHolder ?: return@launch

                holder.getBinding()?.let { pageBinding ->
                    // 重新加载图片到 ImageView
                    com.bumptech.glide.Glide.with(pageBinding.imageView)
                        .load(uri)
                        .skipMemoryCache(true)
                        .into(pageBinding.imageView)

                    // 重新处理图片（骨骼检测）
                    pageBinding.imageView.post {
                        processCurrentImage(pageBinding)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                showError("Failed to reload preview: ${e.message}")
            }
        }
    }

    private fun registerEditPermissionLauncher() {
        editPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                retryPendingSave()
            } else {
                pendingSaveRequest = null
                showError(getString(R.string.crop_failed))
                isProcessing = false
                showLoading(false)
            }
        }
    }

    private fun registerDeletePermissionLauncher() {
        deletePermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                retryPendingDelete()
            } else {
                pendingDeleteUri = null
                showError("Delete cancelled")
                isProcessing = false
                showLoading(false)
            }
        }
    }

    private fun requestWritePermission(intentSender: IntentSender) {
        val request = IntentSenderRequest.Builder(intentSender).build()
        showStatus(getString(R.string.permission_required))
        editPermissionLauncher.launch(request)
    }

    private fun retryPendingSave() {
        val pending = pendingSaveRequest ?: return
        lifecycleScope.launch {
            isProcessing = true
            showLoading(true, getString(R.string.saving_image))
            try {
                when (val result = repository.saveCroppedImage(pending.sourceUri, pending.tempFile, pending.overwriteOriginal)) {
                    is SaveImageResult.Success -> {
                        pendingSaveRequest = null
                        handleSaveSuccess(pending.overwriteOriginal)
                    }
                    is SaveImageResult.RequiresPermission -> {
                        requestWritePermission(result.intentSender)
                        return@launch
                    }
                    is SaveImageResult.Error -> {
                        pendingSaveRequest = null
                        showError(getString(R.string.crop_failed))
                    }
                }
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

    private fun deleteCurrentImage() {
        if (isProcessing) return
        if (imageUris.isEmpty()) return

        lifecycleScope.launch {
            isProcessing = true
            showLoading(true, "Deleting image...")

            try {
                val uri = imageUris[currentPosition]

                when (val result = repository.deleteImage(uri)) {
                    is DeleteImageResult.Success -> {
                        pendingDeleteUri = null
                        handleDeleteSuccess()
                    }
                    is DeleteImageResult.RequiresPermission -> {
                        pendingDeleteUri = uri
                        showLoading(false)
                        isProcessing = false
                        requestDeletePermission(result.intentSender)
                        return@launch
                    }
                    is DeleteImageResult.Error -> {
                        pendingDeleteUri = null
                        showError("Failed to delete image")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                showError("Delete error: ${e.message}")
            } finally {
                isProcessing = false
                showLoading(false)
            }
        }
    }

    private fun handleDeleteSuccess() {
        showStatus("Image deleted")

        // 标记图片已修改
        hasModified = true

        // 从列表中移除当前图片
        val mutableUris = imageUris.toMutableList()
        mutableUris.removeAt(currentPosition)

        // 更新 ImageDataHolder
        ImageDataHolder.setImageUris(mutableUris)
        imageUris = mutableUris

        // 如果列表为空,关闭 Activity 并返回结果
        if (imageUris.isEmpty()) {
            Toast.makeText(this, "No more images", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK, Intent().putExtra("MODIFIED_POSITION", currentPosition))
            finish()
            return
        }

        // 如果删除的是最后一张,跳转到前一张
        val newPosition = if (currentPosition >= imageUris.size) {
            imageUris.size - 1
        } else {
            currentPosition
        }

        // 清理当前状态
        currentCropBoxes = emptyList()
        currentBitmap?.recycle()
        currentBitmap = null

        // 更新 adapter 并跳转
        adapter = ImagePagerAdapter(imageUris) { _, _ -> }
        binding.viewPager.adapter = adapter
        binding.viewPager.setCurrentItem(newPosition, false)
        currentPosition = newPosition

        // 更新导航按钮
        updateNavigationButtons()

        // 处理新图片
        if (isPoseDetectorReady) {
            binding.viewPager.post {
                val viewHolder = (binding.viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
                    ?.findViewHolderForAdapterPosition(newPosition) as? ImagePagerAdapter.ImagePageViewHolder

                viewHolder?.getBinding()?.let { pageBinding ->
                    processCurrentImage(pageBinding)
                }
            }
        }
    }

    private fun requestDeletePermission(intentSender: IntentSender) {
        val request = IntentSenderRequest.Builder(intentSender).build()
        showStatus("Permission required to delete")
        deletePermissionLauncher.launch(request)
    }

    private fun retryPendingDelete() {
        val uri = pendingDeleteUri ?: return
        lifecycleScope.launch {
            isProcessing = true
            showLoading(true, "Deleting image...")
            try {
                when (val result = repository.deleteImage(uri)) {
                    is DeleteImageResult.Success -> {
                        pendingDeleteUri = null
                        handleDeleteSuccess()
                    }
                    is DeleteImageResult.RequiresPermission -> {
                        requestDeletePermission(result.intentSender)
                        return@launch
                    }
                    is DeleteImageResult.Error -> {
                        pendingDeleteUri = null
                        showError("Failed to delete image")
                    }
                }
            } finally {
                isProcessing = false
                showLoading(false)
            }
        }
    }

    private data class PendingSaveRequest(
        val sourceUri: Uri,
        val tempFile: File,
        val overwriteOriginal: Boolean
    )

    override fun finish() {
        // 如果图片被修改，返回结果给 Gallery
        if (hasModified) {
            setResult(RESULT_OK, Intent().putExtra("MODIFIED_POSITION", currentPosition))
        }
        super.finish()
    }
}

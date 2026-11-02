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
import com.autocrop.album.data.SettingsManager
import com.autocrop.album.data.models.CropBox
import com.autocrop.album.data.MediaStoreRepository.SaveImageResult
import com.autocrop.album.data.MediaStoreRepository.DeleteImageResult
import com.autocrop.album.databinding.ActivityEditorBinding
import com.autocrop.album.databinding.PageEditorImageBinding
import com.autocrop.album.domain.CropCalculator
import com.autocrop.album.domain.ImageProcessor
import com.autocrop.album.domain.ImageSegmentation
import com.autocrop.album.domain.SegmentationModel
import com.autocrop.album.domain.EditableMask
import com.autocrop.album.domain.InteractivePersonSelector
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.TooltipCompat
import kotlinx.coroutines.CancellationException
import com.autocrop.album.domain.PersonDetector
import com.autocrop.album.ui.editor.ImagePagerAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 编辑器 Activity - 全屏图片编辑和裁剪
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var adapter: ImagePagerAdapter
    private lateinit var personDetector: PersonDetector
    private lateinit var imageProcessor: ImageProcessor
    private lateinit var repository: MediaStoreRepository
    private lateinit var settingsManager: SettingsManager

    private var imageUris: List<Uri> = emptyList()
    private var currentPosition: Int = 0
    private var currentCropBoxes: List<CropBox> = emptyList()

    // 标记图片是否被修改（保存/删除），用于返回结果给 Gallery
    private var hasModified: Boolean = false
    private var currentBitmap: Bitmap? = null
    private var currentLetterboxResult: com.autocrop.album.domain.LetterboxDetector.DetectionResult? = null

    private lateinit var gestureDetector: GestureDetectorCompat
    private var segmentationModel = SegmentationModel.MULTICLASS
    private var bystanderMask: EditableMask? = null
    private var maskBrushRadiusDp = 12f
    private var mosaicBlockSize = SettingsManager.DEFAULT_MOSAIC_BLOCK_SIZE
    private val isBystanderEditing: Boolean get() = bystanderMask != null
    private var segmentationTouch = false
    private var imageSwipeCandidate = false
    private var imageSwipeStartX = 0f
    private var imageSwipeStartY = 0f
    private var imageSwipePosition = 0
    private var isProcessing = false
    private var isPersonDetectorReady = false
    private lateinit var editPermissionLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var deletePermissionLauncher: ActivityResultLauncher<IntentSenderRequest>
    private var pendingSaveRequest: PendingSaveRequest? = null
    private var pendingDeleteUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化
        personDetector = PersonDetector(this)
        imageProcessor = ImageProcessor(this)
        repository = MediaStoreRepository(this)
        settingsManager = SettingsManager.getInstance(this)
        segmentationModel = settingsManager.getSegmentationModel()
        mosaicBlockSize = settingsManager.getMosaicBlockSize()
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
        setupBystanderControls()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isBystanderEditing) { if (!isProcessing) leaveBystander() }
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })

        // 初始化 PersonDetector（后台静默进行，不阻塞）
        binding.btnDetectPose.isEnabled = false
        lifecycleScope.launch {
            try {
                personDetector.initialize()
                isPersonDetectorReady = true
                binding.btnDetectPose.isEnabled = currentBitmap != null && !isProcessing
            } catch (e: Exception) {
                showError("Failed to initialize person detector: ${e.message}")
                isPersonDetectorReady = false
            }
        }
    }

    private fun setupViewPager() {
        adapter = createImagePagerAdapter()

        binding.viewPager.adapter = adapter
        binding.viewPager.setCurrentItem(currentPosition, false)

        // Keep paging under our control so gestures starting near crop boxes edit them.
        binding.viewPager.isUserInputEnabled = false

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (isBystanderEditing) leaveBystander(restoreImage = false)
                currentPageBinding()?.cropOverlayView?.clearSegmentation()
                currentPosition = position
                currentCropBoxes = emptyList()
                currentLetterboxResult = null
                currentBitmap?.recycle()
                currentBitmap = null
                binding.btnDetectPose.isEnabled = false
                binding.btnSegment.isEnabled = false
                binding.btnSegmentModel.isEnabled = false

                // 更新导航按钮的可见性
                updateNavigationButtons()

                loadCurrentImageWhenReady()
            }

            override fun onPageScrollStateChanged(state: Int) {
                updateNavigationButtons()
                if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    loadCurrentImageWhenReady()
                }
            }
        })

        // 初始化导航按钮
        updateNavigationButtons()
    }

    private fun currentPageBinding(): PageEditorImageBinding? {
        val holder = (binding.viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
            ?.findViewHolderForAdapterPosition(currentPosition) as? ImagePagerAdapter.ImagePageViewHolder
        return holder?.getBinding()
    }

    private fun createImagePagerAdapter() = ImagePagerAdapter(imageUris) { position, pageBinding ->
        pageBinding.cropOverlayView.onSegmentationRegionSelected = { rect ->
            if (position == currentPosition) {
                currentCropBoxes = listOf(CropBox(id = 0, rect = rect, isSelected = true))
                pageBinding.cropOverlayView.setCropBoxes(currentCropBoxes)
                pageBinding.cropOverlayView.getSelectedCropBox()?.let { constrained ->
                    currentCropBoxes = listOf(constrained)
                    val actual = constrained.rect
                    android.util.Log.d("EditorActivity", "Segmentation crop constrained: $actual ratio=${actual.width() / actual.height()}")
                }
            }
        }
        pageBinding.cropOverlayView.onSegmentationModeChanged = { showing ->
            if (position == currentPosition) updateSegmentationUi(showing)
        }
        if (position == currentPosition) loadCurrentImageWhenReady()
    }

    private fun updateSegmentationUi(showing: Boolean) {
        binding.btnSegment.setIconResource(if (showing) R.drawable.ic_back else R.drawable.ic_segment)
        val action = getString(if (showing) R.string.segmentation_exit else R.string.segmentation_test)
        val description = getString(R.string.segmentation_model_action, action, segmentationModel.displayName)
        binding.btnSegment.contentDescription = description
        TooltipCompat.setTooltipText(binding.btnSegment, description)
        val modelDescription = getString(R.string.segmentation_model_current, segmentationModel.displayName)
        binding.btnSegmentModel.contentDescription = modelDescription
        TooltipCompat.setTooltipText(binding.btnSegmentModel, modelDescription)
        binding.segmentationHint.visibility = if (showing) View.VISIBLE else View.GONE
        binding.btnCrop.isEnabled = !isProcessing && !showing && !isBystanderEditing
    }

    private fun showSegmentationModels() {
        if (isProcessing) return
        val models = SegmentationModel.values()
        PopupMenu(this, binding.btnSegmentModel).apply {
            models.forEachIndexed { index, model ->
                menu.add(0, index + 1, index, model.displayName).apply {
                    isCheckable = true
                    isChecked = model == segmentationModel
                }
            }
            menu.setGroupCheckable(0, true, true)
            setOnMenuItemClickListener { item ->
                val model = models[item.itemId - 1]
                if (model != segmentationModel) {
                    val overlay = currentPageBinding()?.cropOverlayView
                    val rerun = overlay?.isShowingSegmentation == true
                    overlay?.clearSegmentation()
                    segmentationModel = model
                    settingsManager.setSegmentationModel(model)
                    updateSegmentationUi(false)
                    if (rerun) testSegmentation()
                }
                true
            }
            show()
        }
    }

    private fun testSegmentation() {
        if (isProcessing) return
        val model = segmentationModel
        val bitmap = currentBitmap ?: return
        val page = currentPageBinding() ?: return
        val overlay = page.cropOverlayView
        if (overlay.isShowingSegmentation) {
            overlay.clearSegmentation()
            return
        }
        val visible = overlay.getVisibleImageRect()
        if (visible.isEmpty) return
        val left = ceil(visible.left).toInt().coerceIn(0, bitmap.width - 1)
        val top = ceil(visible.top).toInt().coerceIn(0, bitmap.height - 1)
        val right = floor(visible.right).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = floor(visible.bottom).toInt().coerceIn(top + 1, bitmap.height)
        val imageBounds = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        // Detach inference input from the Activity bitmap before a destroy/cancel can recycle it.
        val snapshot = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top).let {
            if (it === bitmap) bitmap.copy(Bitmap.Config.ARGB_8888, false) else it
        }
        lifecycleScope.launch {
            isProcessing = true
            showLoading(true, getString(R.string.segmenting_image))
            try {
                val regions = withContext(Dispatchers.Default) {
                    ImageSegmentation(this@EditorActivity).segment(snapshot, model)
                }
                if (regions.regions.isEmpty()) showError(getString(R.string.segmentation_empty))
                else overlay.showSegmentation(regions, imageBounds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(getString(R.string.segmentation_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                snapshot.recycle()
                isProcessing = false
                showLoading(false)
            }
        }
    }

    private fun setupBystanderControls() {
        binding.btnBystander.setOnClickListener { enterBystander() }
        TooltipCompat.setTooltipText(binding.btnBystander, getString(R.string.bystander_title))
        binding.btnMaskTool.setOnClickListener { showMaskTools() }
        binding.btnMaskSettings.setOnClickListener { showMaskSettings() }
        for (button in listOf(binding.btnMaskTool, binding.btnMaskUndo, binding.btnMaskSettings,
            binding.btnMaskPreview, binding.btnMaskOverwrite, binding.btnMaskSave, binding.btnMaskCancel)) {
            TooltipCompat.setTooltipText(button, button.contentDescription)
        }
        binding.btnMaskUndo.setOnClickListener {
            if (!isProcessing && bystanderMask?.undo() == true) {
                val view = currentPageBinding()?.maskEditView
                view?.refresh()
                if (view?.preview == true) renderBystanderPreview() else updateBystanderControls()
            }
        }
        binding.btnMaskCancel.setOnClickListener { if (!isProcessing) leaveBystander() }
        binding.btnMaskPreview.setOnClickListener {
            val page = currentPageBinding() ?: return@setOnClickListener
            if (page.maskEditView.preview) {
                page.maskEditView.preview = false
                page.imageView.setImageBitmap(currentBitmap)
                updateBystanderControls()
            } else renderBystanderPreview()
        }
        binding.btnMaskOverwrite.setOnClickListener { saveBystander(overwriteOriginal = true) }
        binding.btnMaskSave.setOnClickListener { saveBystander(overwriteOriginal = false) }
    }

    private fun showMaskTools() {
        if (isProcessing) return
        val view = currentPageBinding()?.maskEditView ?: return
        val tools = MaskEditView.Tool.values()
        val labels = intArrayOf(R.string.mask_select, R.string.mask_add, R.string.mask_erase)
        PopupMenu(this, binding.btnMaskTool).apply {
            tools.forEachIndexed { index, tool -> menu.add(0, index + 1, index, labels[index]).apply {
                isCheckable = true; isChecked = view.tool == tool
            } }
            menu.setGroupCheckable(0, true, true)
            setOnMenuItemClickListener { item -> view.tool = tools[item.itemId - 1]; updateBystanderControls(); true }
            show()
        }
    }

    private fun showMaskSettings() {
        if (isProcessing || !isBystanderEditing) return
        val body = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
        }
        fun slider(label: Int, from: Float, to: Float, step: Float, value: Float, change: (Float) -> Unit) {
            val title = android.widget.TextView(this).apply { text = getString(label) }
            val control = com.google.android.material.slider.Slider(this).apply {
                valueFrom = from; valueTo = to; stepSize = step; this.value = value
                contentDescription = getString(label)
                addOnChangeListener { _, changed, _ -> change(changed) }
            }
            body.addView(title); body.addView(control)
        }
        slider(R.string.mask_brush_size, 4f, 40f, 2f, maskBrushRadiusDp) {
            maskBrushRadiusDp = it; currentPageBinding()?.maskEditView?.brushRadiusDp = it
        }
        slider(R.string.mask_strength, SettingsManager.MIN_MOSAIC_BLOCK_SIZE.toFloat(),
            SettingsManager.MAX_MOSAIC_BLOCK_SIZE.toFloat(), SettingsManager.MOSAIC_BLOCK_SIZE_STEP.toFloat(),
            mosaicBlockSize.toFloat()) {
            mosaicBlockSize = it.toInt()
            settingsManager.setMosaicBlockSize(mosaicBlockSize)
        }
        val before = mosaicBlockSize
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mask_settings).setView(body).setPositiveButton(R.string.ok, null)
            .setOnDismissListener {
                if (mosaicBlockSize != before && currentPageBinding()?.maskEditView?.preview == true) renderBystanderPreview()
            }.show()
    }

    private fun enterBystander() {
        if (isProcessing || isBystanderEditing || binding.viewPager.scrollState != ViewPager2.SCROLL_STATE_IDLE) return
        val bitmap = currentBitmap ?: return
        val page = currentPageBinding() ?: return
        page.cropOverlayView.clearSegmentation()
        bystanderMask = EditableMask(bitmap.width, bitmap.height)
        com.bumptech.glide.Glide.with(page.imageView).clear(page.imageView)
        page.imageView.setImageBitmap(bitmap)
        page.cropOverlayView.externalMaskEditing = true
        page.maskEditView.apply {
            mask = bystanderMask
            preview = false
            tool = MaskEditView.Tool.SELECT
            brushRadiusDp = maskBrushRadiusDp
            onSelect = { x, y -> selectBystander(x, y) }
            onMaskChanged = { updateBystanderControls() }
            visibility = View.VISIBLE
        }
        binding.bystanderPanel.visibility = View.VISIBLE
        binding.bystanderHint.visibility = View.VISIBLE
        binding.segmentationControls.visibility = View.GONE
        binding.segmentationHint.visibility = View.GONE
        binding.editorActions.visibility = View.GONE
        binding.btnExpandOptions.visibility = View.GONE
        binding.saveOptionsPanel.visibility = View.GONE
        updateNavigationButtons()
        updateBystanderControls()
    }

    private fun leaveBystander(restoreImage: Boolean = true) {
        val page = currentPageBinding()
        bystanderMask = null
        page?.maskEditView?.apply { mask = null; preview = false; visibility = View.GONE; onSelect = null; onMaskChanged = null }
        page?.cropOverlayView?.externalMaskEditing = false
        binding.bystanderPanel.visibility = View.GONE
        binding.bystanderHint.visibility = View.GONE
        binding.segmentationControls.visibility = View.VISIBLE
        binding.editorActions.visibility = View.VISIBLE
        binding.btnExpandOptions.visibility = View.VISIBLE
        if (restoreImage && page != null) {
            com.bumptech.glide.Glide.with(page.imageView).load(imageUris[currentPosition]).fitCenter().into(page.imageView)
        }
        updateNavigationButtons()
        updateSegmentationUi(false)
    }

    private fun updateBystanderControls() {
        val mask = bystanderMask
        val available = !isProcessing && mask != null
        val preview = currentPageBinding()?.maskEditView?.preview == true
        binding.btnMaskTool.isEnabled = available && !preview
        binding.btnMaskSettings.isEnabled = available
        binding.btnMaskUndo.isEnabled = available && mask?.canUndo == true
        binding.btnMaskPreview.isEnabled = available && mask?.hasSelection == true
        binding.btnMaskOverwrite.isEnabled = available && mask?.hasSelection == true
        binding.btnMaskSave.isEnabled = available && mask?.hasSelection == true
        binding.btnMaskCancel.isEnabled = !isProcessing
        val tool = currentPageBinding()?.maskEditView?.tool ?: MaskEditView.Tool.SELECT
        val label = when (tool) {
            MaskEditView.Tool.SELECT -> R.string.mask_select
            MaskEditView.Tool.ADD -> R.string.mask_add
            MaskEditView.Tool.ERASE -> R.string.mask_erase
        }
        binding.btnMaskTool.setIconResource(when (tool) {
            MaskEditView.Tool.SELECT -> R.drawable.ic_segment
            MaskEditView.Tool.ADD -> android.R.drawable.ic_menu_edit
            MaskEditView.Tool.ERASE -> R.drawable.ic_eraser
        })
        binding.btnMaskTool.contentDescription = getString(R.string.mask_tool_current, getString(label))
        TooltipCompat.setTooltipText(binding.btnMaskTool, binding.btnMaskTool.contentDescription)
        binding.bystanderHint.setText(if (preview) R.string.mask_preview_hint else when (tool) {
            MaskEditView.Tool.SELECT -> R.string.mask_select_hint
            MaskEditView.Tool.ADD -> R.string.mask_add_hint
            MaskEditView.Tool.ERASE -> R.string.mask_erase_hint
        })
        binding.btnMaskPreview.contentDescription = getString(if (preview) R.string.mask_edit else R.string.mask_preview)
        TooltipCompat.setTooltipText(binding.btnMaskPreview, binding.btnMaskPreview.contentDescription)
        binding.btnMaskPreview.iconTint = android.content.res.ColorStateList.valueOf(if (preview) 0xff63e6be.toInt() else android.graphics.Color.WHITE)

    }

    private fun selectBystander(x: Float, y: Float) {
        if (isProcessing) return
        val mask = bystanderMask ?: return
        val source = currentBitmap ?: return
        val snapshot = source.copy(Bitmap.Config.ARGB_8888, false)
        lifecycleScope.launch {
            isProcessing = true; showLoading(true, getString(R.string.mask_selecting))
            try {
                val selection = withContext(Dispatchers.Default) { InteractivePersonSelector(this@EditorActivity).select(snapshot, x, y) }
                if (selection.none { it.toInt() != 0 }) showError(getString(R.string.mask_no_selection))
                else {
                    mask.addSelection(selection)
                    currentPageBinding()?.maskEditView?.refresh()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { showError(getString(R.string.mask_failed, e.message ?: e.javaClass.simpleName)) }
            finally { snapshot.recycle(); isProcessing = false; showLoading(false) }
        }
    }

    private fun renderBystanderPreview() {
        if (isProcessing) return
        val mask = bystanderMask ?: return
        val page = currentPageBinding() ?: return
        val source = currentBitmap ?: return
        val snapshot = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = mask.copyPixels()
        val block = mosaicBlockSize
        lifecycleScope.launch {
            isProcessing = true; showLoading(true)
            try {
                withContext(Dispatchers.Default) { imageProcessor.applyMosaic(snapshot, pixels, mask.width, mask.height, block) }
                page.imageView.setImageBitmap(snapshot)
                page.maskEditView.preview = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { showError(getString(R.string.mask_failed, e.message ?: e.javaClass.simpleName)) }
            finally { isProcessing = false; showLoading(false) }
        }
    }

    private fun saveBystander(overwriteOriginal: Boolean) {
        if (isProcessing) return
        val mask = bystanderMask ?: return
        if (!mask.hasSelection) return
        val pixels = mask.copyPixels()
        val uri = imageUris[currentPosition]
        val block = mosaicBlockSize
        val suffix = if (overwriteOriginal) "tmp" else "png"
        val temp = imageProcessor.getTempFile("mosaic_${System.currentTimeMillis()}.$suffix")
        lifecycleScope.launch {
            isProcessing = true; showLoading(true, getString(R.string.mask_saving))
            try {
                val format = if (overwriteOriginal) imageProcessor.mosaicOverwriteFormat(uri) else Bitmap.CompressFormat.PNG
                if (format == null) {
                    showError(getString(R.string.mask_format_unsupported))
                    return@launch
                }
                check(imageProcessor.saveMosaic(uri, pixels, mask.width, mask.height, block, temp, format)) { "Cannot encode image" }
                persistBystanderSave(PendingSaveRequest(uri, temp, overwriteOriginal, isMosaic = true))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                pendingSaveRequest = null
                showError(getString(R.string.mask_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                if (pendingSaveRequest?.tempFile != temp) temp.delete()
                isProcessing = pendingSaveRequest?.isMosaic == true
                showLoading(false)
            }
        }
    }

    private suspend fun persistBystanderSave(pending: PendingSaveRequest, allowPermissionRequest: Boolean = true) {
        val result = if (pending.overwriteOriginal) {
            repository.saveCroppedImage(pending.sourceUri, pending.tempFile, overwriteOriginal = true)
        } else repository.saveNewPng(pending.tempFile)
        when (result) {
            is SaveImageResult.Success -> {
                pendingSaveRequest = null
                android.util.Log.d("BystanderEditor", "Saved mosaic: ${result.uri} overwrite=${pending.overwriteOriginal}")
                leaveBystander(restoreImage = !pending.overwriteOriginal)
                handleSaveSuccess(pending.overwriteOriginal)
            }
            is SaveImageResult.RequiresPermission -> {
                check(allowPermissionRequest) { "Write permission was not granted" }
                pendingSaveRequest = pending
                requestWritePermission(result.intentSender)
            }
            is SaveImageResult.Error -> throw result.throwable ?: IllegalStateException("Cannot save image")
        }
    }

    private fun loadCurrentImageWhenReady() {
        binding.viewPager.post {
            if (isProcessing || currentBitmap != null ||
                binding.viewPager.scrollState != ViewPager2.SCROLL_STATE_IDLE) return@post
            val holder = (binding.viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
                ?.findViewHolderForAdapterPosition(currentPosition) as? ImagePagerAdapter.ImagePageViewHolder
            holder?.getBinding()?.let { detectBlackBorders(it) }
        }
    }

    private fun setupButtons() {
        binding.btnSegment.setOnClickListener { testSegmentation() }
        binding.btnSegmentModel.setOnClickListener { showSegmentationModels() }
        updateSegmentationUi(false)
        binding.btnSegment.isEnabled = currentBitmap != null && !isProcessing
        binding.btnCrop.setOnClickListener {
            performCrop()
        }

        binding.btnCancel.setOnClickListener {
            deleteCurrentImage()
        }

        binding.btnDetectPose.setOnClickListener {
            detectPersonsAndGenerateCropBoxes()
        }

        // 展开/收起保存选项
        binding.btnExpandOptions.setOnClickListener {
            toggleSaveOptions()
        }

        // 导航按钮
        binding.btnPrevImage.setOnClickListener {
            if (!isProcessing && !isBystanderEditing && binding.viewPager.scrollState == ViewPager2.SCROLL_STATE_IDLE && currentPosition > 0) {
                binding.viewPager.setCurrentItem(currentPosition - 1, true)
            }
        }

        binding.btnNextImage.setOnClickListener {
            if (!isProcessing && !isBystanderEditing && binding.viewPager.scrollState == ViewPager2.SCROLL_STATE_IDLE && currentPosition < imageUris.size - 1) {
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
        val canNavigate = !isProcessing && !isBystanderEditing && binding.viewPager.scrollState == ViewPager2.SCROLL_STATE_IDLE
        binding.btnPrevImage.isEnabled = canNavigate
        binding.btnNextImage.isEnabled = canNavigate
        // 第一张图片时隐藏"上一张"按钮
        binding.btnPrevImage.visibility = if (!isBystanderEditing && currentPosition > 0) View.VISIBLE else View.GONE

        // 最后一张图片时隐藏"下一张"按钮
        binding.btnNextImage.visibility = if (!isBystanderEditing && currentPosition < imageUris.size - 1) View.VISIBLE else View.GONE
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

    private fun detectBlackBorders(pageBinding: PageEditorImageBinding) {
        if (isProcessing) return

        lifecycleScope.launch {
            isProcessing = true
            showLoading(true, getString(R.string.detecting_borders))

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

                currentBitmap?.recycle()
                currentBitmap = bitmap
                currentLetterboxResult = letterboxResult
                currentCropBoxes = emptyList()

                // 如果检测到黑边，自动生成覆盖内容区域的裁剪框
                if (letterboxResult != null && letterboxResult.letterboxRegions.isNotEmpty()) {
                    val bounds = letterboxResult.bounds
                    val cropBox = CropBox(
                        id = 0,
                        rect = RectF(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat()),
                        isSelected = true
                    )
                    currentCropBoxes = listOf(cropBox)
                }

                // 更新 UI - 显示黑边可视化和裁剪框
                withContext(Dispatchers.Main) {
                    updateOverlayView(pageBinding, letterboxResult?.letterboxRegions ?: emptyList())
                }

            } catch (e: Exception) {
                e.printStackTrace()
                showError("Error loading image: ${e.message}")
            } finally {
                isProcessing = false
                showLoading(false)
            }
        }
    }

    private fun detectPersonsAndGenerateCropBoxes() {
        if (isProcessing) return

        if (!isPersonDetectorReady) {
            showStatus(getString(R.string.pose_detector_not_ready))
            return
        }

        val bitmap = currentBitmap
        if (bitmap == null) {
            showError("No image loaded")
            return
        }

        val holder = (binding.viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
            ?.findViewHolderForAdapterPosition(currentPosition) as? ImagePagerAdapter.ImagePageViewHolder
        val pageBinding = holder?.getBinding() ?: return
        val visible = pageBinding.cropOverlayView.getVisibleImageRect()
        // Use only pixels wholly inside the visible image, excluding the letterboxed screen area.
        val left = ceil(visible.left).toInt().coerceIn(0, bitmap.width - 1)
        val top = ceil(visible.top).toInt().coerceIn(0, bitmap.height - 1)
        val right = floor(visible.right).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = floor(visible.bottom).toInt().coerceIn(top + 1, bitmap.height)

        lifecycleScope.launch {
            isProcessing = true
            showLoading(true, getString(R.string.detecting_pose))
            var detectionBitmap: Bitmap? = null
            try {
                val regionBitmap = withContext(Dispatchers.Default) {
                    Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                }
                detectionBitmap = regionBitmap
                val persons = personDetector.detectPersons(regionBitmap)
                val borders = withContext(Dispatchers.Default) {
                    com.autocrop.album.domain.LetterboxDetector.detect(regionBitmap)
                }
                val localBoxes = if (persons.isNotEmpty()) {
                    CropCalculator.generateCropBoxes(persons, regionBitmap.width, regionBitmap.height,
                        borders, settingsManager.getExpansionRatio())
                } else {
                    showStatus(getString(R.string.no_person_detected))
                    listOf(CropCalculator.createManualCropBox(0, regionBitmap.width, regionBitmap.height))
                }
                // Detection coordinates are local to the snapshot; restore preview-image coordinates.
                val snapshotRegion = ImageViewport.Region(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
                currentCropBoxes = localBoxes.map { box ->
                    val r = box.rect
                    val mapped = snapshotRegion.fromLocal(ImageViewport.Region(r.left, r.top, r.right, r.bottom))
                    box.copyWithRect(RectF(mapped.left, mapped.top, mapped.right, mapped.bottom))
                }
                updateOverlayView(pageBinding, currentLetterboxResult?.letterboxRegions ?: emptyList())
            } catch (e: Exception) {
                e.printStackTrace()
                showError("Error detecting persons: ${e.message}")
            } finally {
                detectionBitmap?.takeIf { it !== bitmap }?.recycle()
                isProcessing = false
                showLoading(false)
            }
        }
    }

    private fun updateOverlayView(
        pageBinding: PageEditorImageBinding,
        letterboxRegions: List<android.graphics.Rect> = emptyList()
    ) {
        val imageView = pageBinding.imageView
        val overlayView = pageBinding.cropOverlayView

        // 使用检测时的 bitmap 尺寸（而非 drawable 尺寸）确保坐标一致
        val detectionBitmap = currentBitmap
        if (detectionBitmap == null) return

        // 计算图片在 ImageView 中的位置和缩放
        imageView.post {
            if (currentBitmap !== detectionBitmap || detectionBitmap.isRecycled) return@post
            val imageWidth = detectionBitmap.width
            val imageHeight = detectionBitmap.height

            if (imageWidth == 0 || imageHeight == 0) return@post

            // Both views use preview-image coordinates; zoom never modifies crop geometry.
            overlayView.setImageTransform(imageWidth, imageHeight)

            // 设置黑边区域（debug可视化）
            overlayView.setLetterboxRegions(letterboxRegions)

            // 设置裁剪框
            overlayView.setCropBoxes(currentCropBoxes)
        }
    }

    private fun createManualCropBox() {
        if (currentBitmap == null || isBystanderEditing || currentPageBinding()?.cropOverlayView?.isShowingSegmentation == true) return

        val currentHolder = (binding.viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
            ?.findViewHolderForAdapterPosition(currentPosition) as? ImagePagerAdapter.ImagePageViewHolder
        val visible = currentHolder?.getBinding()?.cropOverlayView?.getVisibleImageRect() ?: return
        if (isProcessing || visible.isEmpty) return
        val newBox = CropBox(
            id = currentCropBoxes.size,
            rect = RectF(visible.left + visible.width() * 0.25f, visible.top + visible.height() * 0.25f,
                visible.right - visible.width() * 0.25f, visible.bottom - visible.height() * 0.25f),
            isSelected = true
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
                    pageBinding.cropOverlayView.resetImage()
                    pageBinding.imageView.resetPreviewTransform()
                    // 重新加载图片到 ImageView
                    com.bumptech.glide.Glide.with(pageBinding.imageView)
                        .load(uri)
                        .skipMemoryCache(true)
                        .into(pageBinding.imageView)

                    // 重新检测黑边
                    pageBinding.imageView.post {
                        detectBlackBorders(pageBinding)
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
                val mosaic = pendingSaveRequest?.isMosaic == true
                if (mosaic) pendingSaveRequest?.tempFile?.delete()
                pendingSaveRequest = null
                if (!mosaic) showError(getString(R.string.crop_failed))
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
                if (pending.isMosaic) {
                    persistBystanderSave(pending, allowPermissionRequest = false)
                    return@launch
                }
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
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                pendingSaveRequest = null
                showError(if (pending.isMosaic) getString(R.string.mask_failed, e.message ?: e.javaClass.simpleName)
                    else getString(R.string.crop_failed))
            } finally {
                if (pending.isMosaic && pendingSaveRequest?.tempFile != pending.tempFile) pending.tempFile.delete()
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
        val holder = (binding.viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
            ?.findViewHolderForAdapterPosition(currentPosition) as? ImagePagerAdapter.ImagePageViewHolder
        holder?.getBinding()?.cropOverlayView?.interactionEnabled = !isProcessing
        updateNavigationButtons()
        binding.btnDetectPose.isEnabled = !isProcessing && currentBitmap != null && isPersonDetectorReady
        binding.btnSegment.isEnabled = !isProcessing && currentBitmap != null
        binding.btnSegmentModel.isEnabled = !isProcessing && currentBitmap != null
        binding.btnBystander.isEnabled = !isProcessing && currentBitmap != null
        currentPageBinding()?.maskEditView?.isEnabled = !isProcessing
        updateBystanderControls()
        updateSegmentationUi(holder?.getBinding()?.cropOverlayView?.isShowingSegmentation == true)
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
        pendingSaveRequest?.takeIf { it.isMosaic }?.tempFile?.delete()
        personDetector.close()
        currentBitmap?.recycle()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                segmentationTouch = isBystanderEditing || currentPageBinding()?.cropOverlayView?.isShowingSegmentation == true
                imageSwipeCandidate = false
                if (!isProcessing && !isBystanderEditing && currentBitmap != null &&
                    binding.viewPager.scrollState == ViewPager2.SCROLL_STATE_IDLE) {
                    val holder = (binding.viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
                        ?.findViewHolderForAdapterPosition(currentPosition) as? ImagePagerAdapter.ImagePageViewHolder
                    holder?.getBinding()?.cropOverlayView?.let { overlay ->
                        val location = IntArray(2)
                        overlay.getLocationOnScreen(location)
                        val x = ev.rawX - location[0]
                        val y = ev.rawY - location[1]
                        imageSwipeCandidate = x >= 0 && x < overlay.width &&
                            y >= 0 && y < overlay.height && !overlay.isZoomed && !overlay.isShowingSegmentation && !overlay.isNearCropBox(x, y)
                    }
                }
                imageSwipeStartX = ev.rawX
                imageSwipeStartY = ev.rawY
                imageSwipePosition = currentPosition
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> imageSwipeCandidate = false
            MotionEvent.ACTION_UP -> {
                val dx = ev.rawX - imageSwipeStartX
                val dy = ev.rawY - imageSwipeStartY
                val minDistance = 48f * resources.displayMetrics.density
                if (imageSwipeCandidate && !isProcessing && currentPosition == imageSwipePosition &&
                    binding.viewPager.scrollState == ViewPager2.SCROLL_STATE_IDLE &&
                    abs(dx) >= minDistance && abs(dx) > abs(dy) * 1.5f) {
                    val nextPosition = currentPosition + if (dx < 0) 1 else -1
                    if (nextPosition in imageUris.indices) {
                        binding.viewPager.setCurrentItem(nextPosition, true)
                    }
                }
                imageSwipeCandidate = false
            }
        }
        if (!segmentationTouch) gestureDetector.onTouchEvent(ev)
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
        adapter = createImagePagerAdapter()
        binding.viewPager.adapter = adapter
        binding.viewPager.setCurrentItem(newPosition, false)
        currentPosition = newPosition

        // 更新导航按钮
        updateNavigationButtons()

        // 检测新图片的黑边
        binding.viewPager.post {
            val viewHolder = (binding.viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
                ?.findViewHolderForAdapterPosition(newPosition) as? ImagePagerAdapter.ImagePageViewHolder

            viewHolder?.getBinding()?.let { pageBinding ->
                detectBlackBorders(pageBinding)
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
        val overwriteOriginal: Boolean,
        val isMosaic: Boolean = false
    )

    override fun finish() {
        // 如果图片被修改，返回结果给 Gallery
        if (hasModified) {
            setResult(RESULT_OK, Intent().putExtra("MODIFIED_POSITION", currentPosition))
        }
        super.finish()
    }
}

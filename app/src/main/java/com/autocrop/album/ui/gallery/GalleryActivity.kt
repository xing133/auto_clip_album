package com.autocrop.album.ui.gallery

import android.content.Intent
import android.os.Bundle
import java.io.File
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.autocrop.album.R
import com.autocrop.album.data.ImageDataHolder
import com.autocrop.album.data.MediaStoreRepository
import com.autocrop.album.data.models.ImageItem
import com.autocrop.album.databinding.ActivityGalleryBinding
import com.autocrop.album.ui.common.PermissionHelper
import com.autocrop.album.ui.editor.EditorActivity
import com.autocrop.album.ui.settings.SettingsActivity
import android.os.Parcelable
import com.autocrop.album.data.SettingsManager
import com.autocrop.album.ui.common.showNumberInputDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Gallery Activity - 图片浏览界面
 */
class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private lateinit var repository: MediaStoreRepository
    private lateinit var adapter: GalleryAdapter

    private var currentSortOrder = ImageItem.Companion.SortOrder.NAME_DESC
    private var imageList: List<ImageItem> = emptyList()
    private var currentFolder: String? = null
    private var availableFolders: List<String> = emptyList()

    private var currentPage = 0
    private var pageCount = 0
    private var totalCount = 0
    private var pageSize = SettingsManager.DEFAULT_PAGE_SIZE
    private var isLoading = false
    private var loadJob: Job? = null
    private var loadVersion = 0
    private var restoredState: Parcelable? = null
    private var restoredFolder = false
    private var requestedPage = 0
    private lateinit var settingsLauncher: ActivityResultLauncher<Intent>

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var editorLauncher: ActivityResultLauncher<Intent>

    private fun initPermissionLauncher() {
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            if (permissions.all { it.value }) {
                // 所有权限已授权
                loadInitialImages()
            } else {
                // 权限被拒绝
                if (!PermissionHelper.shouldShowRationale(this)) {
                    // 用户选择了"不再询问"
                    PermissionHelper.showPermissionDeniedDialog(this)
                } else {
                    // 再次显示说明
                    PermissionHelper.showRationaleDialog(this) {
                        permissionLauncher.launch(PermissionHelper.getRequiredPermissions())
                    }
                }
            }
        }
    }

    private fun initEditorLauncher() {
        editorLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                // 图片被修改/删除，清除 Glide 缓存并重新查询列表
                lifecycleScope.launch {
                    // 清除 Glide 缓存
                    try {
                        withContext(Dispatchers.IO) {
                            com.bumptech.glide.Glide.get(this@GalleryActivity).clearDiskCache()
                        }
                        com.bumptech.glide.Glide.get(this@GalleryActivity).clearMemory()
                    } catch (e: Exception) {
                        android.util.Log.e("GalleryActivity", "Error clearing cache", e)
                    }

                    loadImages(currentPage, preserveScroll = true)
                }
            }
            // 如果 resultCode != RESULT_OK，说明没有修改，Gallery 自然保持原状态
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        initPermissionLauncher()
        initEditorLauncher()
        repository = MediaStoreRepository(this)
        pageSize = SettingsManager.getInstance(this).getPageSize()
        if (savedInstanceState != null) {
            currentPage = savedInstanceState.getInt("page")
            currentFolder = savedInstanceState.getString("folder")
            restoredFolder = true
            currentSortOrder = ImageItem.Companion.SortOrder.values()
                .getOrElse(savedInstanceState.getInt("sort")) { ImageItem.Companion.SortOrder.NAME_DESC }
            restoredState = savedInstanceState.getParcelable("grid")
        }
        setupRecyclerView()
        setupFolderSelector()
        setupPaging()
        settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val newSize = SettingsManager.getInstance(this).getPageSize()
            if (newSize != pageSize) {
                val manager = binding.recyclerView.layoutManager as GridLayoutManager
                val first = manager.findFirstVisibleItemPosition().coerceAtLeast(0)
                val absolute = currentPage.toLong() * pageSize + first
                pageSize = newSize
                loadImages((absolute / newSize).toInt(), anchor = (absolute % newSize).toInt())
            }
        }

        // 检查权限并加载（会自动查找 Screenshots 目录）
        checkPermissionsAndLoad()
    }

    // 移除 onResume 中的逻辑，让 Gallery 自然保持状态

    private fun setupRecyclerView() {
        adapter = GalleryAdapter { imageItem, position ->
            openEditor(position)
        }

        binding.recyclerView.apply {
            layoutManager = GridLayoutManager(this@GalleryActivity, 3)
            adapter = this@GalleryActivity.adapter
        }

        // 连接快速滚动条
        binding.fastScroller.attachToRecyclerView(binding.recyclerView)
    }

    private fun checkPermissionsAndLoad() {
        if (PermissionHelper.hasPermissions(this)) {
            loadInitialImages()
        } else {
            PermissionHelper.requestPermissions(permissionLauncher)
        }
    }

    private fun loadInitialImages() {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                availableFolders = repository.queryFolders()
                if (!restoredFolder) {
                    currentFolder = availableFolders.firstOrNull { folder ->
                        folder.contains("Screenshots", ignoreCase = true) ||
                            folder.contains("截屏", ignoreCase = true) ||
                            folder.contains("ScreenRecords", ignoreCase = true) ||
                            folder.contains("录屏", ignoreCase = true)
                    }
                }
                updateTitle(currentFolder)
                loadImages(currentPage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                binding.emptyView.visibility = View.VISIBLE
                binding.emptyView.text = getString(R.string.gallery_load_failed)
            }
        }
    }

    private fun setupPaging() {
        binding.btnPreviousPage.setOnClickListener { loadImages(currentPage - 1) }
        binding.btnNextPage.setOnClickListener { loadImages(currentPage + 1) }
        binding.btnPageNumber.setOnClickListener {
            if (pageCount > 0) showNumberInputDialog(this, getString(R.string.jump_to_page),
                currentPage + 1, 1, pageCount) { page ->
                if (page - 1 != currentPage) loadImages(page - 1)
            }
        }
        binding.emptyView.setOnClickListener {
            if (!isLoading) {
                if (availableFolders.isEmpty()) loadInitialImages() else loadImages(requestedPage)
            }
        }
        updatePaging()
    }

    private fun updatePaging() {
        binding.btnPreviousPage.isEnabled = !isLoading && currentPage > 0 && pageCount > 0
        binding.btnNextPage.isEnabled = !isLoading && currentPage + 1 < pageCount
        binding.btnPageNumber.isEnabled = !isLoading && pageCount > 0
        binding.btnPageNumber.text = getString(R.string.page_number, if (pageCount == 0) 0 else currentPage + 1, pageCount)
        val start = if (totalCount == 0) 0 else currentPage * pageSize + 1
        val end = minOf(currentPage.toLong() * pageSize + imageList.size, totalCount.toLong()).toInt()
        binding.pageRange.text = getString(R.string.page_range, start, end, totalCount)
    }

    private fun setupFolderSelector() {
        binding.btnSelectFolder.setOnClickListener {
            showFolderSelector()
        }
    }

    private fun showFolderSelector() {
        lifecycleScope.launch {
            try {
                if (availableFolders.isEmpty()) {
                    availableFolders = repository.queryFolders()
                }

                val folderNames = mutableListOf("All folders")
                folderNames.addAll(availableFolders.map { File(it).name })

                androidx.appcompat.app.AlertDialog.Builder(this@GalleryActivity)
                    .setTitle("Select Folder")
                    .setItems(folderNames.toTypedArray()) { dialog, which ->
                        currentFolder = if (which == 0) null else availableFolders[which - 1]
                        updateTitle(currentFolder)
                        loadImages()
                    }
                    .show()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun loadImages(page: Int = 0, preserveScroll: Boolean = false, anchor: Int = 0) {
        loadJob?.cancel()
        val version = ++loadVersion
        requestedPage = page
        val scrollState = if (preserveScroll) binding.recyclerView.layoutManager?.onSaveInstanceState() else restoredState
        restoredState = null
        val sort = currentSortOrder
        val folder = currentFolder
        val size = pageSize
        isLoading = true
        binding.progressBar.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE
        updatePaging()
        loadJob = lifecycleScope.launch {
            try {
                val result = repository.queryImagePage(sort, folder, page, size)
                if (version != loadVersion) return@launch
                currentPage = result.window.pageIndex
                pageCount = result.window.pageCount
                totalCount = result.window.totalCount
                imageList = result.images
                binding.recyclerView.recycledViewPool.clear()
                adapter.submitList(imageList) {
                    if (version == loadVersion) {
                        val manager = binding.recyclerView.layoutManager as GridLayoutManager
                        if (scrollState != null && currentPage == page) manager.onRestoreInstanceState(scrollState)
                        else manager.scrollToPositionWithOffset(anchor.coerceAtMost((imageList.size - 1).coerceAtLeast(0)), 0)
                        binding.recyclerView.visibility = if (imageList.isEmpty()) View.GONE else View.VISIBLE
                        binding.fastScroller.visibility = if (imageList.isEmpty()) View.GONE else View.VISIBLE
                        binding.emptyView.text = getString(R.string.no_images_found)
                        binding.emptyView.visibility = if (imageList.isEmpty()) View.VISIBLE else View.GONE
                        isLoading = false
                        binding.progressBar.visibility = View.GONE
                        updatePaging()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (version == loadVersion) {
                    android.util.Log.e("GalleryActivity", "Error loading page", e)
                    binding.recyclerView.visibility = View.GONE
                    binding.fastScroller.visibility = View.GONE
                    binding.emptyView.visibility = View.VISIBLE
                    binding.emptyView.text = getString(R.string.gallery_load_failed)
                    isLoading = false
                    binding.progressBar.visibility = View.GONE
                    updatePaging()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", currentPage)
        outState.putString("folder", currentFolder)
        outState.putInt("sort", currentSortOrder.ordinal)
        outState.putParcelable("grid", binding.recyclerView.layoutManager?.onSaveInstanceState())
    }

    private fun openEditor(position: Int) {
        if (isLoading || position !in imageList.indices) return
        // 使用 ImageDataHolder 共享数据，避免通过 Intent 传递大量数据
        ImageDataHolder.setImageUris(imageList.map { it.uri })

        val intent = Intent(this, EditorActivity::class.java).apply {
            putExtra(EditorActivity.EXTRA_IMAGE_POSITION, position)
        }
        editorLauncher.launch(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_gallery, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.sort_by_name_asc -> {
                currentSortOrder = ImageItem.Companion.SortOrder.NAME_ASC
                loadImages()
                true
            }
            R.id.sort_by_date_modified -> {
                currentSortOrder = if (currentSortOrder == ImageItem.Companion.SortOrder.DATE_MODIFIED_DESC) {
                    ImageItem.Companion.SortOrder.DATE_MODIFIED_ASC
                } else {
                    ImageItem.Companion.SortOrder.DATE_MODIFIED_DESC
                }
                loadImages()
                true
            }
            R.id.sort_by_date_taken -> {
                currentSortOrder = if (currentSortOrder == ImageItem.Companion.SortOrder.DATE_TAKEN_DESC) {
                    ImageItem.Companion.SortOrder.DATE_TAKEN_ASC
                } else {
                    ImageItem.Companion.SortOrder.DATE_TAKEN_DESC
                }
                loadImages()
                true
            }
            R.id.action_settings -> {
                openSettings()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        settingsLauncher.launch(intent)
    }

    private fun updateTitle(folderPath: String?) {
        if (folderPath == null) {
            binding.toolbar.title = getString(R.string.gallery_title)
        } else {
            binding.toolbar.title = File(folderPath).name
        }
    }
}

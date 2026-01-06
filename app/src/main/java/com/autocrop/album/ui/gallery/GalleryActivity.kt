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

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var editorLauncher: ActivityResultLauncher<Intent>

    private fun initPermissionLauncher() {
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            if (permissions.all { it.value }) {
                // 所有权限已授权
                loadImages()
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

                    // 重新查询列表以获取最新的 MediaStore 数据
                    val newImageList = repository.queryImages(currentSortOrder, currentFolder)

                    if (newImageList != imageList) {
                        imageList = newImageList
                        // 清除 RecyclerView 的缓存池，避免删除文件后出现错位
                        // 特别是网格布局（3列），缓存的 ViewHolder 可能持有旧的图片引用
                        binding.recyclerView.recycledViewPool.clear()
                        // 使用 submitList，DiffUtil 会智能保持滚动位置
                        // 不手动调用 scrollToPosition，让 RecyclerView 自然保持状态
                        adapter.submitList(imageList)
                    }
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
        setupRecyclerView()
        setupFolderSelector()

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
        lifecycleScope.launch {
            // 先查找 Screenshots 目录
            try {
                availableFolders = repository.queryFolders()
                currentFolder = availableFolders.firstOrNull { folder ->
                    folder.contains("Screenshots", ignoreCase = true) ||
                    folder.contains("截屏", ignoreCase = true) ||
                    folder.contains("ScreenRecords", ignoreCase = true) ||
                    folder.contains("录屏", ignoreCase = true)
                }
                updateTitle(currentFolder)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 然后加载图片
            if (PermissionHelper.hasPermissions(this@GalleryActivity)) {
                loadImages()
            } else {
                if (PermissionHelper.shouldShowRationale(this@GalleryActivity)) {
                    PermissionHelper.showRationaleDialog(this@GalleryActivity) {
                        PermissionHelper.requestPermissions(permissionLauncher)
                    }
                } else {
                    PermissionHelper.requestPermissions(permissionLauncher)
                }
            }
        }
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

    private fun loadImages() {
        // 只在没有数据时显示进度条，避免闪烁
        if (imageList.isEmpty()) {
            binding.progressBar.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
            binding.emptyView.visibility = View.GONE
        }

        lifecycleScope.launch {
            try {
                imageList = repository.queryImages(currentSortOrder, currentFolder)

                if (imageList.isEmpty()) {
                    binding.emptyView.visibility = View.VISIBLE
                    binding.recyclerView.visibility = View.GONE
                } else {
                    binding.recyclerView.visibility = View.VISIBLE
                    binding.emptyView.visibility = View.GONE
                    adapter.submitList(imageList)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                binding.emptyView.visibility = View.VISIBLE
                binding.recyclerView.visibility = View.GONE
                binding.emptyView.text = getString(R.string.error_occurred)
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun openEditor(position: Int) {
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
        startActivity(intent)
    }

    private fun updateTitle(folderPath: String?) {
        if (folderPath == null) {
            binding.toolbar.title = getString(R.string.gallery_title)
        } else {
            binding.toolbar.title = File(folderPath).name
        }
    }
}

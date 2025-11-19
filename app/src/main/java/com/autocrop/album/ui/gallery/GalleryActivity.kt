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

    private var currentSortOrder = ImageItem.Companion.SortOrder.DATE_MODIFIED_DESC
    private var imageList: List<ImageItem> = emptyList()
    private var currentFolder: String? = null
    private var availableFolders: List<String> = emptyList()

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        initPermissionLauncher()
        repository = MediaStoreRepository(this)
        setupRecyclerView()
        setupFolderSelector()

        // 默认显示 Screenshots 目录
        findScreenshotsFolder()

        checkPermissionsAndLoad()
    }

    override fun onResume() {
        super.onResume()
        // 从编辑页面返回时刷新列表
        if (PermissionHelper.hasPermissions(this)) {
            // 清除 Glide 缓存并重新加载图片列表，确保显示最新的图片
            lifecycleScope.launch {
                try {
                    android.util.Log.d("GalleryActivity", "Clearing Glide cache and reloading images...")

                    // 先清除磁盘缓存（后台线程）
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.bumptech.glide.Glide.get(this@GalleryActivity).clearDiskCache()
                    }

                    // 再清除内存缓存（主线程）
                    com.bumptech.glide.Glide.get(this@GalleryActivity).clearMemory()

                    android.util.Log.d("GalleryActivity", "Cache cleared, reloading images...")
                } catch (e: Exception) {
                    android.util.Log.e("GalleryActivity", "Error clearing cache", e)
                    e.printStackTrace()
                }

                // 重新加载图片列表（这会刷新 MediaStore 数据，包括新的 dateModified）
                loadImages()
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = GalleryAdapter { imageItem, position ->
            openEditor(position)
        }

        binding.recyclerView.apply {
            layoutManager = GridLayoutManager(this@GalleryActivity, 3)
            adapter = this@GalleryActivity.adapter
        }
    }

    private fun checkPermissionsAndLoad() {
        if (PermissionHelper.hasPermissions(this)) {
            loadImages()
        } else {
            if (PermissionHelper.shouldShowRationale(this)) {
                PermissionHelper.showRationaleDialog(this) {
                    PermissionHelper.requestPermissions(permissionLauncher)
                }
            } else {
                PermissionHelper.requestPermissions(permissionLauncher)
            }
        }
    }

    private fun setupFolderSelector() {
        binding.btnSelectFolder.setOnClickListener {
            showFolderSelector()
        }
    }

    private fun findScreenshotsFolder() {
        lifecycleScope.launch {
            try {
                availableFolders = repository.queryFolders()
                // 查找 Screenshots 或 截屏 目录
                currentFolder = availableFolders.firstOrNull { folder ->
                    folder.contains("Screenshots", ignoreCase = true) ||
                    folder.contains("截屏", ignoreCase = true) ||
                    folder.contains("ScreenRecords", ignoreCase = true) ||
                    folder.contains("录屏", ignoreCase = true)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
                        loadImages()
                    }
                    .show()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun loadImages() {
        binding.progressBar.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.GONE
        binding.emptyView.visibility = View.GONE

        lifecycleScope.launch {
            try {
                imageList = repository.queryImages(currentSortOrder, currentFolder)

                if (imageList.isEmpty()) {
                    binding.emptyView.visibility = View.VISIBLE
                } else {
                    binding.recyclerView.visibility = View.VISIBLE
                    adapter.submitList(imageList)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                binding.emptyView.visibility = View.VISIBLE
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
        startActivity(intent)
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
            else -> super.onOptionsItemSelected(item)
        }
    }
}

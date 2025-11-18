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

    // DEBUG MODE: 自动加载特定图片
    private val DEBUG_TARGET_PATH = "/storage/emulated/0/DCIM/Screenshots/Screenshot_2025-10-11-19-58-09-203_com.ss.android.ugc.aweme.jpg"
    private var debugAutoLoadCompleted = false

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
            // 清除 Glide 缓存，确保显示最新的图片
            lifecycleScope.launch {
                try {
                    // 清除内存缓存（主线程）
                    com.bumptech.glide.Glide.get(this@GalleryActivity).clearMemory()

                    // 清除磁盘缓存（后台线程）
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.bumptech.glide.Glide.get(this@GalleryActivity).clearDiskCache()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // 重新加载图片列表
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

                    // DEBUG MODE: 自动加载目标图片（仅首次）
                    if (!debugAutoLoadCompleted && File(DEBUG_TARGET_PATH).exists()) {
                        debugAutoLoadCompleted = true
                        autoLoadDebugTarget()
                    }
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

    /**
     * DEBUG MODE: 自动加载目标图片
     */
    private fun autoLoadDebugTarget() {
        lifecycleScope.launch {
            try {
                // 使用 IO 线程直接查询目标图片的 URI
                val targetUri = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val projection = arrayOf(
                        android.provider.MediaStore.Images.Media._ID,
                        android.provider.MediaStore.Images.Media.DATA
                    )
                    val selection = "${android.provider.MediaStore.Images.Media.DATA} = ?"
                    val selectionArgs = arrayOf(DEBUG_TARGET_PATH)

                    val cursor = contentResolver.query(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        projection,
                        selection,
                        selectionArgs,
                        null
                    )

                    cursor?.use {
                        if (it.moveToFirst()) {
                            val id = it.getLong(it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID))
                            android.net.Uri.withAppendedPath(
                                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                id.toString()
                            )
                        } else null
                    }
                }

                if (targetUri != null) {
                    // 在图片列表中查找这个 URI 的位置
                    val targetPosition = imageList.indexOfFirst { it.uri == targetUri }

                    if (targetPosition >= 0) {
                        android.util.Log.w("GalleryActivity", "🎯 DEBUG TARGET FOUND at position $targetPosition! Auto-loading...")
                        // 延迟一点时间，确保 UI 完全加载
                        kotlinx.coroutines.delay(500)
                        openEditor(targetPosition)
                    } else {
                        android.util.Log.w("GalleryActivity", "⚠️ DEBUG TARGET URI found but not in current list. URI: $targetUri")
                    }
                } else {
                    android.util.Log.w("GalleryActivity", "⚠️ DEBUG TARGET not found in MediaStore. Path: $DEBUG_TARGET_PATH")

                    // DEBUG: 输出 Screenshots 文件夹中的前10张图片路径
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val projection = arrayOf(
                                android.provider.MediaStore.Images.Media._ID,
                                android.provider.MediaStore.Images.Media.DATA,
                                android.provider.MediaStore.Images.Media.DISPLAY_NAME
                            )
                            val selection = "${android.provider.MediaStore.Images.Media.DATA} LIKE ?"
                            val selectionArgs = arrayOf("%/DCIM/Screenshots/%")

                            val cursor = contentResolver.query(
                                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                projection,
                                selection,
                                selectionArgs,
                                "${android.provider.MediaStore.Images.Media.DATE_MODIFIED} DESC"
                            )

                            cursor?.use {
                                android.util.Log.d("GalleryActivity", "📁 Screenshots folder contains ${it.count} total images")
                                android.util.Log.d("GalleryActivity", "   Showing first 10:")
                                var count = 0
                                while (it.moveToNext() && count < 10) {
                                    val path = it.getString(it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DATA))
                                    val name = it.getString(it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DISPLAY_NAME))
                                    android.util.Log.d("GalleryActivity", "  [$count] name=$name")
                                    android.util.Log.d("GalleryActivity", "      path=$path")
                                    if (path.contains("2025-10-11")) {
                                        android.util.Log.w("GalleryActivity", "      ⭐ This matches our target date!")
                                    }
                                    count++
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("GalleryActivity", "Error listing screenshots", e)
                            e.printStackTrace()
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("GalleryActivity", "Error in autoLoadDebugTarget", e)
                e.printStackTrace()
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

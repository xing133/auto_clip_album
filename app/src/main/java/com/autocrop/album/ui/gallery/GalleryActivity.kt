package com.autocrop.album.ui.gallery

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.autocrop.album.R
import com.autocrop.album.data.MediaStoreRepository
import com.autocrop.album.data.models.ImageItem
import com.autocrop.album.databinding.ActivityGalleryBinding
import com.autocrop.album.ui.common.PermissionHelper
import com.autocrop.album.ui.editor.EditorActivity
import kotlinx.coroutines.launch

/**
 * Gallery Activity - 图片浏览界面
 */
class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private lateinit var repository: MediaStoreRepository
    private lateinit var adapter: GalleryAdapter

    private var currentSortOrder = ImageItem.Companion.SortOrder.DATE_MODIFIED_DESC
    private var imageList: List<ImageItem> = emptyList()

    private val permissionLauncher = registerForActivityResult(
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
                    PermissionHelper.requestPermissions(permissionLauncher)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        repository = MediaStoreRepository(this)
        setupRecyclerView()

        checkPermissionsAndLoad()
    }

    override fun onResume() {
        super.onResume()
        // 从编辑页面返回时刷新列表
        if (PermissionHelper.hasPermissions(this)) {
            loadImages()
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

    private fun loadImages() {
        binding.progressBar.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.GONE
        binding.emptyView.visibility = View.GONE

        lifecycleScope.launch {
            try {
                imageList = repository.queryImages(currentSortOrder)

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
        val intent = Intent(this, EditorActivity::class.java).apply {
            putExtra(EditorActivity.EXTRA_IMAGE_POSITION, position)
            putParcelableArrayListExtra(
                EditorActivity.EXTRA_IMAGE_URIS,
                ArrayList(imageList.map { it.uri })
            )
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

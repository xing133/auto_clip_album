package com.autocrop.album.ui.editor

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.autocrop.album.databinding.PageEditorImageBinding
import com.bumptech.glide.Glide

/**
 * ViewPager2 适配器 - 用于图片滑动浏览
 */
class ImagePagerAdapter(
    private val imageUris: List<Uri>,
    private val onImageLoaded: (Int, PageEditorImageBinding) -> Unit
) : RecyclerView.Adapter<ImagePagerAdapter.ImagePageViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImagePageViewHolder {
        val binding = PageEditorImageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ImagePageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ImagePageViewHolder, position: Int) {
        holder.bind(imageUris[position], position)
    }

    override fun getItemCount(): Int = imageUris.size

    override fun onViewRecycled(holder: ImagePageViewHolder) {
        holder.getBinding().cropOverlayView.clearSegmentation()
        super.onViewRecycled(holder)
    }

    inner class ImagePageViewHolder(
        private val binding: PageEditorImageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(uri: Uri, position: Int) {
            binding.maskEditView.mask = null
            binding.maskEditView.visibility = android.view.View.GONE
            binding.maskEditView.viewport = binding.cropOverlayView
            binding.cropOverlayView.resetImage()
            binding.imageView.resetPreviewTransform()
            binding.cropOverlayView.onImageTransformChanged = { width, height, scale, x, y ->
                binding.imageView.setPreviewTransform(width, height, scale, x, y)
                binding.maskEditView.setImageTransform(scale, x, y)
            }
            Glide.with(binding.imageView)
                .load(uri)
                .fitCenter()
                .into(binding.imageView)

            // 图片加载完成后回调
            binding.imageView.post {
                onImageLoaded(position, binding)
            }
        }

        fun getBinding(): PageEditorImageBinding = binding
    }
}

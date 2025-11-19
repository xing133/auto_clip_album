package com.autocrop.album.ui.gallery

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.autocrop.album.data.models.ImageItem
import com.autocrop.album.databinding.ItemGalleryImageBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.signature.ObjectKey

/**
 * Gallery 图片网格适配器
 */
class GalleryAdapter(
    private val onItemClick: (ImageItem, Int) -> Unit
) : ListAdapter<ImageItem, GalleryAdapter.ImageViewHolder>(ImageDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val binding = ItemGalleryImageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ImageViewHolder(binding, onItemClick)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    class ImageViewHolder(
        private val binding: ItemGalleryImageBinding,
        private val onItemClick: (ImageItem, Int) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ImageItem, position: Int) {
            // 使用图片的ID和修改时间作为签名，确保文件删除/修改后缓存会更新
            Glide.with(binding.imageView)
                .load(item.uri)
                .fitCenter()  // 显示完整图片，不裁剪
                .signature(ObjectKey("${item.id}_${item.dateModified.time}"))
                .skipMemoryCache(true)  // 跳过内存缓存，确保每次从磁盘重新加载
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(binding.imageView)

            binding.cardView.setOnClickListener {
                onItemClick(item, position)
            }
        }
    }

    private class ImageDiffCallback : DiffUtil.ItemCallback<ImageItem>() {
        override fun areItemsTheSame(oldItem: ImageItem, newItem: ImageItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ImageItem, newItem: ImageItem): Boolean {
            return oldItem == newItem
        }
    }
}

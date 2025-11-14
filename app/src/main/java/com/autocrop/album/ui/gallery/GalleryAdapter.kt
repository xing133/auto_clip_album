package com.autocrop.album.ui.gallery

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.autocrop.album.data.models.ImageItem
import com.autocrop.album.databinding.ItemGalleryImageBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions

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
            Glide.with(binding.imageView)
                .load(item.uri)
                .centerCrop()
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

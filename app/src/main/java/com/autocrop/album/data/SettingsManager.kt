package com.autocrop.album.data

import android.content.Context
import android.content.SharedPreferences
import com.autocrop.album.domain.SegmentationModel

/**
 * 设置管理器 - 使用 SharedPreferences 保存用户配置
 */
class SettingsManager private constructor(context: Context) {

    private val sharedPreferences: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "auto_crop_album_settings"
        private const val KEY_CROP_ASPECT_NUMERATOR = "crop_aspect_numerator"
        const val DEFAULT_CROP_ASPECT_NUMERATOR = com.autocrop.album.domain.CropAspectRatio.DEFAULT_NUMERATOR
        const val MIN_CROP_ASPECT_NUMERATOR = com.autocrop.album.domain.CropAspectRatio.MIN_NUMERATOR
        const val MAX_CROP_ASPECT_NUMERATOR = com.autocrop.album.domain.CropAspectRatio.MAX_NUMERATOR
        private const val KEY_SEGMENTATION_MODEL = "segmentation_model"
        private const val KEY_SEGMENTATION_CROP_PADDING = "segmentation_crop_padding_percent"
        const val DEFAULT_SEGMENTATION_CROP_PADDING = 2
        const val MIN_SEGMENTATION_CROP_PADDING = 0
        const val MAX_SEGMENTATION_CROP_PADDING = 20
        private const val KEY_MOSAIC_BLOCK_SIZE = "mosaic_block_size"
        const val DEFAULT_MOSAIC_BLOCK_SIZE = 20
        const val MIN_MOSAIC_BLOCK_SIZE = 8
        const val MAX_MOSAIC_BLOCK_SIZE = 48
        const val MOSAIC_BLOCK_SIZE_STEP = 4
        private const val KEY_PAGE_SIZE = "page_size"
        const val DEFAULT_PAGE_SIZE = 1000
        const val MIN_PAGE_SIZE = 1
        const val MAX_PAGE_SIZE = 10000
        private const val KEY_EXPANSION_RATIO = "expansion_ratio"

        // 默认值：裁剪框在人物检测框外统一扩展的比例
        const val DEFAULT_EXPANSION_RATIO =
            com.autocrop.album.domain.CropCalculator.DEFAULT_EXPANSION_RATIO

        @Volatile
        private var instance: SettingsManager? = null

        fun getInstance(context: Context): SettingsManager {
            return instance ?: synchronized(this) {
                instance ?: SettingsManager(context).also { instance = it }
            }
        }
    }

    fun getCropAspectNumerator(): Int = sharedPreferences.getInt(KEY_CROP_ASPECT_NUMERATOR, DEFAULT_CROP_ASPECT_NUMERATOR)
        .coerceIn(MIN_CROP_ASPECT_NUMERATOR, MAX_CROP_ASPECT_NUMERATOR)

    fun setCropAspectNumerator(numerator: Int) {
        require(numerator in MIN_CROP_ASPECT_NUMERATOR..MAX_CROP_ASPECT_NUMERATOR)
        sharedPreferences.edit().putInt(KEY_CROP_ASPECT_NUMERATOR, numerator).apply()
    }

    fun getSegmentationModel(): SegmentationModel = SegmentationModel.fromKey(
        sharedPreferences.getString(KEY_SEGMENTATION_MODEL, null))

    fun setSegmentationModel(model: SegmentationModel) {
        sharedPreferences.edit().putString(KEY_SEGMENTATION_MODEL, model.key).apply()
    }

    fun getSegmentationCropPaddingPercent(): Int = sharedPreferences.getInt(
        KEY_SEGMENTATION_CROP_PADDING, DEFAULT_SEGMENTATION_CROP_PADDING
    ).coerceIn(MIN_SEGMENTATION_CROP_PADDING, MAX_SEGMENTATION_CROP_PADDING)

    fun setSegmentationCropPaddingPercent(percent: Int) {
        require(percent in MIN_SEGMENTATION_CROP_PADDING..MAX_SEGMENTATION_CROP_PADDING)
        sharedPreferences.edit().putInt(KEY_SEGMENTATION_CROP_PADDING, percent).apply()
    }

    fun getMosaicBlockSize(): Int {
        val size = sharedPreferences.getInt(KEY_MOSAIC_BLOCK_SIZE, DEFAULT_MOSAIC_BLOCK_SIZE)
            .coerceIn(MIN_MOSAIC_BLOCK_SIZE, MAX_MOSAIC_BLOCK_SIZE)
        return MIN_MOSAIC_BLOCK_SIZE + (size - MIN_MOSAIC_BLOCK_SIZE) / MOSAIC_BLOCK_SIZE_STEP * MOSAIC_BLOCK_SIZE_STEP
    }

    fun setMosaicBlockSize(size: Int) {
        require(size in MIN_MOSAIC_BLOCK_SIZE..MAX_MOSAIC_BLOCK_SIZE &&
            (size - MIN_MOSAIC_BLOCK_SIZE) % MOSAIC_BLOCK_SIZE_STEP == 0)
        sharedPreferences.edit().putInt(KEY_MOSAIC_BLOCK_SIZE, size).apply()
    }

    fun getPageSize(): Int = sharedPreferences.getInt(KEY_PAGE_SIZE, DEFAULT_PAGE_SIZE)
        .coerceIn(MIN_PAGE_SIZE, MAX_PAGE_SIZE)

    fun setPageSize(size: Int) {
        require(size in MIN_PAGE_SIZE..MAX_PAGE_SIZE)
        sharedPreferences.edit().putInt(KEY_PAGE_SIZE, size).apply()
    }

    /**
     * 获取外扩系数（裁剪框在人物检测框外统一扩展的比例）
     */
    fun getExpansionRatio(): Float {
        return sharedPreferences.getFloat(
            KEY_EXPANSION_RATIO,
            DEFAULT_EXPANSION_RATIO
        )
    }

    /**
     * 设置外扩系数
     */
    fun setExpansionRatio(ratio: Float) {
        sharedPreferences.edit()
            .putFloat(KEY_EXPANSION_RATIO, ratio)
            .apply()
    }
}

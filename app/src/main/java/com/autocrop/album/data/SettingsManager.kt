package com.autocrop.album.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 设置管理器 - 使用 SharedPreferences 保存用户配置
 */
class SettingsManager private constructor(context: Context) {

    private val sharedPreferences: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "auto_crop_album_settings"
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

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
        private const val KEY_TOP_EXPANSION_MULTIPLIER = "top_expansion_multiplier"

        // 默认值：2.5（与 CropCalculator 中的原始值一致）
        const val DEFAULT_TOP_EXPANSION_MULTIPLIER = 2.5f

        @Volatile
        private var instance: SettingsManager? = null

        fun getInstance(context: Context): SettingsManager {
            return instance ?: synchronized(this) {
                instance ?: SettingsManager(context).also { instance = it }
            }
        }
    }

    /**
     * 获取顶部外扩系数（用于眼嘴距离计算）
     */
    fun getTopExpansionMultiplier(): Float {
        return sharedPreferences.getFloat(
            KEY_TOP_EXPANSION_MULTIPLIER,
            DEFAULT_TOP_EXPANSION_MULTIPLIER
        )
    }

    /**
     * 设置顶部外扩系数
     */
    fun setTopExpansionMultiplier(multiplier: Float) {
        sharedPreferences.edit()
            .putFloat(KEY_TOP_EXPANSION_MULTIPLIER, multiplier)
            .apply()
    }
}

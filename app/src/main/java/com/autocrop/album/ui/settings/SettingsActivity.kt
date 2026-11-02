package com.autocrop.album.ui.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.autocrop.album.R
import com.autocrop.album.data.SettingsManager
import com.autocrop.album.databinding.ActivitySettingsBinding
import java.util.Locale
import com.autocrop.album.ui.common.showNumberInputDialog

/**
 * Settings Activity - 设置界面
 * 允许用户配置应用参数
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settingsManager: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        settingsManager = SettingsManager.getInstance(this)

        setupUI()
        loadSettings()
    }

    private fun setupUI() {
        binding.btnSegmentationCropPadding.setOnClickListener {
            showNumberInputDialog(this, getString(R.string.segmentation_crop_padding_title),
                settingsManager.getSegmentationCropPaddingPercent(),
                SettingsManager.MIN_SEGMENTATION_CROP_PADDING, SettingsManager.MAX_SEGMENTATION_CROP_PADDING) { percent ->
                settingsManager.setSegmentationCropPaddingPercent(percent)
                updateSegmentationCropPadding()
            }
        }
        binding.btnPageSize.setOnClickListener {
            showNumberInputDialog(this, getString(R.string.page_size_title), settingsManager.getPageSize(),
                SettingsManager.MIN_PAGE_SIZE, SettingsManager.MAX_PAGE_SIZE) { size ->
                settingsManager.setPageSize(size)
                updatePageSize()
            }
        }
        // 滑块变化监听
        binding.sliderTopExpansion.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                updateValueDisplay(value)
                saveExpansionRatio(value)
            }
        }

        // 重置按钮
        binding.btnReset.setOnClickListener {
            resetToDefault()
        }
    }

    private fun updatePageSize() {
        binding.btnPageSize.text = getString(R.string.page_size_value, settingsManager.getPageSize())
    }

    private fun updateSegmentationCropPadding() {
        binding.btnSegmentationCropPadding.text = getString(R.string.segmentation_crop_padding_value,
            settingsManager.getSegmentationCropPaddingPercent())
    }

    private fun loadSettings() {
        updateSegmentationCropPadding()
        updatePageSize()
        val currentValue = settingsManager.getExpansionRatio()
        binding.sliderTopExpansion.value = currentValue
        updateValueDisplay(currentValue)
    }

    private fun updateValueDisplay(value: Float) {
        // 外扩系数以百分比展示，例如 0.08 -> "8%"
        binding.tvCurrentValue.text = String.format(Locale.getDefault(), "%.0f%%", value * 100)
    }

    private fun saveExpansionRatio(value: Float) {
        settingsManager.setExpansionRatio(value)
    }

    private fun resetToDefault() {
        val defaultValue = SettingsManager.DEFAULT_EXPANSION_RATIO
        binding.sliderTopExpansion.value = defaultValue
        updateValueDisplay(defaultValue)
        saveExpansionRatio(defaultValue)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

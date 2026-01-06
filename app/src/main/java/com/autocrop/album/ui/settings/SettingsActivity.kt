package com.autocrop.album.ui.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.autocrop.album.R
import com.autocrop.album.data.SettingsManager
import com.autocrop.album.databinding.ActivitySettingsBinding
import java.util.Locale

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
        // 滑块变化监听
        binding.sliderTopExpansion.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                updateValueDisplay(value)
                saveTopExpansionMultiplier(value)
            }
        }

        // 重置按钮
        binding.btnReset.setOnClickListener {
            resetToDefault()
        }
    }

    private fun loadSettings() {
        val currentValue = settingsManager.getTopExpansionMultiplier()
        binding.sliderTopExpansion.value = currentValue
        updateValueDisplay(currentValue)
    }

    private fun updateValueDisplay(value: Float) {
        binding.tvCurrentValue.text = String.format(Locale.getDefault(), "%.1f", value)
    }

    private fun saveTopExpansionMultiplier(value: Float) {
        settingsManager.setTopExpansionMultiplier(value)
    }

    private fun resetToDefault() {
        val defaultValue = SettingsManager.DEFAULT_TOP_EXPANSION_MULTIPLIER
        binding.sliderTopExpansion.value = defaultValue
        updateValueDisplay(defaultValue)
        saveTopExpansionMultiplier(defaultValue)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

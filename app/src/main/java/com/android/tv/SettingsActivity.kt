package com.android.tv

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var editSourceUrl: EditText
    private lateinit var sourceCacheStatus: TextView
    private lateinit var sourceTestStatus: TextView
    private lateinit var testSourceButton: Button
    private var sourceTestJob: Job? = null
    private var sourceTestGeneration = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        editSourceUrl = findViewById(R.id.edit_source_url)
        sourceCacheStatus = findViewById(R.id.source_cache_status)
        sourceTestStatus = findViewById(R.id.source_test_status)
        testSourceButton = findViewById(R.id.btn_test_source)
        val btnSave = findViewById<Button>(R.id.btn_save)
        val previewSwitch = findViewById<SwitchCompat>(R.id.switch_preview_enabled)

        editSourceUrl.setText(TvDataManager.getSourceUrl(this))
        previewSwitch.isChecked = ChannelPreviewPreferences.isEnabled(this)
        previewSwitch.setOnCheckedChangeListener { _, enabled ->
            ChannelPreviewPreferences.setEnabled(this, enabled)
        }
        editSourceUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                sourceTestGeneration += 1L
                sourceTestJob?.cancel()
                sourceTestJob = null
                testSourceButton.isEnabled = true
                sourceTestStatus.visibility = View.GONE
            }
        })

        testSourceButton.setOnClickListener { testCandidateSource() }
        btnSave.setOnClickListener {
            val newUrl = editSourceUrl.text.toString().trim()
            if (TvDataManager.isValidSourceUrl(newUrl)) {
                sourceTestGeneration += 1L
                sourceTestJob?.cancel()
                TvDataManager.saveSourceUrl(this, newUrl)
                ChannelRepository.invalidate()
                ChannelRepository.refresh(applicationContext, force = true)
                Toast.makeText(this, getString(R.string.source_saved_refreshing), Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, getString(R.string.source_url_invalid), Toast.LENGTH_SHORT).show()
            }
        }

        loadCachedSourceStatus()
    }

    override fun onDestroy() {
        sourceTestGeneration += 1L
        sourceTestJob?.cancel()
        sourceTestJob = null
        super.onDestroy()
    }

    private fun testCandidateSource() {
        val candidateUrl = editSourceUrl.text.toString().trim()
        sourceTestJob?.cancel()
        val generation = ++sourceTestGeneration
        if (!TvDataManager.isValidSourceUrl(candidateUrl)) {
            testSourceButton.isEnabled = true
            sourceTestJob = null
            showTestStatus(getString(R.string.source_url_invalid))
            return
        }

        testSourceButton.isEnabled = false
        showTestStatus(getString(R.string.source_testing))
        sourceTestJob = lifecycleScope.launch {
            try {
                val result = TvDataManager.checkSource(this@SettingsActivity, candidateUrl)
                if (!isCurrentCandidate(generation, candidateUrl)) return@launch
                showTestStatus(
                    getString(
                        R.string.source_test_success,
                        result.groupCount,
                        result.channelCount
                    )
                )
            } catch (_: CancellationException) {
                return@launch
            } catch (error: Exception) {
                if (!isCurrentCandidate(generation, candidateUrl)) return@launch
                showTestStatus(getString(errorMessageResId(error)))
            } finally {
                if (generation == sourceTestGeneration) {
                    testSourceButton.isEnabled = true
                    sourceTestJob = null
                }
            }
        }
    }

    private fun isCurrentCandidate(generation: Long, candidateUrl: String): Boolean {
        return generation == sourceTestGeneration &&
            editSourceUrl.text.toString().trim() == candidateUrl
    }

    private fun showTestStatus(message: String) {
        sourceTestStatus.text = message
        sourceTestStatus.visibility = View.VISIBLE
    }

    private fun errorMessageResId(error: Exception): Int {
        val message = error.message.orEmpty()
        return if (message.contains("为空") || message.contains("没有可播放")) {
            R.string.source_test_empty
        } else {
            R.string.source_test_failed
        }
    }

    private fun loadCachedSourceStatus() {
        lifecycleScope.launch {
            val info = TvDataManager.readCachedSourceInfo(this@SettingsActivity)
            sourceCacheStatus.text = if (info == null) {
                getString(R.string.source_cache_empty)
            } else {
                getString(
                    R.string.source_cache_summary,
                    info.channelCount,
                    formatTimestamp(info.updatedAtMillis)
                )
            }
        }
    }

    private fun formatTimestamp(updatedAtMillis: Long): String {
        return DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT,
            Locale.getDefault()
        ).format(Date(updatedAtMillis))
    }
}

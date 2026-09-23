package com.android.tv

import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
    private lateinit var sourceUrlFocusContainer: View
    private lateinit var sourceUrlDisplay: TextView
    private lateinit var previewFocusRow: View
    private lateinit var sourceCurrentHost: TextView
    private lateinit var sourceCurrentUrl: TextView
    private lateinit var sourceRuntimeStatus: TextView
    private lateinit var sourceChannelStats: TextView
    private lateinit var sourceRefreshButton: Button
    private lateinit var settingsAboutText: TextView
    private lateinit var settingsScrollView: ScrollView
    private var isTvUiMode = false
    private var sourceTestJob: Job? = null
    private var sourceTestGeneration = 0L
    private var cachedSourceInfo: TvCachedSourceInfo? = null
    private var sourceRefreshInProgress = false
    private var cacheInfoReloadInFlightAtMillis: Long? = null
    private var cacheInfoReloadedAtMillis: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isTvUiMode = (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
        setContentView(R.layout.activity_settings)

        editSourceUrl = findViewById(R.id.edit_source_url)
        sourceCacheStatus = findViewById(R.id.source_cache_status)
        sourceTestStatus = findViewById(R.id.source_test_status)
        testSourceButton = findViewById(R.id.btn_test_source)
        val btnSave = findViewById<Button>(R.id.btn_save)
        val previewSwitch = findViewById<SwitchCompat>(R.id.switch_preview_enabled)

        editSourceUrl.setText(TvDataManager.getSourceUrl(this))
        if (isTvUiMode) {
            settingsScrollView = findViewById(R.id.settings_scroll_view)
            sourceUrlFocusContainer = findViewById(R.id.source_url_focus_container)
            sourceUrlDisplay = findViewById(R.id.source_url_display)
            previewFocusRow = findViewById(R.id.preview_focus_row)
            sourceCurrentHost = findViewById(R.id.source_current_host)
            sourceCurrentUrl = findViewById(R.id.source_current_url)
            sourceRuntimeStatus = findViewById(R.id.source_runtime_status)
            sourceChannelStats = findViewById(R.id.source_channel_stats)
            sourceRefreshButton = findViewById(R.id.btn_refresh_source)
            settingsAboutText = findViewById(R.id.settings_about_text)
            listOf(
                sourceUrlFocusContainer,
                sourceRuntimeStatus,
                sourceRefreshButton,
                testSourceButton,
                btnSave,
                findViewById(R.id.source_cache_status),
                settingsAboutText
            ).forEach { focusTarget: View ->
                focusTarget.setOnFocusChangeListener { focused, hasFocus ->
                    if (hasFocus) keepTvFocusAboveBottomEdge(focused)
                }
            }
            sourceUrlDisplay.text = editSourceUrl.text
            bindCurrentSource()
            bindAboutSummary()
            sourceRefreshButton.setOnClickListener {
                if (sourceRefreshInProgress) return@setOnClickListener
                sourceRefreshInProgress = true
                sourceRuntimeStatus.text = getString(R.string.settings_source_refreshing)
                ChannelRepository.refresh(applicationContext, force = true)
            }
            sourceUrlFocusContainer.setOnClickListener { showSourceUrlDialog() }
            sourceUrlFocusContainer.setOnKeyListener { view, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                    view.clearFocus()
                    if (!sourceRuntimeStatus.requestFocus()) {
                        sourceRuntimeStatus.post {
                            if (!isFinishing && !isDestroyed) sourceRuntimeStatus.requestFocus()
                        }
                    }
                    true
                } else {
                    false
                }
            }
            previewFocusRow.setOnClickListener { previewSwitch.performClick() }
            previewFocusRow.setOnFocusChangeListener { focused, hasFocus ->
                previewSwitch.isSelected = hasFocus
                if (hasFocus) keepTvFocusAboveBottomEdge(focused)
            }
            val focusPreviewOnDown = View.OnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                    if (!previewFocusRow.requestFocus()) {
                        previewFocusRow.post {
                            if (!isFinishing && !isDestroyed) previewFocusRow.requestFocus()
                        }
                    }
                    true
                } else {
                    false
                }
            }
            testSourceButton.setOnKeyListener(focusPreviewOnDown)
            btnSave.setOnKeyListener(focusPreviewOnDown)
            observeTvSourceState()
            ChannelRepository.ensureLoaded(applicationContext)
        }
        previewSwitch.isChecked = ChannelPreviewPreferences.isEnabled(this)
        if (isTvUiMode) updatePreviewFocusAccessibility(previewSwitch.isChecked)
        previewSwitch.setOnCheckedChangeListener { _, enabled ->
            ChannelPreviewPreferences.setEnabled(this, enabled)
            if (isTvUiMode) updatePreviewFocusAccessibility(enabled)
        }
        editSourceUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (isTvUiMode) sourceUrlDisplay.text = s?.toString().orEmpty()
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
        if (isTvUiMode) {
            window.decorView.post {
                if (!isFinishing && !isDestroyed) sourceUrlFocusContainer.requestFocus()
            }
        }
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

    private fun showSourceUrlDialog() {
        val dialogContext = ContextThemeWrapper(this, R.style.Theme_TV_SettingsDialog)
        val input = EditText(dialogContext).apply {
            hint = getString(R.string.source_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            maxLines = 1
            minHeight = (64 * resources.displayMetrics.density).toInt()
            textSize = 20f
            setTextColor(Color.WHITE)
            setHintTextColor(0xB8FFFFFF.toInt())
            setBackgroundResource(R.drawable.tv_settings_dialog_input_background)
            val inputPadding = (16 * resources.displayMetrics.density).toInt()
            setPadding(inputPadding, paddingTop, inputPadding, paddingBottom)
            setText(editSourceUrl.text)
            setSelection(text?.length ?: 0)
        }

        val dialog = AlertDialog.Builder(dialogContext)
            .setTitle(R.string.source_url_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.source_url_dialog_done, null)
            .setNegativeButton(R.string.source_url_dialog_cancel, null)
            .create()

        dialog.setOnDismissListener {
            if (!isFinishing && !isDestroyed) {
                sourceUrlFocusContainer.post { sourceUrlFocusContainer.requestFocus() }
            }
        }
        dialog.setOnShowListener {
            val applyCandidate = {
                editSourceUrl.setText(input.text.toString())
                dialog.dismiss()
            }
            val doneButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val cancelButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            listOf(doneButton, cancelButton).forEach { button ->
                button.setBackgroundResource(R.drawable.tv_settings_dialog_button_background)
                button.setTextColor(Color.WHITE)
                button.isFocusable = true
                button.isFocusableInTouchMode = true
                val buttonPadding = (16 * resources.displayMetrics.density).toInt()
                button.setPadding(buttonPadding, button.paddingTop, buttonPadding, button.paddingBottom)
                button.minHeight = (52 * resources.displayMetrics.density).toInt()
            }
            doneButton.setOnClickListener { applyCandidate() }
            input.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    applyCandidate()
                    true
                } else {
                    false
                }
            }
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            dialog.window?.setBackgroundDrawableResource(R.drawable.tv_settings_dialog_background)
            input.requestFocus()
        }
        dialog.show()
    }

    private fun updatePreviewFocusAccessibility(isEnabled: Boolean) {
        val stateStringId = if (isEnabled) {
            R.string.channel_preview_enabled_state
        } else {
            R.string.channel_preview_disabled_state
        }
        previewFocusRow.contentDescription = getString(
            R.string.channel_preview_accessibility_state,
            getString(R.string.channel_preview_title),
            getString(stateStringId)
        )
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
            cachedSourceInfo = info
            updateCacheStatus(info)
            if (isTvUiMode) renderTvSourceState(ChannelRepository.state.value)
        }
    }

    private fun bindCurrentSource() {
        val sourceUrl = TvDataManager.getSourceUrl(this)
        val host = Uri.parse(sourceUrl).host.orEmpty().ifBlank { sourceUrl }
        sourceCurrentHost.text = getString(R.string.settings_source_current_host, host)
        sourceCurrentUrl.text = sourceUrl
    }

    private fun keepTvFocusAboveBottomEdge(focused: View) {
        settingsScrollView.postDelayed({
            if (!focused.isAttachedToWindow || !focused.isFocused) return@postDelayed
            val focusedLocation = IntArray(2)
            val scrollLocation = IntArray(2)
            focused.getLocationOnScreen(focusedLocation)
            settingsScrollView.getLocationOnScreen(scrollLocation)
            val safeBottom = scrollLocation[1] + settingsScrollView.height -
                (48 * resources.displayMetrics.density).toInt()
            val overflow = focusedLocation[1] + focused.height - safeBottom
            if (overflow > 0) settingsScrollView.scrollBy(0, overflow)
        }, 320L)
    }

    private fun bindAboutSummary() {
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { getString(R.string.settings_version_unknown) }
        settingsAboutText.text = getString(R.string.settings_about_summary, versionName)
    }

    private fun observeTvSourceState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ChannelRepository.state.collect(::renderTvSourceState)
            }
        }
    }

    private fun renderTvSourceState(state: ChannelState, checkCacheAfterSuccess: Boolean = true) {
        if (!isTvUiMode) return
        val presentation = TvSettingsSourcePolicy.presentation(state)
        if (presentation.state == TvSettingsSourcePresentation.State.ONLINE) {
            val successfulAt = presentation.updatedAtMillis
            if (checkCacheAfterSuccess && successfulAt != null) {
                reloadCacheInfoAfterSuccess(successfulAt)
            }
        }
        sourceRuntimeStatus.text = getString(
            when (presentation.state) {
                TvSettingsSourcePresentation.State.LOADING -> R.string.settings_source_loading
                TvSettingsSourcePresentation.State.REFRESHING -> R.string.settings_source_refreshing
                TvSettingsSourcePresentation.State.ONLINE -> R.string.settings_source_online
                TvSettingsSourcePresentation.State.CACHED -> R.string.settings_source_cached
                TvSettingsSourcePresentation.State.EMPTY -> R.string.settings_source_empty
                TvSettingsSourcePresentation.State.EMPTY_CACHED -> R.string.settings_source_empty_cached
                TvSettingsSourcePresentation.State.ERROR -> R.string.settings_source_error
                TvSettingsSourcePresentation.State.ERROR_WITH_CHANNELS -> {
                    R.string.settings_source_error_with_channels
                }
            }
        )

        val channelCount = presentation.playableChannelCount
        val updatedAtMillis = presentation.updatedAtMillis ?: cachedSourceInfo?.updatedAtMillis
        val shownCount = if (channelCount > 0) channelCount else cachedSourceInfo?.channelCount ?: 0
        sourceChannelStats.text = if (shownCount > 0) {
            getString(
                R.string.settings_source_channel_stats,
                shownCount,
                updatedAtMillis?.let(::formatTimestamp) ?: getString(R.string.settings_time_unknown)
            )
        } else {
            getString(R.string.settings_source_no_channel_stats)
        }
        sourceRefreshInProgress = presentation.state == TvSettingsSourcePresentation.State.LOADING ||
            presentation.state == TvSettingsSourcePresentation.State.REFRESHING
    }

    private fun reloadCacheInfoAfterSuccess(successfulAtMillis: Long) {
        if (cachedSourceInfo?.updatedAtMillis == successfulAtMillis ||
            cacheInfoReloadInFlightAtMillis == successfulAtMillis ||
            cacheInfoReloadedAtMillis == successfulAtMillis
        ) return

        cacheInfoReloadInFlightAtMillis = successfulAtMillis
        lifecycleScope.launch {
            val info = TvDataManager.readCachedSourceInfo(this@SettingsActivity)
            if (cacheInfoReloadInFlightAtMillis == successfulAtMillis) {
                cacheInfoReloadInFlightAtMillis = null
                cacheInfoReloadedAtMillis = successfulAtMillis
                cachedSourceInfo = info
                updateCacheStatus(info)
                renderTvSourceState(ChannelRepository.state.value, checkCacheAfterSuccess = false)
            }
        }
    }

    private fun updateCacheStatus(info: TvCachedSourceInfo?) {
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

    private fun formatTimestamp(updatedAtMillis: Long): String {
        return DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT,
            Locale.getDefault()
        ).format(Date(updatedAtMillis))
    }

}

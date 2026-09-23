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
import android.widget.RadioButton
import android.widget.RadioGroup
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
    private lateinit var sourcePreviousSummary: TextView
    private lateinit var testSourceButton: Button
    private lateinit var restoreSourceButton: Button
    private lateinit var saveSourceButton: Button
    private lateinit var sourceUrlFocusContainer: View
    private lateinit var sourceUrlDisplay: TextView
    private lateinit var previewModeRadioGroup: RadioGroup
    private lateinit var previewModeOffButton: RadioButton
    private lateinit var previewModeFocusedButton: RadioButton
    private lateinit var previewModeFullButton: RadioButton
    private lateinit var sourceCurrentHost: TextView
    private lateinit var sourceCurrentUrl: TextView
    private lateinit var sourceRuntimeStatus: TextView
    private lateinit var sourceChannelStats: TextView
    private lateinit var sourceRefreshButton: Button
    private lateinit var settingsAboutText: TextView
    private lateinit var settingsScrollView: ScrollView
    private var isTvUiMode = false
    private var sourceTestJob: Job? = null
    private var sourceActivationJob: Job? = null
    private var sourceRestoreJob: Job? = null
    private var sourceTestGeneration = 0L
    private var sourceSwitchInProgress = false
    private var cachedSourceInfo: TvCachedSourceInfo? = null
    private var cacheStatusLoadGeneration = 0L
    private var previousSourceLoadGeneration = 0L
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
        sourcePreviousSummary = findViewById(R.id.source_previous_summary)
        testSourceButton = findViewById(R.id.btn_test_source)
        restoreSourceButton = findViewById(R.id.btn_restore_source)
        saveSourceButton = findViewById(R.id.btn_save)
        val btnSave = saveSourceButton
        val previewSwitch: SwitchCompat? = if (isTvUiMode) {
            null
        } else {
            findViewById(R.id.switch_preview_enabled)
        }

        editSourceUrl.setText(TvDataManager.getSourceUrl(this))
        if (isTvUiMode) {
            settingsScrollView = findViewById(R.id.settings_scroll_view)
            sourceUrlFocusContainer = findViewById(R.id.source_url_focus_container)
            sourceUrlDisplay = findViewById(R.id.source_url_display)
            previewModeRadioGroup = findViewById(R.id.preview_focus_row)
            previewModeOffButton = findViewById(R.id.preview_mode_off)
            previewModeFocusedButton = findViewById(R.id.preview_mode_focused)
            previewModeFullButton = findViewById(R.id.preview_mode_full)
            sourceCurrentHost = findViewById(R.id.source_current_host)
            sourceCurrentUrl = findViewById(R.id.source_current_url)
            sourceRuntimeStatus = findViewById(R.id.source_runtime_status)
            sourceChannelStats = findViewById(R.id.source_channel_stats)
            sourceRefreshButton = findViewById(R.id.btn_refresh_source)
            settingsAboutText = findViewById(R.id.settings_about_text)
            val clearRecentWatchesButton: Button = findViewById(R.id.btn_clear_recent_watches)
            listOf(
                sourceUrlFocusContainer,
                sourceRuntimeStatus,
                sourceRefreshButton,
                testSourceButton,
                btnSave,
                restoreSourceButton,
                previewModeOffButton,
                previewModeFocusedButton,
                previewModeFullButton,
                findViewById(R.id.source_cache_status),
                clearRecentWatchesButton,
                settingsAboutText
            ).forEach { focusTarget: View ->
                focusTarget.setOnFocusChangeListener { focused, hasFocus ->
                    if (hasFocus) keepTvFocusAboveBottomEdge(focused)
                }
            }
            sourceUrlDisplay.text = editSourceUrl.text
            updateSourceUrlAccessibility()
            bindCurrentSource()
            bindAboutSummary()
            sourceRefreshButton.setOnClickListener {
                if (sourceRefreshInProgress || sourceSwitchInProgress) return@setOnClickListener
                sourceRefreshInProgress = true
                sourceRuntimeStatus.text = getString(R.string.settings_source_refreshing)
                ChannelRepository.refresh(applicationContext, force = true)
            }
            clearRecentWatchesButton.setOnClickListener {
                val storedCount = RecentWatchRepository.records(this).size
                if (storedCount == 0) {
                    Toast.makeText(this, R.string.settings_clear_recent_empty, Toast.LENGTH_SHORT).show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.settings_clear_recent_title)
                        .setMessage(getString(R.string.settings_clear_recent_message, storedCount))
                        .setNegativeButton(R.string.source_url_dialog_cancel, null)
                        .setPositiveButton(R.string.settings_clear_recent_confirm) { _, _ ->
                            val cleared = RecentWatchRepository.clear(this)
                            Toast.makeText(
                                this,
                                if (cleared) R.string.settings_clear_recent_done
                                else R.string.settings_clear_recent_failed,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        .show()
                }
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
            val focusPreviousOrPreviewOnDown = View.OnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                    val target = if (restoreSourceButton.visibility == View.VISIBLE) {
                        restoreSourceButton
                    } else {
                        previewModeOffButton
                    }
                    if (!target.requestFocus()) {
                        target.post {
                            if (!isFinishing && !isDestroyed) target.requestFocus()
                        }
                    }
                    true
                } else {
                    false
                }
            }
            btnSave.setOnKeyListener(focusPreviousOrPreviewOnDown)
            testSourceButton.setOnKeyListener(focusPreviousOrPreviewOnDown)
            restoreSourceButton.setOnKeyListener(
                View.OnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                        event.action == KeyEvent.ACTION_DOWN
                    ) {
                        previewModeOffButton.requestFocus()
                        true
                    } else {
                        false
                    }
                }
            )
            restoreSourceButton.setOnFocusChangeListener { focused, hasFocus ->
                if (hasFocus) keepTvFocusAboveBottomEdge(focused)
            }
            previewModeRadioGroup.check(tvPreviewModeButtonId(ChannelPreviewPreferences.getTvMode(this)))
            previewModeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
                tvPreviewModeForButton(checkedId)?.let { ChannelPreviewPreferences.setTvMode(this, it) }
            }
            observeTvSourceState()
            ChannelRepository.ensureLoaded(applicationContext)
        }
        previewSwitch?.let { phonePreviewSwitch ->
            phonePreviewSwitch.isChecked = ChannelPreviewPreferences.isEnabled(this)
            phonePreviewSwitch.setOnCheckedChangeListener { _, enabled ->
                ChannelPreviewPreferences.setEnabled(this, enabled)
            }
        }
        editSourceUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (isTvUiMode) {
                    sourceUrlDisplay.text = s?.toString().orEmpty()
                    updateSourceUrlAccessibility()
                }
                sourceTestGeneration += 1L
                sourceTestJob?.cancel()
                sourceTestJob = null
                sourceActivationJob?.cancel()
                sourceActivationJob = null
                sourceTestStatus.visibility = View.GONE
            }
        })

        testSourceButton.setOnClickListener { testCandidateSource() }
        btnSave.setOnClickListener { activateCandidateSource() }
        restoreSourceButton.setOnClickListener { restorePreviousSource() }

        loadCachedSourceStatus()
        loadPreviousSourceStatus()
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
        sourceActivationJob?.cancel()
        sourceActivationJob = null
        sourceRestoreJob?.cancel()
        sourceRestoreJob = null
        super.onDestroy()
    }

    private fun testCandidateSource() {
        if (sourceSwitchInProgress || sourceTestJob?.isActive == true) return
        val candidateUrl = editSourceUrl.text.toString().trim()
        val generation = ++sourceTestGeneration
        if (!TvDataManager.isValidSourceUrl(candidateUrl)) {
            sourceTestJob = null
            showTestStatus(getString(R.string.source_url_invalid))
            return
        }

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
                    sourceTestJob = null
                }
            }
        }
    }

    private fun activateCandidateSource() {
        if (sourceSwitchInProgress) return
        val candidateUrl = editSourceUrl.text.toString().trim()
        if (!TvDataManager.isValidSourceUrl(candidateUrl)) {
            showTestStatus(getString(R.string.source_url_invalid))
            return
        }

        sourceSwitchInProgress = true
        sourceTestGeneration += 1L
        val generation = sourceTestGeneration
        sourceTestJob?.cancel()
        sourceTestJob = null
        showTestStatus(getString(R.string.source_activation_loading))
        sourceActivationJob = lifecycleScope.launch {
            try {
                val prepared = TvDataManager.prepareSource(this@SettingsActivity, candidateUrl)
                if (!isCurrentCandidate(generation, candidateUrl)) return@launch

                ChannelRepository.activatePreparedSource(
                    applicationContext,
                    prepared
                )
                bindCurrentSource()
                showTestStatus(
                    getString(
                        R.string.source_activation_success,
                        prepared.snapshot.groupCount,
                        prepared.snapshot.channelCount
                    )
                )
                // Loading the persisted records also updates cache and fallback details.
                loadCachedSourceStatus()
                loadPreviousSourceStatus()
            } catch (_: CancellationException) {
                return@launch
            } catch (error: Exception) {
                if (generation == sourceTestGeneration) {
                    val messageResId = if (errorMessageResId(error) == R.string.source_test_empty) {
                        R.string.source_test_empty
                    } else {
                        R.string.source_activation_failed
                    }
                    showTestStatus(getString(messageResId))
                }
            } finally {
                sourceActivationJob = null
                sourceSwitchInProgress = false
                setSourceEditingEnabled(true)
            }
        }
        setSourceEditingEnabled(false)
    }

    private fun restorePreviousSource() {
        if (sourceSwitchInProgress || sourceRestoreJob?.isActive == true) return
        sourceSwitchInProgress = true
        sourceTestGeneration += 1L
        sourceTestJob?.cancel()
        sourceTestJob = null
        sourceActivationJob?.cancel()
        sourceActivationJob = null
        showTestStatus(getString(R.string.settings_restoring_source))
        sourceRestoreJob = lifecycleScope.launch {
            try {
                val result = ChannelRepository.restorePreviousSource(applicationContext)
                if (result == null) {
                    updatePreviousSource(null)
                    showTestStatus(getString(R.string.settings_no_previous_source))
                    return@launch
                }

                val restored = result.fetchResult
                if (editSourceUrl.text.toString() != restored.sourceUrl) {
                    editSourceUrl.setText(restored.sourceUrl)
                }
                bindCurrentSource()
                val counts = countPlayableChannels(restored.groups)
                showTestStatus(
                    getString(R.string.settings_restore_source_success, counts.second, counts.first)
                )
                loadCachedSourceStatus()
                loadPreviousSourceStatus()
            } catch (_: CancellationException) {
                return@launch
            } catch (_: Exception) {
                showTestStatus(getString(R.string.settings_restore_source_failed))
            } finally {
                sourceRestoreJob = null
                sourceSwitchInProgress = false
                setSourceEditingEnabled(true)
            }
        }
        setSourceEditingEnabled(false)
    }

    private fun setSourceEditingEnabled(enabled: Boolean) {
        editSourceUrl.isEnabled = enabled
        if (isTvUiMode) {
            sourceUrlFocusContainer.isEnabled = enabled
            sourceUrlFocusContainer.isClickable = enabled
            sourceUrlFocusContainer.isFocusable = enabled
            sourceUrlFocusContainer.isFocusableInTouchMode = enabled
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

    private fun tvPreviewModeButtonId(mode: TvChannelPreviewMode): Int = when (mode) {
        TvChannelPreviewMode.OFF -> R.id.preview_mode_off
        TvChannelPreviewMode.FOCUSED_ONLY -> R.id.preview_mode_focused
        TvChannelPreviewMode.FOCUSED_AND_VISIBLE -> R.id.preview_mode_full
    }

    private fun tvPreviewModeForButton(buttonId: Int): TvChannelPreviewMode? = when (buttonId) {
        R.id.preview_mode_off -> TvChannelPreviewMode.OFF
        R.id.preview_mode_focused -> TvChannelPreviewMode.FOCUSED_ONLY
        R.id.preview_mode_full -> TvChannelPreviewMode.FOCUSED_AND_VISIBLE
        else -> null
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
        val generation = ++cacheStatusLoadGeneration
        lifecycleScope.launch {
            val info = TvDataManager.readCachedSourceInfo(this@SettingsActivity)
            if (generation != cacheStatusLoadGeneration) return@launch
            cachedSourceInfo = info
            updateCacheStatus(info)
            if (isTvUiMode) renderTvSourceState(ChannelRepository.state.value)
        }
    }

    private fun loadPreviousSourceStatus() {
        val generation = ++previousSourceLoadGeneration
        lifecycleScope.launch {
            val info = TvDataManager.readPreviousSourceInfo(this@SettingsActivity)
            if (generation == previousSourceLoadGeneration) updatePreviousSource(info)
        }
    }

    private fun updatePreviousSource(info: TvAvailableSourceInfo?) {
        if (info == null) {
            sourcePreviousSummary.text = getString(R.string.settings_no_previous_source)
            sourcePreviousSummary.visibility = View.GONE
            restoreSourceButton.visibility = View.GONE
        } else {
            val host = Uri.parse(info.sourceUrl).host.orEmpty().ifBlank { info.sourceUrl }
            sourcePreviousSummary.text = getString(
                R.string.settings_previous_source_summary,
                host,
                info.channelCount,
                formatTimestamp(info.updatedAtMillis),
                info.sourceUrl
            )
            sourcePreviousSummary.visibility = View.VISIBLE
            restoreSourceButton.visibility = View.VISIBLE
        }

        if (isTvUiMode) {
            val next = if (info == null) previewModeOffButton else restoreSourceButton
            testSourceButton.nextFocusDownId = next.id
            saveSourceButton.nextFocusDownId = next.id
            restoreSourceButton.nextFocusUpId = saveSourceButton.id
            previewModeOffButton.nextFocusUpId =
                if (info == null) saveSourceButton.id else restoreSourceButton.id
        }
    }

    private fun countPlayableChannels(groups: Map<String, List<Movie>>): Pair<Int, Int> {
        val playableGroups = groups.filterValues { channels ->
            channels.any { channel -> channel.isLive && !channel.videoUrl.isNullOrBlank() }
        }
        val channelCount = playableGroups.values.sumOf { channels ->
            channels.count { channel -> channel.isLive && !channel.videoUrl.isNullOrBlank() }
        }
        return playableGroups.size to channelCount
    }

    private fun bindCurrentSource() {
        val sourceUrl = TvDataManager.getSourceUrl(this)
        val host = Uri.parse(sourceUrl).host.orEmpty().ifBlank { sourceUrl }
        sourceCurrentHost.text = getString(R.string.settings_source_current_host, host)
        sourceCurrentUrl.text = sourceUrl
    }

    private fun updateSourceUrlAccessibility() {
        val candidate = sourceUrlDisplay.text?.toString().orEmpty().ifBlank {
            getString(R.string.source_url_hint)
        }
        sourceUrlFocusContainer.contentDescription = getString(
            R.string.settings_source_edit_accessibility,
            getString(R.string.settings_source_edit_label),
            candidate
        )
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

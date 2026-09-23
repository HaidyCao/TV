package com.android.tv

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity

/**
 * Loads [MainFragment].
 */
class MainActivity : FragmentActivity() {

    private lateinit var channelStatusBar: TextView
    private lateinit var settingsButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        channelStatusBar = findViewById(R.id.channel_status_bar)
        settingsButton = findViewById(R.id.tv_settings_button)
        settingsButton.setOnClickListener { mainFragment()?.openSettingsFromToolbar() }
        settingsButton.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> mainFragment()?.focusFirstBrowseChannel() == true
                KeyEvent.KEYCODE_DPAD_LEFT -> searchOrb()?.requestFocus() == true
                else -> false
            }
        }
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                .replace(R.id.main_browse_fragment, MainFragment())
                .commitNow()
        }
    }

    fun showChannelStatus(status: TvChannelStatus?) {
        if (!::channelStatusBar.isInitialized) return
        if (status == null) {
            channelStatusBar.visibility = View.GONE
            return
        }

        channelStatusBar.setText(status.messageResId)
        channelStatusBar.setTextColor(
            ContextCompat.getColor(
                this,
                when (status.style) {
                    TvChannelStatus.Style.PROGRESS -> R.color.tv_status_progress_text
                    TvChannelStatus.Style.INFO -> R.color.tv_status_info_text
                    TvChannelStatus.Style.EMPTY -> R.color.tv_status_empty_text
                    TvChannelStatus.Style.ERROR -> R.color.tv_status_error_text
                }
            )
        )
        channelStatusBar.visibility = View.VISIBLE
    }

    private fun mainFragment(): MainFragment? =
        supportFragmentManager.findFragmentById(R.id.main_browse_fragment) as? MainFragment

    @SuppressLint("MissingInflatedId") // Leanback creates the search orb inside its fragment view.
    private fun searchOrb(): View? = window.decorView.findViewById(androidx.leanback.R.id.title_orb)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
            searchOrb()?.setOnKeyListener { _, keyCode, event ->
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT &&
                    event.action == KeyEvent.ACTION_DOWN &&
                    settingsButton.requestFocus()
            }
        }
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}

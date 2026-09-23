package com.android.tv

import android.os.Bundle
import android.view.View
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        channelStatusBar = findViewById(R.id.channel_status_bar)
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}

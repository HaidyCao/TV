package com.android.tv

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.util.UnstableApi

/**
 * Loads [MainFragment].
 */
@UnstableApi
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                .replace(R.id.main_browse_fragment, MainFragment())
                .commitNow()
        }
    }
}
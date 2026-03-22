package com.android.tv

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.util.UnstableApi

/**
 * Loads [MainFragment].
 */
@UnstableApi
class MainActivity : FragmentActivity() {

    private var previewContainer: FrameLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate: savedInstanceState=${savedInstanceState != null}")
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_browse_fragment, MainFragment())
                .commitNow()
        }
        supportFragmentManager.addOnBackStackChangedListener {
            val searchFragment = supportFragmentManager.findFragmentByTag("search")
            if (searchFragment == null) {
                val mainFragment = supportFragmentManager.findFragmentById(R.id.main_browse_fragment)
                if (mainFragment is MainFragment) {
                    supportFragmentManager.beginTransaction()
                        .show(mainFragment)
                        .commit()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d("MainActivity", "onStart")
    }

    override fun onResume() {
        super.onResume()
        Log.d("MainActivity", "onResume")
    }

    override fun onPause() {
        super.onPause()
        Log.d("MainActivity", "onPause")
    }

    override fun onStop() {
        super.onStop()
        Log.d("MainActivity", "onStop")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MainActivity", "onDestroy")
        // 清理 PreviewGenerator
        PreviewGenerator.destroy()
    }

    /**
     * 初始化 PreviewGenerator
     * 由 MainFragment 调用
     */
    fun initializePreviewGenerator(onPreviewReady: (String, String, android.graphics.Bitmap) -> Unit) {
        if (previewContainer == null) {
            previewContainer = findViewById(R.id.preview_container)
            // 如果已经附加，直接初始化
            if (previewContainer?.isAttachedToWindow == true) {
                Log.d("MainActivity", "Preview container already attached, initializing...")
                PreviewGenerator.init(previewContainer!!, onPreviewReady)
            } else {
                Log.d("MainActivity", "Preview container not attached, waiting...")
                // 确保容器已附加到窗口
                previewContainer?.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        Log.d("MainActivity", "Preview container attached, initializing...")
                        PreviewGenerator.init(previewContainer!!, onPreviewReady)
                    }

                    override fun onViewDetachedFromWindow(v: View) {
                        // 处理窗口分离
                    }
                })
            }
        }
    }
}

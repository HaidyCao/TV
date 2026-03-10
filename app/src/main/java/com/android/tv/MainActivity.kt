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
            getSupportFragmentManager().beginTransaction()
                .replace(R.id.main_browse_fragment, MainFragment())
                .commitNow()
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
    fun initializePreviewGenerator(onPreviewReady: (String, android.graphics.Bitmap) -> Unit) {
        if (previewContainer == null) {
            previewContainer = findViewById(R.id.preview_container)
            // 确保容器已附加到窗口
            previewContainer?.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    PreviewGenerator.init(previewContainer!!, onPreviewReady)
                }

                override fun onViewDetachedFromWindow(v: View) {
                    // 处理窗口分离
                }
            })
            // 如果已经附加，直接初始化
            if (previewContainer?.isAttachedToWindow == true) {
                PreviewGenerator.init(previewContainer!!, onPreviewReady)
            }
        }
    }
}

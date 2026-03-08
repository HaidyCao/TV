package com.android.tv

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
class PhoneMainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PhoneChannelAdapter
    private lateinit var swipeRefresh: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private lateinit var toolbar: androidx.appcompat.widget.Toolbar
    private lateinit var gridLayoutManager: GridLayoutManager
    private lateinit var sharedPreferences: SharedPreferences

    private val allChannels = mutableListOf<Movie>()
    private var isDataLoaded = false
    private var isInitialLayoutComplete = false

    private var gridLevel = 4

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_GRID_LEVEL = "grid_level"
    }

    private fun getColumnCount(): Int {
        return when (gridLevel) {
            1 -> 1
            2 -> 2
            3 -> 3
            else -> 4
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_main)

        // 初始化 SharedPreferences 并读取保存的 gridLevel
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        gridLevel = sharedPreferences.getInt(KEY_GRID_LEVEL, 4)

        recyclerView = findViewById(R.id.channel_list)
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        val gridLayoutManager = GridLayoutManager(this, getColumnCount())
        recyclerView.layoutManager = gridLayoutManager
        this.gridLayoutManager = gridLayoutManager
        swipeRefresh = findViewById(R.id.swipe_refresh)
        swipeRefresh.setOnRefreshListener {
            loadChannels()
        }

        adapter = PhoneChannelAdapter({ channel ->
            val intent = Intent(this, PhonePlaybackActivity::class.java)
            intent.putExtra("channel", channel)
            startActivity(intent)
        }, getColumnCount())
        recyclerView.adapter = adapter

        // 滚动监听器，用于动态更新预览图优先级
        var scrollRunnable: Runnable? = null
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                
                // 使用防抖机制，避免频繁更新
                scrollRunnable?.let { recyclerView.removeCallbacks(it) }
                scrollRunnable = Runnable {
                    adapter.refreshPlayers()
                }.apply {
                    recyclerView.postDelayed(this, 150)
                }
            }
            
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                
                // 滚动停止时立即更新
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    scrollRunnable?.let { recyclerView.removeCallbacks(it) }
                    adapter.refreshPlayers()
                }
            }
        })
        
        // 布局完成监听器，用于初始加载时更新可见范围并触发预览生成
        // 只在第一次布局完成后触发
        recyclerView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (isInitialLayoutComplete) {
                    return
                }

                val firstVisible = gridLayoutManager.findFirstVisibleItemPosition()
                val lastVisible = gridLayoutManager.findLastVisibleItemPosition()
                if (firstVisible != RecyclerView.NO_POSITION && lastVisible != RecyclerView.NO_POSITION) {
                    isInitialLayoutComplete = true
                    adapter.updateVisibleRange(firstVisible, lastVisible)

                    // 移除监听器，避免重复触发
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                        recyclerView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    } else {
                        @Suppress("DEPRECATION")
                        recyclerView.viewTreeObserver.removeGlobalOnLayoutListener(this)
                    }
                    Log.d("PhoneMainActivity", "[LAYOUT] Initial layout complete")
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        loadChannels()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_phone_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_grid -> {
                gridLevel = if (gridLevel >= 4) 1 else gridLevel + 1
                updateLayoutManager()
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun updateLayoutManager() {
        val newSpanCount = getColumnCount()
        gridLayoutManager.spanCount = newSpanCount
        
        // 保存 gridLevel 到 SharedPreferences
        sharedPreferences.edit().putInt(KEY_GRID_LEVEL, gridLevel).apply()
    }

    private fun loadChannels() {
        if (isDataLoaded) {
            adapter.submitList(allChannels.toList())
            swipeRefresh.isRefreshing = false

            // 只需要调用一次，避免重复
            recyclerView.postDelayed({
                adapter.refreshPlayers()
            }, 300)
            return
        }

        swipeRefresh.isRefreshing = true
        CoroutineScope(Dispatchers.Main).launch {
            val channels = withContext(Dispatchers.IO) {
                TvDataManager.fetchTvChannels(this@PhoneMainActivity)
            }

            if (!isDestroyed && !isFinishing) {
                allChannels.clear()
                channels.values.forEach { allChannels.addAll(it) }

                adapter.submitList(allChannels.toList())
                swipeRefresh.isRefreshing = false
                isDataLoaded = true

                // 延迟触发预览生成，确保布局已完成
                recyclerView.postDelayed({
                    adapter.refreshPlayers()
                }, 500)
            }
        }
    }

    override fun onDestroy() {
        // 清理预览生成器
        PreviewGenerator.destroy()
        super.onDestroy()
    }
}

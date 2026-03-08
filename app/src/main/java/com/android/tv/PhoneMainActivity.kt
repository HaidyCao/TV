package com.android.tv

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
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

        var scrollRunnable: Runnable? = null
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                scrollRunnable?.let { recyclerView.removeCallbacks(it) }
                scrollRunnable = Runnable {
                    adapter.refreshPlayers()
                }.apply {
                    recyclerView.postDelayed(this, 100)
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

                recyclerView.postDelayed({
                    adapter.refreshPlayers()
                }, 300)
            }
        }
    }
}

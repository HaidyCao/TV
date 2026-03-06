package com.android.tv

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PhoneMainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PhoneChannelAdapter
    private lateinit var swipeRefresh: androidx.swiperefreshlayout.widget.SwipeRefreshLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_main)

        recyclerView = findViewById(R.id.channel_list)
        recyclerView.layoutManager = GridLayoutManager(this, 4)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        swipeRefresh = findViewById(R.id.swipe_refresh)
        swipeRefresh.setOnRefreshListener {
            loadChannels()
        }

        adapter = PhoneChannelAdapter { channel ->
            val intent = Intent(this, PhonePlaybackActivity::class.java)
            intent.putExtra("channel", channel)
            startActivity(intent)
        }
        recyclerView.adapter = adapter
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
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadChannels() {
        swipeRefresh.isRefreshing = true
        CoroutineScope(Dispatchers.Main).launch {
            val channels = withContext(Dispatchers.IO) {
                TvDataManager.fetchTvChannels(this@PhoneMainActivity)
            }
            
            val allChannels = mutableListOf<Movie>()
            channels.values.forEach { allChannels.addAll(it) }
            
            adapter.submitList(allChannels)
            swipeRefresh.isRefreshing = false
            
            if (allChannels.isEmpty()) {
                Toast.makeText(this@PhoneMainActivity, "无法加载频道，请检查网络或源地址", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

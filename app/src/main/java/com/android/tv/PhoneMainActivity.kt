package com.android.tv

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.launch

class PhoneMainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PhoneChannelAdapter
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var gridLayoutManager: GridLayoutManager
    private lateinit var emptyView: TextView

    private var allChannels: List<Movie> = emptyList()
    private var activeQuery = ""
    private var currentState: ChannelState = ChannelState.Idle
    private var gridLevel = 3

    private fun getColumnCount(): Int = gridLevel.coerceIn(1, 4)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_main)
        gridLevel = preferredColumnCount()

        recyclerView = findViewById(R.id.channel_list)
        emptyView = findViewById(R.id.empty_view)
        setSupportActionBar(findViewById(R.id.toolbar))

        gridLayoutManager = GridLayoutManager(this, getColumnCount())
        recyclerView.layoutManager = gridLayoutManager
        adapter = PhoneChannelAdapter(
            onClick = { channel ->
                startActivity(Intent(this, PhonePlaybackActivity::class.java).putExtra("channel", channel))
            },
            initialPlayerPoolSize = 1
        )
        recyclerView.adapter = adapter
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    adapter.schedulePreviewUpdate()
                } else {
                    adapter.pausePreview()
                }
            }
        })

        swipeRefresh = findViewById(R.id.swipe_refresh)
        swipeRefresh.setOnRefreshListener {
            ChannelRepository.refresh(this, force = true)
        }

        observeChannelState()
    }

    override fun onStart() {
        super.onStart()
        ChannelRepository.ensureLoaded(this)
    }

    override fun onResume() {
        super.onResume()
        adapter.schedulePreviewUpdate()
    }

    override fun onPause() {
        adapter.pausePreview()
        super.onPause()
    }

    override fun onDestroy() {
        adapter.releasePreviewPlayer()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_phone_main, menu)
        (menu.findItem(R.id.action_search).actionView as? SearchView)?.apply {
            queryHint = getString(R.string.search_hint)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String): Boolean {
                    activeQuery = query
                    applyFilter()
                    clearFocus()
                    return true
                }

                override fun onQueryTextChange(newText: String): Boolean {
                    activeQuery = newText
                    applyFilter()
                    return true
                }
            })
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_grid -> {
                gridLevel = if (gridLevel >= 4) 1 else gridLevel + 1
                gridLayoutManager.spanCount = getColumnCount()
                adapter.updatePoolSize(getColumnCount())
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun observeChannelState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ChannelRepository.state.collect { state ->
                    currentState = state
                    allChannels = state.groups.values.flatten()
                    swipeRefresh.isRefreshing = state is ChannelState.Loading
                    applyFilter()
                }
            }
        }
    }

    private fun applyFilter() {
        val query = activeQuery.trim()
        val visibleChannels = if (query.isBlank()) {
            allChannels
        } else {
            allChannels.filter { channel ->
                sequenceOf(channel.title, channel.description, channel.category)
                    .filterNotNull()
                    .any { it.contains(query, ignoreCase = true) }
            }
        }
        adapter.submitList(visibleChannels)
        updateEmptyState(visibleChannels.isEmpty())
        recyclerView.post { adapter.schedulePreviewUpdate() }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        val message = when {
            !isEmpty -> null
            currentState is ChannelState.Loading -> getString(R.string.channel_loading)
            currentState is ChannelState.Error -> (currentState as ChannelState.Error).message
            activeQuery.isNotBlank() -> getString(R.string.no_results)
            else -> getString(R.string.channel_empty)
        }
        emptyView.text = message
        emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    private fun preferredColumnCount(): Int {
        return if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 4 else 3
    }
}

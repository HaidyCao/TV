package com.android.tv

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
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
    private var favoriteKeys: Set<String> = emptySet()
    private var showingFavorites = false
    private var favoritesMenuItem: MenuItem? = null

    private fun getColumnCount(): Int = gridLevel.coerceIn(1, 4)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_main)
        gridLevel = preferredColumnCount()
        favoriteKeys = ChannelFavorites.favoriteKeys(this)

        recyclerView = findViewById(R.id.channel_list)
        emptyView = findViewById(R.id.empty_view)
        setSupportActionBar(findViewById(R.id.toolbar))

        gridLayoutManager = GridLayoutManager(this, getColumnCount())
        recyclerView.layoutManager = gridLayoutManager
        adapter = PhoneChannelAdapter(
            onClick = { channel ->
                startActivity(Intent(this, PhonePlaybackActivity::class.java).putExtra("channel", channel))
            },
            onToggleFavorite = ::toggleFavorite,
            initialPlayerPoolSize = 1
        )
        adapter.updateFavorites(favoriteKeys)
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
        refreshFavoriteKeys()
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
        favoritesMenuItem = menu.findItem(R.id.action_favorites)
        updateFavoritesMenuItem()
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
            R.id.action_favorites -> {
                showingFavorites = !showingFavorites
                updateFavoritesMenuItem()
                applyFilter()
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
        val migratedFavorites = ChannelFavorites.migrateLegacyKeys(this, allChannels)
        if (migratedFavorites != favoriteKeys) {
            favoriteKeys = migratedFavorites
            adapter.updateFavorites(favoriteKeys)
        }
        val query = activeQuery.trim()
        val scopedChannels = if (showingFavorites) {
            FavoriteChannelResolver.resolve(allChannels, favoriteKeys)
        } else {
            allChannels
        }
        val visibleChannels = if (query.isBlank()) {
            scopedChannels
        } else {
            scopedChannels.filter { channel ->
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
            showingFavorites -> getString(R.string.favorite_empty)
            else -> getString(R.string.channel_empty)
        }
        emptyView.text = message
        emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    private fun preferredColumnCount(): Int {
        return if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 4 else 3
    }

    private fun toggleFavorite(channel: Movie) {
        val added = ChannelFavorites.toggle(this, channel)
        favoriteKeys = ChannelFavorites.favoriteKeys(this)
        adapter.updateFavorites(favoriteKeys)
        applyFilter()
        Toast.makeText(
            this,
            if (added) R.string.favorite_added else R.string.favorite_removed,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun refreshFavoriteKeys() {
        val updatedFavorites = ChannelFavorites.favoriteKeys(this)
        if (updatedFavorites == favoriteKeys) return
        favoriteKeys = updatedFavorites
        adapter.updateFavorites(favoriteKeys)
        applyFilter()
    }

    private fun updateFavoritesMenuItem() {
        favoritesMenuItem?.apply {
            title = getString(if (showingFavorites) R.string.menu_all_channels else R.string.menu_favorites)
            setIcon(if (showingFavorites) R.drawable.ic_favorite else R.drawable.ic_favorite_outline)
        }
    }
}

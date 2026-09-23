package com.android.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class SearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val cardPresenter = CardPresenter(
        isFavorite = { channel -> ChannelFavorites.isFavorite(channel, favoriteKeys) }
    )
    private val handler = Handler(Looper.getMainLooper())
    private var searchTask: Runnable? = null
    private var allChannels: List<Movie> = emptyList()
    private var channelGroups: Map<String, List<Movie>> = emptyMap()
    private var favoriteKeys: Set<String> = emptySet()
    private var activeQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSearchResultProvider(this)
        setOnItemViewClickedListener(ItemViewClickedListener())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        favoriteKeys = ChannelFavorites.favoriteKeys(requireContext())
        observeChannels()
        ChannelRepository.ensureLoaded(requireContext())
    }

    override fun onResume() {
        super.onResume()
        val updatedFavorites = ChannelFavorites.favoriteKeys(requireContext())
        if (updatedFavorites != favoriteKeys) {
            favoriteKeys = updatedFavorites
            if (activeQuery.isBlank()) renderRecommendations()
        }
    }

    override fun onDestroyView() {
        searchTask?.let(handler::removeCallbacks)
        searchTask = null
        super.onDestroyView()
    }

    private fun observeChannels() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ChannelRepository.state.collect { state ->
                    channelGroups = state.groups
                    allChannels = channelGroups.values.flatten()
                    if (activeQuery.isBlank()) {
                        renderRecommendations()
                    } else {
                        renderQuery(activeQuery)
                    }
                }
            }
        }
    }

    override fun getResultsAdapter(): ObjectAdapter = rowsAdapter

    override fun onQueryTextChange(newQuery: String): Boolean {
        activeQuery = newQuery
        searchTask?.let(handler::removeCallbacks)
        searchTask = null

        if (newQuery.isBlank()) {
            renderRecommendations()
        } else {
            searchTask = Runnable { renderQuery(newQuery) }
            handler.postDelayed(searchTask!!, SEARCH_DELAY_MS)
        }
        return true
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        activeQuery = query
        searchTask?.let(handler::removeCallbacks)
        searchTask = null
        if (query.isBlank()) {
            renderRecommendations()
        } else {
            renderQuery(query)
        }
        return true
    }

    private fun renderRecommendations() {
        rowsAdapter.clear()
        val recommendations = TvSearchRecommendationPolicy.select(channelGroups, favoriteKeys)

        if (recommendations.favorites.isNotEmpty()) {
            val favoritesAdapter = ArrayObjectAdapter(cardPresenter)
            recommendations.favorites.forEach(favoritesAdapter::add)
            rowsAdapter.add(
                ListRow(
                    HeaderItem(rowsAdapter.size().toLong(), getString(R.string.favorite_channels)),
                    favoritesAdapter
                )
            )
        }

        if (recommendations.commonChannels.isNotEmpty()) {
            val commonAdapter = ArrayObjectAdapter(cardPresenter)
            recommendations.commonChannels.forEach(commonAdapter::add)
            rowsAdapter.add(
                ListRow(
                    HeaderItem(rowsAdapter.size().toLong(), getString(R.string.common_channels)),
                    commonAdapter
                )
            )
        }
    }

    private fun renderQuery(query: String) {
        rowsAdapter.clear()
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return

        val results = allChannels.filter { channel ->
            sequenceOf(channel.title, channel.description, channel.studio, channel.category)
                .filterNotNull()
                .any { it.contains(normalizedQuery, ignoreCase = true) }
        }
        val resultAdapter = ArrayObjectAdapter(cardPresenter)
        results.forEach(resultAdapter::add)
        val header = if (results.isEmpty()) {
            HeaderItem(0, getString(R.string.no_results))
        } else {
            HeaderItem(0, getString(R.string.search_results, results.size))
        }
        rowsAdapter.add(ListRow(header, resultAdapter))
    }

    private inner class ItemViewClickedListener : OnItemViewClickedListener {
        override fun onItemClicked(
            itemViewHolder: Presenter.ViewHolder,
            item: Any,
            rowViewHolder: RowPresenter.ViewHolder,
            row: Row
        ) {
            val movie = item as? Movie ?: return
            val destination = if (movie.isLive) PlaybackActivity::class.java else DetailsActivity::class.java
            startActivity(Intent(requireActivity(), destination).putExtra(DetailsActivity.MOVIE, movie))
        }
    }

    companion object {
        private const val SEARCH_DELAY_MS = 300L
    }
}

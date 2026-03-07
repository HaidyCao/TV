package com.android.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class SearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val cardPresenter = CardPresenter()
    private val handler = Handler(Looper.getMainLooper())
    private var loadQueryTask: Runnable? = null
    private var allChannels: List<Movie> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSearchResultProvider(this)
        setOnItemViewClickedListener(ItemViewClickedListener())

        loadAllChannels()
    }

    private fun clearSearch() {
        setSearchQuery("", true)
        rowsAdapter.clear()
    }

    private fun loadAllChannels() {
        lifecycleScope.launch {
            val channels = mutableListOf<Movie>()
            val tvGroups = TvDataManager.fetchTvChannels(requireContext())
            tvGroups.values.forEach { channels.addAll(it) }
            allChannels = channels
            Log.d(TAG, "Loaded ${allChannels.size} channels")
        }
    }

    override fun getResultsAdapter(): ObjectAdapter {
        return rowsAdapter
    }

    override fun onQueryTextChange(newQuery: String): Boolean {
        Log.d(TAG, "onQueryTextChange: $newQuery")
        // 取消之前的任务
        loadQueryTask?.let {
            handler.removeCallbacks(it)
        }
        loadQueryTask = null

        if (newQuery.isNotEmpty()) {
            loadQueryTask = Runnable {
                loadQuery(newQuery)
            }
            handler.postDelayed(loadQueryTask!!, SEARCH_DELAY_MS.toLong())
        } else {
            rowsAdapter.clear()
        }
        return true
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        Log.d(TAG, "onQueryTextSubmit: $query")
        // 取消之前的任务
        loadQueryTask?.let {
            handler.removeCallbacks(it)
        }
        loadQueryTask = null

        loadQuery(query)
        return true
    }

    private fun loadQuery(query: String) {
        rowsAdapter.clear()

        val filteredChannels = allChannels.filter { channel ->
            val title = channel.title?.lowercase() ?: ""
            val description = channel.description?.lowercase() ?: ""
            val studio = channel.studio?.lowercase() ?: ""

            title.contains(query.lowercase()) ||
                description.contains(query.lowercase()) ||
                studio.contains(query.lowercase())
        }

        Log.d(TAG, "Found ${filteredChannels.size} results for: $query")

        if (filteredChannels.isEmpty()) {
            val listRowAdapter = ArrayObjectAdapter(cardPresenter)
            val header = HeaderItem(0, "未找到相关频道")
            rowsAdapter.add(ListRow(header, listRowAdapter))
        } else {
            val listRowAdapter = ArrayObjectAdapter(cardPresenter)
            filteredChannels.forEach { listRowAdapter.add(it) }
            val header = HeaderItem(0, "搜索结果 (${filteredChannels.size})")
            rowsAdapter.add(ListRow(header, listRowAdapter))
        }
    }

    private inner class ItemViewClickedListener : OnItemViewClickedListener {
        override fun onItemClicked(
            itemViewHolder: Presenter.ViewHolder,
            item: Any,
            rowViewHolder: RowPresenter.ViewHolder,
            row: Row
        ) {
            if (item is Movie) {
                Log.d(TAG, "Item: " + item.toString())
                val intent = if (item.studio == "直播频道") {
                    Intent(requireActivity(), PlaybackActivity::class.java)
                } else {
                    Intent(requireActivity(), DetailsActivity::class.java)
                }
                intent.putExtra(DetailsActivity.MOVIE, item)
                startActivity(intent)
            }
        }
    }

    companion object {
        private val TAG = "SearchFragment"
        private const val SEARCH_DELAY_MS = 300
    }
}

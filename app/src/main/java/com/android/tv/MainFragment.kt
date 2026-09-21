package com.android.tv

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.leanback.app.BackgroundManager
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import kotlinx.coroutines.launch

/** Displays the shared, app-scoped TV channel list. */
class MainFragment : BrowseSupportFragment() {

    private val backgroundHandler = Handler(Looper.getMainLooper())
    private var backgroundUpdate: Runnable? = null
    private lateinit var backgroundManager: BackgroundManager
    private var defaultBackground: Drawable? = null
    private lateinit var metrics: DisplayMetrics
    private var backgroundUri: String? = null
    private var previewFrameManager: PreviewFrameManager? = null
    private var favoriteKeys: Set<String> = emptySet()
    private var latestGroups: Map<String, List<Movie>> = emptyMap()
    private var latestStatusMessage: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        favoriteKeys = ChannelFavorites.favoriteKeys(requireContext())
        previewFrameManager = PreviewFrameManager()
        prepareBackgroundManager()
        setupUiElements()
        setupEventListeners()
        observeChannelState()
        ChannelRepository.ensureLoaded(requireContext())
    }

    override fun onResume() {
        super.onResume()
        val updatedFavorites = ChannelFavorites.favoriteKeys(requireContext())
        if (updatedFavorites != favoriteKeys) {
            favoriteKeys = updatedFavorites
            renderRows(latestGroups, latestStatusMessage)
        }
    }

    override fun onDestroyView() {
        backgroundUpdate?.let(backgroundHandler::removeCallbacks)
        backgroundUpdate = null
        previewFrameManager?.release()
        previewFrameManager = null
        super.onDestroyView()
    }

    private fun prepareBackgroundManager() {
        backgroundManager = BackgroundManager.getInstance(requireActivity())
        backgroundManager.attach(requireActivity().window)
        defaultBackground = ContextCompat.getDrawable(requireContext(), R.drawable.default_background)
        metrics = resources.displayMetrics
        backgroundManager.drawable = defaultBackground
    }

    private fun setupUiElements() {
        title = getString(R.string.browse_title)
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = ContextCompat.getColor(requireContext(), R.color.fastlane_background)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.search_opaque)
    }

    private fun observeChannelState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ChannelRepository.state.collect { state ->
                    when (state) {
                        ChannelState.Idle -> renderRows(emptyMap(), getString(R.string.channel_loading))
                        is ChannelState.Loading -> renderRows(
                            state.groups,
                            if (state.groups.isEmpty()) getString(R.string.channel_loading) else null
                        )
                        is ChannelState.Content -> renderRows(
                            state.groups,
                            if (state.fromSnapshot) getString(R.string.channel_offline_snapshot) else null
                        )
                        is ChannelState.Empty -> renderRows(emptyMap(), getString(R.string.channel_empty))
                        is ChannelState.Error -> renderRows(state.groups, state.message)
                    }
                }
            }
        }
    }

    private fun renderRows(
        tvGroups: Map<String, List<Movie>>,
        statusMessage: String?
    ) {
        latestGroups = tvGroups
        latestStatusMessage = statusMessage
        val channels = tvGroups.values.flatten()
        val migratedFavorites = ChannelFavorites.migrateLegacyKeys(requireContext(), channels)
        if (migratedFavorites != favoriteKeys) {
            favoriteKeys = migratedFavorites
        }
        val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        val cardPresenter = CardPresenter(
            previewFrameManager = previewFrameManager,
            isFavorite = { channel -> ChannelFavorites.isFavorite(channel, favoriteKeys) },
            onFavoriteToggle = ::toggleFavorite
        )

        val favorites = FavoriteChannelResolver.resolve(tvGroups.values.flatten(), favoriteKeys)
        if (favorites.isNotEmpty()) {
            val favoritesAdapter = ArrayObjectAdapter(cardPresenter)
            favorites.forEach(favoritesAdapter::add)
            rowsAdapter.add(
                ListRow(
                    HeaderItem(rowsAdapter.size().toLong(), getString(R.string.favorite_channels)),
                    favoritesAdapter
                )
            )
        }

        tvGroups.forEach { (category, channels) ->
            val listRowAdapter = ArrayObjectAdapter(cardPresenter)
            channels.forEach(listRowAdapter::add)
            rowsAdapter.add(ListRow(HeaderItem(rowsAdapter.size().toLong(), category), listRowAdapter))
        }

        statusMessage?.let { message ->
            val statusAdapter = ArrayObjectAdapter(StatusPresenter())
            statusAdapter.add(message)
            rowsAdapter.add(ListRow(HeaderItem(rowsAdapter.size().toLong(), getString(R.string.channel_status)), statusAdapter))
        }

        val settingsAdapter = ArrayObjectAdapter(GridItemPresenter())
        settingsAdapter.add(getString(R.string.personal_settings))
        rowsAdapter.add(
            ListRow(
                HeaderItem(rowsAdapter.size().toLong(), getString(R.string.settings_header)),
                settingsAdapter
            )
        )
        adapter = rowsAdapter
    }

    private fun toggleFavorite(channel: Movie) {
        val added = ChannelFavorites.toggle(requireContext(), channel)
        favoriteKeys = ChannelFavorites.favoriteKeys(requireContext())
        renderRows(latestGroups, latestStatusMessage)
        Toast.makeText(
            requireContext(),
            if (added) R.string.favorite_added else R.string.favorite_removed,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun setupEventListeners() {
        setOnSearchClickedListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.main_browse_fragment, SearchFragment())
                .addToBackStack(null)
                .commit()
        }
        onItemViewClickedListener = ItemViewClickedListener()
        onItemViewSelectedListener = ItemViewSelectedListener()
    }

    private inner class ItemViewClickedListener : OnItemViewClickedListener {
        override fun onItemClicked(
            itemViewHolder: Presenter.ViewHolder,
            item: Any,
            rowViewHolder: RowPresenter.ViewHolder,
            row: Row
        ) {
            when (item) {
                is Movie -> openMovie(item, itemViewHolder)
                is String -> if (item == getString(R.string.personal_settings)) {
                    startActivity(Intent(requireActivity(), SettingsActivity::class.java))
                }
            }
        }
    }

    private fun openMovie(movie: Movie, itemViewHolder: Presenter.ViewHolder) {
        val intent = Intent(
            requireActivity(),
            if (movie.isLive) PlaybackActivity::class.java else DetailsActivity::class.java
        ).putExtra(DetailsActivity.MOVIE, movie)

        if (!movie.isLive) {
            val imageView = (itemViewHolder.view as? ImageCardView)?.mainImageView
            if (imageView != null) {
                val bundle = ActivityOptionsCompat.makeSceneTransitionAnimation(
                    requireActivity(),
                    imageView,
                    DetailsActivity.SHARED_ELEMENT_NAME
                ).toBundle()
                startActivity(intent, bundle)
                return
            }
        }
        startActivity(intent)
    }

    private inner class ItemViewSelectedListener : OnItemViewSelectedListener {
        override fun onItemSelected(
            itemViewHolder: Presenter.ViewHolder?,
            item: Any?,
            rowViewHolder: RowPresenter.ViewHolder,
            row: Row
        ) {
            if (item is Movie) {
                backgroundUri = item.backgroundImageUrl
                scheduleBackgroundUpdate()
            }
        }
    }

    private fun scheduleBackgroundUpdate() {
        backgroundUpdate?.let(backgroundHandler::removeCallbacks)
        backgroundUpdate = Runnable { updateBackground(backgroundUri) }
        backgroundHandler.postDelayed(backgroundUpdate!!, BACKGROUND_UPDATE_DELAY_MS)
    }

    private fun updateBackground(uri: String?) {
        if (uri.isNullOrBlank()) {
            backgroundManager.drawable = defaultBackground
            return
        }
        Glide.with(this)
            .load(uri)
            .centerCrop()
            .error(defaultBackground)
            .into(object : CustomTarget<Drawable>(metrics.widthPixels, metrics.heightPixels) {
                override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                    backgroundManager.drawable = resource
                }

                override fun onLoadCleared(placeholder: Drawable?) = Unit
            })
    }

    private inner class GridItemPresenter : Presenter() {
        override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
            val view = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(GRID_ITEM_WIDTH, GRID_ITEM_HEIGHT)
                isFocusable = true
                isFocusableInTouchMode = true
                setBackgroundColor(ContextCompat.getColor(context, R.color.default_background))
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }
            return Presenter.ViewHolder(view)
        }

        override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
            (viewHolder.view as TextView).text = item as? String
        }

        override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) = Unit
    }

    private inner class StatusPresenter : Presenter() {
        override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
            val view = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(STATUS_ITEM_WIDTH, STATUS_ITEM_HEIGHT)
                setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = false
            }
            return Presenter.ViewHolder(view)
        }

        override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
            (viewHolder.view as TextView).text = item as? String
        }

        override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) = Unit
    }

    companion object {
        private const val BACKGROUND_UPDATE_DELAY_MS = 300L
        private const val GRID_ITEM_WIDTH = 320
        private const val GRID_ITEM_HEIGHT = 120
        private const val STATUS_ITEM_WIDTH = 720
        private const val STATUS_ITEM_HEIGHT = 72
    }
}

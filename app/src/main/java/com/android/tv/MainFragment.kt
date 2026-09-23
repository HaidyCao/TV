package com.android.tv

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
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
import androidx.leanback.widget.ViewHolderTask
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
    private lateinit var cardMetrics: TvCardMetrics
    private var backgroundUri: String? = null
    private var previewFrameManager: PreviewFrameManager? = null
    private var livePreviewFrameStore: LivePreviewFrameStore? = null
    private var tvChannelPreviewController: TvChannelPreviewController? = null
    private var visibleLivePreviewController: VisibleLivePreviewController? = null
    private var focusedPreviewMovie: Movie? = null
    private var focusedPreviewHolder: CardPresenter.CardViewHolder? = null
    private var appliedPreviewMode = TvChannelPreviewMode.FOCUSED_AND_VISIBLE
    private var favoriteKeys: Set<String> = emptySet()
    private var latestGroups: Map<String, List<Movie>> = emptyMap()
    private var rowsInitialized = false
    private var savedFocusPosition: TvSavedFocusPosition? = null
    private var pendingFocusRestore: TvSavedFocusPosition? = null
    private var pendingFavoriteFocusRestore: TvSavedFocusPosition? = null
    private var pendingResumeFocusRestore: TvSavedFocusPosition? = null
    private var initialFocusPending = false
    private var initialFocusApplied = false
    private var focusRequestToken = 0L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initialFocusPending = false
        initialFocusApplied = false
        pendingFocusRestore = savedFocusPosition
        pendingFavoriteFocusRestore = null
        appliedPreviewMode = ChannelPreviewPreferences.getTvMode(requireContext())
        favoriteKeys = ChannelFavorites.favoriteKeys(requireContext())
        previewFrameManager = PreviewFrameManager()
        livePreviewFrameStore = LivePreviewFrameStore()
        tvChannelPreviewController = TvChannelPreviewController(
            requireContext(),
            frameStore = livePreviewFrameStore!!
        )
        visibleLivePreviewController = VisibleLivePreviewController(
            requireContext(),
            frameStore = livePreviewFrameStore!!
        )
        prepareBackgroundManager()
        setupUiElements()
        setupEventListeners()
        observeChannelState()
        ChannelRepository.ensureLoaded(requireContext())
    }

    override fun onResume() {
        super.onResume()
        val storedPreviewMode = ChannelPreviewPreferences.getTvMode(requireContext())
        if (storedPreviewMode != appliedPreviewMode) {
            val previousMode = appliedPreviewMode
            visibleLivePreviewController?.pause()
            tvChannelPreviewController?.stop()
            if (storedPreviewMode == TvChannelPreviewMode.OFF) {
                livePreviewFrameStore?.clear()
            } else if (
                previousMode == TvChannelPreviewMode.FOCUSED_AND_VISIBLE &&
                storedPreviewMode == TvChannelPreviewMode.FOCUSED_ONLY
            ) {
                livePreviewFrameStore?.clearStaticFrames()
            }
            appliedPreviewMode = storedPreviewMode
            pendingFocusRestore = pendingResumeFocusRestore ?: savedFocusPosition
            pendingResumeFocusRestore = null
            initialFocusApplied = false
            initialFocusPending = false
            renderRows(latestGroups, force = true)
        }
        if (!TvChannelPreviewModePolicy.allowsFocusedStream(appliedPreviewMode)) {
            visibleLivePreviewController?.pause()
            visibleLivePreviewController?.setFocusPriorityPending(false)
            tvChannelPreviewController?.stop()
        } else {
            if (TvChannelPreviewModePolicy.allowsStaticCapture(appliedPreviewMode)) {
                visibleLivePreviewController?.resume()
            } else {
                visibleLivePreviewController?.pause()
            }
            val movie = focusedPreviewMovie
            val holder = focusedPreviewHolder
            if (movie != null && holder != null && holder.cardView.hasFocus()) {
                val focusPreviewEligible = TvChannelPreviewPolicy.canPreview(movie)
                visibleLivePreviewController?.setFocusPriorityPending(
                    TvChannelPreviewModePolicy.allowsStaticCapture(appliedPreviewMode) &&
                        focusPreviewEligible
                )
                tvChannelPreviewController?.schedule(movie, holder) {
                    visibleLivePreviewController?.setFocusPriorityPending(false)
                }
            }
        }
        val updatedFavorites = ChannelFavorites.favoriteKeys(requireContext())
        if (updatedFavorites != favoriteKeys) {
            favoriteKeys = updatedFavorites
            renderRows(latestGroups, force = true)
        }
        requestSavedFocusRestore()
    }

    override fun onPause() {
        visibleLivePreviewController?.pause()
        visibleLivePreviewController?.setFocusPriorityPending(false)
        tvChannelPreviewController?.stop()
        super.onPause()
    }

    override fun onDestroyView() {
        backgroundUpdate?.let(backgroundHandler::removeCallbacks)
        backgroundUpdate = null
        visibleLivePreviewController?.release()
        visibleLivePreviewController = null
        (activity as? MainActivity)?.showChannelStatus(null)
        rowsInitialized = false
        tvChannelPreviewController?.release()
        tvChannelPreviewController = null
        focusedPreviewMovie = null
        focusedPreviewHolder = null
        previewFrameManager?.release()
        previewFrameManager = null
        livePreviewFrameStore?.clear()
        livePreviewFrameStore = null
        super.onDestroyView()
    }

    private fun prepareBackgroundManager() {
        backgroundManager = BackgroundManager.getInstance(requireActivity())
        backgroundManager.attach(requireActivity().window)
        defaultBackground = ContextCompat.getDrawable(requireContext(), R.drawable.default_background)
        metrics = resources.displayMetrics
        cardMetrics = TvCardMetrics.forScreenWidth(metrics.widthPixels)
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
                    val status = TvChannelStatusPolicy.presentation(state)
                    (activity as? MainActivity)?.showChannelStatus(status)
                    renderRows(state.groups)
                }
            }
        }
    }

    private fun renderRows(
        tvGroups: Map<String, List<Movie>>,
        force: Boolean = false
    ) {
        val previousGroups = latestGroups
        val previousFavoriteKeys = favoriteKeys
        val channels = tvGroups.values.flatten()
        favoriteKeys = ChannelFavorites.migrateLegacyKeys(requireContext(), channels)
        val groupsChanged = tvGroups != previousGroups
        latestGroups = tvGroups
        if (
            rowsInitialized &&
            !force &&
            !groupsChanged &&
            favoriteKeys == previousFavoriteKeys
        ) return

        focusRequestToken += 1L
        initialFocusPending = false
        visibleLivePreviewController?.reset()
        visibleLivePreviewController?.setFocusPriorityPending(false)
        tvChannelPreviewController?.stop()
        focusedPreviewMovie = null
        focusedPreviewHolder = null
        val rowsAdapter = ArrayObjectAdapter(TvListRowPresenter(cardMetrics))
        val cardPresenter = CardPresenter(
            previewFrameManager = previewFrameManager,
            livePreviewFrameStore = livePreviewFrameStore,
            isFavorite = { channel -> ChannelFavorites.isFavorite(channel, favoriteKeys) },
            onFavoriteToggle = ::toggleFavorite,
            onCardUnbound = ::onCardUnbound,
            onCardFocusChanged = { movie, holder, hasFocus ->
                if (hasFocus) {
                    visibleLivePreviewController?.cancel(holder)
                    focusedPreviewMovie = movie
                    focusedPreviewHolder = holder
                } else if (focusedPreviewHolder === holder) {
                    focusedPreviewMovie = null
                    focusedPreviewHolder = null
                    visibleLivePreviewController?.setFocusPriorityPending(false)
                }
                if (hasFocus &&
                    TvChannelPreviewModePolicy.allowsFocusedStream(appliedPreviewMode)
                ) {
                    val focusPreviewEligible = TvChannelPreviewPolicy.canPreview(movie)
                    visibleLivePreviewController?.setFocusPriorityPending(
                        TvChannelPreviewModePolicy.allowsStaticCapture(appliedPreviewMode) &&
                            focusPreviewEligible
                    )
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                        TvChannelPreviewModePolicy.allowsStaticCapture(appliedPreviewMode)
                    ) {
                        visibleLivePreviewController?.resume()
                    }
                    tvChannelPreviewController?.schedule(movie, holder) {
                        visibleLivePreviewController?.setFocusPriorityPending(false)
                    }
                } else {
                    tvChannelPreviewController?.stopIfAttached(holder)
                    if (TvChannelPreviewModePolicy.allowsStaticCapture(appliedPreviewMode)) {
                        visibleLivePreviewController?.request(movie, holder)
                    } else {
                        visibleLivePreviewController?.cancel(holder)
                    }
                }
            },
            onCardVisibilityChanged = { movie, holder, isVisible ->
                if (isVisible &&
                    TvChannelPreviewModePolicy.allowsStaticCapture(appliedPreviewMode) &&
                    !holder.isFocused()
                ) {
                    visibleLivePreviewController?.request(movie, holder)
                } else {
                    visibleLivePreviewController?.cancel(holder)
                }
            },
            cardMetrics = cardMetrics,
            previewMode = appliedPreviewMode,
            onCardScrolled = { visibleLivePreviewController?.onViewportScrolled() }
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

        adapter = rowsAdapter
        rowsInitialized = true
        requestInitialFocusIfNeeded()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            TvChannelPreviewModePolicy.allowsStaticCapture(appliedPreviewMode)
        ) {
            visibleLivePreviewController?.resume()
        }
    }

    private fun requestInitialFocusIfNeeded() {
        if (initialFocusApplied || initialFocusPending) return

        val restorePosition = pendingFavoriteFocusRestore ?: pendingFocusRestore
        val restoredTarget = TvFocusRestorePolicy.choose(
            latestGroups,
            favoriteKeys,
            restorePosition
        )
        if (restoredTarget == null && TvInitialFocusPolicy.choose(latestGroups, favoriteKeys) == null) {
            return
        }
        initialFocusPending = true
        val requestToken = focusRequestToken
        val rootView = view ?: run {
            initialFocusPending = false
            return
        }
        rootView.post {
            initialFocusPending = false
            if (requestToken != focusRequestToken || initialFocusApplied || !isAdded || view !== rootView) {
                return@post
            }

            val currentRestoredTarget = TvFocusRestorePolicy.choose(
                latestGroups,
                favoriteKeys,
                pendingFavoriteFocusRestore ?: pendingFocusRestore
            )
            val currentTarget = currentRestoredTarget
                ?: TvInitialFocusPolicy.choose(latestGroups, favoriteKeys)
                ?: return@post
            setSelectedPosition(
                currentTarget.rowIndex,
                false,
                object : Presenter.ViewHolderTask() {
                    override fun run(viewHolder: Presenter.ViewHolder) {
                        if (requestToken != focusRequestToken || !isAdded || view !== rootView) return
                        val listRowViewHolder = viewHolder as? ListRowPresenter.ViewHolder ?: return
                        listRowViewHolder.gridView.setSelectedPosition(
                            currentTarget.itemIndex,
                            ViewHolderTask { selectedViewHolder ->
                                if (requestToken == focusRequestToken && isAdded && view === rootView) {
                                    selectedViewHolder.itemView.requestFocus()
                                    pendingFocusRestore = null
                                    pendingFavoriteFocusRestore = null
                                    initialFocusApplied = true
                                }
                            }
                        )
                    }
                }
            )
        }
    }

    private fun requestSavedFocusRestore() {
        val savedPosition = pendingResumeFocusRestore ?: return
        val target = TvFocusRestorePolicy.choose(latestGroups, favoriteKeys, savedPosition)
            ?: run {
                pendingResumeFocusRestore = null
                return
            }
        if (!isAdded) return
        val rootView = view ?: return
        rootView.post {
            if (!isAdded || view !== rootView) return@post
            rootView.viewTreeObserver.addOnPreDrawListener(
                object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        rootView.viewTreeObserver.removeOnPreDrawListener(this)
                        if (!isAdded || view !== rootView) return true
                        setSelectedPosition(
                            target.rowIndex,
                            false,
                            object : Presenter.ViewHolderTask() {
                                override fun run(viewHolder: Presenter.ViewHolder) {
                                    val listRowViewHolder =
                                        viewHolder as? ListRowPresenter.ViewHolder ?: return
                                    listRowViewHolder.gridView.setSelectedPosition(
                                        target.itemIndex,
                                        ViewHolderTask { selectedViewHolder ->
                                            selectedViewHolder.itemView.requestFocus()
                                            rootView.post {
                                                if (pendingResumeFocusRestore == savedPosition) {
                                                    pendingResumeFocusRestore = null
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        )
                        return true
                    }
                }
            )
        }
    }

    fun openSettingsFromToolbar() {
        val fallback = TvInitialFocusPolicy.choose(latestGroups, favoriteKeys)?.let { target ->
            val favoriteChannels = FavoriteChannelResolver.resolve(
                latestGroups.values.flatten(),
                favoriteKeys
            )
            if (favoriteChannels.isNotEmpty() && target.rowIndex == 0) {
                favoriteChannels.getOrNull(target.itemIndex)
            } else {
                latestGroups.values.elementAtOrNull(target.rowIndex)?.getOrNull(target.itemIndex)
            }
        }
        if (savedFocusPosition == null && fallback != null) {
            val captured = TvFocusRestorePolicy.capture(latestGroups, fallback, null)
            val favorites = FavoriteChannelResolver.resolve(
                latestGroups.values.flatten(),
                favoriteKeys
            )
            savedFocusPosition = if (favorites.isNotEmpty() && fallback == favorites.first()) {
                captured?.copy(
                    groupName = null,
                    rowKind = TvFocusRowKind.FAVORITES
                )
            } else {
                captured
            }
        }
        pendingResumeFocusRestore = savedFocusPosition
        visibleLivePreviewController?.pause()
        visibleLivePreviewController?.setFocusPriorityPending(false)
        tvChannelPreviewController?.stop()
        startActivity(Intent(requireActivity(), SettingsActivity::class.java))
    }

    fun focusFirstBrowseChannel(): Boolean {
        val target = TvInitialFocusPolicy.choose(latestGroups, favoriteKeys) ?: return false
        val rootView = view ?: return false
        if (!rowsInitialized) return false
        rootView.post {
            if (!isAdded || view !== rootView) return@post
            setSelectedPosition(
                target.rowIndex,
                false,
                object : Presenter.ViewHolderTask() {
                    override fun run(viewHolder: Presenter.ViewHolder) {
                        val rowViewHolder = viewHolder as? ListRowPresenter.ViewHolder ?: return
                        rowViewHolder.gridView.setSelectedPosition(
                            target.itemIndex,
                            ViewHolderTask { selectedViewHolder ->
                                selectedViewHolder.itemView.requestFocus()
                            }
                        )
                    }
                }
            )
        }
        return true
    }

    private fun onCardUnbound(holder: CardPresenter.CardViewHolder) {
        visibleLivePreviewController?.cancel(holder)
        if (focusedPreviewHolder === holder) {
            focusedPreviewMovie = null
            focusedPreviewHolder = null
            visibleLivePreviewController?.setFocusPriorityPending(false)
        }
        tvChannelPreviewController?.stopIfAttached(holder)
    }

    private fun toggleFavorite(channel: Movie) {
        val focusPosition = TvFocusRestorePolicy.capture(
            groups = latestGroups,
            channel = channel,
            previous = savedFocusPosition
        )
        if (focusPosition != null) {
            // Freeze the identity before rebuilding rows. The favorite row may
            // be inserted or removed, so row indexes alone are not stable.
            savedFocusPosition = focusPosition
            initialFocusApplied = false
            initialFocusPending = false
        }
        pendingFavoriteFocusRestore = savedFocusPosition
        val added = ChannelFavorites.toggle(requireContext(), channel)
        favoriteKeys = ChannelFavorites.favoriteKeys(requireContext())
        renderRows(latestGroups, force = true)
        Toast.makeText(
            requireContext(),
            if (added) R.string.favorite_added else R.string.favorite_removed,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun setupEventListeners() {
        setOnSearchClickedListener {
            visibleLivePreviewController?.pause()
            visibleLivePreviewController?.setFocusPriorityPending(false)
            tvChannelPreviewController?.stop()
            pendingResumeFocusRestore = savedFocusPosition
            startActivity(Intent(requireActivity(), SearchActivity::class.java))
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
            visibleLivePreviewController?.pause()
            visibleLivePreviewController?.setFocusPriorityPending(false)
            tvChannelPreviewController?.stop()
            when (item) {
                is Movie -> openMovie(item, itemViewHolder)
            }
        }
    }

    private fun openMovie(movie: Movie, itemViewHolder: Presenter.ViewHolder) {
        visibleLivePreviewController?.pause()
        visibleLivePreviewController?.setFocusPriorityPending(false)
        tvChannelPreviewController?.stop()
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
                rememberFocusPosition(item, row)
                backgroundUri = item.backgroundImageUrl
                scheduleBackgroundUpdate()
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    TvChannelPreviewModePolicy.allowsStaticCapture(appliedPreviewMode)
                ) {
                    visibleLivePreviewController?.resume()
                }
            } else {
                tvChannelPreviewController?.stop()
                visibleLivePreviewController?.pause()
                visibleLivePreviewController?.setFocusPriorityPending(false)
            }
        }
    }

    private fun rememberFocusPosition(
        movie: Movie,
        row: Row
    ) {
        if (
            pendingResumeFocusRestore != null ||
            pendingFocusRestore != null ||
            pendingFavoriteFocusRestore != null
        ) return
        val channelKey = ChannelFavorites.favoriteKeyFor(movie) ?: return
        val headerName = row.headerItem?.name ?: return
        val isFavoritesRow = headerName == getString(R.string.favorite_channels)
        val originalGroupName = latestGroups.entries.firstOrNull { (_, channels) ->
            channels.any { channel ->
                ChannelFavorites.favoriteKeyFor(channel) == channelKey
            }
        }?.key
        val rowKind = if (isFavoritesRow) {
            TvFocusRowKind.FAVORITES
        } else {
            TvFocusRowKind.ORIGINAL_GROUP
        }
        val groupName = originalGroupName ?: headerName.takeUnless { isFavoritesRow }
        savedFocusPosition = TvSavedFocusPosition(
            channelKey = channelKey,
            groupName = groupName,
            rowKind = rowKind
        )
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

    private class TvListRowPresenter(
        private val cardMetrics: TvCardMetrics
    ) : ListRowPresenter() {
        override fun initializeRowViewHolder(holder: RowPresenter.ViewHolder) {
            super.initializeRowViewHolder(holder)
            (holder as? ListRowPresenter.ViewHolder)
                ?.gridView
                ?.setHorizontalSpacing(cardMetrics.itemSpacingPx)
        }
    }

    companion object {
        private const val BACKGROUND_UPDATE_DELAY_MS = 300L
    }
}

package com.android.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.os.BundleCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class PlaybackActivity : AppCompatActivity() {

    private data class PlaybackRequest(
        val generation: Long,
        val channelId: Long,
        val sourceUrl: String,
        val videoUrl: String
    )

    private var player: ExoPlayer? = null
    private var currentChannel: Movie? = null
    private var playbackRequestGeneration = 0L
    private var activePlaybackRequest: PlaybackRequest? = null
    private var recordedPlaybackRequestGeneration: Long? = null
    private var playbackUiState = PlaybackUiState.CONNECTING
    private var playbackCanRetry = true
    private var channelOverlayState = PlaybackChannelOverlayPolicy.hidden()
    private val overlayHandler = Handler(Looper.getMainLooper())
    private var hideChannelOverlay: Runnable? = null

    private lateinit var playerView: PlayerView
    private lateinit var channelOverlay: View
    private lateinit var channelTitle: TextView
    private lateinit var channelCategory: TextView
    private lateinit var statusOverlay: View
    private lateinit var statusMessage: TextView
    private lateinit var statusActionRow: View
    private lateinit var nextChannelButton: Button
    private lateinit var retryButton: Button
    private lateinit var backButton: Button
    private lateinit var channelDirectoryPanel: View
    private lateinit var directoryNotice: TextView
    private lateinit var directoryCategories: RecyclerView
    private lateinit var directoryChannels: RecyclerView
    private lateinit var directoryCategoryAdapter: DirectoryCategoryAdapter
    private lateinit var directoryChannelAdapter: DirectoryChannelAdapter
    private var channelDirectoryOpen = false
    private var channelDirectory: PlaybackChannelDirectory? = null
    private var selectedDirectoryCategoryIndex = 0
    private var focusedDirectoryCategoryIndex = 0
    private var directoryPreviousFocus: View? = null
    private val channelDirectoryBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            closeChannelDirectory(restoreFocus = true, restorePlaybackChrome = true)
        }
    }

    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersivePlayback()
        setContentView(R.layout.activity_playback)
        bindViews()
        onBackPressedDispatcher.addCallback(this, channelDirectoryBackCallback)
        disablePlayerController()

        nextChannelButton.setOnClickListener {
            switchChannel(direction = PlaybackChannelKeyPolicy.NEXT_CHANNEL)
        }
        retryButton.setOnClickListener { retryCurrentChannel() }
        backButton.setOnClickListener { finish() }
        renderPlaybackState()

        val initialChannel = BundleCompat.getSerializable(
            intent.extras ?: Bundle(),
            DetailsActivity.MOVIE,
            Movie::class.java
        )
        if (initialChannel == null) {
            showPlaybackError(getString(R.string.playback_invalid_channel), canRetry = false)
            return
        }

        val videoUrl = initialChannel.videoUrl
        Log.d(TAG, "Opening channel: ${initialChannel.title}")
        if (videoUrl.isNullOrBlank()) {
            Log.e(TAG, "Video URL is null or empty")
            showPlaybackError(getString(R.string.playback_invalid_channel), canRetry = false)
            return
        }
        currentChannel = initialChannel

        player = PlaybackPlayerFactory.create(this).also { createdPlayer ->
            createdPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (playbackUiState != PlaybackUiState.ERROR) {
                        updatePlaybackState(state, createdPlayer.isPlaying, createdPlayer)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (playbackUiState != PlaybackUiState.ERROR) {
                        updatePlaybackState(createdPlayer.playbackState, isPlaying, createdPlayer)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "Player error: ${error.errorCodeName} (${error.message})")
                    showPlaybackError(
                        getString(playbackFailureCategory(error).messageResId()),
                        canRetry = true
                    )
                }
            })
            playerView.player = createdPlayer
        }

        playChannel(initialChannel, showOverlay = true)
        ChannelRepository.ensureLoaded(this)
        observeChannelDirectorySource()
    }

    override fun onResume() {
        super.onResume()
        enableImmersivePlayback()
        if (playbackUiState != PlaybackUiState.ERROR && currentChannel != null) {
            player?.play()
        }
    }

    override fun onPause() {
        player?.pause()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersivePlayback()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK &&
            event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0
        ) {
            when (PlaybackDirectoryKeyPolicy.route(event.keyCode, channelDirectoryOpen)) {
                PlaybackDirectoryKeyRoute.OPEN_DIRECTORY -> {
                    openChannelDirectory()
                    return true
                }
                PlaybackDirectoryKeyRoute.CLOSE_DIRECTORY -> {
                    closeChannelDirectory(restoreFocus = true, restorePlaybackChrome = true)
                    return true
                }
                PlaybackDirectoryKeyRoute.PREVIOUS_CHANNEL -> {
                    switchChannel(direction = PlaybackChannelKeyPolicy.PREVIOUS_CHANNEL)
                    return true
                }
                PlaybackDirectoryKeyRoute.NEXT_CHANNEL -> {
                    switchChannel(direction = PlaybackChannelKeyPolicy.NEXT_CHANNEL)
                    return true
                }
                PlaybackDirectoryKeyRoute.TOGGLE_CHANNEL_INFO -> {
                    if (currentChannel != null &&
                        playbackUiState != PlaybackUiState.ERROR &&
                        playbackUiState != PlaybackUiState.ENDED
                    ) {
                        toggleChannelOverlay()
                        return true
                    }
                }
                PlaybackDirectoryKeyRoute.NAVIGATE_DIRECTORY,
                PlaybackDirectoryKeyRoute.EXIT_PLAYBACK,
                PlaybackDirectoryKeyRoute.UNHANDLED -> Unit
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        hideChannelOverlay?.let(overlayHandler::removeCallbacks)
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun bindViews() {
        playerView = findViewById(R.id.player_view)
        channelOverlay = findViewById(R.id.channel_switch_overlay)
        channelTitle = findViewById(R.id.channel_switch_title)
        channelCategory = findViewById(R.id.channel_switch_category)
        statusOverlay = findViewById(R.id.playback_status_overlay)
        statusMessage = findViewById(R.id.playback_status_message)
        statusActionRow = findViewById(R.id.playback_action_row)
        nextChannelButton = findViewById(R.id.playback_next_channel)
        retryButton = findViewById(R.id.playback_retry)
        backButton = findViewById(R.id.playback_back)
        channelDirectoryPanel = findViewById(R.id.channel_directory_panel)
        directoryNotice = findViewById(R.id.channel_directory_notice)
        directoryCategories = findViewById(R.id.channel_directory_categories)
        directoryChannels = findViewById(R.id.channel_directory_channels)
        directoryCategoryAdapter = DirectoryCategoryAdapter()
        directoryChannelAdapter = DirectoryChannelAdapter()
        directoryCategories.layoutManager = LinearLayoutManager(this)
        directoryCategories.adapter = directoryCategoryAdapter
        directoryChannels.layoutManager = LinearLayoutManager(this)
        directoryChannels.adapter = directoryChannelAdapter
        directoryCategories.setItemViewCacheSize(12)
        directoryChannels.setItemViewCacheSize(16)
        sizeChannelDirectoryForScreen()
    }

    private fun observeChannelDirectorySource() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ChannelRepository.state.collect {
                    if (channelDirectoryOpen) {
                        rebuildChannelDirectory(requestFocus = false)
                    }
                }
            }
        }
    }

    private fun sizeChannelDirectoryForScreen() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).roundToInt()

        val screenWidth = resources.displayMetrics.widthPixels
        val availableWidth = (screenWidth - dp(56)).coerceAtLeast(dp(1))
        val maxWidth = minOf(dp(620), availableWidth)
        val minWidth = minOf(dp(380), maxWidth)
        val panelWidth = (screenWidth * 0.62f).roundToInt().coerceIn(minWidth, maxWidth)
        channelDirectoryPanel.layoutParams = channelDirectoryPanel.layoutParams.apply {
            width = panelWidth
        }
        directoryCategories.layoutParams = directoryCategories.layoutParams.apply {
            width = (panelWidth * 0.32f).roundToInt().coerceIn(dp(124), dp(160))
        }
        directoryCategories.requestLayout()
        channelDirectoryPanel.requestLayout()
    }

    private fun openChannelDirectory() {
        if (channelDirectoryOpen) return
        directoryPreviousFocus = currentFocus
        channelDirectoryOpen = true
        channelDirectoryBackCallback.isEnabled = true
        channelDirectoryPanel.visibility = View.VISIBLE

        hideChannelOverlay?.let(overlayHandler::removeCallbacks)
        hideChannelOverlay = null
        channelOverlayState = PlaybackChannelOverlayPolicy.hidden()
        channelOverlay.visibility = View.GONE
        statusOverlay.visibility = View.GONE
        hideStatusActions()

        channelDirectory = null
        selectedDirectoryCategoryIndex = 0
        rebuildChannelDirectory(requestFocus = true)
        channelDirectoryPanel.announceForAccessibility(getString(R.string.playback_directory_title))
    }

    private fun closeChannelDirectory(
        restoreFocus: Boolean,
        restorePlaybackChrome: Boolean
    ) {
        if (!channelDirectoryOpen) return
        channelDirectoryOpen = false
        channelDirectoryBackCallback.isEnabled = false
        channelDirectoryPanel.visibility = View.GONE

        if (restorePlaybackChrome) {
            when (playbackUiState) {
                PlaybackUiState.ERROR -> {
                    statusOverlay.visibility = View.VISIBLE
                    renderStatusActions(canRetry = playbackCanRetry, requestFocus = false)
                }
                else -> renderPlaybackState()
            }
        } else {
            statusOverlay.visibility = View.GONE
            hideStatusActions()
        }

        val focusTarget = directoryPreviousFocus
        directoryPreviousFocus = null
        if (restoreFocus && focusTarget != null) {
            focusTarget.post {
                if (!channelDirectoryOpen && focusTarget.isShown && focusTarget.isFocusable) {
                    focusTarget.requestFocus()
                } else if (!channelDirectoryOpen && hasPlaybackStatusActions()) {
                    renderStatusActions(canRetry = playbackCanRetry, requestFocus = true)
                }
            }
        } else if (restoreFocus && hasPlaybackStatusActions()) {
            renderStatusActions(canRetry = playbackCanRetry, requestFocus = true)
        }
    }

    private fun hasPlaybackStatusActions(): Boolean =
        playbackUiState == PlaybackUiState.ERROR || playbackUiState == PlaybackUiState.ENDED

    private fun rebuildChannelDirectory(
        notice: String? = null,
        requestFocus: Boolean
    ) {
        val previousModel = channelDirectory
        val previousCategory = previousModel?.categories?.getOrNull(selectedDirectoryCategoryIndex)
        val previousCategoryKey = previousCategory?.key
        val categoriesHadFocus = directoryCategories.hasFocus()
        val channelsHadFocus = directoryChannels.hasFocus()
        val focusedChannelPosition = directoryChannels.findFocus()
            ?.let(directoryChannels::getChildAdapterPosition)
            ?.takeIf { it != RecyclerView.NO_POSITION }
        val focusedChannel = previousCategory?.channels?.getOrNull(focusedChannelPosition ?: -1)
        val state = ChannelRepository.state.value
        val sourceUrl = TvDataManager.getSourceUrl(this)
        val groupsSourceUrl = when (state) {
            is ChannelState.Content -> state.sourceUrl
            is ChannelState.Loading,
            is ChannelState.Error -> ChannelRepository.groupsSourceUrl()
            is ChannelState.Empty -> state.sourceUrl
            ChannelState.Idle -> null
        }
        val recentChannels = RecentWatchRepository.resolve(
            context = this,
            sourceUrl = sourceUrl,
            groupsSourceUrl = groupsSourceUrl,
            groups = state.groups
        ).map(ResolvedRecentWatch::channel)
        val model = PlaybackChannelDirectoryPolicy.build(
            sourceUrl = sourceUrl,
            groupsSourceUrl = groupsSourceUrl,
            groups = state.groups,
            favoriteKeys = ChannelFavorites.favoriteKeys(this),
            recentChannels = recentChannels,
            currentChannel = currentChannel,
            currentPlaybackSourceUrl = activePlaybackRequest?.sourceUrl
        )
        channelDirectory = model
        val retainedCategoryIndex = previousCategoryKey?.let { categoryKey ->
            model.categories.indexOfFirst { it.key == categoryKey }
        }?.takeIf { it >= 0 }
        selectedDirectoryCategoryIndex = (retainedCategoryIndex ?: model.initialCategoryIndex)
            .coerceIn(0, (model.categories.size - 1).coerceAtLeast(0))
        focusedDirectoryCategoryIndex = selectedDirectoryCategoryIndex
        directoryCategoryAdapter.submit(model.categories, selectedDirectoryCategoryIndex)
        val selectedCategory = model.categories.getOrNull(selectedDirectoryCategoryIndex)
        directoryChannelAdapter.submit(
            channels = selectedCategory?.channels.orEmpty(),
            currentChannelId = model.currentChannelId,
            currentVideoUrl = model.currentChannelVideoUrl
        )

        directoryNotice.text = notice ?: when {
            model.categories.isNotEmpty() -> getString(R.string.playback_directory_help)
            groupsSourceUrl != sourceUrl -> {
                ChannelRepository.ensureLoaded(applicationContext)
                when (state) {
                    is ChannelState.Error -> getString(R.string.playback_directory_error)
                    else -> getString(R.string.playback_directory_loading)
                }
            }
            else -> getString(R.string.playback_directory_empty)
        }

        val becameAvailable = previousModel?.categories.isNullOrEmpty() && model.categories.isNotEmpty()
        if (requestFocus || becameAvailable) {
            val currentPosition = if (selectedDirectoryCategoryIndex == model.initialCategoryIndex) {
                model.initialChannelIndex
            } else {
                selectedCategory?.channels?.indexOfFirst { channel ->
                    channel.id == model.currentChannelId && channel.videoUrl == model.currentChannelVideoUrl
                }?.takeIf { it >= 0 } ?: 0
            }
            if (selectedCategory?.channels?.isNotEmpty() == true) {
                requestDirectoryCategoryFocus(selectedDirectoryCategoryIndex) {
                    requestDirectoryChannelFocus(currentPosition)
                }
            } else if (model.categories.isNotEmpty()) {
                requestDirectoryCategoryFocus(selectedDirectoryCategoryIndex)
            }
        } else if (categoriesHadFocus && model.categories.isNotEmpty()) {
            requestDirectoryCategoryFocus(selectedDirectoryCategoryIndex)
        } else if (channelsHadFocus && selectedCategory?.channels?.isNotEmpty() == true) {
            val retainedChannelPosition = focusedChannel?.let { focused ->
                selectedCategory.channels.indexOfFirst { channel ->
                    channel.id == focused.id && channel.videoUrl == focused.videoUrl
                }
            }?.takeIf { it >= 0 }
            val currentPosition = retainedChannelPosition
                ?: selectedCategory.channels.indexOfFirst { channel ->
                    channel.id == model.currentChannelId && channel.videoUrl == model.currentChannelVideoUrl
                }.takeIf { it >= 0 }
                ?: 0
            requestDirectoryCategoryFocus(selectedDirectoryCategoryIndex) {
                requestDirectoryChannelFocus(currentPosition)
            }
        }
    }

    private fun activateDirectoryCategory(position: Int, moveFocusToChannel: Boolean) {
        val model = channelDirectory ?: return
        val category = model.categories.getOrNull(position) ?: return
        selectedDirectoryCategoryIndex = position
        directoryCategoryAdapter.setSelectedPosition(position)
        directoryChannelAdapter.submit(
            channels = category.channels,
            currentChannelId = model.currentChannelId,
            currentVideoUrl = model.currentChannelVideoUrl
        )
        if (moveFocusToChannel && category.channels.isNotEmpty()) {
            val currentIndex = category.channels.indexOfFirst { channel ->
                channel.id == model.currentChannelId && channel.videoUrl == model.currentChannelVideoUrl
            }.takeIf { it >= 0 } ?: 0
            requestDirectoryChannelFocus(currentIndex)
        }
    }

    private fun requestDirectoryCategoryFocus(position: Int, afterFocus: (() -> Unit)? = null) {
        if (position !in 0 until directoryCategoryAdapter.itemCount) return
        requestRecyclerItemFocus(directoryCategories, position, afterFocus = afterFocus)
    }

    private fun requestDirectoryChannelFocus(position: Int) {
        if (position !in 0 until directoryChannelAdapter.itemCount) return
        requestRecyclerItemFocus(directoryChannels, position)
    }

    private fun requestRecyclerItemFocus(
        recyclerView: RecyclerView,
        position: Int,
        attempt: Int = 0,
        afterFocus: (() -> Unit)? = null
    ) {
        val itemCount = recyclerView.adapter?.itemCount ?: 0
        if (!channelDirectoryOpen || position !in 0 until itemCount) return
        recyclerView.scrollToPosition(position)
        recyclerView.post {
            val itemView = recyclerView.findViewHolderForAdapterPosition(position)?.itemView
            if (itemView?.requestFocus() == true) {
                afterFocus?.invoke()
            } else if (attempt < 4 && channelDirectoryOpen) {
                recyclerView.postDelayed({
                    requestRecyclerItemFocus(recyclerView, position, attempt + 1, afterFocus)
                }, 32L)
            }
        }
    }

    private fun focusDirectoryCategory() {
        requestDirectoryCategoryFocus(selectedDirectoryCategoryIndex)
    }

    private fun selectDirectoryChannel(channelId: Long) {
        val model = channelDirectory ?: return
        val state = ChannelRepository.state.value
        val currentSourceUrl = TvDataManager.getSourceUrl(this)
        val currentGroupsSourceUrl = ChannelRepository.groupsSourceUrl()
        when (val result = PlaybackChannelDirectoryPolicy.validateSelection(
            directory = model,
            selectedChannelId = channelId,
            currentSourceUrl = currentSourceUrl,
            currentGroupsSourceUrl = currentGroupsSourceUrl,
            currentGroups = state.groups
        )) {
            is PlaybackDirectorySelection.Ready -> {
                val current = currentChannel
                val sameCurrentRequest = current != null &&
                    current.id == result.channel.id &&
                    current.videoUrl == result.channel.videoUrl &&
                    activePlaybackRequest?.sourceUrl == currentSourceUrl &&
                    activePlaybackRequest?.channelId == result.channel.id &&
                    activePlaybackRequest?.videoUrl == result.channel.videoUrl?.toUri()?.toString() &&
                    playbackUiState != PlaybackUiState.ERROR &&
                    playbackUiState != PlaybackUiState.ENDED
                if (sameCurrentRequest) {
                    closeChannelDirectory(restoreFocus = true, restorePlaybackChrome = true)
                    showChannelOverlay(result.channel)
                } else {
                    closeChannelDirectory(restoreFocus = false, restorePlaybackChrome = false)
                    playChannel(result.channel, showOverlay = true)
                }
            }
            PlaybackDirectorySelection.SourceChanged,
            PlaybackDirectorySelection.ChannelChanged -> {
                directoryNotice.text = getString(R.string.playback_directory_stale)
                directoryNotice.announceForAccessibility(directoryNotice.text)
                rebuildChannelDirectory(
                    notice = getString(R.string.playback_directory_stale),
                    requestFocus = true
                )
                ChannelRepository.ensureLoaded(applicationContext)
            }
            PlaybackDirectorySelection.Unavailable -> {
                directoryNotice.text = getString(R.string.playback_directory_stale)
                directoryNotice.announceForAccessibility(directoryNotice.text)
            }
        }
    }

    private inner class DirectoryCategoryAdapter : RecyclerView.Adapter<DirectoryCategoryViewHolder>() {
        private var categories: List<PlaybackDirectoryCategory> = emptyList()
        private var selectedPosition = RecyclerView.NO_POSITION

        fun submit(nextCategories: List<PlaybackDirectoryCategory>, selected: Int) {
            categories = nextCategories
            selectedPosition = selected
            notifyDataSetChanged()
        }

        fun setSelectedPosition(position: Int) {
            val previous = selectedPosition
            selectedPosition = position
            if (previous in categories.indices) notifyItemChanged(previous)
            if (position in categories.indices) notifyItemChanged(position)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): DirectoryCategoryViewHolder {
            val density = parent.resources.displayMetrics.density
            val row = TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    (66 * density + 0.5f).roundToInt()
                ).also { params ->
                    params.setMargins(0, 0, (4 * density + 0.5f).roundToInt(), (6 * density + 0.5f).roundToInt())
                }
                background = getDrawable(R.drawable.playback_directory_row_background)
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                maxLines = 2
                setPadding((10 * density).roundToInt(), 0, (8 * density).roundToInt(), 0)
                textSize = 15f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            return DirectoryCategoryViewHolder(row)
        }

        override fun onBindViewHolder(holder: DirectoryCategoryViewHolder, position: Int) {
            val category = categories[position]
            val label = directoryCategoryLabel(category)
            holder.row.text = getString(
                R.string.playback_directory_category_row,
                label,
                category.channels.size
            )
            holder.row.contentDescription = getString(
                R.string.playback_directory_category_accessibility,
                label,
                category.channels.size
            )
            holder.row.isActivated = position == selectedPosition
            holder.row.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) focusedDirectoryCategoryIndex = holder.bindingAdapterPosition
            }
            holder.row.setOnClickListener {
                val currentPosition = holder.bindingAdapterPosition
                if (currentPosition != RecyclerView.NO_POSITION) {
                    activateDirectoryCategory(currentPosition, moveFocusToChannel = true)
                }
            }
            holder.row.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return@setOnKeyListener false
                val currentPosition = holder.bindingAdapterPosition
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (currentPosition != RecyclerView.NO_POSITION) {
                            activateDirectoryCategory(currentPosition, moveFocusToChannel = true)
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> true
                    else -> false
                }
            }
        }

        override fun getItemCount(): Int = categories.size
    }

    private inner class DirectoryCategoryViewHolder(
        val row: TextView
    ) : RecyclerView.ViewHolder(row)

    private inner class DirectoryChannelAdapter : RecyclerView.Adapter<DirectoryChannelViewHolder>() {
        private var channels: List<Movie> = emptyList()
        private var currentChannelId: Long? = null
        private var currentVideoUrl: String? = null

        fun submit(channels: List<Movie>, currentChannelId: Long?, currentVideoUrl: String?) {
            this.channels = channels
            this.currentChannelId = currentChannelId
            this.currentVideoUrl = currentVideoUrl
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): DirectoryChannelViewHolder {
            val density = parent.resources.displayMetrics.density
            val row = LinearLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    (66 * density + 0.5f).roundToInt()
                ).also { params ->
                    params.setMargins(0, 0, 0, (6 * density + 0.5f).roundToInt())
                }
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = getDrawable(R.drawable.playback_directory_row_background)
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                setPadding((12 * density).roundToInt(), (5 * density).roundToInt(), (10 * density).roundToInt(), (5 * density).roundToInt())
            }
            val title = TextView(parent.context).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                textSize = 17f
                setTextColor(0xFFFFFFFF.toInt())
            }
            val current = TextView(parent.context).apply {
                text = getString(R.string.playback_directory_current)
                textSize = 12f
                setTextColor(0xFF9ED8FF.toInt())
                visibility = View.GONE
            }
            row.addView(title, LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            row.addView(current, LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            return DirectoryChannelViewHolder(row, title, current)
        }

        override fun onBindViewHolder(holder: DirectoryChannelViewHolder, position: Int) {
            val channel = channels[position]
            val isCurrent = channel.id == currentChannelId && channel.videoUrl == currentVideoUrl
            holder.title.text = channel.title.orEmpty().ifBlank { getString(R.string.live_badge) }
            holder.current.visibility = if (isCurrent) View.VISIBLE else View.GONE
            holder.row.contentDescription = if (isCurrent) {
                getString(R.string.playback_directory_channel_current_accessibility, holder.title.text)
            } else {
                getString(R.string.playback_directory_channel_accessibility, holder.title.text)
            }
            holder.row.setOnClickListener {
                val currentPosition = holder.bindingAdapterPosition
                channels.getOrNull(currentPosition)
                    ?.let { selected -> selectDirectoryChannel(selected.id) }
            }
            holder.row.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        focusDirectoryCategory()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> true
                    else -> false
                }
            }
        }

        override fun getItemCount(): Int = channels.size
    }

    private inner class DirectoryChannelViewHolder(
        val row: LinearLayout,
        val title: TextView,
        val current: TextView
    ) : RecyclerView.ViewHolder(row)

    private fun directoryCategoryLabel(category: PlaybackDirectoryCategory): String = when (category.kind) {
        PlaybackDirectoryCategoryKind.FAVORITES -> getString(R.string.favorite_channels)
        PlaybackDirectoryCategoryKind.RECENT -> getString(R.string.recent_channels)
        PlaybackDirectoryCategoryKind.GROUP -> category.groupName.orEmpty()
    }

    private fun switchChannel(direction: Int) {
        val current = currentChannel ?: return
        val nextChannel = ChannelPlaybackNavigator.adjacent(
            current = current,
            channels = ChannelRepository.liveChannels(),
            direction = direction
        )
        if (nextChannel == null) {
            Toast.makeText(this, R.string.channel_switch_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        playChannel(nextChannel, showOverlay = true)
    }

    private fun retryCurrentChannel() {
        val channel = currentChannel
        if (channel == null || channel.videoUrl.isNullOrBlank()) {
            showPlaybackError(getString(R.string.playback_invalid_channel), canRetry = false)
            return
        }
        playChannel(channel)
    }

    private fun playChannel(channel: Movie, showOverlay: Boolean = false) {
        val videoUrl = channel.videoUrl
        if (videoUrl.isNullOrBlank()) {
            showPlaybackError(getString(R.string.playback_invalid_channel), canRetry = false)
            return
        }
        currentChannel = channel
        playbackRequestGeneration += 1L
        activePlaybackRequest = PlaybackRequest(
            generation = playbackRequestGeneration,
            channelId = channel.id,
            sourceUrl = TvDataManager.getSourceUrl(this),
            videoUrl = videoUrl.toUri().toString()
        )
        recordedPlaybackRequestGeneration = null
        playbackCanRetry = true
        playbackUiState = PlaybackUiState.CONNECTING
        renderPlaybackState()
        player?.apply {
            stop()
            setMediaItem(MediaItem.fromUri(videoUrl.toUri()))
            prepare()
            playWhenReady = true
        }
        if (showOverlay) showChannelOverlay(channel)
    }

    private fun updatePlaybackState(playerState: Int, isPlaying: Boolean, callbackPlayer: ExoPlayer) {
        playbackUiState = PlaybackStateMapper.fromPlayerState(playerState, isPlaying)
        renderPlaybackState()
        recordRecentPlaybackIfEligible(callbackPlayer, isPlaying)
    }

    private fun recordRecentPlaybackIfEligible(callbackPlayer: ExoPlayer, isPlaying: Boolean) {
        val request = activePlaybackRequest ?: return
        val channel = currentChannel ?: return
        val repositoryState = ChannelRepository.state.value
        val sourceStillCurrent = request.sourceUrl == TvDataManager.getSourceUrl(this) &&
            ChannelRepository.groupsSourceUrl() == request.sourceUrl
        val channelStillCurrent = channel.id == request.channelId &&
            channel.videoUrl?.toUri()?.toString() == request.videoUrl &&
            callbackPlayer.currentMediaItem?.localConfiguration?.uri?.toString() == request.videoUrl
        val requestStillCurrent = callbackPlayer === player &&
            activePlaybackRequest === request &&
            request.generation == playbackRequestGeneration
        if (!RecentWatchPolicy.shouldRecord(
                playbackState = playbackUiState,
                playerIsPlaying = isPlaying,
                requestStillCurrent = requestStillCurrent,
                channelStillCurrent = channelStillCurrent,
                playlistSourceStillCurrent = sourceStillCurrent
            )
        ) return
        if (recordedPlaybackRequestGeneration == request.generation) return

        val stored = RecentWatchRepository.recordPlayingChannel(
            context = this,
            sourceUrl = request.sourceUrl,
            groupsSourceUrl = ChannelRepository.groupsSourceUrl(),
            groups = repositoryState.groups,
            playingChannel = channel
        )
        if (stored) recordedPlaybackRequestGeneration = request.generation
    }

    private fun showPlaybackError(message: String, canRetry: Boolean) {
        playbackUiState = PlaybackUiState.ERROR
        playbackCanRetry = canRetry
        statusMessage.text = message
        if (channelDirectoryOpen) {
            statusOverlay.visibility = View.GONE
            hideStatusActions()
            return
        }
        statusOverlay.visibility = View.VISIBLE
        renderStatusActions(canRetry = canRetry, requestFocus = true)
    }

    private fun renderPlaybackState() {
        if (playbackUiState == PlaybackUiState.ERROR) return
        statusMessage.text = when (playbackUiState) {
            PlaybackUiState.CONNECTING -> getString(R.string.playback_connecting)
            PlaybackUiState.BUFFERING -> getString(R.string.playback_buffering)
            PlaybackUiState.ENDED -> getString(R.string.playback_ended)
            PlaybackUiState.PLAYING,
            PlaybackUiState.PAUSED,
            PlaybackUiState.ERROR -> statusMessage.text
        }
        if (channelDirectoryOpen) {
            statusOverlay.visibility = View.GONE
            hideStatusActions()
            return
        }
        statusOverlay.visibility = when (playbackUiState) {
            PlaybackUiState.PLAYING,
            PlaybackUiState.PAUSED -> View.GONE
            else -> View.VISIBLE
        }
        if (playbackUiState == PlaybackUiState.ENDED) {
            renderStatusActions(canRetry = true, requestFocus = true)
        } else {
            hideStatusActions()
        }
    }

    private fun renderStatusActions(canRetry: Boolean, requestFocus: Boolean) {
        if (channelDirectoryOpen) {
            hideStatusActions()
            return
        }
        val actions = TvPlaybackActionPolicy.resolve(
            currentChannel = currentChannel,
            channels = ChannelRepository.liveChannels(),
            canRetry = canRetry
        )
        nextChannelButton.visibility = if (actions.hasNextChannel) View.VISIBLE else View.GONE
        retryButton.visibility = if (actions.canRetry) View.VISIBLE else View.GONE
        backButton.visibility = View.VISIBLE
        statusActionRow.visibility = View.VISIBLE
        if (requestFocus) {
            val focusTarget = when (actions.primaryAction) {
                TvPlaybackPrimaryAction.NEXT_CHANNEL -> nextChannelButton
                TvPlaybackPrimaryAction.RETRY -> retryButton
                TvPlaybackPrimaryAction.BACK -> backButton
            }
            focusTarget.post { focusTarget.requestFocus() }
        }
    }

    private fun hideStatusActions() {
        statusActionRow.visibility = View.GONE
        nextChannelButton.visibility = View.GONE
        retryButton.visibility = View.GONE
        backButton.visibility = View.GONE
    }

    private fun disablePlayerController() {
        playerView.useController = false
        playerView.isFocusable = false
        playerView.isFocusableInTouchMode = false
        playerView.clearFocus()
    }

    private fun showChannelOverlay(channel: Movie) {
        channelTitle.text = channel.title
        channelCategory.text = channel.category ?: getString(R.string.live_badge)
        channelOverlayState = PlaybackChannelOverlayPolicy.autoShown(SystemClock.uptimeMillis())
        channelOverlay.visibility = View.VISIBLE
        hideChannelOverlay?.let(overlayHandler::removeCallbacks)
        hideChannelOverlay = Runnable {
            channelOverlayState = PlaybackChannelOverlayPolicy.hidden()
            channelOverlay.visibility = View.GONE
            hideChannelOverlay = null
        }
        overlayHandler.postDelayed(
            hideChannelOverlay!!,
            PlaybackChannelOverlayPolicy.AUTO_HIDE_DURATION_MILLIS
        )
    }

    private fun toggleChannelOverlay() {
        hideChannelOverlay?.let(overlayHandler::removeCallbacks)
        hideChannelOverlay = null
        channelOverlayState = PlaybackChannelOverlayPolicy.toggle(
            state = channelOverlayState,
            nowMillis = SystemClock.uptimeMillis()
        )
        channelOverlay.visibility = if (channelOverlayState.isVisible) View.VISIBLE else View.GONE
        val autoHideAtMillis = channelOverlayState.autoHideAtMillis
        if (autoHideAtMillis != null) {
            val delayMillis = (autoHideAtMillis - SystemClock.uptimeMillis())
                .coerceAtLeast(0L)
            hideChannelOverlay = Runnable {
                channelOverlayState = PlaybackChannelOverlayPolicy.hidden()
                channelOverlay.visibility = View.GONE
                hideChannelOverlay = null
            }
            overlayHandler.postDelayed(hideChannelOverlay!!, delayMillis)
        }
    }

    private fun enableImmersivePlayback() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private companion object {
        const val TAG = "PlaybackActivity"
    }
}

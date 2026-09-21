package com.android.tv

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ChannelState {
    val groups: Map<String, List<Movie>>

    data object Idle : ChannelState {
        override val groups = emptyMap<String, List<Movie>>()
    }

    data class Loading(
        override val groups: Map<String, List<Movie>> = emptyMap()
    ) : ChannelState

    data class Content(
        override val groups: Map<String, List<Movie>>,
        val sourceUrl: String,
        val fromSnapshot: Boolean,
        val updatedAtMillis: Long
    ) : ChannelState

    data class Empty(
        val sourceUrl: String,
        val fromSnapshot: Boolean
    ) : ChannelState {
        override val groups = emptyMap<String, List<Movie>>()
    }

    data class Error(
        val message: String,
        override val groups: Map<String, List<Movie>> = emptyMap()
    ) : ChannelState
}

/**
 * App-scoped playlist state. TV browse, mobile browse, and search all consume
 * this state instead of independently downloading and parsing the same source.
 */
object ChannelRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<ChannelState>(ChannelState.Idle)
    private val refreshGate = RefreshRequestGate()

    val state: StateFlow<ChannelState> = mutableState.asStateFlow()

    private var refreshJob: Job? = null
    @Volatile
    private var loadedSourceUrl: String? = null

    fun ensureLoaded(context: Context) {
        val sourceUrl = TvDataManager.getSourceUrl(context)
        if (refreshJob?.isActive == true || loadedSourceUrl == sourceUrl) return
        refresh(context)
    }

    fun refresh(context: Context, force: Boolean = false) {
        if (refreshJob?.isActive == true) {
            if (!force) return
            refreshJob?.cancel()
        }

        val requestId = refreshGate.begin()

        val appContext = context.applicationContext
        val previousGroups = mutableState.value.groups
        mutableState.value = ChannelState.Loading(previousGroups)

        refreshJob = scope.launch {
            try {
                val result = TvDataManager.fetchTvChannels(appContext, forceNetwork = force)
                val nextState = if (result.groups.isEmpty()) {
                    ChannelState.Empty(result.sourceUrl, result.fromSnapshot)
                } else {
                    ChannelState.Content(
                        groups = result.groups,
                        sourceUrl = result.sourceUrl,
                        fromSnapshot = result.fromSnapshot,
                        updatedAtMillis = result.updatedAtMillis
                    )
                }
                refreshGate.runIfCurrent(requestId) {
                    loadedSourceUrl = result.sourceUrl
                    mutableState.value = nextState
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                refreshGate.runIfCurrent(requestId) {
                    mutableState.value = ChannelState.Error(
                        message = error.message ?: "无法加载频道，请检查节目源和网络连接。",
                        groups = previousGroups
                    )
                }
            }
        }
    }

    fun invalidate() {
        loadedSourceUrl = null
        refreshJob?.cancel()
        refreshGate.begin()
    }

    /**
     * Returns the current playlist order for in-player channel navigation.
     * Reading StateFlow.value is safe here because every emitted collection is
     * immutable after publication.
     */
    fun liveChannels(): List<Movie> {
        return mutableState.value.groups.values
            .asSequence()
            .flatten()
            .filter { channel -> channel.isLive && !channel.videoUrl.isNullOrBlank() }
            .toList()
    }
}

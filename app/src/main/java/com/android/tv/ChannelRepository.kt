package com.android.tv

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val repositoryLock = Any()

    val state: StateFlow<ChannelState> = mutableState.asStateFlow()

    private var refreshJob: Job? = null
    @Volatile
    private var loadedSourceUrl: String? = null
    @Volatile
    private var sourceActivationInProgress = false

    private data class RepositorySnapshot(
        val state: ChannelState,
        val loadedSourceUrl: String?
    )

    fun ensureLoaded(context: Context) {
        val sourceUrl = TvDataManager.getSourceUrl(context)
        val shouldLoad = synchronized(repositoryLock) {
            !sourceActivationInProgress && refreshJob?.isActive != true && loadedSourceUrl != sourceUrl
        }
        if (shouldLoad) refresh(context)
    }

    fun refresh(context: Context, force: Boolean = false) {
        synchronized(repositoryLock) {
            if (sourceActivationInProgress) return
            if (refreshJob?.isActive == true) {
                if (!force) return
                refreshJob?.cancel()
            }

            val requestId = refreshGate.begin()

            val appContext = context.applicationContext
            val sourceGeneration = TvDataManager.currentSourceGeneration()
            val previousGroups = mutableState.value.groups
            mutableState.value = ChannelState.Loading(previousGroups)

            refreshJob = scope.launch {
                try {
                    val result = TvDataManager.fetchTvChannels(
                        appContext,
                        forceNetwork = force,
                        expectedSourceGeneration = sourceGeneration
                    )
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
    }

    /** Starts the atomic activation only after the caller has accepted the prepared candidate. */
    internal suspend fun activatePreparedSource(
        context: Context,
        prepared: TvPreparedSource
    ): TvSourceActivationResult {
        val appContext = context.applicationContext
        currentCoroutineContext().ensureActive()

        val previous = withContext(Dispatchers.Main.immediate) {
            synchronized(repositoryLock) {
                check(!sourceActivationInProgress) { "节目源切换正在进行" }
                sourceActivationInProgress = true
                refreshGate.begin()
                refreshJob?.cancel()
                refreshJob = null
                RepositorySnapshot(mutableState.value, loadedSourceUrl)
            }
        }

        return withContext(NonCancellable) {
            try {
                val result = withContext(Dispatchers.IO) {
                    TvDataManager.activatePreparedSource(appContext, prepared)
                }
                withContext(Dispatchers.Main.immediate) {
                    synchronized(repositoryLock) {
                        publishActivatedSource(result)
                        sourceActivationInProgress = false
                    }
                }
                result
            } catch (error: Throwable) {
                withContext(Dispatchers.Main.immediate) {
                    val shouldReload = synchronized(repositoryLock) {
                        loadedSourceUrl = previous.loadedSourceUrl
                        mutableState.value = previous.state
                        sourceActivationInProgress = false
                        previous.state is ChannelState.Loading
                    }
                    if (shouldReload) refresh(appContext, force = true)
                }
                throw error
            }
        }
    }

    /** Restores a valid previous playlist from local storage without contacting the network. */
    suspend fun restorePreviousSource(context: Context): TvSourceActivationResult? {
        val appContext = context.applicationContext
        val previous = withContext(Dispatchers.Main.immediate) {
            synchronized(repositoryLock) {
                check(!sourceActivationInProgress) { "节目源切换正在进行" }
                sourceActivationInProgress = true
                refreshGate.begin()
                refreshJob?.cancel()
                refreshJob = null
                RepositorySnapshot(mutableState.value, loadedSourceUrl)
            }
        }

        return withContext(NonCancellable) {
            try {
                val result = withContext(Dispatchers.IO) {
                    TvDataManager.restorePreviousSource(appContext)
                }
                withContext(Dispatchers.Main.immediate) {
                    if (result == null) {
                        val shouldReload = synchronized(repositoryLock) {
                            loadedSourceUrl = previous.loadedSourceUrl
                            mutableState.value = previous.state
                            sourceActivationInProgress = false
                            previous.state is ChannelState.Loading
                        }
                        if (shouldReload) refresh(appContext, force = true)
                    } else {
                        synchronized(repositoryLock) {
                            publishActivatedSource(result)
                            sourceActivationInProgress = false
                        }
                    }
                }
                result
            } catch (error: Throwable) {
                withContext(Dispatchers.Main.immediate) {
                    val shouldReload = synchronized(repositoryLock) {
                        loadedSourceUrl = previous.loadedSourceUrl
                        mutableState.value = previous.state
                        sourceActivationInProgress = false
                        previous.state is ChannelState.Loading
                    }
                    if (shouldReload) refresh(appContext, force = true)
                }
                throw error
            }
        }
    }

    private fun publishActivatedSource(result: TvSourceActivationResult) = synchronized(repositoryLock) {
        // The second gate also rejects a repository refresh that raced just before the guard.
        refreshGate.begin()
        val fetchResult = result.fetchResult
        loadedSourceUrl = fetchResult.sourceUrl
        mutableState.value = ChannelState.Content(
            groups = fetchResult.groups,
            sourceUrl = fetchResult.sourceUrl,
            fromSnapshot = fetchResult.fromSnapshot,
            updatedAtMillis = fetchResult.updatedAtMillis
        )
    }

    fun invalidate() {
        synchronized(repositoryLock) {
            loadedSourceUrl = null
            refreshJob?.cancel()
            refreshGate.begin()
        }
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

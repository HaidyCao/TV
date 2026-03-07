package com.android.tv

import android.graphics.SurfaceTexture
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem

class PhoneChannelAdapter(
    private val onClick: (Movie) -> Unit,
    private val initialPlayerPoolSize: Int = 2
) : ListAdapter<Movie, PhoneChannelAdapter.ViewHolder>(DiffCallback()) {

    private val playerPool = mutableListOf<ExoPlayer>()
    private var playerPoolSize = initialPlayerPoolSize
    private val activeHolders = mutableSetOf<ViewHolder>()
    private val holdersWaitingForSurface = mutableMapOf<ViewHolder, ExoPlayer>()
    private var isManaging = false
    private val playerPauseHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var recyclerView: RecyclerView? = null

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        releasePlayerPool()
    }

    private fun initializePlayerPool() {
        val context = recyclerView?.context ?: return
        repeat(playerPoolSize) {
            val player = ExoPlayer.Builder(context).build().apply {
                volume = 0f
                playWhenReady = true
            }
            playerPool.add(player)
            android.util.Log.d("PhoneChannelAdapter", "Player created: ${player.hashCode()}, pool size: ${playerPool.size}")
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
        initializePlayerPool()
    }

    private fun releasePlayerPool() {
        playerPool.forEach { it.release() }
        playerPool.clear()
        holdersWaitingForSurface.clear()
    }

    fun updatePoolSize(newSize: Int) {
        if (playerPoolSize == newSize) return
        
        android.util.Log.d("PhoneChannelAdapter", "Updating pool size from $playerPoolSize to $newSize")
        
        val oldSize = playerPoolSize
        playerPoolSize = newSize
        
        val targetPoolSize = newSize * 2
        
        while (playerPool.size < targetPoolSize) {
            val context = recyclerView?.context ?: return
            val player = ExoPlayer.Builder(context).build().apply {
                volume = 0f
                playWhenReady = true
            }
            playerPool.add(player)
            android.util.Log.d("PhoneChannelAdapter", "Player added: ${player.hashCode()}, pool size: ${playerPool.size}")
        }
        
        if (playerPool.size > targetPoolSize) {
            while (playerPool.size > targetPoolSize) {
                val player = playerPool.removeLastOrNull()
                player?.release()
                android.util.Log.d("PhoneChannelAdapter", "Player removed and released, pool size: ${playerPool.size}")
            }
        }
        
        activeHolders.clear()
        holdersWaitingForSurface.clear()
        refreshPlayers()
    }

    fun refreshPlayers() {
        if (isManaging) return
        isManaging = true
        
        android.util.Log.d("PhoneChannelAdapter", "refreshPlayers() called, pool size: ${playerPool.size}, active holders: ${activeHolders.size}")
        
        try {
            val validHolders = activeHolders.filter { 
                it.bindingAdapterPosition >= 0 && it.movie?.videoUrl != null
            }
            
            val layoutManager = recyclerView?.layoutManager as? GridLayoutManager
            val firstVisiblePosition = layoutManager?.findFirstVisibleItemPosition() ?: 0
            
            val rowStart = firstVisiblePosition
            val rowEnd = firstVisiblePosition + playerPoolSize - 1
            
            android.util.Log.d("PhoneChannelAdapter", "First visible position: $firstVisiblePosition, Target range: $rowStart - $rowEnd")
            
            val visibleHolders = validHolders.filter { 
                it.bindingAdapterPosition in rowStart..rowEnd
            }
            
            val candidates = visibleHolders + validHolders.filter { holder ->
                holder.player == null && holder.bindingAdapterPosition !in rowStart..rowEnd
            }.sortedBy { it.bindingAdapterPosition }.take(playerPool.size - visibleHolders.size)
            
            val neededPlayers = candidates.filter { holder ->
                holder.player == null
            }
            
            android.util.Log.d("PhoneChannelAdapter", "Valid holders: ${validHolders.size}, Visible holders: ${visibleHolders.size}, Needed players: ${neededPlayers.size}")

            neededPlayers.take(playerPool.size - validHolders.count { it.player != null }).forEach { holder ->
                val availablePlayer = playerPool.find { player ->
                   !validHolders.any { it.player === player }
                }
                
                android.util.Log.d("PhoneChannelAdapter", "Available player for ${holder.movie?.title}: ${availablePlayer != null}")
                
                availablePlayer?.let { player ->
                    holder.attachPlayer(player)
                }
            }
            
            visibleHolders.forEach { holder ->
                holder.requestFocusOrContinue(true)
            }
            
            val hiddenHolders = validHolders.filter { holder ->
                holder.player != null && holder.bindingAdapterPosition !in rowStart..rowEnd
            }
            
            hiddenHolders.forEach { holder ->
                holder.requestFocusOrContinue(false)
            }
        } finally {
            isManaging = false
        }
    }

    private fun getAvailablePlayer(): ExoPlayer? {
        return playerPool.find { player ->
            !activeHolders.any { it.player === player }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        onBindViewHolder(holder, position)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ViewHolder) {
        android.util.Log.d("PhoneChannelAdapter", "onViewRecycled for ${holder.movie?.title}")
        holder.releasePlayer()
        activeHolders.remove(holder)
        super.onViewRecycled(holder)
    }
    
    fun schedulePlayerUpdate() {
        recyclerView?.postDelayed({
            refreshPlayers()
        }, 200)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.channel_title)
        private val image: ImageView = itemView.findViewById(R.id.channel_image)
        private val videoContainer: ViewGroup = itemView.findViewById(R.id.video_container)
        
        var player: ExoPlayer? = null
        var movie: Movie? = null
        var isPlaying = false
        
        private var textureView: TextureView? = null
        private var surfaceReady = false
        
        private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                android.util.Log.d("PhoneChannelAdapter", "SurfaceTextureAvailable for ${movie?.title}")
                surfaceReady = true
                val waitingPlayer = holdersWaitingForSurface.remove(this@ViewHolder)
                if (waitingPlayer != null) {
                    android.util.Log.d("PhoneChannelAdapter", "Found waiting player, attaching")
                    attachSurfaceAndPlay(waitingPlayer, surface)
                } else if (player != null) {
                    android.util.Log.d("PhoneChannelAdapter", "Player already exists, attaching")
                    attachSurfaceAndPlay(player!!, surface)
                }
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                android.util.Log.d("PhoneChannelAdapter", "SurfaceTextureDestroyed for ${movie?.title}")
                surfaceReady = false
                player?.setVideoSurface(null)
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        fun bind(movie: Movie) {
            this.movie = movie
            title.text = movie.title
            
            if (movie.cardImageUrl != null) {
                Glide.with(itemView.context)
                    .load(movie.cardImageUrl)
                    .into(image)
            } else {
                image.setBackgroundColor(0xFF333333.toInt())
            }
            
            itemView.setOnClickListener { onClick(movie) }
            
            android.util.Log.d("PhoneChannelAdapter", "bind called for ${movie.title}, pos: $bindingAdapterPosition, hasVideo: ${movie.videoUrl != null}, isPlaying: $isPlaying")
            
            if (movie.videoUrl != null && bindingAdapterPosition >= 0 && player == null) {
                activeHolders.add(this)
                schedulePlayerUpdate()
            }
        }

        private var shouldContinue = true
        
        fun attachPlayer(playerToAttach: ExoPlayer?) {
            if (playerToAttach == null) return
            
            android.util.Log.d("PhoneChannelAdapter", "attachPlayer called for ${movie?.title}, player: ${playerToAttach.hashCode()}, surfaceReady: $surfaceReady")
            
            this.player = playerToAttach
            isPlaying = true
            activeHolders.add(this)
            
            if (surfaceReady) {
                textureView?.surfaceTexture?.let { surface ->
                    attachSurfaceAndPlay(playerToAttach, surface)
                }
            } else {
                ensureTextureView()
                holdersWaitingForSurface[this] = playerToAttach
                android.util.Log.d("PhoneChannelAdapter", "Holder added to waiting list, waiting for surface")
            }
        }

        fun requestFocusOrContinue(shouldContinue: Boolean) {
            this.shouldContinue = shouldContinue
            val currentPos = bindingAdapterPosition
            val layoutManager = recyclerView?.layoutManager as? GridLayoutManager ?: return
            val firstVisible = layoutManager.findFirstVisibleItemPosition()
            val inFirstRow = currentPos in firstVisible..(firstVisible + playerPoolSize - 1)
            
            if (shouldContinue && inFirstRow) {
                player?.playWhenReady = true
                player?.play()
                android.util.Log.d("PhoneChannelAdapter", "Continuing play for ${movie?.title}, pos: $currentPos")
            } else if (!shouldContinue && !inFirstRow) {
                player?.playWhenReady = false
                android.util.Log.d("PhoneChannelAdapter", "Pausing play for ${movie?.title}, pos: $currentPos")
            }
        }

        private fun attachSurfaceAndPlay(exoPlayer: ExoPlayer, surface: SurfaceTexture) {
            android.util.Log.d("PhoneChannelAdapter", "attachSurfaceAndPlay called for ${movie?.title}")
            exoPlayer.setVideoSurface(Surface(surface))
            val mediaItem = MediaItem.fromUri(movie?.videoUrl ?: return)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.play()
            android.util.Log.d("PhoneChannelAdapter", "Player started for ${movie?.title}")
            
            playerPauseHandler.postDelayed({
                val currentPos = bindingAdapterPosition
                val layoutManager = recyclerView?.layoutManager as? GridLayoutManager
                val firstVisible = layoutManager?.findFirstVisibleItemPosition() ?: 0
                val inFirstRow = currentPos in firstVisible..(firstVisible + playerPoolSize - 1)
                
                if (!inFirstRow) {
                    exoPlayer.playWhenReady = false
                    android.util.Log.d("PhoneChannelAdapter", "Auto-paused after 1s for ${movie?.title}, pos: $currentPos")
                }
            }, 1000)
        }

        private fun ensureTextureView() {
            android.util.Log.d("PhoneChannelAdapter", "ensureTextureView called for ${movie?.title}, existing: ${textureView != null}")
            if (textureView != null) return
            
            textureView = TextureView(itemView.context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                surfaceTextureListener = this@ViewHolder.surfaceTextureListener
            }
            
            val transparentViewIndex = videoContainer.childCount - 2
            videoContainer.addView(textureView, transparentViewIndex)
            image.visibility = View.GONE
            android.util.Log.d("PhoneChannelAdapter", "TextureView added, children count: ${videoContainer.childCount}")
        }

        fun releasePlayer() {
            android.util.Log.d("PhoneChannelAdapter", "releasePlayer called for ${movie?.title}")
            player?.stop()
            player?.clearVideoSurface()
            isPlaying = false
            player = null
            shouldContinue = true
            
            holdersWaitingForSurface.remove(this)
            textureView?.surfaceTextureListener = null
            val tv = textureView
            if (tv != null) {
                videoContainer.removeView(tv)
            }
            textureView = null
            surfaceReady = false
            
            image.visibility = View.VISIBLE
            android.util.Log.d("PhoneChannelAdapter", "releasePlayer completed")
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Movie>() {
        override fun areItemsTheSame(oldItem: Movie, newItem: Movie) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Movie, newItem: Movie) = oldItem == newItem
    }
}

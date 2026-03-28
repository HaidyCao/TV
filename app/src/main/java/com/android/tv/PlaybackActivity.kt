package com.android.tv

import android.graphics.drawable.Drawable
import android.media.MediaCodecList
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.ui.PlayerView
import java.lang.reflect.Field

@UnstableApi
class PlaybackActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var decoderInfoUpdated = false
    private var selectedDecoderName: String? = null
    private var currentMediaItem: MediaItem? = null
    private var currentTitle: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_playback)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val controller = window.insetsController
            if (controller != null) {
                controller.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }

        val movie = intent.extras?.getSerializable(DetailsActivity.MOVIE) as? Movie
        val videoUrl = movie?.videoUrl

        if (videoUrl.isNullOrEmpty()) {
            finish()
            return
        }

        val mediaItem = MediaItem.fromUri(videoUrl.toUri())
        start(mediaItem, movie.title ?: "")
    }

    private fun start(mediaItem: MediaItem, title: String) {
        currentMediaItem = mediaItem
        currentTitle = title
        val playerView = findViewById<PlayerView>(R.id.player_view)

        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val decoders = MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                if (selectedDecoderName != null) {
                    val matched = decoders.filter { it.name == selectedDecoderName }
                    if (matched.isNotEmpty()) return@setMediaCodecSelector matched
                }
                decoders.filter { !it.name.startsWith("c2.qti.avc.decoder") }.toList() // TODO: 小米 avc 优先使用 c2.android.avc.decoder
            }
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        player?.release()
        player = ExoPlayer.Builder(this, renderersFactory)
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY && !decoderInfoUpdated) {
                            updateDecoderInfo(playerView)
                            decoderInfoUpdated = true
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("PlaybackActivity", "Player error: ${error.message}")
                    }
                })
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true
            }
        playerView.player = player
    }

    private fun updateDecoderInfo(playerView: PlayerView) {
        try {
            val controllerField = PlayerView::class.java.getDeclaredField("controller").apply { isAccessible = true }
            val controller = controllerField.get(playerView) ?: return

            val settingsAdapterField = controller.javaClass.getDeclaredField("settingsAdapter").apply { isAccessible = true }
            val originalAdapter = settingsAdapterField.get(controller) as androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>

            var currentMimeType: String? = null
            player?.currentTracks?.groups?.forEach { group ->
                if (group.type == androidx.media3.common.C.TRACK_TYPE_VIDEO && group.isSelected) {
                    currentMimeType = group.getTrackFormat(0).sampleMimeType
                }
            }
            val mime = currentMimeType ?: "video/avc"
            val availableDecoders = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .filter { !it.isEncoder && it.supportedTypes.contains(mime) }
                .map { it.name }
            val currentDecoder = selectedDecoderName ?: availableDecoders.firstOrNull() ?: "Default"

            val mainTextsField = originalAdapter.javaClass.getDeclaredField("mainTexts").apply { isAccessible = true }
            val subTextsField = originalAdapter.javaClass.getDeclaredField("subTexts").apply { isAccessible = true }
            val iconIdsField = originalAdapter.javaClass.getDeclaredField("iconIds").apply { isAccessible = true }

            val mainTexts = (mainTextsField.get(originalAdapter) as Array<String>).toMutableList()
            val subTexts = (subTextsField.get(originalAdapter) as Array<String>).toMutableList()
            val iconIds = (iconIdsField.get(originalAdapter) as Array<Drawable>).toMutableList()

            var decoderIndex = -1

            if (!mainTexts.contains("解码器")) {
                decoderIndex = mainTexts.size
                mainTexts.add("解码器")
                subTexts.add(currentDecoder)
                iconIds.add(iconIds[0])
                mainTextsField.set(originalAdapter, mainTexts.toTypedArray())
                subTextsField.set(originalAdapter, subTexts.toTypedArray())
                iconIdsField.set(originalAdapter, iconIds.toTypedArray())
            } else {
                val index = mainTexts.indexOf("解码器")
                decoderIndex = index
                subTexts[index] = currentDecoder
                subTextsField.set(originalAdapter, subTexts.toTypedArray())
            }

            val settingsViewField = controller.javaClass.getDeclaredField("settingsView").apply { isAccessible = true }
            val settingsView = settingsViewField.get(controller) as androidx.recyclerview.widget.RecyclerView

            // 使用包装适配器拦截点击事件
            val wrapperAdapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
                override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) = originalAdapter.onCreateViewHolder(parent, viewType)
                
                override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                    originalAdapter.onBindViewHolder(holder, position)
                    Log.d("PlaybackActivity", "onBindViewHolder called for position $position")
                    
                    val currentTexts = mainTextsField.get(originalAdapter) as Array<String>
                    if (position >= 0 && position < currentTexts.size && currentTexts[position] == "解码器") {
                        Log.d("PlaybackActivity", "Setting click listener for item at position $position")
                        holder.itemView.setOnClickListener {
                            // 弹出对话框
                            showDecoderSelectionDialog(availableDecoders)
                            // 反射调用 hideSettingsMenu
                            try {
                                val hideMethod = controller.javaClass.getDeclaredMethod("hideSettingsMenu")
                                hideMethod.isAccessible = true
                                hideMethod.invoke(controller)
                            } catch (e: Exception) {}
                        }
                    }
                }

                override fun getItemCount() = originalAdapter.itemCount
                override fun getItemViewType(position: Int) = originalAdapter.getItemViewType(position)
                override fun getItemId(position: Int) = originalAdapter.getItemId(position)
            }

//            settingsAdapterField.set(controller, wrapperAdapter)
//            settingsView.adapter = wrapperAdapter

            settingsView.addOnChildAttachStateChangeListener(object : androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener {

                override fun onChildViewAttachedToWindow(itemView: View) {
                    val position = settingsView.getChildAdapterPosition(itemView)
                    Log.d("PlaybackActivity", "onChildViewAttachedToWindow called for position $position")
                    if (position == decoderIndex) {
                        itemView.setOnClickListener {
                            showDecoderSelectionDialog(availableDecoders)

                            // 反射调用 hideSettingsMenu
                            try {
                                val settingsWindowField = controller.javaClass.getDeclaredField("settingsWindow")
                                settingsWindowField.isAccessible = true
                                val settingsWindow = settingsWindowField.get(controller)

                                val dismissMethod = settingsWindow.javaClass.getDeclaredMethod("dismiss")
                                dismissMethod.isAccessible = true

                                dismissMethod.invoke(settingsWindow)
                            } catch (e: Exception) {}
                        }
                    }
                }

                override fun onChildViewDetachedFromWindow(p0: View) {
                }
            })
            Log.d("PlaybackActivity", "Adapter hijacked successfully")

        } catch (e: Exception) {
            Log.e("PlaybackActivity", "Failed to hijack adapter: ${e.message}")
        }
    }

    private fun showDecoderSelectionDialog(decoders: List<String>) {
        // TODO: 使用 PlayerControlView 中的 UI 显示
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择解码器")
            .setItems(decoders.toTypedArray()) { _, which ->
                selectedDecoderName = decoders[which]
                decoderInfoUpdated = false
                currentMediaItem?.let { start(it, currentTitle ?: "") }
            }
            .show()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}

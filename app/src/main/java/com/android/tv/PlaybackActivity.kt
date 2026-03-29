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
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.ui.PlayerView

@UnstableApi
class PlaybackActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var selectedDecoderName: String? = null
    private var currentMediaItem: MediaItem? = null
    private var currentTitle: String? = null

    private var mAvailableDecoders: List<String> = emptyList()


    private inner class DecoderAdapter(private val decoders: List<String>, private val controller: Any) :
        RecyclerView.Adapter<DecoderAdapter.SubSettingViewHolder>() {

        private var selectedIndex: Int = decoders.indexOf(selectedDecoderName).let { if (it == -1) 0 else it }

        inner class SubSettingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val textView: TextView = itemView.findViewById(androidx.media3.ui.R.id.exo_text)
            val checkView: View = itemView.findViewById(androidx.media3.ui.R.id.exo_check)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SubSettingViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(
                androidx.media3.ui.R.layout.exo_styled_sub_settings_list_item,
                parent,
                false
            )
            return SubSettingViewHolder(v)
        }

        override fun onBindViewHolder(holder: SubSettingViewHolder, position: Int) {
            holder.textView.text = decoders[position]
            if (position == selectedIndex) {
                holder.itemView.isSelected = true
                holder.checkView.visibility = View.VISIBLE
            } else {
                holder.itemView.isSelected = false
                holder.checkView.visibility = View.INVISIBLE
            }
            holder.itemView.setOnClickListener {
                if (position != selectedIndex) {
                    val newDecoder = decoders[position]
                    selectedDecoderName = newDecoder
                    currentMediaItem?.let { start(it, currentTitle ?: "") }
                }
                dismissSettingsWindow()
            }
        }

        private fun dismissSettingsWindow() {
            try {
                val settingsWindowField = controller.javaClass.getDeclaredField("settingsWindow")
                settingsWindowField.isAccessible = true
                val settingsWindow = settingsWindowField.get(controller)
                val dismissMethod = settingsWindow.javaClass.getDeclaredMethod("dismiss")
                dismissMethod.invoke(settingsWindow)
            } catch (e: Exception) {
                Log.e("PlaybackActivity", "Failed to dismiss settings window: ${e.message}")
            }
        }

        override fun getItemCount(): Int = decoders.size
    }

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
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
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

                var filteredDecoders = decoders

                // 小米 avc 优先使用 c2.android.avc.decoder
                if (android.os.Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) &&
                    mimeType.equals(androidx.media3.common.MimeTypes.VIDEO_H264, ignoreCase = true)) {
                    val priorityDecoder = "c2.android.avc.decoder"
                    val preferred = filteredDecoders.filter { it.name == priorityDecoder }
                    val others = filteredDecoders.filter { it.name != priorityDecoder }
                    filteredDecoders = preferred + others
                }

                if (!filteredDecoders.isEmpty()) {
                    selectedDecoderName = filteredDecoders.first().name
                }
                mAvailableDecoders = filteredDecoders.map { it.name }.toList()
                updateDecoderInfo(playerView)
                filteredDecoders.toList()
            }
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        player?.release()
        player = ExoPlayer.Builder(this, renderersFactory)
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        Log.d("PlaybackActivity", "onPlaybackStateChanged() called with: state = $state")
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
            val originalAdapter = settingsAdapterField.get(controller) as RecyclerView.Adapter<RecyclerView.ViewHolder>

            val availableDecoders = mAvailableDecoders
            val currentDecoder = selectedDecoderName ?: availableDecoders.firstOrNull() ?: "Default"

            val mainTextsField = originalAdapter.javaClass.getDeclaredField("mainTexts").apply { isAccessible = true }
            val subTextsField = originalAdapter.javaClass.getDeclaredField("subTexts").apply { isAccessible = true }
            val iconIdsField = originalAdapter.javaClass.getDeclaredField("iconIds").apply { isAccessible = true }

            val mainTexts = (mainTextsField.get(originalAdapter) as Array<String>).toMutableList()
            val subTexts = (subTextsField.get(originalAdapter) as Array<String>).toMutableList()
            val iconIds = (iconIdsField.get(originalAdapter) as Array<Drawable>).toMutableList()

            var decoderIndex: Int
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
            val settingsView = settingsViewField.get(controller) as RecyclerView

            settingsView.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {

                override fun onChildViewAttachedToWindow(itemView: View) {
                    val position = settingsView.getChildAdapterPosition(itemView)
                    Log.d("PlaybackActivity", "onChildViewAttachedToWindow called for position $position")
                    if (settingsView.adapter == originalAdapter &&  position == decoderIndex) {
                        itemView.setOnClickListener {
                            showDecoderSelectionDialog(controller, availableDecoders)
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

    private fun showDecoderSelectionDialog(controller: Any, decoders: List<String>) {
        Log.d(
            "PlaybackActivity",
            "showDecoderSelectionDialog() called with: controller = $controller, decoders = $decoders"
        )
        try {
            // 反射获取 settingsButton 作为 anchorView
            val settingsButtonField = controller.javaClass.getDeclaredField("settingsButton")
            settingsButtonField.isAccessible = true
            val anchorView = settingsButtonField.get(controller) as View

            val displayMethod = controller.javaClass.getDeclaredMethod(
                "displaySettingsWindow",
                RecyclerView.Adapter::class.java,
                View::class.java
            )
            displayMethod.isAccessible = true
            displayMethod.invoke(controller, DecoderAdapter(decoders, controller), anchorView)
        } catch (e: Exception) {
            Log.e("PlaybackActivity", "Failed to display settings window via reflection: ${e.message}")
        }
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

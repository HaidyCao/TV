package com.android.tv

import android.graphics.PixelFormat
import android.os.Bundle
import android.util.Log
import android.view.SurfaceView
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView


@UnstableApi
class PlaybackActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_playback)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 隐藏状态栏和导航栏（沉浸式全屏）
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
            Log.e("PlaybackActivity", "Video URL is null or empty!")
            finish()
            return
        }

        // 启动播放，整合 MediaItem 与 标题/内容 处理
        val mediaItem = MediaItem.fromUri(videoUrl.toUri())
        start(mediaItem, movie.title ?: "")
    }

    /**
     * 根据 MediaItem 和 标题 启动播放，整合特效与初始化逻辑
     */
    private fun start(mediaItem: MediaItem, title: String) {
        Log.d("PlaybackActivity", "Starting playback for: $title")
        val playerView = findViewById<PlayerView>(R.id.player_view)

//        playerView.videoSurfaceView?.scaleX = 1.01f
//        playerView.videoSurfaceView?.scaleY = 1.01f

        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        player = ExoPlayer.Builder(this, renderersFactory)
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        super.onVideoSizeChanged(videoSize)
                        Log.d("PlaybackActivity", "Video size changed: ${videoSize.width}, ${videoSize.height}")

//                        if (videoSize.width > 0 && videoSize.height > 0) {
//                            // 获取屏幕密度
//                            val density = 1
//                            surfaceView?.holder?.setFixedSize(videoSize.width, videoSize.height)
//
////                            // 将 PlayerView 的宽高设为视频原始像素对应的 dp
////                            // 这样在渲染时，1个视频像素正好对应1个物理像素
//                            val lp = playerView.layoutParams
//                            lp.width = (videoSize.width / density).toInt()
//                            lp.height = (videoSize.height / density).toInt()
//                            playerView.setLayoutParams(lp)
////
////                            // 关键：禁止 AspectRatioFrameLayout 的二次拉伸
//                            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
//                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        val stateName = when(state) {
                            1 -> "STATE_IDLE"
                            2 -> "STATE_BUFFERING"
                            3 -> "STATE_READY"
                            4 -> "STATE_ENDED"
                            else -> "UNKNOWN"
                        }
                        Log.d("PlaybackActivity", "Playback state: $state ($stateName)")
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("PlaybackActivity", "Player error: ${error.message}")
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        Log.d("PlaybackActivity", "Is playing: $isPlaying")
                    }

                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                        Log.d("PlaybackActivity", "Tracks changed:")
                        tracks.groups.forEach { group ->
                            val trackType = group.type
                            val mimeType = group.getTrackFormat(0).sampleMimeType
                            Log.d("PlaybackActivity", "  Track type: $trackType, mime: $mimeType, selected: ${group.isSelected}")
                        }
                    }
                })

                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true
            }

        playerView.player = player
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

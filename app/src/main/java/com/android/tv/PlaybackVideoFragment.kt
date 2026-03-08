package com.android.tv

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.ui.PlayerView

@UnstableApi
class PlaybackVideoFragment : Fragment() {

    private var player: ExoPlayer? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.activity_playback, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val playerView = view.findViewById<PlayerView>(R.id.player_view)
        playerView.useController = true

        val movie = activity?.intent?.extras?.getSerializable(DetailsActivity.MOVIE) as? Movie
        val videoUrl = movie?.videoUrl

        Log.d("Playback", "videoUrl: $videoUrl")

        if (videoUrl.isNullOrEmpty()) {
            Toast.makeText(context, "视频地址无效", Toast.LENGTH_LONG).show()
            return
        }

        // 启用软解码器回退，并优先选使用扩展（Jellyfin FFmpeg）以支持更多的音频编码
        val renderersFactory = DefaultRenderersFactory(requireContext())
            .setEnableDecoderFallback(true) 
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        player = ExoPlayer.Builder(requireContext(), renderersFactory).build().apply {
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.e("Playback", "Player error: ${error.message}")
                    Toast.makeText(context, "播放错误: ${error.message}", Toast.LENGTH_LONG).show()
                }

                override fun onPlaybackStateChanged(state: Int) {
                    Log.d("Playback", "State changed: $state")
                }
            })

            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_OFF

            Log.d("Playback", "Setting media item")
            setMediaItem(MediaItem.fromUri(Uri.parse(videoUrl)))
            prepare()
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

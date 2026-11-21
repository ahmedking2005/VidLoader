package com.ahmed.vidloader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.ahmed.vidloader.databinding.ActivityPlayerBinding

/**
 * Built-in player for downloaded library files (v1.8). Plays the item's
 * content URI with ExoPlayer and the standard PlayerView controller; audio
 * files get the same controller over a dark background with icon + title.
 *
 * Lifecycle follows the media3 guidance for API 24+: the player is built in
 * onStart and released in onStop, keeping the position/play-state across
 * stop/start cycles (e.g. multi-window) in plain fields.
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null

    private var mediaUri: Uri? = null
    private var playWhenReady = true
    private var startPosition = 0L

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Keep the screen awake only while something is actually playing.
            binding.playerView.keepScreenOn = isPlaying
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) binding.playerView.keepScreenOn = false
        }

        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(this@PlayerActivity, R.string.player_error, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaUri = intent.getStringExtra(EXTRA_URI)?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        if (mediaUri == null) {
            Toast.makeText(this, R.string.player_error, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (title.isNotBlank()) setTitle(title)
        if (intent.getBooleanExtra(EXTRA_AUDIO, false)) {
            binding.audioBox.visibility = View.VISIBLE
            binding.playerTitle.text = title
        }
    }

    override fun onStart() {
        super.onStart()
        if (mediaUri != null) initializePlayer()
    }

    override fun onStop() {
        releasePlayer()
        super.onStop()
    }

    private fun initializePlayer() {
        val uri = mediaUri ?: return
        player = ExoPlayer.Builder(this).build().also { exo ->
            binding.playerView.player = exo
            exo.addListener(playerListener)
            exo.setMediaItem(MediaItem.fromUri(uri), startPosition)
            exo.playWhenReady = playWhenReady
            exo.prepare()
        }
    }

    private fun releasePlayer() {
        player?.let { exo ->
            playWhenReady = exo.playWhenReady
            startPosition = exo.currentPosition
            exo.removeListener(playerListener)
            exo.release()
        }
        player = null
        binding.playerView.player = null
        binding.playerView.keepScreenOn = false
    }

    companion object {
        private const val EXTRA_URI = "player_uri"
        private const val EXTRA_TITLE = "player_title"
        private const val EXTRA_AUDIO = "player_audio"

        fun start(context: Context, uri: Uri, title: String, isAudio: Boolean) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_URI, uri.toString())
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_AUDIO, isAudio)
            }
            context.startActivity(intent)
        }
    }
}

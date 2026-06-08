package moe.tekuza.m9player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object BookReaderPlaybackSession {
    @Volatile
    private var player: ExoPlayer? = null

    @Volatile
    private var currentAudioUriText: String? = null

    @Synchronized
    fun acquirePlayer(context: Context): ExoPlayer {
        val existing = player
        if (existing != null) return existing
        return ExoPlayer.Builder(context.applicationContext)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .build()
            .also { sharedPlayer ->
                sharedPlayer.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .build(),
                    true
                )
                sharedPlayer.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        BookReaderFloatingBridge.notifyPlaybackState(isPlaying)
                    }
                })
                player = sharedPlayer
            }
    }

    fun currentAudioUri(): String? = currentAudioUriText

    fun isPlaying(): Boolean = player?.isPlaying == true

    fun currentPositionMs(): Long = player?.currentPosition?.coerceAtLeast(0L) ?: 0L

    fun currentPlaybackSpeed(): Float = player?.playbackParameters?.speed ?: 1f

    fun setPlaying(play: Boolean) {
        val sharedPlayer = player ?: return
        if (play) {
            sharedPlayer.play()
        } else {
            sharedPlayer.pause()
        }
    }

    fun togglePlayPause() {
        val sharedPlayer = player ?: return
        if (sharedPlayer.playWhenReady || sharedPlayer.isPlaying) {
            sharedPlayer.pause()
        } else {
            sharedPlayer.play()
        }
    }

    fun seekToPosition(positionMs: Long) {
        player?.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun seekPrevious() {
        player?.seekBack()
    }

    fun seekNext() {
        player?.seekForward()
    }

    fun setPlaybackSpeed(speed: Float) {
        player?.playbackParameters = PlaybackParameters(speed.coerceIn(0.5f, 3.0f))
    }

    @Synchronized
    fun prepareAudioIfNeeded(
        context: Context,
        audioUri: Uri,
        restorePositionMs: Long = 0L,
        forceSeekOnSameAudio: Boolean = false
    ): ExoPlayer {
        val sharedPlayer = acquirePlayer(context)
        val targetUriText = audioUri.toString()
        val currentUriText = currentAudioUriText
        if (currentUriText != targetUriText) {
            sharedPlayer.setMediaItem(MediaItem.fromUri(audioUri))
            sharedPlayer.prepare()
            sharedPlayer.seekTo(restorePositionMs.coerceAtLeast(0L))
            currentAudioUriText = targetUriText
            return sharedPlayer
        }
        if (
            forceSeekOnSameAudio &&
            restorePositionMs > 0L &&
            abs(sharedPlayer.currentPosition - restorePositionMs) > 800L
        ) {
            sharedPlayer.seekTo(restorePositionMs.coerceAtLeast(0L))
        }
        return sharedPlayer
    }
}

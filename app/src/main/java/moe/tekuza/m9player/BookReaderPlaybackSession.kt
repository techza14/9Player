package moe.tekuza.m9player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs

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
            .also { player = it }
    }

    fun currentAudioUri(): String? = currentAudioUriText

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

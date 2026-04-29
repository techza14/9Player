package moe.tekuza.m9player

object BookReaderFloatingBridge {
    data class CueSnapshot(
        val text: String,
        val startMs: Long,
        val endMs: Long,
        val bookTitle: String?,
        val audioUri: String?,
        val fullSentenceText: String?,
        val fullSentenceStartMs: Long?,
        val fullSentenceEndMs: Long?
    )

    interface Controller {
        fun isPlaying(): Boolean
        fun isFavorite(): Boolean
        fun isCueLoopEnabled(): Boolean
        fun togglePlayPause()
        fun setPlaying(play: Boolean)
        fun seekPrevious()
        fun seekNext()
        fun replayCurrentCue()
        fun toggleCueLoop()
        fun toggleFavorite()
        fun showReader()
        fun lookupCurrentSubtitleAt(offset: Int)
    }

    interface PlaybackStateListener {
        fun onPlaybackStateChanged(isPlaying: Boolean)
    }

    interface FavoriteStateListener {
        fun onFavoriteStateChanged(isFavorite: Boolean)
    }

    interface SubtitleStateListener {
        fun onSubtitleChanged(text: String?)
    }

    interface PlaybackPositionListener {
        fun onPlaybackPositionChanged(positionMs: Long)
    }

    interface PlaybackSpeedListener {
        fun onPlaybackSpeedChanged(speed: Float)
    }

    interface CueLoopStateListener {
        fun onCueLoopStateChanged(enabled: Boolean)
    }

    @Volatile
    private var controller: Controller? = null
    private val listeners = linkedSetOf<PlaybackStateListener>()
    private val favoriteListeners = linkedSetOf<FavoriteStateListener>()
    private val subtitleListeners = linkedSetOf<SubtitleStateListener>()
    private val playbackPositionListeners = linkedSetOf<PlaybackPositionListener>()
    private val playbackSpeedListeners = linkedSetOf<PlaybackSpeedListener>()
    private val cueLoopStateListeners = linkedSetOf<CueLoopStateListener>()
    @Volatile
    private var playingSnapshot: Boolean = false
    @Volatile
    private var favoriteSnapshot: Boolean = false
    @Volatile
    private var currentAudioUriSnapshot: String? = null
    @Volatile
    private var subtitleSnapshot: String? = null
    @Volatile
    private var cueSnapshot: CueSnapshot? = null
    @Volatile
    private var subtitleTrackAvailableSnapshot: Boolean = false
    @Volatile
    private var playbackPositionSnapshot: Long = 0L
    @Volatile
    private var playbackSpeedSnapshot: Float = 1f
    @Volatile
    private var cueLoopEnabledSnapshot: Boolean = false

    fun attach(controller: Controller) {
        synchronized(this) {
            this.controller = controller
            playingSnapshot = controller.isPlaying()
            favoriteSnapshot = controller.isFavorite()
            cueLoopEnabledSnapshot = controller.isCueLoopEnabled()
        }
        notifyPlaybackState(playingSnapshot)
        notifyFavoriteState(favoriteSnapshot)
        notifySubtitle(subtitleSnapshot)
        notifyPlaybackPosition(playbackPositionSnapshot)
        notifyPlaybackSpeed(playbackSpeedSnapshot)
    }

    fun detach(controller: Controller) {
        synchronized(this) {
            if (this.controller === controller) {
                this.controller = null
                playingSnapshot = false
                favoriteSnapshot = false
                subtitleSnapshot = null
                cueSnapshot = null
                subtitleTrackAvailableSnapshot = false
                cueLoopEnabledSnapshot = false
            }
        }
        notifyPlaybackState(playingSnapshot)
        notifyFavoriteState(favoriteSnapshot)
        notifySubtitle(subtitleSnapshot)
        notifyPlaybackPosition(0L)
        notifyPlaybackSpeed(1f)
    }

    fun addPlaybackStateListener(listener: PlaybackStateListener) {
        synchronized(this) {
            listeners += listener
        }
        listener.onPlaybackStateChanged(playingSnapshot)
    }

    fun removePlaybackStateListener(listener: PlaybackStateListener) {
        synchronized(this) {
            listeners -= listener
        }
    }

    fun addFavoriteStateListener(listener: FavoriteStateListener) {
        synchronized(this) {
            favoriteListeners += listener
        }
        listener.onFavoriteStateChanged(favoriteSnapshot)
    }

    fun removeFavoriteStateListener(listener: FavoriteStateListener) {
        synchronized(this) {
            favoriteListeners -= listener
        }
    }

    fun addSubtitleStateListener(listener: SubtitleStateListener) {
        synchronized(this) {
            subtitleListeners += listener
        }
        listener.onSubtitleChanged(subtitleSnapshot)
    }

    fun removeSubtitleStateListener(listener: SubtitleStateListener) {
        synchronized(this) {
            subtitleListeners -= listener
        }
    }

    fun addPlaybackPositionListener(listener: PlaybackPositionListener) {
        synchronized(this) {
            playbackPositionListeners += listener
        }
        listener.onPlaybackPositionChanged(playbackPositionSnapshot)
    }

    fun removePlaybackPositionListener(listener: PlaybackPositionListener) {
        synchronized(this) {
            playbackPositionListeners -= listener
        }
    }

    fun addPlaybackSpeedListener(listener: PlaybackSpeedListener) {
        synchronized(this) {
            playbackSpeedListeners += listener
        }
        listener.onPlaybackSpeedChanged(playbackSpeedSnapshot)
    }

    fun removePlaybackSpeedListener(listener: PlaybackSpeedListener) {
        synchronized(this) {
            playbackSpeedListeners -= listener
        }
    }

    fun addCueLoopStateListener(listener: CueLoopStateListener) {
        synchronized(this) {
            cueLoopStateListeners += listener
        }
        listener.onCueLoopStateChanged(cueLoopEnabledSnapshot)
    }

    fun removeCueLoopStateListener(listener: CueLoopStateListener) {
        synchronized(this) {
            cueLoopStateListeners -= listener
        }
    }

    fun notifyPlaybackState(isPlaying: Boolean) {
        val snapshot: List<PlaybackStateListener>
        synchronized(this) {
            playingSnapshot = isPlaying
            snapshot = listeners.toList()
        }
        snapshot.forEach { it.onPlaybackStateChanged(isPlaying) }
    }

    fun isPlaying(): Boolean = playingSnapshot
    fun isFavorite(): Boolean = favoriteSnapshot
    fun currentAudioUri(): String? = currentAudioUriSnapshot
    fun currentSubtitle(): String? = subtitleSnapshot
    fun currentCue(): CueSnapshot? = cueSnapshot
    fun hasSubtitleTrack(): Boolean = subtitleTrackAvailableSnapshot
    fun currentPlaybackPositionMs(): Long = playbackPositionSnapshot
    fun currentPlaybackSpeed(): Float = playbackSpeedSnapshot
    fun isCueLoopEnabled(): Boolean = cueLoopEnabledSnapshot

    fun setCurrentAudioUri(audioUri: String?) {
        synchronized(this) {
            currentAudioUriSnapshot = audioUri?.takeIf { it.isNotBlank() }
        }
    }

    fun setCurrentCue(
        text: String?,
        startMs: Long?,
        endMs: Long?,
        bookTitle: String?,
        audioUri: String?,
        fullSentenceText: String?,
        fullSentenceStartMs: Long?,
        fullSentenceEndMs: Long?
    ) {
        synchronized(this) {
            cueSnapshot = if (text.isNullOrBlank() || startMs == null || endMs == null) {
                null
            } else {
                CueSnapshot(
                    text = text,
                    startMs = startMs,
                    endMs = endMs,
                    bookTitle = bookTitle?.takeIf { it.isNotBlank() },
                    audioUri = audioUri?.takeIf { it.isNotBlank() },
                    fullSentenceText = fullSentenceText?.trim()?.takeIf { it.isNotBlank() },
                    fullSentenceStartMs = fullSentenceStartMs,
                    fullSentenceEndMs = fullSentenceEndMs
                )
            }
        }
    }

    fun setSubtitleTrackAvailable(available: Boolean) {
        subtitleTrackAvailableSnapshot = available
    }

    fun notifySubtitle(text: String?) {
        val normalized = text?.trim()?.takeIf { it.isNotEmpty() }
        val snapshot: List<SubtitleStateListener>
        synchronized(this) {
            subtitleSnapshot = normalized
            snapshot = subtitleListeners.toList()
        }
        snapshot.forEach { it.onSubtitleChanged(normalized) }
    }

    fun notifyPlaybackPosition(positionMs: Long) {
        val normalized = positionMs.coerceAtLeast(0L)
        val snapshot: List<PlaybackPositionListener>
        synchronized(this) {
            playbackPositionSnapshot = normalized
            snapshot = playbackPositionListeners.toList()
        }
        snapshot.forEach { it.onPlaybackPositionChanged(normalized) }
    }

    fun notifyPlaybackSpeed(speed: Float) {
        val normalized = if (speed.isFinite() && speed > 0f) speed else 1f
        val snapshot: List<PlaybackSpeedListener>
        synchronized(this) {
            playbackSpeedSnapshot = normalized
            snapshot = playbackSpeedListeners.toList()
        }
        snapshot.forEach { it.onPlaybackSpeedChanged(normalized) }
    }

    fun notifyFavoriteState(isFavorite: Boolean) {
        val snapshot: List<FavoriteStateListener>
        synchronized(this) {
            favoriteSnapshot = isFavorite
            snapshot = favoriteListeners.toList()
        }
        snapshot.forEach { it.onFavoriteStateChanged(isFavorite) }
    }

    fun notifyCueLoopState(enabled: Boolean) {
        val snapshot: List<CueLoopStateListener>
        synchronized(this) {
            cueLoopEnabledSnapshot = enabled
            snapshot = cueLoopStateListeners.toList()
        }
        snapshot.forEach { it.onCueLoopStateChanged(enabled) }
    }

    fun togglePlayPause() {
        controller?.togglePlayPause()
    }

    fun setPlaying(play: Boolean) {
        controller?.setPlaying(play)
    }

    fun seekPrevious() {
        controller?.seekPrevious()
    }

    fun seekNext() {
        controller?.seekNext()
    }

    fun replayCurrentCue() {
        controller?.replayCurrentCue()
    }

    fun toggleCueLoop() {
        val before = cueLoopEnabledSnapshot
        controller?.toggleCueLoop()
        val after = controller?.isCueLoopEnabled() ?: !before
        notifyCueLoopState(after)
    }

    fun toggleFavorite() {
        controller?.toggleFavorite()
    }

    fun showReader() {
        controller?.showReader()
    }

    fun lookupCurrentSubtitleAt(offset: Int) {
        controller?.lookupCurrentSubtitleAt(offset)
    }
}

private const val TIMED_SUBTITLE_SCROLL_START_HOLD = 0.12f
private const val TIMED_SUBTITLE_SCROLL_END_HOLD = 0.18f
private const val TIMED_SUBTITLE_SCROLL_TARGET_MAX = 1.0f

internal fun mapTimedSubtitleScrollProgress(linearProgress: Float): Float {
    val progress = linearProgress.coerceIn(0f, 1f)
    val motionEnd = (1f - TIMED_SUBTITLE_SCROLL_END_HOLD).coerceAtLeast(TIMED_SUBTITLE_SCROLL_START_HOLD + 0.01f)
    if (progress <= TIMED_SUBTITLE_SCROLL_START_HOLD) return 0f
    if (progress >= motionEnd) return TIMED_SUBTITLE_SCROLL_TARGET_MAX
    val normalized = ((progress - TIMED_SUBTITLE_SCROLL_START_HOLD) / (motionEnd - TIMED_SUBTITLE_SCROLL_START_HOLD))
        .coerceIn(0f, 1f)
    val eased = if (normalized < 0.5f) {
        4f * normalized * normalized * normalized
    } else {
        1f - ((-2f * normalized + 2f) * (-2f * normalized + 2f) * (-2f * normalized + 2f) / 2f)
    }
    return (TIMED_SUBTITLE_SCROLL_TARGET_MAX * eased).coerceIn(0f, TIMED_SUBTITLE_SCROLL_TARGET_MAX)
}


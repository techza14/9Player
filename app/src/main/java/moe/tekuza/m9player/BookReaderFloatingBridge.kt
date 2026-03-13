package moe.tekuza.m9player

object BookReaderFloatingBridge {
    interface Controller {
        fun isPlaying(): Boolean
        fun isFavorite(): Boolean
        fun togglePlayPause()
        fun seekPrevious()
        fun seekNext()
        fun toggleFavorite()
    }

    interface PlaybackStateListener {
        fun onPlaybackStateChanged(isPlaying: Boolean)
    }

    interface FavoriteStateListener {
        fun onFavoriteStateChanged(isFavorite: Boolean)
    }

    @Volatile
    private var controller: Controller? = null
    private val listeners = linkedSetOf<PlaybackStateListener>()
    private val favoriteListeners = linkedSetOf<FavoriteStateListener>()
    @Volatile
    private var playingSnapshot: Boolean = false
    @Volatile
    private var favoriteSnapshot: Boolean = false
    @Volatile
    private var currentAudioUriSnapshot: String? = null

    fun attach(controller: Controller) {
        synchronized(this) {
            this.controller = controller
            playingSnapshot = controller.isPlaying()
            favoriteSnapshot = controller.isFavorite()
        }
        notifyPlaybackState(playingSnapshot)
        notifyFavoriteState(favoriteSnapshot)
    }

    fun detach(controller: Controller) {
        synchronized(this) {
            if (this.controller === controller) {
                this.controller = null
                playingSnapshot = false
                favoriteSnapshot = false
            }
        }
        notifyPlaybackState(playingSnapshot)
        notifyFavoriteState(favoriteSnapshot)
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

    fun setCurrentAudioUri(audioUri: String?) {
        synchronized(this) {
            currentAudioUriSnapshot = audioUri?.takeIf { it.isNotBlank() }
        }
    }

    fun notifyFavoriteState(isFavorite: Boolean) {
        val snapshot: List<FavoriteStateListener>
        synchronized(this) {
            favoriteSnapshot = isFavorite
            snapshot = favoriteListeners.toList()
        }
        snapshot.forEach { it.onFavoriteStateChanged(isFavorite) }
    }

    fun togglePlayPause() {
        controller?.togglePlayPause()
    }

    fun seekPrevious() {
        controller?.seekPrevious()
    }

    fun seekNext() {
        controller?.seekNext()
    }

    fun toggleFavorite() {
        controller?.toggleFavorite()
    }
}


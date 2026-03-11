package com.tekuza.p9player

object BookReaderFloatingBridge {
    interface Controller {
        fun isPlaying(): Boolean
        fun togglePlayPause()
        fun seekPrevious()
        fun seekNext()
        fun toggleFavorite()
    }

    interface PlaybackStateListener {
        fun onPlaybackStateChanged(isPlaying: Boolean)
    }

    @Volatile
    private var controller: Controller? = null
    private val listeners = linkedSetOf<PlaybackStateListener>()
    @Volatile
    private var playingSnapshot: Boolean = false

    fun attach(controller: Controller) {
        synchronized(this) {
            this.controller = controller
            playingSnapshot = controller.isPlaying()
        }
        notifyPlaybackState(playingSnapshot)
    }

    fun detach(controller: Controller) {
        synchronized(this) {
            if (this.controller === controller) {
                this.controller = null
                playingSnapshot = false
            }
        }
        notifyPlaybackState(playingSnapshot)
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

    fun notifyPlaybackState(isPlaying: Boolean) {
        val snapshot: List<PlaybackStateListener>
        synchronized(this) {
            playingSnapshot = isPlaying
            snapshot = listeners.toList()
        }
        snapshot.forEach { it.onPlaybackStateChanged(isPlaying) }
    }

    fun isPlaying(): Boolean = playingSnapshot

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


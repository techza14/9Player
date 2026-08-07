package moe.tekuza.m9player

internal object ReaderPlaybackScreenVisibility {
    private val visibleScreens = linkedSetOf<Int>()

    @Synchronized
    fun markVisible(screen: Any) {
        visibleScreens += System.identityHashCode(screen)
    }

    @Synchronized
    fun markHidden(screen: Any) {
        visibleScreens -= System.identityHashCode(screen)
    }

    @Synchronized
    fun isReaderOrPlayerScreenVisible(): Boolean = visibleScreens.isNotEmpty()
}

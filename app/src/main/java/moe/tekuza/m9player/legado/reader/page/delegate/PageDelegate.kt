package moe.tekuza.m9player.legado.reader.page.delegate

import android.view.View

internal abstract class PageDelegate {
    abstract fun onPageChanged(pageView: View, forward: Boolean)

    protected fun reset(pageView: View) {
        pageView.animate().cancel()
        pageView.alpha = 1f
        pageView.translationX = 0f
        pageView.translationY = 0f
        pageView.rotationY = 0f
    }
}

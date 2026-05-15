package moe.tekuza.m9player.legado.reader.page.delegate

import android.view.View

internal class CoverPageDelegate : PageDelegate() {
    override fun onPageChanged(pageView: View, forward: Boolean) {
        reset(pageView)
        pageView.alpha = 0.25f
        pageView.animate().alpha(1f).setDuration(140L).start()
    }
}

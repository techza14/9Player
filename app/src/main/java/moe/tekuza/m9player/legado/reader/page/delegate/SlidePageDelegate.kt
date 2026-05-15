package moe.tekuza.m9player.legado.reader.page.delegate

import android.view.View

internal class SlidePageDelegate : PageDelegate() {
    override fun onPageChanged(pageView: View, forward: Boolean) {
        reset(pageView)
        val distance = if (forward) pageView.width * 0.18f else -pageView.width * 0.18f
        pageView.translationX = distance
        pageView.animate().translationX(0f).setDuration(160L).start()
    }
}

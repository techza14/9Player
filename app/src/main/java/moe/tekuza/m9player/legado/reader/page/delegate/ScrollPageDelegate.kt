package moe.tekuza.m9player.legado.reader.page.delegate

import android.view.View

internal class ScrollPageDelegate : PageDelegate() {
    override fun onPageChanged(pageView: View, forward: Boolean) {
        reset(pageView)
        val distance = if (forward) pageView.height * 0.12f else -pageView.height * 0.12f
        pageView.translationY = distance
        pageView.animate().translationY(0f).setDuration(160L).start()
    }
}

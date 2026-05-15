package moe.tekuza.m9player.legado.reader.page.delegate

import android.view.View

internal class NoAnimPageDelegate : PageDelegate() {
    override fun onPageChanged(pageView: View, forward: Boolean) {
        reset(pageView)
    }
}

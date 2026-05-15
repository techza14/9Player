package moe.tekuza.m9player.legado.reader.page.delegate

import android.view.View

internal class SimulationPageDelegate : PageDelegate() {
    override fun onPageChanged(pageView: View, forward: Boolean) {
        reset(pageView)
        pageView.cameraDistance = pageView.resources.displayMetrics.density * 8000f
        pageView.pivotX = if (forward) pageView.width.toFloat() else 0f
        pageView.rotationY = if (forward) -12f else 12f
        pageView.translationX = if (forward) pageView.width * 0.08f else -pageView.width * 0.08f
        pageView.animate()
            .rotationY(0f)
            .translationX(0f)
            .setDuration(180L)
            .start()
    }
}

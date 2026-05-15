package moe.tekuza.m9player.legado.reader

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import moe.tekuza.m9player.R

internal class SearchMenu @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    var onPrevious: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onResults: (() -> Unit)? = null
    var onMainMenu: (() -> Unit)? = null
    var onExit: (() -> Unit)? = null

    private val infoText: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.view_m9_search_menu, this, true)
        visibility = View.GONE
        infoText = findViewById(R.id.search_info)
        findViewById<View>(R.id.search_menu_scrim).setOnClickListener { hideMenu() }
        findViewById<TextView>(R.id.search_prev_float).setOnClickListener { onPrevious?.invoke() }
        findViewById<TextView>(R.id.search_next_float).setOnClickListener { onNext?.invoke() }
        findViewById<ImageButton>(R.id.search_prev).setOnClickListener { onPrevious?.invoke() }
        findViewById<ImageButton>(R.id.search_next).setOnClickListener { onNext?.invoke() }
        findViewById<TextView>(R.id.search_results).setOnClickListener { onResults?.invoke() }
        findViewById<TextView>(R.id.search_main_menu).setOnClickListener { onMainMenu?.invoke() }
        findViewById<TextView>(R.id.search_exit).setOnClickListener { onExit?.invoke() }
    }

    fun showMenu() {
        visibility = View.VISIBLE
    }

    fun hideMenu() {
        visibility = View.GONE
    }

    fun updateInfo(text: String) {
        infoText.text = text
    }
}

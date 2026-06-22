package moe.tekuza.m9player.legado.reader.config

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.Switch
import android.widget.TextView
import moe.tekuza.m9player.R

internal data class MoreConfigState(
    val screenOrientationIndex: Int = 0,
    val keepScreenOn: Boolean = false,
    val progressByChapter: Boolean = true,
    val selectionPrimaryActionSummary: String = "",
    val clickRegionSummary: String = "",
    val hideStatusBar: Boolean = false,
    val readBodyToLh: Boolean = true,
    val hideNavigationBar: Boolean = false,
    val showBrightnessView: Boolean = true,
    val showReadTitleAddition: Boolean = true,
    val useZhLayout: Boolean = true,
    val useZhLayoutLabel: String = "",
    val textFullJustify: Boolean = true,
    val textBottomJustify: Boolean = true,
    val noAnimScrollPage: Boolean = false,
    val hideUnreadImages: Boolean = false,
    val disableReturnKey: Boolean = false,
    val readBarStyleFollowPage: Boolean = false
)

internal class MoreConfigDialog @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    var onHideStatusBarChanged: ((Boolean) -> Unit)? = null
    var onHideNavigationBarChanged: ((Boolean) -> Unit)? = null
    var onReadBodyToLhChanged: ((Boolean) -> Unit)? = null
    var onShowBrightnessViewChanged: ((Boolean) -> Unit)? = null
    var onShowReadTitleAdditionChanged: ((Boolean) -> Unit)? = null
    var onUseZhLayoutChanged: ((Boolean) -> Unit)? = null
    var onTextFullJustifyChanged: ((Boolean) -> Unit)? = null
    var onTextBottomJustifyChanged: ((Boolean) -> Unit)? = null
    var onNoAnimScrollPageChanged: ((Boolean) -> Unit)? = null
    var onHideUnreadImagesChanged: ((Boolean) -> Unit)? = null
    var onDisableReturnKeyChanged: ((Boolean) -> Unit)? = null
    var onReadBarStyleFollowPageChanged: ((Boolean) -> Unit)? = null
    var onScreenOrientationClicked: (() -> Unit)? = null
    var onKeepLightClicked: (() -> Unit)? = null
    var onProgressBehaviorClicked: (() -> Unit)? = null
    var onSelectionPrimaryActionClicked: (() -> Unit)? = null
    var onClickRegionalConfigClicked: (() -> Unit)? = null
    var onResetDefaultsClicked: (() -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.dialog_m9_more_config, this, true)
        isClickable = true
        isFocusable = true
    }

    fun bind(state: MoreConfigState) {
        findViewById<TextView>(R.id.more_screen_orientation_value).text =
            resources.getStringArray(R.array.reader_screen_direction_titles)
                .getOrElse(state.screenOrientationIndex) { resources.getString(R.string.screen_unspecified) }
        findViewById<TextView>(R.id.more_keep_light_value).text =
            resources.getStringArray(R.array.reader_keep_light_titles)
                .getOrElse(if (state.keepScreenOn) 1 else 0) { resources.getString(R.string.reader_keep_light_default) }
        findViewById<TextView>(R.id.more_progress_behavior_value).text =
            resources.getStringArray(R.array.reader_progress_behavior_titles)
                .getOrElse(if (state.progressByChapter) 0 else 1) { resources.getString(R.string.reader_progress_behavior_page) }
        findViewById<TextView>(R.id.more_selection_primary_action_value).text =
            state.selectionPrimaryActionSummary
        findViewById<TextView>(R.id.more_click_regional_config_value).text = state.clickRegionSummary
        findViewById<TextView>(R.id.more_zh_layout_value).text = state.useZhLayoutLabel
        bindSwitch(R.id.more_hide_status_bar, state.hideStatusBar, onHideStatusBarChanged)
        bindSwitch(R.id.more_read_body_to_lh, state.readBodyToLh, onReadBodyToLhChanged)
        bindSwitch(R.id.more_hide_navigation_bar, state.hideNavigationBar, onHideNavigationBarChanged)
        bindSwitch(R.id.more_show_brightness_view, state.showBrightnessView, onShowBrightnessViewChanged)
        bindSwitch(R.id.more_show_read_title_addition, state.showReadTitleAddition, onShowReadTitleAdditionChanged)
        bindSwitch(R.id.more_text_full_justify, state.textFullJustify, onTextFullJustifyChanged)
        bindSwitch(R.id.more_text_bottom_justify, state.textBottomJustify, onTextBottomJustifyChanged)
        bindSwitch(R.id.more_no_anim_scroll_page, state.noAnimScrollPage, onNoAnimScrollPageChanged)
        bindSwitch(R.id.more_hide_unread_images, state.hideUnreadImages, onHideUnreadImagesChanged)
        bindSwitch(R.id.more_disable_return_key, state.disableReturnKey, onDisableReturnKeyChanged)
        bindSwitch(R.id.more_read_bar_style_follow_page, state.readBarStyleFollowPage, onReadBarStyleFollowPageChanged)

        findViewById<View>(R.id.more_screen_orientation).setOnClickListener { onScreenOrientationClicked?.invoke() }
        findViewById<View>(R.id.more_keep_light).setOnClickListener { onKeepLightClicked?.invoke() }
        findViewById<View>(R.id.more_progress_behavior).setOnClickListener { onProgressBehaviorClicked?.invoke() }
        findViewById<View>(R.id.more_selection_primary_action).setOnClickListener { onSelectionPrimaryActionClicked?.invoke() }
        findViewById<View>(R.id.more_click_regional_config).setOnClickListener { onClickRegionalConfigClicked?.invoke() }
        findViewById<View>(R.id.more_zh_layout_row).setOnClickListener { onUseZhLayoutChanged?.invoke(!state.useZhLayout) }
        findViewById<View>(R.id.more_reset_defaults).setOnClickListener { onResetDefaultsClicked?.invoke() }
    }

    private fun bindSwitch(id: Int, checked: Boolean, callback: ((Boolean) -> Unit)?) {
        findViewById<Switch>(id).apply {
            setOnCheckedChangeListener(null)
            isChecked = checked
            setOnCheckedChangeListener { _, value -> callback?.invoke(value) }
        }
    }

}

package moe.tekuza.m9player.legado.reader.config

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.Switch
import moe.tekuza.m9player.R

internal data class MoreConfigState(
    val hideStatusBar: Boolean = false,
    val readBodyToLh: Boolean = true,
    val hideNavigationBar: Boolean = false,
    val showBrightnessView: Boolean = true,
    val showReadTitleAddition: Boolean = true,
    val useZhLayout: Boolean = true,
    val textFullJustify: Boolean = true,
    val textBottomJustify: Boolean = true,
    val mouseWheelPage: Boolean = true,
    val keyPageOnLongPress: Boolean = false,
    val noAnimScrollPage: Boolean = false,
    val previewImageByClick: Boolean = false,
    val optimizeRender: Boolean = false,
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
    var onMouseWheelPageChanged: ((Boolean) -> Unit)? = null
    var onKeyPageOnLongPressChanged: ((Boolean) -> Unit)? = null
    var onNoAnimScrollPageChanged: ((Boolean) -> Unit)? = null
    var onPreviewImageByClickChanged: ((Boolean) -> Unit)? = null
    var onOptimizeRenderChanged: ((Boolean) -> Unit)? = null
    var onDisableReturnKeyChanged: ((Boolean) -> Unit)? = null
    var onReadBarStyleFollowPageChanged: ((Boolean) -> Unit)? = null
    var onScreenOrientationClicked: (() -> Unit)? = null
    var onKeepLightClicked: (() -> Unit)? = null
    var onDoublePageClicked: (() -> Unit)? = null
    var onProgressBehaviorClicked: (() -> Unit)? = null
    var onPageTouchSlopClicked: (() -> Unit)? = null
    var onClickRegionalConfigClicked: (() -> Unit)? = null
    var onCustomPageKeyClicked: (() -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.dialog_m9_more_config, this, true)
        isClickable = true
        isFocusable = true
    }

    fun bind(state: MoreConfigState) {
        bindSwitch(R.id.more_hide_status_bar, state.hideStatusBar, onHideStatusBarChanged)
        bindSwitch(R.id.more_read_body_to_lh, state.readBodyToLh, onReadBodyToLhChanged)
        bindSwitch(R.id.more_hide_navigation_bar, state.hideNavigationBar, onHideNavigationBarChanged)
        bindSwitch(R.id.more_show_brightness_view, state.showBrightnessView, onShowBrightnessViewChanged)
        bindSwitch(R.id.more_show_read_title_addition, state.showReadTitleAddition, onShowReadTitleAdditionChanged)
        bindSwitch(R.id.more_use_zh_layout, state.useZhLayout, onUseZhLayoutChanged)
        bindSwitch(R.id.more_text_full_justify, state.textFullJustify, onTextFullJustifyChanged)
        bindSwitch(R.id.more_text_bottom_justify, state.textBottomJustify, onTextBottomJustifyChanged)
        bindSwitch(R.id.more_mouse_wheel_page, state.mouseWheelPage, onMouseWheelPageChanged)
        bindSwitch(R.id.more_key_page_on_long_press, state.keyPageOnLongPress, onKeyPageOnLongPressChanged)
        bindSwitch(R.id.more_no_anim_scroll_page, state.noAnimScrollPage, onNoAnimScrollPageChanged)
        bindSwitch(R.id.more_preview_image_by_click, state.previewImageByClick, onPreviewImageByClickChanged)
        bindSwitch(R.id.more_optimize_render, state.optimizeRender, onOptimizeRenderChanged)
        bindSwitch(R.id.more_disable_return_key, state.disableReturnKey, onDisableReturnKeyChanged)
        bindSwitch(R.id.more_read_bar_style_follow_page, state.readBarStyleFollowPage, onReadBarStyleFollowPageChanged)

        findViewById<View>(R.id.more_screen_orientation).setOnClickListener { onScreenOrientationClicked?.invoke() }
        findViewById<View>(R.id.more_keep_light).setOnClickListener { onKeepLightClicked?.invoke() }
        findViewById<View>(R.id.more_double_page).setOnClickListener { onDoublePageClicked?.invoke() }
        findViewById<View>(R.id.more_progress_behavior).setOnClickListener { onProgressBehaviorClicked?.invoke() }
        findViewById<View>(R.id.more_page_touch_slop).setOnClickListener { onPageTouchSlopClicked?.invoke() }
        findViewById<View>(R.id.more_click_regional_config).setOnClickListener { onClickRegionalConfigClicked?.invoke() }
        findViewById<View>(R.id.more_custom_page_key).setOnClickListener { onCustomPageKeyClicked?.invoke() }
    }

    private fun bindSwitch(id: Int, checked: Boolean, callback: ((Boolean) -> Unit)?) {
        findViewById<Switch>(id).apply {
            setOnCheckedChangeListener(null)
            isChecked = checked
            setOnCheckedChangeListener { _, value -> callback?.invoke(value) }
        }
    }

}

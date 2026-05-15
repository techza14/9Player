package moe.tekuza.m9player.legado.reader.config

import android.app.Activity
import android.view.Gravity
import android.view.ViewGroup
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import moe.tekuza.m9player.legado.reader.M9PageAnim
import moe.tekuza.m9player.R

internal data class ReadStyleState(
    val textSizeSp: Int,
    val letterSpacingDp: Int,
    val lineSpacingDp: Int,
    val paragraphSpacingDp: Int,
    val pageAnim: M9PageAnim
)

internal class ReadStyleDialog(
    private val activity: Activity,
    private val state: ReadStyleState,
    private val callback: Callback
) {
    interface Callback {
        fun onTextSizeChanged(valueSp: Int)
        fun onLetterSpacingChanged(value: Int)
        fun onLineSpacingChanged(valueDp: Int)
        fun onParagraphSpacingChanged(valueDp: Int)
        fun onTextSizeChangeFinished(valueSp: Int) {}
        fun onLetterSpacingChangeFinished(value: Int) {}
        fun onLineSpacingChangeFinished(valueDp: Int) {}
        fun onParagraphSpacingChangeFinished(valueDp: Int) {}
        fun onInfoClicked()
        fun onWeightClicked()
        fun onFontClicked()
        fun onIndentClicked()
        fun onPaddingClicked()
        fun onTipClicked()
        fun onPageAnimClicked(animIndex: Int)
        fun onBackgroundClicked(index: Int)
    }

    fun show() {
        val view = activity.layoutInflater.inflate(R.layout.dialog_m9_read_style, null, false)
        bindTopButtons(view)
        bindSeekBar(view, R.id.style_text_size_label, R.id.style_text_size, "字体大小", 14, state.textSizeSp, callback::onTextSizeChanged, callback::onTextSizeChangeFinished)
        bindSeekBar(view, R.id.style_letter_label, R.id.style_letter_size, "字距", -50, state.letterSpacingDp, callback::onLetterSpacingChanged, callback::onLetterSpacingChangeFinished)
        bindSeekBar(view, R.id.style_line_label, R.id.style_line_size, "行距", 0, state.lineSpacingDp, callback::onLineSpacingChanged, callback::onLineSpacingChangeFinished)
        bindSeekBar(view, R.id.style_paragraph_label, R.id.style_paragraph_size, "段距", 0, state.paragraphSpacingDp, callback::onParagraphSpacingChanged, callback::onParagraphSpacingChangeFinished)
        view.findViewById<RadioGroup>(R.id.style_page_anim).check(
            when (state.pageAnim) {
                M9PageAnim.COVER -> R.id.style_anim_cover
                M9PageAnim.SLIDE -> R.id.style_anim_slide
                M9PageAnim.SCROLL -> R.id.style_anim_scroll
                M9PageAnim.SIMULATION,
                M9PageAnim.NONE -> R.id.style_anim_none
            }
        )
        view.findViewById<RadioGroup>(R.id.style_page_anim).setOnCheckedChangeListener { _, _ ->
            val group = view.findViewById<RadioGroup>(R.id.style_page_anim)
            callback.onPageAnimClicked(group.indexOfChild(group.findViewById(group.checkedRadioButtonId)))
        }
        listOf(
            R.id.style_color_paper,
            R.id.style_color_white,
            R.id.style_color_green,
            R.id.style_color_dark
        ).forEachIndexed { index, id ->
            view.findViewById<TextView>(id).setOnClickListener {
                callback.onBackgroundClicked(index)
            }
        }

        val dialog = AlertDialog.Builder(activity).setView(view).create()
        dialog.setOnShowListener {
            dialog.window?.run {
                setBackgroundDrawableResource(android.R.color.transparent)
                val attr = attributes
                attr.gravity = Gravity.BOTTOM
                attributes = attr
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
        dialog.show()
    }

    private fun bindTopButtons(view: android.view.View) {
        view.findViewById<TextView>(R.id.style_info).setOnClickListener { callback.onInfoClicked() }
        view.findViewById<TextView>(R.id.style_weight).setOnClickListener { callback.onWeightClicked() }
        view.findViewById<TextView>(R.id.style_font).setOnClickListener { callback.onFontClicked() }
        view.findViewById<TextView>(R.id.style_indent).setOnClickListener { callback.onIndentClicked() }
        view.findViewById<TextView>(R.id.style_padding).setOnClickListener { callback.onPaddingClicked() }
        view.findViewById<TextView>(R.id.style_info).setOnLongClickListener {
            callback.onTipClicked()
            true
        }
    }

    private fun bindSeekBar(
        view: android.view.View,
        labelId: Int,
        seekId: Int,
        title: String,
        min: Int,
        value: Int,
        onChanged: (Int) -> Unit,
        onChangeFinished: (Int) -> Unit
    ) {
        val label = view.findViewById<TextView>(labelId)
        val seek = view.findViewById<SeekBar>(seekId)
        seek.progress = (value - min).coerceIn(0, seek.max)
        label.text = "$title：${min + seek.progress}"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val next = min + progress
                    label.text = "$title：$next"
                    onChanged(next)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                onChangeFinished(min + seek.progress)
            }
        })
    }

}

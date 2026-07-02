package moe.tekuza.m9player.legado.reader.config

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.util.Locale
import kotlin.math.abs
import moe.tekuza.m9player.legado.reader.M9PageAnim
import moe.tekuza.m9player.legado.reader.M9TextWeight
import moe.tekuza.m9player.R

internal data class ReadStyleState(
    val textSizeSp: Int,
    val letterSpacingDp: Int,
    val lineSpacingDp: Int,
    val paragraphSpacingDp: Int,
    val textWeight: M9TextWeight,
    val backgroundStyleIndex: Int,
    val backgroundStyles: List<ReadStyleColorItem>,
    val pageAnim: M9PageAnim
)

internal data class ReadStyleColorItem(
    val name: String,
    val bgColor: Int,
    val textColor: Int,
    val tipColor: Int,
    val bgAlpha: Int,
    val bgAssetName: String?,
    val bgImageUri: String?
)

internal class ReadStyleDialog(
    private val activity: Activity,
    state: ReadStyleState,
    private val callback: Callback
) {
    companion object {
        private const val TEXT_SIZE_MIN_SP = 5
        private const val LETTER_SPACING_MIN = -50
        private const val LINE_SPACING_PROGRESS_OFFSET = 10
    }

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
        fun onWeightClicked(onChanged: (M9TextWeight) -> Unit)
        fun onFontClicked()
        fun onIndentClicked()
        fun onPaddingClicked()
        fun onTipClicked()
        fun onPageAnimClicked(animIndex: Int)
        fun onBackgroundClicked(index: Int): ReadStyleState
        fun onBackgroundLongClicked(index: Int)
        fun onBackgroundAddClicked()
    }
    private var dialog: AlertDialog? = null
    private var currentState: ReadStyleState = state

    fun show() {
        val view = activity.layoutInflater.inflate(R.layout.dialog_m9_read_style, null, false)
        bindTopButtons(view)
        bindSeekBar(
            view,
            R.id.style_text_size_label,
            R.id.style_text_size_value,
            R.id.style_text_size_minus,
            R.id.style_text_size,
            R.id.style_text_size_plus,
            activity.getString(R.string.reader_style_text_size),
            TEXT_SIZE_MIN_SP,
            currentState.textSizeSp,
            formatValue = { value -> value.toString() },
            onChanged = { value ->
                currentState = currentState.copy(textSizeSp = value)
                callback.onTextSizeChanged(value)
            },
            onChangeFinished = callback::onTextSizeChangeFinished
        )
        bindSeekBar(
            view,
            R.id.style_letter_label,
            R.id.style_letter_value,
            R.id.style_letter_minus,
            R.id.style_letter_size,
            R.id.style_letter_plus,
            activity.getString(R.string.reader_style_letter_spacing),
            LETTER_SPACING_MIN,
            currentState.letterSpacingDp,
            formatValue = { value -> formatLetterSpacing(value) },
            onChanged = { value ->
                currentState = currentState.copy(letterSpacingDp = value)
                callback.onLetterSpacingChanged(value)
            },
            onChangeFinished = callback::onLetterSpacingChangeFinished
        )
        bindSeekBar(
            view,
            R.id.style_line_label,
            R.id.style_line_value,
            R.id.style_line_minus,
            R.id.style_line_size,
            R.id.style_line_plus,
            activity.getString(R.string.reader_style_line_spacing),
            0,
            currentState.lineSpacingDp,
            formatValue = { value -> formatOneDecimal((value - LINE_SPACING_PROGRESS_OFFSET) / 10f) },
            onChanged = { value ->
                currentState = currentState.copy(lineSpacingDp = value)
                callback.onLineSpacingChanged(value)
            },
            onChangeFinished = callback::onLineSpacingChangeFinished
        )
        bindSeekBar(
            view,
            R.id.style_paragraph_label,
            R.id.style_paragraph_value,
            R.id.style_paragraph_minus,
            R.id.style_paragraph_size,
            R.id.style_paragraph_plus,
            activity.getString(R.string.reader_style_paragraph_spacing),
            0,
            currentState.paragraphSpacingDp,
            formatValue = { value -> formatOneDecimal(value / 10f) },
            onChanged = { value ->
                currentState = currentState.copy(paragraphSpacingDp = value)
                callback.onParagraphSpacingChanged(value)
            },
            onChangeFinished = callback::onParagraphSpacingChangeFinished
        )
        view.findViewById<RadioGroup>(R.id.style_page_anim).check(
            when (currentState.pageAnim) {
                M9PageAnim.COVER -> R.id.style_anim_cover
                M9PageAnim.SLIDE -> R.id.style_anim_slide
                M9PageAnim.SIMULATION -> R.id.style_anim_simulation
                M9PageAnim.SCROLL -> R.id.style_anim_scroll
                M9PageAnim.NONE -> R.id.style_anim_none
            }
        )
        view.findViewById<RadioGroup>(R.id.style_page_anim).setOnCheckedChangeListener { _, _ ->
            val group = view.findViewById<RadioGroup>(R.id.style_page_anim)
            val animIndex = group.indexOfChild(group.findViewById(group.checkedRadioButtonId))
            currentState = currentState.copy(
                pageAnim = when (animIndex) {
                    0 -> M9PageAnim.COVER
                    1 -> M9PageAnim.SLIDE
                    2 -> M9PageAnim.SIMULATION
                    3 -> M9PageAnim.SCROLL
                    else -> M9PageAnim.NONE
                }
            )
            callback.onPageAnimClicked(animIndex)
        }
        bindBackgroundStyles(view)

        dialog = AlertDialog.Builder(activity).setView(view).create()
        dialog?.show()
        configureWindow(dialog?.window)
    }

    private fun bindCurrentStyleState(view: android.view.View) {
        bindSeekBarValue(
            view.findViewById(R.id.style_text_size_label),
            view.findViewById(R.id.style_text_size_value),
            view.findViewById(R.id.style_text_size),
            activity.getString(R.string.reader_style_text_size),
            TEXT_SIZE_MIN_SP,
            currentState.textSizeSp
        ) { value -> value.toString() }
        bindSeekBarValue(
            view.findViewById(R.id.style_letter_label),
            view.findViewById(R.id.style_letter_value),
            view.findViewById(R.id.style_letter_size),
            activity.getString(R.string.reader_style_letter_spacing),
            LETTER_SPACING_MIN,
            currentState.letterSpacingDp
        ) { value -> formatLetterSpacing(value) }
        bindSeekBarValue(
            view.findViewById(R.id.style_line_label),
            view.findViewById(R.id.style_line_value),
            view.findViewById(R.id.style_line_size),
            activity.getString(R.string.reader_style_line_spacing),
            0,
            currentState.lineSpacingDp
        ) { value -> formatOneDecimal((value - LINE_SPACING_PROGRESS_OFFSET) / 10f) }
        bindSeekBarValue(
            view.findViewById(R.id.style_paragraph_label),
            view.findViewById(R.id.style_paragraph_value),
            view.findViewById(R.id.style_paragraph_size),
            activity.getString(R.string.reader_style_paragraph_spacing),
            0,
            currentState.paragraphSpacingDp
        ) { value -> formatOneDecimal(value / 10f) }
        view.findViewById<TextView>(R.id.style_weight).text = fontWeightLabel(currentState.textWeight)
        bindBackgroundStyles(view)
        dialog?.window?.setBackgroundDrawable(ColorDrawable(currentBackgroundColor()))
    }

    private fun bindBackgroundStyles(view: android.view.View) {
        val row = view.findViewById<LinearLayout>(R.id.style_color_row)
        row.removeAllViews()
        currentState.backgroundStyles.forEachIndexed { index, item ->
            val selected = index == currentState.backgroundStyleIndex
            val textView = TextView(activity)
            textView.text = item.name.ifBlank { activity.getString(R.string.reader_style_sample_text) }
            textView.setTextColor(item.textColor)
            textView.gravity = android.view.Gravity.CENTER
            textView.textSize = 11f
            textView.maxLines = 2
            textView.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                alpha = (item.bgAlpha.coerceIn(0, 100) * 255 / 100)
                setColor(item.bgColor)
                setStroke(
                    dp(if (selected) 2 else 1),
                    if (selected) 0xFF2E9F6E.toInt() else item.tipColor
                )
            }
            textView.setOnClickListener {
                currentState = callback.onBackgroundClicked(index)
                bindCurrentStyleState(view)
            }
            textView.setOnLongClickListener {
                dialog?.dismiss()
                callback.onBackgroundLongClicked(index)
                true
            }
            row.addView(textView, LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                marginEnd = dp(8)
            })
        }
        row.addView(TextView(activity).apply {
            text = "+"
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFF7D6E5C.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x22FFFFFF)
                setStroke(dp(1), 0xFF7D6E5C.toInt())
            }
            setOnClickListener {
                dialog?.dismiss()
                callback.onBackgroundAddClicked()
            }
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
    }

    private fun bindTopButtons(view: android.view.View) {
        val weightView = view.findViewById<TextView>(R.id.style_weight)
        weightView.text = fontWeightLabel(currentState.textWeight)
        view.findViewById<TextView>(R.id.style_info).setOnClickListener { callback.onInfoClicked() }
        weightView.setOnClickListener {
            callback.onWeightClicked { nextWeight ->
                currentState = currentState.copy(textWeight = nextWeight)
                weightView.text = fontWeightLabel(nextWeight)
            }
        }
        view.findViewById<TextView>(R.id.style_font).setOnClickListener { callback.onFontClicked() }
        view.findViewById<TextView>(R.id.style_indent).setOnClickListener { callback.onIndentClicked() }
        view.findViewById<TextView>(R.id.style_padding).setOnClickListener { callback.onPaddingClicked() }
        view.findViewById<TextView>(R.id.style_info).setOnLongClickListener {
            callback.onTipClicked()
            true
        }
    }

    private fun fontWeightLabel(weight: M9TextWeight): SpannableString {
        val label = SpannableString(activity.getString(R.string.reader_style_font_weight_text))
        val selectedIndex = when (weight) {
            M9TextWeight.NORMAL -> 0
            M9TextWeight.BOLD -> 2
            M9TextWeight.LIGHT -> 4
        }
        if (selectedIndex < label.length) {
            label.setSpan(
                ForegroundColorSpan(0xFF2E9F6E.toInt()),
                selectedIndex,
                (selectedIndex + 1).coerceAtMost(label.length),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return label
    }

    private fun bindSeekBar(
        view: android.view.View,
        labelId: Int,
        valueId: Int,
        minusId: Int,
        seekId: Int,
        plusId: Int,
        title: String,
        min: Int,
        value: Int,
        formatValue: (Int) -> String,
        onChanged: (Int) -> Unit,
        onChangeFinished: (Int) -> Unit
    ) {
        val label = view.findViewById<TextView>(labelId)
        val valueLabel = view.findViewById<TextView>(valueId)
        val minus = view.findViewById<TextView>(minusId)
        val seek = view.findViewById<SeekBar>(seekId)
        val plus = view.findViewById<TextView>(plusId)
        bindSeekBarValue(label, valueLabel, seek, title, min, value, formatValue)
        fun applyValue(next: Int, finished: Boolean) {
            val safe = next.coerceIn(min, min + seek.max)
            seek.progress = safe - min
            valueLabel.text = formatValue(safe)
            onChanged(safe)
            if (finished) onChangeFinished(safe)
        }
        minus.setOnClickListener {
            applyValue(min + seek.progress - 1, finished = true)
        }
        plus.setOnClickListener {
            applyValue(min + seek.progress + 1, finished = true)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val next = min + progress
                    valueLabel.text = formatValue(next)
                    onChanged(next)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                onChangeFinished(min + seek.progress)
            }
        })
    }

    private fun bindSeekBarValue(
        label: TextView,
        valueLabel: TextView,
        seek: SeekBar,
        title: String,
        min: Int,
        value: Int,
        formatValue: (Int) -> String
    ) {
        seek.progress = (value - min).coerceIn(0, seek.max)
        label.text = title
        valueLabel.text = formatValue(min + seek.progress)
    }

    private fun currentBackgroundColor(): Int {
        return currentState.backgroundStyles
            .getOrNull(currentState.backgroundStyleIndex)
            ?.bgColor
            ?: 0xFFF8F1E3.toInt()
    }

    private fun configureWindow(window: Window?) {
        window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawable(ColorDrawable(currentBackgroundColor()))
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0f
            attr.gravity = Gravity.BOTTOM
            attributes = attr
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun formatOneDecimal(value: Float): String {
        val normalized = if (abs(value) < 0.05f) 0f else value
        return String.format(Locale.US, "%.1f", normalized)
    }

    private fun formatLetterSpacing(value: Int): String {
        return (value / 100f).toString()
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

}

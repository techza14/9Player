package moe.tekuza.m9player.legado.reader.page

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.StaticLayout
import android.util.AttributeSet
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.roundToInt

class ReaderBatteryTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {
    private val batteryPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outFrame = Rect()
    private val polar = Rect()
    private var batteryPercent: Int = 0
    private var batteryTipEnabled: Boolean = false
    private var plainTypeface: Typeface? = typeface

    init {
        val density = resources.displayMetrics.density
        setPadding(
            (4f * density).roundToInt(),
            (3f * density).roundToInt(),
            (6f * density).roundToInt(),
            (3f * density).roundToInt()
        )
        batteryPaint.strokeWidth = density
        batteryPaint.color = currentTextColor
    }

    fun setPlainText(value: CharSequence?) {
        batteryTipEnabled = false
        super.setTypeface(plainTypeface)
        text = value
        invalidate()
    }

    fun setBatteryValue(value: Int, prefix: String? = null) {
        batteryPercent = value.coerceIn(0, 100)
        if (!batteryTipEnabled) {
            plainTypeface = typeface
        }
        batteryTipEnabled = true
        super.setTypeface(Typeface.MONOSPACE)
        text = if (prefix.isNullOrBlank()) {
            batteryPercent.toString()
        } else {
            "$prefix  $batteryPercent"
        }
        invalidate()
    }

    override fun setTypeface(tf: Typeface?) {
        if (batteryTipEnabled) {
            plainTypeface = tf
        } else {
            plainTypeface = tf
            super.setTypeface(tf)
        }
    }

    override fun setTextColor(color: Int) {
        super.setTextColor(color)
        batteryPaint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawBattery(canvas)
    }

    private fun drawBattery(canvas: Canvas) {
        if (!batteryTipEnabled) return
        val layout = layout ?: return
        val content = text ?: return
        val batteryText = batteryPercent.toString()
        val batteryStartIndex = content.length - batteryText.length
        if (batteryStartIndex < 0) return

        val density = resources.displayMetrics.density
        layout.getLineBounds(0, outFrame)
        val batteryStart = layout
            .getPrimaryHorizontal(batteryStartIndex)
            .toInt() + (2f * density).roundToInt()
        val batteryEnd = batteryStart +
            StaticLayout.getDesiredWidth(batteryText, paint).toInt() +
            (4f * density).roundToInt()
        val verticalOffset = layoutVerticalOffset(layout)
        val lineInset = (2f * density).roundToInt()
        val top = verticalOffset + outFrame.top + lineInset
        val bottom = verticalOffset + outFrame.bottom - lineInset
        if (batteryEnd <= batteryStart || bottom <= top) return

        outFrame.set(batteryStart, top, batteryEnd, bottom)
        val third = (outFrame.bottom - outFrame.top) / 3
        val nubWidth = (2f * density).roundToInt().coerceAtLeast(1)
        polar.set(
            batteryEnd,
            outFrame.top + third,
            batteryEnd + nubWidth,
            outFrame.bottom - third
        )
        batteryPaint.style = Paint.Style.STROKE
        canvas.drawRect(outFrame, batteryPaint)
        batteryPaint.style = Paint.Style.FILL
        canvas.drawRect(polar, batteryPaint)
    }

    private fun layoutVerticalOffset(layout: android.text.Layout): Int {
        val availableHeight = height - compoundPaddingTop - compoundPaddingBottom
        val extraHeight = availableHeight - layout.height
        val gravityOffset = when (gravity and Gravity.VERTICAL_GRAVITY_MASK) {
            Gravity.BOTTOM -> extraHeight.coerceAtLeast(0)
            Gravity.CENTER_VERTICAL -> (extraHeight / 2).coerceAtLeast(0)
            else -> 0
        }
        return compoundPaddingTop + gravityOffset
    }
}

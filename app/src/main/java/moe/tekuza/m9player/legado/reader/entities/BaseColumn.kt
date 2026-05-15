package moe.tekuza.m9player.legado.reader.entities

import android.graphics.Canvas
import moe.tekuza.m9player.legado.reader.page.ContentTextView

internal interface BaseColumn {
    var start: Float
    var end: Float
    var sourceStart: Int
    var sourceEnd: Int

    fun draw(view: ContentTextView, canvas: Canvas, line: TextLine, selected: Boolean)

    fun isTouch(x: Float): Boolean = x >= start && x <= end
}

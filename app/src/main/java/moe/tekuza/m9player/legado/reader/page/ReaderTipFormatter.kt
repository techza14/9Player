package moe.tekuza.m9player.legado.reader.page

import moe.tekuza.m9player.ReaderTipContent
import moe.tekuza.m9player.legado.reader.entities.TextPage
import java.util.Locale

internal object ReaderTipFormatter {
    fun isProgressTip(content: ReaderTipContent): Boolean = when (content) {
        ReaderTipContent.TOTAL_PROGRESS,
        ReaderTipContent.PAGE_OR_PROGRESS -> true
        else -> false
    }

    fun text(
        content: ReaderTipContent,
        page: TextPage?,
        alternateProgress: Boolean,
        bookTitle: String,
        chapterTitle: String,
        clockText: String,
        batteryPercent: Int
    ): String {
        if (page == null) return ""
        return when (content) {
            ReaderTipContent.NONE -> ""
            ReaderTipContent.BOOK_NAME -> bookTitle.ifBlank { page.title }
            ReaderTipContent.CHAPTER_TITLE -> chapterTitle
            ReaderTipContent.TIME -> clockText
            ReaderTipContent.BATTERY -> batteryIconText(batteryPercent)
            ReaderTipContent.BATTERY_PERCENTAGE -> "$batteryPercent%"
            ReaderTipContent.PAGE -> page.chapterPageText()
            ReaderTipContent.PAGE_OR_PROGRESS -> {
                if (alternateProgress) page.chapterProgressText() else page.chapterPageText()
            }
            ReaderTipContent.TOTAL_PROGRESS -> if (alternateProgress) page.chapterReadProgress() else page.readProgress
            ReaderTipContent.CHAPTER_PROGRESS -> page.chapterProgressText()
            ReaderTipContent.PAGE_AND_TOTAL -> "${page.chapterPageText()}  ${page.readProgress}"
            ReaderTipContent.TIME_BATTERY -> "$clockText ${batteryIconText(batteryPercent)}"
            ReaderTipContent.TIME_BATTERY_PERCENTAGE -> "$clockText $batteryPercent%"
        }
    }

    private fun TextPage.chapterPageText(): String {
        val pageCount = chapterPageCount.takeIf { it > 0 }?.toString() ?: "-"
        return "${pageInChapter + 1}/$pageCount"
    }

    private fun TextPage.chapterReadProgress(): String {
        val pageCount = chapterPageCount.takeIf { it > 0 } ?: return "0.0%"
        val value = (pageInChapter + 1).toDouble() * 100.0 / pageCount.toDouble()
        return String.format(Locale.getDefault(), "%.1f%%", value.coerceIn(0.0, 100.0))
    }

    private fun TextPage.chapterProgressText(): String {
        val count = chapterSize.takeIf { it > 0 } ?: 1
        return "${chapterIndex + 1}/$count"
    }

    private fun batteryIconText(percent: Int): String {
        return when {
            percent >= 95 -> "▰▰▰▰"
            percent >= 70 -> "▰▰▰▱"
            percent >= 40 -> "▰▰▱▱"
            percent >= 15 -> "▰▱▱▱"
            else -> "▱▱▱▱"
        }
    }
}

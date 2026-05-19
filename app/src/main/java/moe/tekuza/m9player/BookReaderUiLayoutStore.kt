package moe.tekuza.m9player

import android.content.Context

private const val BOOK_READER_UI_LAYOUT_PREFS = "book_reader_ui_layout_prefs"

internal enum class BookReaderUiModule(val storageValue: String) {
    CHAPTER_SELECTOR("chapter_selector"),
    PLAYBACK_TIMELINE("playback_timeline"),
    PLAYBACK_CONTROLS("playback_controls"),
    CHAPTER_PROGRESS_AND_JUMP_MODE("chapter_progress_and_jump_mode");

    companion object {
        fun fromStorage(value: String?): BookReaderUiModule? {
            return entries.firstOrNull { it.storageValue == value }
        }
    }
}

internal enum class BookReaderUiSlot(val storageValue: String) {
    TOP("top"),
    BOTTOM("bottom"),
    LEFT("left"),
    RIGHT("right"),
    HIDDEN("hidden");

    val isVertical: Boolean
        get() = this == LEFT || this == RIGHT

    companion object {
        fun fromStorage(value: String?): BookReaderUiSlot? {
            return entries.firstOrNull { it.storageValue == value }
        }
    }
}

internal data class BookReaderUiLayoutConfig(
    val top: List<BookReaderUiModule>,
    val bottom: List<BookReaderUiModule>,
    val left: List<BookReaderUiModule>,
    val right: List<BookReaderUiModule>,
    val hidden: List<BookReaderUiModule>
) {
    fun modulesIn(slot: BookReaderUiSlot): List<BookReaderUiModule> {
        return when (slot) {
            BookReaderUiSlot.TOP -> top
            BookReaderUiSlot.BOTTOM -> bottom
            BookReaderUiSlot.LEFT -> left
            BookReaderUiSlot.RIGHT -> right
            BookReaderUiSlot.HIDDEN -> hidden
        }
    }

    fun withSlot(slot: BookReaderUiSlot, modules: List<BookReaderUiModule>): BookReaderUiLayoutConfig {
        return when (slot) {
            BookReaderUiSlot.TOP -> copy(top = modules)
            BookReaderUiSlot.BOTTOM -> copy(bottom = modules)
            BookReaderUiSlot.LEFT -> copy(left = modules)
            BookReaderUiSlot.RIGHT -> copy(right = modules)
            BookReaderUiSlot.HIDDEN -> copy(hidden = modules)
        }.normalized()
    }

    fun move(module: BookReaderUiModule, targetSlot: BookReaderUiSlot, targetIndex: Int): BookReaderUiLayoutConfig {
        if (!module.canUseSlot(targetSlot)) return this
        val without = copy(
            top = top - module,
            bottom = bottom - module,
            left = left - module,
            right = right - module,
            hidden = hidden - module
        )
        val target = without.modulesIn(targetSlot).toMutableList()
        target.add(targetIndex.coerceIn(0, target.size), module)
        return without.withSlot(targetSlot, target).normalized()
    }

    fun normalized(): BookReaderUiLayoutConfig {
        val seen = linkedSetOf<BookReaderUiModule>()
        fun clean(slot: BookReaderUiSlot, modules: List<BookReaderUiModule>): List<BookReaderUiModule> {
            return modules.filter { module ->
                module.canUseSlot(slot) && seen.add(module)
            }
        }
        val cleanTop = clean(BookReaderUiSlot.TOP, top)
        val cleanBottom = clean(BookReaderUiSlot.BOTTOM, bottom)
        val cleanLeft = clean(BookReaderUiSlot.LEFT, left)
        val cleanRight = clean(BookReaderUiSlot.RIGHT, right)
        val cleanHidden = clean(BookReaderUiSlot.HIDDEN, hidden)
        val sideLeft = if (cleanLeft.isNotEmpty()) cleanLeft + cleanRight else emptyList()
        val sideRight = if (cleanLeft.isEmpty()) cleanRight else emptyList()
        val missing = BookReaderUiModule.entries.filterNot { it in seen }
        return copy(
            top = cleanTop,
            bottom = cleanBottom + missing,
            left = sideLeft,
            right = sideRight,
            hidden = cleanHidden
        )
    }
}

internal fun BookReaderUiModule.canUseSlot(slot: BookReaderUiSlot): Boolean {
    return when (this) {
        BookReaderUiModule.CHAPTER_PROGRESS_AND_JUMP_MODE ->
            slot == BookReaderUiSlot.TOP || slot == BookReaderUiSlot.BOTTOM || slot == BookReaderUiSlot.HIDDEN
        else -> true
    }
}

internal fun defaultBookReaderUiLayoutConfig(useSideRail: Boolean = false): BookReaderUiLayoutConfig {
    return if (useSideRail) {
        BookReaderUiLayoutConfig(
            top = listOf(BookReaderUiModule.CHAPTER_SELECTOR),
            bottom = listOf(BookReaderUiModule.CHAPTER_PROGRESS_AND_JUMP_MODE),
            left = listOf(
                BookReaderUiModule.PLAYBACK_TIMELINE,
                BookReaderUiModule.PLAYBACK_CONTROLS
            ),
            right = emptyList(),
            hidden = emptyList()
        )
    } else {
        BookReaderUiLayoutConfig(
            top = emptyList(),
            bottom = listOf(
                BookReaderUiModule.CHAPTER_SELECTOR,
                BookReaderUiModule.PLAYBACK_TIMELINE,
                BookReaderUiModule.PLAYBACK_CONTROLS,
                BookReaderUiModule.CHAPTER_PROGRESS_AND_JUMP_MODE
            ),
            left = emptyList(),
            right = emptyList(),
            hidden = emptyList()
        )
    }.normalized()
}

internal fun loadBookReaderUiLayoutConfig(
    context: Context,
    writingMode: FloatingSubtitleWritingMode,
    fallback: BookReaderUiLayoutConfig = defaultBookReaderUiLayoutConfig()
): BookReaderUiLayoutConfig {
    val prefs = context.getSharedPreferences(BOOK_READER_UI_LAYOUT_PREFS, Context.MODE_PRIVATE)
    val suffix = writingMode.storageValue
    val hasSaved = BookReaderUiSlot.entries.any { slot ->
        prefs.contains(layoutSlotKey(suffix, slot))
    }
    if (!hasSaved) return fallback.normalized()
    return BookReaderUiLayoutConfig(
        top = decodeModuleList(prefs.getString(layoutSlotKey(suffix, BookReaderUiSlot.TOP), null)),
        bottom = decodeModuleList(prefs.getString(layoutSlotKey(suffix, BookReaderUiSlot.BOTTOM), null)),
        left = decodeModuleList(prefs.getString(layoutSlotKey(suffix, BookReaderUiSlot.LEFT), null)),
        right = decodeModuleList(prefs.getString(layoutSlotKey(suffix, BookReaderUiSlot.RIGHT), null)),
        hidden = decodeModuleList(prefs.getString(layoutSlotKey(suffix, BookReaderUiSlot.HIDDEN), null))
    ).normalized()
}

internal fun saveBookReaderUiLayoutConfig(
    context: Context,
    writingMode: FloatingSubtitleWritingMode,
    config: BookReaderUiLayoutConfig
) {
    val normalized = config.normalized()
    val suffix = writingMode.storageValue
    context.getSharedPreferences(BOOK_READER_UI_LAYOUT_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(layoutSlotKey(suffix, BookReaderUiSlot.TOP), encodeModuleList(normalized.top))
        .putString(layoutSlotKey(suffix, BookReaderUiSlot.BOTTOM), encodeModuleList(normalized.bottom))
        .putString(layoutSlotKey(suffix, BookReaderUiSlot.LEFT), encodeModuleList(normalized.left))
        .putString(layoutSlotKey(suffix, BookReaderUiSlot.RIGHT), encodeModuleList(normalized.right))
        .putString(layoutSlotKey(suffix, BookReaderUiSlot.HIDDEN), encodeModuleList(normalized.hidden))
        .apply()
}

private fun layoutSlotKey(suffix: String, slot: BookReaderUiSlot): String {
    return "layout_${suffix}_${slot.storageValue}"
}

private fun encodeModuleList(modules: List<BookReaderUiModule>): String {
    return modules.joinToString(",") { it.storageValue }
}

private fun decodeModuleList(value: String?): List<BookReaderUiModule> {
    if (value.isNullOrBlank()) return emptyList()
    return value.split(",").mapNotNull { BookReaderUiModule.fromStorage(it.trim()) }
}

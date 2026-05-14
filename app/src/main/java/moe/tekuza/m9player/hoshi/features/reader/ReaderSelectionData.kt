package moe.tekuza.m9player.hoshi.features.reader

data class ReaderSelectionData(
    val text: String,
    val sentence: String,
    val rect: ReaderSelectionRect,
    val normalizedOffset: Int?,
    val sentenceOffset: Int? = null,
    val textRects: List<ReaderSelectionTextRect> = emptyList(),
)

data class ReaderSelectionRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

data class ReaderSelectionTextRect(
    val startOffset: Int,
    val endOffset: Int,
    val rect: ReaderSelectionRect,
)

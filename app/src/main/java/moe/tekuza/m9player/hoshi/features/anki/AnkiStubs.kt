package moe.tekuza.m9player.hoshi.features.anki

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

data class AnkiPopupSettings(
    val needsAudio: Boolean = false,
    val allowDupes: Boolean = false,
    val embedMedia: Boolean = false,
    val compactGlossaries: Boolean = true,
    val useFirstDefinitionOnly: Boolean = false,
)

data class AnkiMiningContext(
    val sentence: String,
    val documentTitle: String? = null,
    val coverPath: String? = null,
    val sentenceOffset: Int? = null,
    val sasayakiAudioPath: String? = null,
)

data class AnkiUiState(
    val popupSettings: AnkiPopupSettings = AnkiPopupSettings(),
)

class AnkiRepository

class AnkiViewModel(private val repository: AnkiRepository = AnkiRepository()) : ViewModel() {
    private val mutableUiState = MutableStateFlow(AnkiUiState())
    val uiState: StateFlow<AnkiUiState> = mutableUiState

    fun mineEntry(payload: String, context: AnkiMiningContext): Boolean = false

    fun duplicateCheck(expression: String): Boolean = false
}

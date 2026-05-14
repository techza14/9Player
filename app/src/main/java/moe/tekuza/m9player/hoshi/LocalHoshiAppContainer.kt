package moe.tekuza.m9player.hoshi

import androidx.compose.runtime.staticCompositionLocalOf
import moe.tekuza.m9player.hoshi.features.anki.AnkiRepository

class HoshiAppContainer(
    val ankiRepository: AnkiRepository = AnkiRepository(),
)

val LocalHoshiAppContainer = staticCompositionLocalOf { HoshiAppContainer() }

package moe.tekuza.m9player.hoshi.features.audio

import android.content.Context
import android.webkit.WebResourceResponse

data class AudioSource(
    val name: String = "",
    val url: String,
    val isEnabled: Boolean = true,
    val isDefault: Boolean = false,
)

enum class AudioPlaybackMode(val rawValue: String, val displayName: String) {
    Interrupt("interrupt", "Interrupt"),
    Duck("duck", "Lower Volume"),
    Mix("mix", "Keep Volume");

    companion object {
        fun fromRawValue(value: String?): AudioPlaybackMode =
            entries.firstOrNull { it.rawValue == value } ?: Interrupt
    }
}

data class AudioSettings(
    val audioSources: List<AudioSource> = listOf(DefaultAudioSource),
    val enableLocalAudio: Boolean = false,
    val enableAutoplay: Boolean = false,
    val playbackMode: AudioPlaybackMode = AudioPlaybackMode.Interrupt,
) {
    val enabledAudioSourceUrls: List<String>
        get() = audioSources.filter { it.isEnabled }.map { it.url }

    companion object {
        val DefaultAudioSource = AudioSource(
            name = "Default",
            url = "https://hoshi-reader.manhhaoo-do.workers.dev/?term={term}&reading={reading}",
            isDefault = true,
        )
    }
}

class LocalAudioRepository {
    companion object {
        fun fromContext(context: Context): LocalAudioRepository = LocalAudioRepository()
    }
}

class AudioRequestHandler(private val repository: LocalAudioRepository) {
    fun shouldIntercept(url: String): WebResourceResponse? = null

    fun handleAudioRequest(url: String): WebResourceResponse? = null
}

class WordAudioPlayer private constructor() {
    fun play(url: String, mode: AudioPlaybackMode) = Unit
    fun stop() = Unit

    companion object {
        private val instance = WordAudioPlayer()
        fun get(context: Context): WordAudioPlayer = instance
    }
}

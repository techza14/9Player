package moe.tekuza.m9player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class PersistedDictionaryRef(
    val uri: String,
    val name: String,
    val cacheKey: String? = null
)

internal data class PersistedReaderBook(
    val id: String,
    val title: String,
    val audioUri: String,
    val audioName: String,
    val srtUri: String?,
    val srtName: String?
)

internal data class PersistedImports(
    val audioUri: String?,
    val audioName: String?,
    val srtUri: String?,
    val srtName: String?,
    val audiobookFolderUri: String? = null,
    val audiobookFolderName: String? = null,
    val autoMoveToAudiobookFolder: Boolean = true,
    val importOnboardingCompleted: Boolean = false,
    val books: List<PersistedReaderBook> = emptyList(),
    val selectedBookId: String? = null,
    val homeLibraryView: String = "BOOKSHELF",
    val dictionaries: List<PersistedDictionaryRef>
)

private const val PREFS_NAME = "reader_sync_imports"
private const val KEY_STATE_JSON = "state_json"

internal fun loadPersistedImports(context: Context): PersistedImports {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_STATE_JSON, null) ?: return PersistedImports(
        audioUri = null,
        audioName = null,
        srtUri = null,
        srtName = null,
        audiobookFolderUri = null,
        audiobookFolderName = null,
        autoMoveToAudiobookFolder = true,
        importOnboardingCompleted = false,
        books = emptyList(),
        selectedBookId = null,
        homeLibraryView = "BOOKSHELF",
        dictionaries = emptyList()
    )

    val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return PersistedImports(
        audioUri = null,
        audioName = null,
        srtUri = null,
        srtName = null,
        audiobookFolderUri = null,
        audiobookFolderName = null,
        autoMoveToAudiobookFolder = true,
        importOnboardingCompleted = false,
        books = emptyList(),
        selectedBookId = null,
        homeLibraryView = "BOOKSHELF",
        dictionaries = emptyList()
    )

    val dictionaries = mutableListOf<PersistedDictionaryRef>()
    val array = obj.optJSONArray("dictionaries") ?: JSONArray()
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val uri = item.optString("uri").trim()
        if (uri.isBlank()) continue
        val name = item.optString("name").trim()
        val cacheKey = item.optString("cacheKey").trim().ifBlank { null }
        dictionaries += PersistedDictionaryRef(uri = uri, name = name, cacheKey = cacheKey)
    }

    val books = mutableListOf<PersistedReaderBook>()
    val booksArray = obj.optJSONArray("books") ?: JSONArray()
    for (i in 0 until booksArray.length()) {
        val item = booksArray.optJSONObject(i) ?: continue
        val id = item.optString("id").trim()
        val audioUri = item.optString("audioUri").trim()
        val srtUri = item.optString("srtUri").trim().ifBlank { null }
        if (audioUri.isBlank()) continue
        val audioName = item.optString("audioName").trim().ifBlank { "Unknown audio" }
        val srtName = item.optString("srtName").trim().ifBlank { null }
        val title = item.optString("title").trim().ifBlank { audioName.substringBeforeLast('.') }
        books += PersistedReaderBook(
            id = id.ifBlank { "$audioUri|${srtUri.orEmpty()}" },
            title = title.ifBlank { "Untitled Book" },
            audioUri = audioUri,
            audioName = audioName,
            srtUri = srtUri,
            srtName = srtName
        )
    }

    return PersistedImports(
        audioUri = obj.optString("audioUri").trim().ifBlank { null },
        audioName = obj.optString("audioName").trim().ifBlank { null },
        srtUri = obj.optString("srtUri").trim().ifBlank { null },
        srtName = obj.optString("srtName").trim().ifBlank { null },
        audiobookFolderUri = obj.optString("audiobookFolderUri").trim().ifBlank { null },
        audiobookFolderName = obj.optString("audiobookFolderName").trim().ifBlank { null },
        autoMoveToAudiobookFolder = obj.optBoolean("autoMoveToAudiobookFolder", true),
        importOnboardingCompleted = obj.optBoolean("importOnboardingCompleted", false),
        books = books,
        selectedBookId = obj.optString("selectedBookId").trim().ifBlank { null },
        homeLibraryView = obj.optString("homeLibraryView").trim().ifBlank { "BOOKSHELF" },
        dictionaries = dictionaries
    )
}

internal fun savePersistedImports(context: Context, state: PersistedImports) {
    val obj = JSONObject().apply {
        put("audioUri", state.audioUri ?: "")
        put("audioName", state.audioName ?: "")
        put("srtUri", state.srtUri ?: "")
        put("srtName", state.srtName ?: "")
        put("audiobookFolderUri", state.audiobookFolderUri ?: "")
        put("audiobookFolderName", state.audiobookFolderName ?: "")
        put("autoMoveToAudiobookFolder", state.autoMoveToAudiobookFolder)
        put("importOnboardingCompleted", state.importOnboardingCompleted)
        put(
            "books",
            JSONArray().apply {
                state.books.forEach { book ->
                    put(JSONObject().apply {
                        put("id", book.id)
                        put("title", book.title)
                        put("audioUri", book.audioUri)
                        put("audioName", book.audioName)
                        put("srtUri", book.srtUri ?: "")
                        put("srtName", book.srtName ?: "")
                    })
                }
            }
        )
        put("selectedBookId", state.selectedBookId ?: "")
        put("homeLibraryView", state.homeLibraryView)
        put(
            "dictionaries",
            JSONArray().apply {
                state.dictionaries.forEach { ref ->
                    put(JSONObject().apply {
                        put("uri", ref.uri)
                        put("name", ref.name)
                        put("cacheKey", ref.cacheKey ?: "")
                    })
                }
            }
        )
    }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_STATE_JSON, obj.toString())
        .apply()
}


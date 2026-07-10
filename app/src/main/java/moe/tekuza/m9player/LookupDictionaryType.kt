package moe.tekuza.m9player

internal enum class LookupDictionaryType {
    JMDICT,
    OTHER
}

internal fun inferLookupDictionaryType(
    dictionaryName: String,
    dictionaryFormat: String? = null
): LookupDictionaryType {
    val format = dictionaryFormat.orEmpty().lowercase()
    val name = dictionaryName.lowercase()
    return when {
        name.contains("jmdict") || name.contains("明鏡") || name.contains("yomitan") || format.contains("yomitan") -> {
            LookupDictionaryType.JMDICT
        }
        else -> LookupDictionaryType.OTHER
    }
}

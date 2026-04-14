package moe.tekuza.m9player

internal enum class LookupDictionaryType {
    MDICT,
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
        format.contains("mdx") || format.contains("mdict") || name.endsWith(".mdx") || name.contains("mdict") -> {
            LookupDictionaryType.MDICT
        }
        name.contains("jmdict") || name.contains("明鏡") || name.contains("yomitan") || format.contains("yomitan") -> {
            LookupDictionaryType.JMDICT
        }
        else -> LookupDictionaryType.OTHER
    }
}

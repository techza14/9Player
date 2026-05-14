package moe.tekuza.m9player

import java.security.MessageDigest
import java.util.Locale

internal fun buildDictionaryCacheKey(uri: String, displayName: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$uri|$displayName".toByteArray(Charsets.UTF_8))
    return digest.take(16).joinToString("") { b -> "%02x".format(Locale.US, b) }
}


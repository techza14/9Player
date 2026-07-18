package moe.tekuza.m9player.hoshi.features.dictionary

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.net.URI

internal fun externalBrowserUrl(rawUrl: String): String? {
    val url = rawUrl.trim()
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    return when (uri.scheme?.lowercase()) {
        "http", "https" -> url.takeIf { !uri.host.isNullOrBlank() }
        "mailto", "tel" -> url.takeIf { !uri.schemeSpecificPart.isNullOrBlank() }
        else -> null
    }
}

internal fun Context.openPopupExternalLink(rawUrl: String): Boolean {
    val url = externalBrowserUrl(rawUrl) ?: return false
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        if (this@openPopupExternalLink !is Activity) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    return runCatching {
        startActivity(intent)
        true
    }.getOrElse { error ->
        Log.w("PopupExternalLink", "Unable to open popup link: $url", error)
        false
    }
}

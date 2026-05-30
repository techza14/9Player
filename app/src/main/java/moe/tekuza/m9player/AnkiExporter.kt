package moe.tekuza.m9player

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.ichi2.anki.api.AddContentApi

internal const val ANKI_PACKAGE_NAME = "com.ichi2.anki"
internal const val ANKI_DEBUG_PACKAGE_NAME = "com.ichi2.anki.debug"
internal const val ANKI_READ_WRITE_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

internal enum class AnkiAvailabilityState {
    NOT_INSTALLED,
    API_UNAVAILABLE,
    PERMISSION_MISSING,
    READY
}

internal data class AnkiDuplicateCheckResult(
    val noteIds: List<Long> = emptyList(),
    val allowAdd: Boolean = false
) {
    val duplicate: Boolean get() = noteIds.isNotEmpty()
    val preventAdd: Boolean get() = duplicate && !allowAdd
}

private fun tryStartIntent(context: Context, intent: Intent): Boolean {
    return try {
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            true
        } else {
            false
        }
    } catch (_: ActivityNotFoundException) {
        false
    }
}

internal fun resolveAnkiPackageName(context: Context): String? {
    return AddContentApi.getAnkiDroidPackageName(context)
        ?: findInstalledAnkiPackage(context.packageManager)
}

internal fun requireAnkiPackageName(context: Context): String {
    return resolveAnkiPackageName(context)
        ?: throw IllegalStateException(ankiAvailabilityErrorMessage(context) ?: "AnkiDroid is not installed")
}

internal fun isAnkiInstalled(context: Context): Boolean {
    return resolveAnkiPackageName(context) != null
}

internal fun detectAnkiAvailability(
    context: Context,
    requirePermission: Boolean = false
): AnkiAvailabilityState {
    val installedPackage = findInstalledAnkiPackage(context.packageManager)
    if (installedPackage == null) return AnkiAvailabilityState.NOT_INSTALLED
    if (AddContentApi.getAnkiDroidPackageName(context).isNullOrBlank()) {
        return AnkiAvailabilityState.API_UNAVAILABLE
    }
    if (requirePermission && !hasAnkiReadWritePermission(context)) {
        return AnkiAvailabilityState.PERMISSION_MISSING
    }
    return AnkiAvailabilityState.READY
}

internal fun ankiAvailabilityErrorMessage(
    context: Context,
    requirePermission: Boolean = false
): String? {
    return when (detectAnkiAvailability(context, requirePermission = requirePermission)) {
        AnkiAvailabilityState.NOT_INSTALLED -> context.getString(R.string.error_anki_not_installed)
        AnkiAvailabilityState.API_UNAVAILABLE -> context.getString(R.string.error_anki_api_unavailable)
        AnkiAvailabilityState.PERMISSION_MISSING -> context.getString(R.string.error_anki_permission_required)
        AnkiAvailabilityState.READY -> null
    }
}

internal fun ankiAvailabilityUiMessage(
    context: Context,
    requirePermission: Boolean = false
): String? {
    return when (detectAnkiAvailability(context, requirePermission = requirePermission)) {
        AnkiAvailabilityState.NOT_INSTALLED -> context.getString(R.string.anki_not_installed)
        AnkiAvailabilityState.API_UNAVAILABLE -> context.getString(R.string.anki_api_unavailable)
        AnkiAvailabilityState.PERMISSION_MISSING -> context.getString(R.string.anki_authorize_first)
        AnkiAvailabilityState.READY -> null
    }
}

internal fun hasAnkiReadWritePermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        ANKI_READ_WRITE_PERMISSION
    ) == PackageManager.PERMISSION_GRANTED
}

internal fun openAnkiDroidApp(context: Context): Boolean {
    val packageManager = context.packageManager
    val targetPackage = resolveAnkiPackageName(context)
        ?: installedAnkiPackageCandidates().firstOrNull { packageName ->
            packageManager.getLaunchIntentForPackage(packageName) != null
        }
        ?: return false
    val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage) ?: return false
    return runCatching {
        context.startActivity(
            launchIntent.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        true
    }.getOrDefault(false)
}

internal fun buildAnkiDuplicateNoteSearchQuery(noteIds: List<Long>): String {
    return noteIds
        .filter { it > 0L }
        .distinct()
        .joinToString(" or ") { noteId -> "nid:$noteId" }
}

internal fun openAnkiDuplicateNotesInBrowser(context: Context, noteIds: List<Long>): Boolean {
    val query = buildAnkiDuplicateNoteSearchQuery(noteIds)
    if (query.isBlank()) return false
    val targetPackage = resolveAnkiPackageName(context)
        ?: installedAnkiPackageCandidates().firstOrNull { packageName ->
            context.packageManager.getLaunchIntentForPackage(packageName) != null
        }
        ?: return false

    val cardBrowserIntent = Intent().apply {
        component = ComponentName(targetPackage, "com.ichi2.anki.CardBrowser")
        putExtra("search_query", query)
        putExtra("all_decks", true)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (tryStartIntent(context, cardBrowserIntent)) return true

    val deepLinkIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("anki://x-callback-url/browser")
            .buildUpon()
            .appendQueryParameter("search", query)
            .build()
    ).apply {
        setPackage(targetPackage)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (tryStartIntent(context, deepLinkIntent)) return true

    val processTextIntent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
        type = "text/plain"
        component = ComponentName(targetPackage, "com.ichi2.anki.CardBrowserContextMenuAction")
        putExtra(Intent.EXTRA_PROCESS_TEXT, query)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return tryStartIntent(context, processTextIntent)
}

internal fun createAnkiPermissionRequestIntent(context: Context): Intent? {
    val packageManager = context.packageManager
    val targetPackage = resolveAnkiPackageName(context)
        ?: findInstalledAnkiPackage(packageManager)
        ?: return null
    return Intent("com.ichi2.anki.api.action.REQUEST_PERMISSION").apply {
        `package` = targetPackage
        putExtra("com.ichi2.anki.api.extra.PERMISSION", "READ_WRITE")
        putExtra("permission", "READ_WRITE")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
        .takeIf { it.resolveActivity(packageManager) != null }
}

private fun installedAnkiPackageCandidates(): List<String> {
    return listOf(ANKI_PACKAGE_NAME, ANKI_DEBUG_PACKAGE_NAME)
}

private fun findInstalledAnkiPackage(packageManager: PackageManager): String? {
    return installedAnkiPackageCandidates().firstOrNull { packageName ->
        runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }.isSuccess
    }
}


package moe.tekuza.m9player

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.ichi2.anki.api.AddContentApi
import java.util.Locale

internal const val ANKI_PACKAGE_NAME = "com.ichi2.anki"
internal const val ANKI_DEBUG_PACKAGE_NAME = "com.ichi2.anki.debug"
internal const val ANKI_READ_WRITE_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

internal enum class AnkiAvailabilityState {
    NOT_INSTALLED,
    API_UNAVAILABLE,
    PERMISSION_MISSING,
    READY
}

internal fun exportToAnkiDroid(context: Context, card: MinedCard) {
    val availabilityError = ankiAvailabilityErrorMessage(context, requirePermission = false)
    if (availabilityError != null) {
        throw IllegalStateException(availabilityError)
    }

    val textPayload = buildAnkiPayload(card)
    val mediaUris = buildList {
        card.audioUri?.let { add(it) }
    }

    val primaryIntent = when {
        mediaUris.size > 1 -> Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(mediaUris))
        }

        mediaUris.size == 1 -> Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, mediaUris.first())
        }

        else -> Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
        }
    }.apply {
        setPackage(requireAnkiPackageName(context))
        putExtra(Intent.EXTRA_SUBJECT, card.word)
        putExtra(Intent.EXTRA_TEXT, textPayload)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    if (mediaUris.isNotEmpty()) {
        val clipData = ClipData.newUri(context.contentResolver, "anki-media", mediaUris.first())
        mediaUris.drop(1).forEach { clipData.addItem(ClipData.Item(it)) }
        primaryIntent.clipData = clipData
        primaryIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    if (tryStartIntent(context, primaryIntent)) return

    val textOnlyIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        setPackage(requireAnkiPackageName(context))
        putExtra(Intent.EXTRA_SUBJECT, card.word)
        putExtra(Intent.EXTRA_TEXT, textPayload)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (tryStartIntent(context, textOnlyIntent)) return

    throw IllegalStateException(
        "AnkiDroid is installed, but current share intent was rejected. Open AnkiDroid once and retry."
    )
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

private fun buildAnkiPayload(card: MinedCard): String {
    return buildString {
        appendLine("Word: ${card.word}")
        if (!card.reading.isNullOrBlank()) appendLine("Reading: ${card.reading}")
        appendLine("Sentence: ${card.sentence}")
        if (card.definitions.isNotEmpty()) {
            appendLine("Definitions:")
            card.definitions.take(5).forEachIndexed { index, definition ->
                appendLine("${index + 1}. $definition")
            }
        }
        if (!card.pitch.isNullOrBlank()) appendLine("Pitch: ${card.pitch}")
        if (!card.frequency.isNullOrBlank()) appendLine("Frequency: ${card.frequency}")
        appendLine("Audio Window: ${formatCardTime(card.cueStartMs)} - ${formatCardTime(card.cueEndMs)}")
    }.trim()
}

private fun formatCardTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}


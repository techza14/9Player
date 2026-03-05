package com.example.tset

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.util.Locale

internal const val ANKI_PACKAGE_NAME = "com.ichi2.anki"
internal const val ANKI_READ_WRITE_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

internal fun exportToAnkiDroid(context: Context, card: MinedCard) {
    if (!isAnkiInstalled(context)) {
        throw IllegalStateException("AnkiDroid is not installed")
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
        setPackage(ANKI_PACKAGE_NAME)
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
        setPackage(ANKI_PACKAGE_NAME)
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

internal fun isAnkiInstalled(context: Context): Boolean {
    return try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(ANKI_PACKAGE_NAME, 0)
        true
    } catch (_: Exception) {
        false
    }
}

internal fun hasAnkiReadWritePermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        ANKI_READ_WRITE_PERMISSION
    ) == PackageManager.PERMISSION_GRANTED
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

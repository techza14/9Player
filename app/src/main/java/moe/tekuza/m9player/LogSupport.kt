package moe.tekuza.m9player

import android.util.Log

internal inline fun logDebug(tag: String, message: () -> String) {
    if (Log.isLoggable(tag, Log.DEBUG)) {
        Log.d(tag, message())
    }
}

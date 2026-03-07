package com.tekuza.p9player

import android.content.Context
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

internal fun requestShizukuBinder(context: Context) {
    runCatching { ShizukuProvider.requestBinderForNonProviderProcess(context) }
}

internal suspend fun waitForShizukuBinder(
    context: Context,
    timeoutMs: Long = 2_000L
): Boolean {
    requestShizukuBinder(context)
    if (Shizuku.pingBinder()) return true
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < timeoutMs) {
        delay(120L)
        if (Shizuku.pingBinder()) return true
    }
    return false
}


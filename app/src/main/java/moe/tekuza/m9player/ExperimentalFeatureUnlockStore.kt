package moe.tekuza.m9player

import android.content.Context

@Volatile
private var mdxUnlockedForCurrentProcess: Boolean = false

internal fun loadMdxExperimentalUnlocked(context: Context): Boolean {
    return mdxUnlockedForCurrentProcess
}

internal fun saveMdxExperimentalUnlocked(context: Context, unlocked: Boolean) {
    mdxUnlockedForCurrentProcess = unlocked
}

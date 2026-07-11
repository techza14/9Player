package moe.tekuza.m9player

import android.content.Context

private const val WEARABLE_FEATURE_PREFS = "wearable_feature"
private const val WEARABLE_FEATURE_ENABLED = "enabled"

internal fun loadWearableFeatureEnabled(context: Context): Boolean =
    context.getSharedPreferences(WEARABLE_FEATURE_PREFS, Context.MODE_PRIVATE)
        .getBoolean(WEARABLE_FEATURE_ENABLED, false)

internal fun saveWearableFeatureEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(WEARABLE_FEATURE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(WEARABLE_FEATURE_ENABLED, enabled)
        .apply()
    if (!enabled) {
        stopWearableBridgeService(context)
    } else if (BookReaderPlaybackSession.currentAudioUri() != null) {
        startWearableBridgeService(context)
    }
}

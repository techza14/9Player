package moe.tekuza.m9player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.max

internal data class BitmapBounds(
    val width: Int,
    val height: Int
)

internal fun decodeBitmapBounds(bytes: ByteArray): BitmapBounds? {
    if (bytes.isEmpty()) return null
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    val width = options.outWidth
    val height = options.outHeight
    return if (width > 0 && height > 0) BitmapBounds(width, height) else null
}

internal fun decodeSampledBitmap(
    bytes: ByteArray,
    targetWidthPx: Int,
    targetHeightPx: Int,
    preferredConfig: Bitmap.Config = Bitmap.Config.ARGB_8888
): Bitmap? {
    if (bytes.isEmpty()) return null
    val safeTargetWidth = targetWidthPx.coerceAtLeast(1)
    val safeTargetHeight = targetHeightPx.coerceAtLeast(1)
    val bounds = decodeBitmapBounds(bytes) ?: return null
    val sampleSize = calculateBitmapInSampleSize(
        sourceWidth = bounds.width,
        sourceHeight = bounds.height,
        targetWidth = safeTargetWidth,
        targetHeight = safeTargetHeight
    )
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = preferredConfig
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

private fun calculateBitmapInSampleSize(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int
): Int {
    var sampleSize = 1
    if (sourceHeight > targetHeight || sourceWidth > targetWidth) {
        var halfHeight = sourceHeight / 2
        var halfWidth = sourceWidth / 2
        while (
            halfHeight / sampleSize >= targetHeight &&
            halfWidth / sampleSize >= targetWidth
        ) {
            sampleSize *= 2
        }
    }
    val maxDecodedPixels = max(targetWidth.toLong() * targetHeight.toLong(), 1L)
    while (
        (sourceWidth / sampleSize).toLong() * (sourceHeight / sampleSize).toLong() >
        maxDecodedPixels * 2L
    ) {
        sampleSize *= 2
    }
    return sampleSize.coerceAtLeast(1)
}

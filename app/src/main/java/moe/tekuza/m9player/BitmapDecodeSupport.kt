package moe.tekuza.m9player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import java.nio.ByteBuffer
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
    targetHeightPx: Int
): Bitmap? {
    if (bytes.isEmpty()) return null
    return runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val sample = imageSampleSize(
                srcWidth = info.size.width,
                srcHeight = info.size.height,
                targetWidthPx = targetWidthPx,
                targetHeightPx = targetHeightPx
            )
            if (sample > 1) {
                decoder.setTargetSampleSize(sample)
            }
        }
    }.getOrNull()
}

/**
 * Computes a power-of-two sample size for an aspect-ratio-preserving downscale.
 *
 * Unlike [ImageDecoder.setTargetSize], which decodes to the EXACT requested size and
 * therefore stretches/squashes the image whenever the target rectangle's aspect ratio
 * differs from the source (the previous behavior that flattened tall images), the
 * sample-size path keeps the original proportions. The sample is chosen so that the
 * decoded size never exceeds the target size in either dimension.
 */
private fun imageSampleSize(
    srcWidth: Int,
    srcHeight: Int,
    targetWidthPx: Int,
    targetHeightPx: Int
): Int {
    if (srcWidth <= 0 || srcHeight <= 0) return 1
    val widthRatio = srcWidth.toFloat() / targetWidthPx.coerceAtLeast(1).toFloat()
    val heightRatio = srcHeight.toFloat() / targetHeightPx.coerceAtLeast(1).toFloat()
    val maxRatio = max(widthRatio, heightRatio)
    if (maxRatio <= 1f) return 1
    var sample = 1
    while (sample < maxRatio) {
        sample = sample shl 1
    }
    return sample
}

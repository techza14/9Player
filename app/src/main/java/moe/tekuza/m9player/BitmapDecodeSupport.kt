package moe.tekuza.m9player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import java.nio.ByteBuffer

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
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(targetWidthPx.coerceAtLeast(1), targetHeightPx.coerceAtLeast(1))
        }
    }.getOrNull()
}

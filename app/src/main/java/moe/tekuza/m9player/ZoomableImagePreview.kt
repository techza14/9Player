package moe.tekuza.m9player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil3.request.ImageRequest
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable

@Composable
internal fun ZoomableImagePreview(
    imageBytes: ByteArray,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val zoomableState = rememberZoomableState(
        zoomSpec = ZoomSpec(maxZoomFactor = 4f),
    )
    val request = remember(imageBytes, context) {
        ImageRequest.Builder(context)
            .data(imageBytes)
            .build()
    }
    ZoomableAsyncImage(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .zoomable(state = zoomableState),
        model = request,
        contentDescription = null,
    )
}

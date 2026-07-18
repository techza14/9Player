package moe.tekuza.m9player.hoshi.features.dictionary

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.graphics.toArgb
import moe.tekuza.m9player.ui.theme.TsetTheme

internal class HoshiImagePreviewActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val imageUrl = intent.getStringExtra(EXTRA_IMAGE_URL).orEmpty()
        if (imageUrl.isBlank()) {
            finish()
            return
        }
        configureFullScreenWindow()
        setContent {
            TsetTheme {
                HoshiImagePreviewScreen(
                    imageUrl = imageUrl,
                    onClose = ::finish,
                )
            }
        }
    }

    private fun configureFullScreenWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawableResource(android.R.color.black)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        window.statusBarColor = Color.Black.toArgb()
        window.navigationBarColor = Color.Black.toArgb()
        window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        }
    }

    companion object {
        private const val EXTRA_IMAGE_URL = "extra_image_url"

        internal fun launch(context: Context, imageUrl: String): Boolean {
            if (!isHoshiPreviewImageCandidate(imageUrl)) return false
            return runCatching {
                val intent = Intent(context, HoshiImagePreviewActivity::class.java)
                    .putExtra(EXTRA_IMAGE_URL, imageUrl)
                if (context !is Activity) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        }
    }
}

internal fun Context.openHoshiImagePreview(imageUrl: String): Boolean =
    HoshiImagePreviewActivity.launch(this, imageUrl)

@Composable
private fun HoshiImagePreviewScreen(
    imageUrl: String,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HoshiImagePreview(
            imageUrl = imageUrl,
            onUnavailable = onClose,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

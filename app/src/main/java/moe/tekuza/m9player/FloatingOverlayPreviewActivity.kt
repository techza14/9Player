package moe.tekuza.m9player

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import moe.tekuza.m9player.ui.theme.TsetTheme

class FloatingOverlayPreviewActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialMode = intent.getStringExtra(EXTRA_INITIAL_MODE)
            ?.let { runCatching { FloatingOverlayMode.valueOf(it) }.getOrNull() }
            ?.takeIf { it != FloatingOverlayMode.OFF }
            ?: FloatingOverlayMode.SUBTITLE
        setContent {
            TsetTheme {
                FloatingOverlayPreviewRoute(
                    initialMode = initialMode,
                    onClose = { mode ->
                        setResult(
                            Activity.RESULT_OK,
                            Intent().putExtra(EXTRA_RESULT_MODE, mode.name)
                        )
                        finish()
                    }
                )
            }
        }
    }

    companion object {
        const val EXTRA_INITIAL_MODE = "extra_initial_mode"
        const val EXTRA_RESULT_MODE = "extra_result_mode"
    }
}

@Composable
private fun FloatingOverlayPreviewRoute(
    initialMode: FloatingOverlayMode,
    onClose: (FloatingOverlayMode) -> Unit
) {
    val modes = remember {
        FloatingOverlayMode.entries.filter { it != FloatingOverlayMode.OFF }
    }
    var modeIndex by rememberSaveable {
        mutableIntStateOf(modes.indexOf(initialMode).coerceAtLeast(0))
    }
    var dragAccumulated by remember { mutableFloatStateOf(0f) }
    val mode = modes[modeIndex.coerceIn(0, modes.lastIndex)]
    val context = LocalContext.current
    val previousSubtitle = remember { BookReaderFloatingBridge.currentSubtitle() }

    BackHandler {
        onClose(mode)
    }

    DisposableEffect(Unit) {
        BookReaderFloatingBridge.notifySubtitle("test")
        onDispose {
            BookReaderFloatingBridge.notifySubtitle(previousSubtitle)
        }
    }

    LaunchedEffect(mode) {
        saveAudiobookFloatingOverlayMode(context, mode)
        refreshAudiobookFloatingOverlayService(context)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFDFE3E9))
            .pointerInput(modeIndex) {
                detectTapGestures(
                    onDoubleTap = { onClose(mode) }
                )
            }
            .pointerInput(modeIndex) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, dragAmount ->
                        dragAccumulated += dragAmount
                    },
                    onDragEnd = {
                        when {
                            dragAccumulated <= -80f && modeIndex < modes.lastIndex -> modeIndex += 1
                            dragAccumulated >= 80f && modeIndex > 0 -> modeIndex -= 1
                        }
                        dragAccumulated = 0f
                    },
                    onDragCancel = {
                        dragAccumulated = 0f
                    }
                )
            }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(20.dp)
                .background(Color(0xFFDFE3E9), RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = overlayModeLabel(context, mode),
                color = Color(0xFF0F172A),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "左右滑动切换真实悬浮组件，双击退出并应用",
                color = Color(0xCC0F172A),
                style = MaterialTheme.typography.bodyMedium
            )
            LinearProgressIndicator(
                progress = { (modeIndex + 1).toFloat() / modes.size.toFloat() },
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF3B82F6),
                trackColor = Color(0x334A6FA5)
            )
            Text(
                text = "${modeIndex + 1}/${modes.size}",
                color = Color(0x990F172A),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

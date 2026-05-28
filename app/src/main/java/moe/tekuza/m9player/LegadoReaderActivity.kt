package moe.tekuza.m9player

import android.app.ActivityManager
import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import moe.tekuza.m9player.legado.reader.M9PageAnim
import moe.tekuza.m9player.legado.reader.M9ReadBookConfig
import moe.tekuza.m9player.legado.reader.M9LayoutMode
import moe.tekuza.m9player.legado.reader.M9TextWeight
import moe.tekuza.m9player.legado.reader.SearchMenu
import moe.tekuza.m9player.legado.reader.config.MoreConfigDialog
import moe.tekuza.m9player.legado.reader.config.MoreConfigState
import moe.tekuza.m9player.legado.reader.config.ReadStyleColorItem
import moe.tekuza.m9player.legado.reader.config.ReadStyleDialog
import moe.tekuza.m9player.legado.reader.config.ReadStyleState
import moe.tekuza.m9player.legado.reader.entities.TextPage
import moe.tekuza.m9player.legado.reader.page.ReadView
import moe.tekuza.m9player.legado.reader.provider.TextPageFactory

private const val LEGADO_READER_DEFAULT_TITLE = "吾輩は猫である"
private const val LEGADO_READER_LOG_TAG = "LegadoReader"
private const val NIGHT_BAR_BG = 0xFF677C8B.toInt()
private const val NIGHT_BOTTOM_BG = 0xFF3A3A3A.toInt()
private const val NIGHT_FLOATING_BG = 0xFF303030.toInt()
private const val NIGHT_BRIGHTNESS_BG = 0x80303030.toInt()
private const val NIGHT_ACCENT = 0xFFE36A3C.toInt()
private val LEGADO_READER_DEFAULT_PARAGRAPHS = listOf(
    "吾輩は猫である。名前はまだ無い。",
    "どこで生れたかとんと見當がつかぬ。何でも薄暗いじめじめした所でニャーニャー泣いていた事だけは記憶している。",
    "吾輩はここで始めて人間というものを見た。しかもあとで聞くとそれは書生という人間中で一番獰悪な種族であったそうだ。",
    "この書生というのは時々我々を捕えて煮て食うという話である。しかしその當時は何という考もなかったから別段恐しいとも思わなかった。"
)
private const val DEFAULT_IMAGE_PAUSE_SECONDS = 0
private const val MAX_IMAGE_PAUSE_SECONDS = 300

private data class ReaderPageAnchor(
    val chapterIndex: Int,
    val charPosition: Int
)

private data class ReaderChapterPageCacheKey(
    val chapterIndex: Int,
    val contentWidthPx: Int,
    val contentHeightPx: Int
)

private data class ReaderSearchHit(
    val chapterIndex: Int,
    val chapterTitle: String,
    val preview: String,
    val chapterPosition: Int,
    val query: String
)

private enum class CatalogMode {
    CHAPTERS,
    BOOKMARKS
}

private enum class ReaderOverflowAction(val menuId: Int) {
    PLAYER(1),
    ADD_BOOKMARK(2),
    REMOVE_RUBY(3),
    SWITCH_LAYOUT(4),
    CHARSET(5),
    HELP(6)
}

private data class ReaderImageStopTarget(
    val chapterIndex: Int,
    val imagePosition: Int
) {
    val key: String get() = "$chapterIndex:$imagePosition"
}

class LegadoReaderActivity : AppCompatActivity(), ColorPickerDialogListener {
    private lateinit var readMenu: View
    private lateinit var statusBarScrim: View
    private lateinit var navigationBarScrim: View
    private lateinit var moreSettingsPanel: MoreConfigDialog
    private lateinit var audioControlPanel: View
    private lateinit var playbackBar: View
    private lateinit var searchMenu: SearchMenu
    private lateinit var catalogPanel: View
    private lateinit var catalogListView: ListView
    private lateinit var catalogTitleView: TextView
    private lateinit var catalogSearchInputView: EditText
    private lateinit var catalogTabChaptersView: TextView
    private lateinit var catalogTabBookmarksView: TextView
    private lateinit var searchPanel: View
    private lateinit var searchInputView: EditText
    private lateinit var searchResultInfoView: TextView
    private lateinit var searchResultListView: ListView
    private lateinit var readView: ReadView
    private lateinit var readerRoot: View
    private lateinit var toolbarTitleText: TextView
    private lateinit var chapterSeekBar: SeekBar
    private lateinit var listenActionText: TextView
    private lateinit var audioPlayPauseText: TextView
    private lateinit var playbackBarToggleButton: ImageButton
    private lateinit var chapterControlRow: LinearLayout
    private lateinit var brightnessPanel: View
    private lateinit var brightnessSeekBar: SeekBar
    private lateinit var brightnessAutoButton: ImageView
    private lateinit var brightnessPositionButton: ImageView
    private lateinit var floatingSearchButton: ImageButton
    private lateinit var floatingReplaceButton: ImageButton
    private lateinit var floatingPlaybackBarButton: ImageButton
    private lateinit var floatingNightButton: ImageButton
    private var importedBook: LocalReaderBook? = null
    private var audioUri: Uri? = null
    private var srtUri: Uri? = null
    private var document: EbookDocument? = null
    private var pages: List<TextPage> = emptyList()
    private var pageIndex: Int = 0
    private var cues: List<EbookSrtCue> = emptyList()
    private var srtLoading: Boolean = false
    private var srtLoadError: String? = null
    private var loadedSrtUriText: String? = null
    private var cueMatchesByCueIndex: Map<Int, EbookCueMatch> = emptyMap()
    private var matchData: EbookMatchData? = null
    private var matchSearchWindow: Int = DEFAULT_MATCH_SEARCH_WINDOW
    private var audioCueIndex: Int = -1
    private var activeCueIndex: Int = -1
    private var textSelectionActive: Boolean = false
    private var player: ExoPlayer? = null
    private var syncJob: Job? = null
    private var reloadBookJob: Job? = null
    private var paginationJob: Job? = null
    private var chapterPreloadJob: Job? = null
    private val chapterPageCache: MutableMap<ReaderChapterPageCacheKey, List<TextPage>> = linkedMapOf()
    private var pendingAudioRestorePositionMs: Long = 0L
    private var pendingAudioRestoreDurationMs: Long = 0L
    private var lastSavedPlaybackPositionMs: Long = Long.MIN_VALUE
    private val sharedPlaybackStateListener = object : BookReaderFloatingBridge.PlaybackStateListener {
        override fun onPlaybackStateChanged(isPlaying: Boolean) {
            runOnUiThread { updateAudioControlLabels() }
        }
    }
    private val sharedPlaybackPositionListener = object : BookReaderFloatingBridge.PlaybackPositionListener {
        override fun onPlaybackPositionChanged(positionMs: Long) {
            runOnUiThread {
                if (audioUri != null && isAudioPlaying()) {
                    syncToAudioPosition()
                }
            }
        }
    }
    private var preferredCharsetName: String? = null
    private var searchQuery: String? = null
    private var searchHits: List<ReaderSearchHit> = emptyList()
    private var searchHitIndex: Int = -1
    private var catalogMode: CatalogMode = CatalogMode.CHAPTERS
    private var catalogFilterQuery: String = ""
    private var bookmarks: MutableList<ReaderBookmark> = mutableListOf()
    private var readerTextSizeSp: Int = 20
    private var readerLineSpacingDp: Int = 8
    private var readerParagraphSpacingDp: Int = 14
    private var readerLetterSpacingDp: Int = 0
    private var readerTextWeight: M9TextWeight = M9TextWeight.NORMAL
    private var readerTypefaceIndex: Int = 0
    private var readerTypeface: Typeface? = Typeface.DEFAULT
    private var readerParagraphIndentCount: Int = 0
    private var readerPaddingDp: Int = 22
    private var readerLayoutMode: M9LayoutMode = M9LayoutMode.HORIZONTAL
    private var readerPageAnim: M9PageAnim = M9PageAnim.NONE
    private var readerStyleSelect: Int = DEFAULT_LEGADO_READER_STYLE_INDEX
    private var readerNightMode: Boolean = false
    private var readerStyleConfigs: MutableList<LegadoReaderStyleConfig> =
        defaultLegadoReaderStyleConfigs().toMutableList()
    private var readerBgColor: Int = READER_PAGE_BG
    private var readerTextColor: Int = READER_TEXT
    private var readerTipColor: Int = READER_TIP
    private var readerCueHighlightColor: Int = 0xFFFFEFF6.toInt()
    private var readerBgAlpha: Int = 100
    private var readerDarkStatusIcon: Boolean = true
    private var readerUnderline: Boolean = false
    private var readerBgAssetName: String? = null
    private var readerBgImageUri: String? = null
    private var pendingReaderStyleImageIndex: Int = -1
    private var pendingReaderColorDialogId: Int = -1
    private var pendingReaderColorSelected: ((Int) -> Unit)? = null
    private val readerStyleImagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val index = pendingReaderStyleImageIndex
        pendingReaderStyleImageIndex = -1
        if (uri == null || index !in readerStyleConfigs.indices) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        readerStyleConfigs[index] = readerStyleConfigs[index].copy(
            bgAssetName = null,
            bgImageUri = uri.toString()
        )
        selectReaderStyle(index)
    }
    private var audioStopAtMs: Long? = null
    private var hideStatusBar: Boolean = false
    private var readBodyToLh: Boolean = true
    private var hideNavigationBar: Boolean = false
    private var showBrightnessView: Boolean = true
    private var brightnessAuto: Boolean = true
    private var brightnessValue: Int = 160
    private var brightnessPanelOnRight: Boolean = false
    private var showReadTitleAddition: Boolean = true
    private var bodyTitleMode: ReaderBodyTitleMode = ReaderBodyTitleMode.LEFT
    private var bodyTitleSizeAddSp: Int = 0
    private var bodyTitleTopSpacingDp: Int = 0
    private var bodyTitleBottomSpacingDp: Int = 0
    private var headerMode: ReaderHeaderMode = ReaderHeaderMode.HIDE_WHEN_STATUS_BAR_SHOW
    private var footerMode: ReaderFooterMode = ReaderFooterMode.SHOW
    private var tipHeaderLeft: ReaderTipContent = ReaderTipContent.CHAPTER_TITLE
    private var tipHeaderMiddle: ReaderTipContent = ReaderTipContent.NONE
    private var tipHeaderRight: ReaderTipContent = ReaderTipContent.TIME
    private var tipFooterLeft: ReaderTipContent = ReaderTipContent.BOOK_NAME
    private var tipFooterMiddle: ReaderTipContent = ReaderTipContent.NONE
    private var tipFooterRight: ReaderTipContent = ReaderTipContent.PAGE_AND_TOTAL
    private var tipColorMode: ReaderTipColorMode = ReaderTipColorMode.FOLLOW_CONTENT
    private var tipDividerColorMode: ReaderTipDividerColorMode = ReaderTipDividerColorMode.DEFAULT
    private var tipDividerColor: Int = 0x1F000000
    private var useZhLayout: Boolean = true
    private var textFullJustify: Boolean = true
    private var textBottomJustify: Boolean = true
    private var clickRegionActions: List<ReadView.TapAction> = ReadView.defaultClickRegionActions()
    private var selectionPrimaryActionKey: String = ReadView.DEFAULT_SELECTION_PRIMARY_ACTION_KEY
    private var progressByChapter: Boolean = true
    private var pendingPageSeekIndex: Int? = null
    private var pendingChapterSeekIndex: Int? = null
    private var confirmSkipToChapter: Boolean = false
    private var chapterSourceMode: ReaderChapterSourceMode = ReaderChapterSourceMode.BOOK
    private var m4bChapters: List<M4bChapter> = emptyList()
    private var m4bChapterLoadJob: Job? = null
    private var keepScreenOn: Boolean = false
    private var noAnimScrollPage: Boolean = false
    private var previewImageByClick: Boolean = false
    private var disableReturnKey: Boolean = false
    private var readBarStyleFollowPage: Boolean = false
    private var playbackBarPinnedVisible: Boolean = false
    private var crossPageCueWindowEnabled: Boolean = true
    private var stopPlaybackOnImage: Boolean = false
    private var imagePauseSeconds: Int = DEFAULT_IMAGE_PAUSE_SECONDS
    private var verticalControlDirectionReversed: Boolean = false
    private var verticalProgressDirectionReversed: Boolean = false
    private var lastImageStopKey: String? = null
    private var imagePauseResumeJob: Job? = null
    private var imagePausePageIndex: Int? = null
    private var showRubyText: Boolean = true
    private var playbackBarHeightPx: Int = 0
    private var pendingRestoreAnchor: ReaderPageAnchor? = null
    private var loadedDocumentBookUriText: String? = null
    private var loadedDocumentCharsetName: String? = null
    private var floatingOverlayStartJob: Job? = null
    private var suppressFloatingOverlayOnStop: Boolean = false
    private var lastPublishedBridgeSubtitleCueIndex: Int = Int.MIN_VALUE
    private var lastPublishedBridgeSubtitleText: String? = null
    private var lastPublishedBridgeSubtitleTrackAvailable: Boolean? = null

    private fun readerString(resId: Int): String = getString(resId)

    private fun bridgeCanReturnToPlayer(): Boolean {
        return BookReaderFloatingBridge.hasController() &&
            BookReaderFloatingBridge.currentAudioUri() == audioUri?.toString()
    }

    private fun publishReaderPlaybackBridgeSnapshot(notifyState: Boolean = false) {
        val uriText = audioUri?.toString()
        BookReaderFloatingBridge.setCurrentAudioUri(uriText)
        currentReaderPlaybackKey()?.let { BookReaderFloatingBridge.setCurrentBookKey(this, it) }
        BookReaderFloatingBridge.notifyPlaybackSpeed(currentAudioPlaybackSpeed())
        currentAudioPositionMs()?.let { BookReaderFloatingBridge.notifyPlaybackPosition(it) }
        publishReaderSubtitleBridgeSnapshot(clearWhenMissing = false)
        if (notifyState) {
            BookReaderFloatingBridge.notifyPlaybackState(isAudioPlaying())
        }
    }

    private fun publishReaderSubtitleBridgeSnapshot(clearWhenMissing: Boolean) {
        val trackAvailable = cues.isNotEmpty()
        BookReaderFloatingBridge.setSubtitleTrackAvailable(trackAvailable)
        if (cues.isEmpty()) {
            BookReaderFloatingBridge.notifySubtitle(null)
            BookReaderFloatingBridge.setCurrentCue(null, null, null, null, null, null, null, null)
            logReaderSubtitleBridgePublishIfChanged(false, -1, null, force = true)
            return
        }
        val positionCueIndex = currentAudioPositionMs()
            ?.let { position -> findEbookCueIndexAtTime(cues, position) }
            ?: audioCueIndex
        val cueIndex = when {
            positionCueIndex in cues.indices -> positionCueIndex
            audioCueIndex in cues.indices -> audioCueIndex
            activeCueIndex in cues.indices -> activeCueIndex
            else -> -1
        }
        val cue = cues.getOrNull(cueIndex)
        if (cue == null) {
            if (clearWhenMissing) {
                BookReaderFloatingBridge.notifySubtitle(null)
                BookReaderFloatingBridge.setCurrentCue(null, null, null, null, null, null, null, null)
            }
            logReaderSubtitleBridgePublishIfChanged(trackAvailable, -1, null, force = clearWhenMissing)
            return
        }
        val match = cueMatchesByCueIndex[cueIndex]
        val fullSentence = match?.let(::fullCueText)?.takeIf { it.isNotBlank() } ?: cue.text
        BookReaderFloatingBridge.notifySubtitle(cue.text)
        BookReaderFloatingBridge.setCurrentCue(
            text = cue.text,
            startMs = cue.startMs,
            endMs = cue.endMs,
            bookTitle = currentReaderTitle(),
            audioUri = audioUri?.toString(),
            fullSentenceText = fullSentence,
            fullSentenceStartMs = cue.startMs,
            fullSentenceEndMs = cue.endMs
        )
        logReaderSubtitleBridgePublishIfChanged(trackAvailable, cueIndex, cue.text, force = false)
    }

    private fun logReaderSubtitleBridgePublishIfChanged(
        subtitleTrackAvailable: Boolean,
        cueIndex: Int,
        subtitle: String?,
        force: Boolean
    ) {
        val changed = force ||
            lastPublishedBridgeSubtitleCueIndex != cueIndex ||
            lastPublishedBridgeSubtitleText != subtitle ||
            lastPublishedBridgeSubtitleTrackAvailable != subtitleTrackAvailable
        if (!changed) return
        lastPublishedBridgeSubtitleCueIndex = cueIndex
        lastPublishedBridgeSubtitleText = subtitle
        lastPublishedBridgeSubtitleTrackAvailable = subtitleTrackAvailable
        Log.d(
            LEGADO_READER_LOG_TAG,
            "readerFloatingOverlay publish subtitleTrack=$subtitleTrackAvailable cueIndex=$cueIndex subtitleLen=${subtitle?.length ?: 0}"
        )
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreReaderSettings()
        importedBook = intentLocalReaderBook() ?: loadLastLocalReaderBook(this)
        restoreBookmarks()
        attachSavedAnchorIfNeeded()
        audioUri = intent.getStringExtra(EXTRA_AUDIO_URI)?.trim()?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        srtUri = intent.getStringExtra(EXTRA_SRT_URI)?.trim()?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        pendingAudioRestorePositionMs = intent.getLongExtra(EXTRA_AUDIO_POSITION_MS, -1L).coerceAtLeast(0L)
        pendingAudioRestoreDurationMs = intent.getLongExtra(EXTRA_AUDIO_DURATION_MS, -1L).coerceAtLeast(0L)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.isNavigationBarContrastEnforced = false
        volumeControlStream = AudioManager.STREAM_MUSIC
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!handleReaderBackPressed()) {
                        finish()
                    }
                }
            }
        )
        setContentView(buildLegadoReaderShell())
        applySystemUiSettings()
        updateDisplayedBookTitle()
        BookReaderFloatingBridge.attach(floatingBridgeController)
        BookReaderFloatingBridge.addPlaybackStateListener(sharedPlaybackStateListener)
        BookReaderFloatingBridge.addPlaybackPositionListener(sharedPlaybackPositionListener)
        initAudioPlayerIfNeeded()
        ensureM4bChaptersLoaded()
        readView.post { loadDisplayedBook(anchor = pendingRestoreAnchor) }
    }

    private fun handleReaderBackPressed(): Boolean {
        if (disableReturnKey) {
            setReadMenuVisible(true)
            return true
        }
        when {
            ::searchPanel.isInitialized && searchPanel.visibility == View.VISIBLE -> {
                hideSearchPanel()
                return true
            }
            ::catalogPanel.isInitialized && catalogPanel.visibility == View.VISIBLE -> {
                hideCatalogPanel()
                return true
            }
            ::searchMenu.isInitialized && searchMenu.visibility == View.VISIBLE -> {
                searchQuery = null
                searchHits = emptyList()
                searchHitIndex = -1
                hideSearchMenu()
                renderCurrentPage()
                return true
            }
            ::audioControlPanel.isInitialized && audioControlPanel.visibility == View.VISIBLE -> {
                audioControlPanel.visibility = View.GONE
                updateSystemBarSurfaces()
                return true
            }
            ::moreSettingsPanel.isInitialized && moreSettingsPanel.visibility == View.VISIBLE -> {
                moreSettingsPanel.visibility = View.GONE
                updateSystemBarSurfaces()
                return true
            }
            isReadMenuVisible() -> {
                setReadMenuVisible(false)
                return true
            }
        }
        if (returnToPlayerIfShared()) {
            return true
        }
        return false
    }

    override fun onPause() {
        persistAudioPlaybackSnapshot()
        persistReaderSettings(updateAnchor = false)
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        floatingOverlayStartJob?.cancel()
        floatingOverlayStartJob = null
        suppressFloatingOverlayOnStop = false
        Log.d(LEGADO_READER_LOG_TAG, "readerFloatingOverlay onStart stop existing overlay")
        stopAudiobookFloatingOverlayService(this)
    }

    override fun onStop() {
        super.onStop()
        floatingOverlayStartJob?.cancel()
        if (isChangingConfigurations || suppressFloatingOverlayOnStop) {
            Log.d(
                LEGADO_READER_LOG_TAG,
                "readerFloatingOverlay skip onStop changingConfig=$isChangingConfigurations suppress=$suppressFloatingOverlayOnStop"
            )
            return
        }
        val settings = loadAudiobookSettingsConfig(this)
        val overlayEnabled = settings.floatingOverlayEnabled || settings.floatingOverlaySubtitleEnabled
        publishReaderPlaybackBridgeSnapshot(notifyState = true)
        val playing = BookReaderFloatingBridge.isPlaying()
        Log.d(
            LEGADO_READER_LOG_TAG,
            "readerFloatingOverlay onStop overlayEnabled=$overlayEnabled " +
                "bubble=${settings.floatingOverlayEnabled} subtitle=${settings.floatingOverlaySubtitleEnabled} " +
                "showOnReaderExit=${settings.floatingOverlayShowOnReaderExit} playing=$playing"
        )
        if (!overlayEnabled || !playing) {
            Log.d(LEGADO_READER_LOG_TAG, "readerFloatingOverlay skip start")
            return
        }
        floatingOverlayStartJob = lifecycleScope.launch {
            delay(150L)
            val refreshed = loadAudiobookSettingsConfig(this@LegadoReaderActivity)
            val refreshedOverlayEnabled =
                refreshed.floatingOverlayEnabled || refreshed.floatingOverlaySubtitleEnabled
            val appForeground = isLegadoAppProcessInForeground()
            val shouldShowAfterReaderExit =
                refreshed.floatingOverlayShowOnReaderExit || !appForeground
            val stillPlaying = BookReaderFloatingBridge.isPlaying()
            Log.d(
                LEGADO_READER_LOG_TAG,
                "readerFloatingOverlay delayed overlayEnabled=$refreshedOverlayEnabled " +
                    "showOnReaderExit=${refreshed.floatingOverlayShowOnReaderExit} " +
                    "appForeground=$appForeground playing=$stillPlaying"
            )
            if (refreshedOverlayEnabled && shouldShowAfterReaderExit && stillPlaying) {
                Log.d(LEGADO_READER_LOG_TAG, "readerFloatingOverlay start service")
                startAudiobookFloatingOverlayService(this@LegadoReaderActivity)
            } else {
                Log.d(LEGADO_READER_LOG_TAG, "readerFloatingOverlay skip delayed start")
            }
        }
    }

    private fun intentLocalReaderBook(): LocalReaderBook? {
        val uriText = intent.getStringExtra(EXTRA_EBOOK_URI)?.trim().orEmpty()
        if (uriText.isBlank()) return null
        val title = intent.getStringExtra(EXTRA_EBOOK_TITLE)?.trim()
            ?: intent.getStringExtra(EXTRA_EBOOK_NAME)?.let(::localReaderBookTitleFromDisplayName)
            ?: LEGADO_READER_DEFAULT_TITLE
        val format = intent.getStringExtra(EXTRA_EBOOK_FORMAT)?.trim().orEmpty().ifBlank { "ebook" }
        return LocalReaderBook(
            title = title,
            uri = Uri.parse(uriText),
            format = format,
            importedAtMs = System.currentTimeMillis()
        )
    }

    private fun buildLegadoReaderShell(): View {
        val root = FrameLayout(this).apply {
            readerRoot = this
            setBackgroundColor(readerBgColor)
        }
        val contentContainer = FrameLayout(this)
        statusBarScrim = View(this).apply {
            setBackgroundColor(currentSystemBarColor())
        }
        navigationBarScrim = View(this).apply {
            setBackgroundColor(currentSystemBarColor())
        }
        root.addView(
            statusBarScrim,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                0,
                Gravity.TOP
            )
        )
        root.addView(
            navigationBarScrim,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                0,
                Gravity.BOTTOM
            )
        )
        root.addView(
            contentContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val top = if (hideStatusBar && readBodyToLh) 0 else bars.top
            val bottom = if (hideNavigationBar) 0 else bars.bottom
            contentContainer.setPadding(0, top, 0, bottom)
            (statusBarScrim.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.height = if (hideStatusBar) 0 else bars.top
                statusBarScrim.layoutParams = params
            }
            (navigationBarScrim.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.height = if (hideNavigationBar) 0 else bars.bottom
                navigationBarScrim.layoutParams = params
            }
            insets
        }

        contentContainer.addView(buildStaticPage())

        readMenu = buildReadMenu()
        contentContainer.addView(readMenu)

        searchMenu = buildSearchMenu()
        contentContainer.addView(searchMenu)

        catalogPanel = buildCatalogPanel().apply {
            visibility = View.GONE
        }
        contentContainer.addView(
            catalogPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(520),
                Gravity.BOTTOM
            )
        )

        searchPanel = buildSearchPanel().apply {
            visibility = View.GONE
        }
        contentContainer.addView(
            searchPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(520),
                Gravity.BOTTOM
            )
        )

        moreSettingsPanel = buildMoreSettingsPanel().apply {
            visibility = View.GONE
        }
        contentContainer.addView(
            moreSettingsPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(500),
                Gravity.BOTTOM
            )
        )

        audioControlPanel = buildAudioControlPanel().apply {
            visibility = View.GONE
        }
        contentContainer.addView(
            audioControlPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )

        playbackBar = buildPlaybackBar().apply {
            visibility = if (playbackBarPinnedVisible) View.VISIBLE else View.GONE
            addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                val newHeight = (bottom - top).coerceAtLeast(0)
                val oldHeight = (oldBottom - oldTop).coerceAtLeast(0)
                if (visibility == View.VISIBLE && newHeight > 0 && newHeight != oldHeight) {
                    playbackBarHeightPx = newHeight
                    applyReadViewViewportInset(reload = true)
                }
            }
        }
        contentContainer.addView(
            playbackBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )
        applyDirectionSettings()

        root.setOnClickListener {
            when {
                searchPanel.visibility == View.VISIBLE -> hideSearchPanel()
                catalogPanel.visibility == View.VISIBLE -> hideCatalogPanel()
                audioControlPanel.visibility == View.VISIBLE -> {
                    audioControlPanel.visibility = View.GONE
                    updateSystemBarSurfaces()
                }
                moreSettingsPanel.visibility == View.VISIBLE -> {
                    moreSettingsPanel.visibility = View.GONE
                    updateSystemBarSurfaces()
                }
                else -> {
                    toggleReadMenuVisibility()
                }
            }
        }
        applyBrightnessPanelPosition()
        applyBrightnessState()
        applyReadBarStyle()
        ViewCompat.requestApplyInsets(root)
        return root
    }

    private fun buildStaticPage(): View {
        return ReadView(this).apply {
            readView = this
            setReaderColors(
                readerBgColor,
                readerTextColor,
                effectiveReaderTipColor(),
                readerBgAssetName,
                readerBgImageUri,
                readerBgAlpha
            )
            setCueHighlightColor(readerCueHighlightColor)
            setTextSizeSp(readerTextSizeSp.toFloat())
            setTextWeight(readerTextWeight)
            setTextUnderline(readerUnderline && readerLayoutMode == M9LayoutMode.HORIZONTAL)
            setReaderTypeface(readerTypeface)
            setReaderPadding(dp(readerPaddingDp), dp(34), dp(readerPaddingDp), currentReaderBottomPaddingPx())
            setPageAnim(readerPageAnim)
            setLayoutMode(readerLayoutMode)
            setNoAnimScrollPage(noAnimScrollPage)
            setClickRegionActions(clickRegionActions)
            setShowHeaderFooter(showReadTitleAddition)
            setBookTitle(currentReaderTitle())
            applyReaderInfoConfig()
            setSelectionPrimaryActionKey(selectionPrimaryActionKey)
            onPagePreview = { delta -> pagePreviewForDelta(delta) }
            onMovePages = { delta -> movePage(delta) }
            onPrevPage = { movePage(-1) }
            onNextPage = { movePage(1) }
            onTapAction = { handleTapRegionAction(it) }
            onSelectionAction = { action, text -> handleSelectionAction(action, text) }
            onSelectionProcessText = { intent, text -> handleSelectionProcessText(intent, text) }
            onTextSelectionStateChanged = { active -> handleTextSelectionStateChanged(active) }
            onImageClick = { image ->
                if (previewImageByClick) {
                    showImagePreviewDialog(image)
                }
            }
            onMenu = {
                when {
                    audioControlPanel.visibility == View.VISIBLE -> {
                        audioControlPanel.visibility = View.GONE
                        updateSystemBarSurfaces()
                    }
                    moreSettingsPanel.visibility == View.VISIBLE -> {
                        moreSettingsPanel.visibility = View.GONE
                        updateSystemBarSurfaces()
                    }
                    else -> toggleReadMenuVisibility()
                }
            }
        }
    }

    private fun buildReadMenu(): View {
        return layoutInflater.inflate(R.layout.view_m9_legado_read_menu, null, false).apply {
            findViewById<View>(R.id.reader_menu_scrim).setOnClickListener {
                audioControlPanel.visibility = View.GONE
                moreSettingsPanel.visibility = View.GONE
                setReadMenuVisible(false)
            }
            findViewById<TextView>(R.id.reader_back).also {
                it.setOnClickListener {
                    returnToHome()
                }
            }
            findViewById<TextView>(R.id.reader_toolbar_title).also {
                toolbarTitleText = it
                it.text = currentReaderTitle()
            }
            findViewById<TextView>(R.id.reader_encoding).also {
                it.visibility = View.GONE
                it.setOnClickListener { view -> showEncodingMenu(view) }
            }
            findViewById<TextView>(R.id.reader_overflow).also {
                it.setOnClickListener { view -> showOverflowMenu(view) }
            }
            findViewById<ImageButton>(R.id.reader_search).also {
                floatingSearchButton = it
                it.setOnClickListener {
                    closeReaderChrome()
                    showSearchPanel(searchQuery.orEmpty())
                }
            }
            findViewById<ImageButton>(R.id.reader_replace).also {
                floatingReplaceButton = it
                it.setOnClickListener { showSasayakiMatchDialog() }
            }
            findViewById<ImageButton>(R.id.reader_playback_bar).also {
                floatingPlaybackBarButton = it
                it.setOnClickListener { togglePlaybackBar() }
            }
            findViewById<ImageButton>(R.id.reader_night).also {
                floatingNightButton = it
                it.setOnClickListener { toggleNightMode() }
            }
            findViewById<View>(R.id.reader_brightness).also {
                brightnessPanel = it
                it.visibility = if (showBrightnessView) View.VISIBLE else View.GONE
            }
            findViewById<SeekBar>(R.id.reader_brightness_seek).also { seekBar ->
                brightnessSeekBar = seekBar
                seekBar.max = 255
                seekBar.progress = brightnessValue.coerceIn(1, 255)
                seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            brightnessAuto = false
                            brightnessValue = progress.coerceIn(1, 255)
                            applyBrightnessState()
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        persistReaderSettings()
                    }
                })
            }
            findViewById<ImageView>(R.id.reader_brightness_auto).also {
                brightnessAutoButton = it
                it.setOnClickListener {
                    brightnessAuto = !brightnessAuto
                    applyBrightnessState()
                    persistReaderSettings()
                }
            }
            findViewById<ImageView>(R.id.reader_brightness_position).also {
                brightnessPositionButton = it
                it.setOnClickListener {
                    brightnessPanelOnRight = !brightnessPanelOnRight
                    applyBrightnessPanelPosition()
                    persistReaderSettings()
                }
            }
            findViewById<TextView>(R.id.reader_prev_chapter).setOnClickListener { moveChapter(-1) }
            findViewById<TextView>(R.id.reader_next_chapter).setOnClickListener { moveChapter(1) }
            findViewById<LinearLayout>(R.id.reader_chapter_control_row).also {
                chapterControlRow = it
            }
            findViewById<SeekBar>(R.id.reader_page_seek).also { seek ->
                chapterSeekBar = seek
                seek.max = 0
                seek.progress = 0
                seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (!fromUser || pages.isEmpty()) return
                        if (progressByChapter) {
                            pendingPageSeekIndex = progress
                        } else {
                            pendingChapterSeekIndex = progress
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {
                        pendingPageSeekIndex = null
                        pendingChapterSeekIndex = null
                    }

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        if (progressByChapter) {
                            val targetPage = pendingPageSeekIndex ?: seekBar?.progress ?: return
                            pendingPageSeekIndex = null
                            pendingChapterSeekIndex = null
                            pageIndex = seekProgressToPageIndex(targetPage)
                            activeCueIndex = -1
                            renderCurrentPage(persistAnchor = true, anchorSource = "pageSeek")
                        } else {
                            val targetChapter = pendingChapterSeekIndex ?: seekBar?.progress ?: return
                            pendingChapterSeekIndex = null
                            pendingPageSeekIndex = null
                            confirmOrJumpToChapterFromSeekBar(targetChapter)
                        }
                    }
                })
            }
            findViewById<LinearLayout>(R.id.reader_catalog).setOnClickListener {
                closeReaderChrome()
                showChapterListDialog()
            }
            findViewById<LinearLayout>(R.id.reader_listen).setOnClickListener {
                toggleAudioControlPanel()
            }
            findViewById<TextView>(R.id.reader_listen_text).also {
                listenActionText = it
            }
            findViewById<LinearLayout>(R.id.reader_style).setOnClickListener {
                showStyleDialog()
            }
            findViewById<LinearLayout>(R.id.reader_setting).setOnClickListener {
                audioControlPanel.visibility = View.GONE
                moreSettingsPanel.visibility =
                    if (moreSettingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                updateSystemBarSurfaces()
            }
        }
    }

    private fun buildPlaybackBar(): View {
        return layoutInflater.inflate(R.layout.view_reader_playback_bar, null, false).apply {
            elevation = dp(8).toFloat()
            isClickable = true
            setOnClickListener { }
            findViewById<ImageButton>(R.id.reader_playback_catalog).setOnClickListener {
                showChapterListDialog()
            }
            findViewById<ImageButton>(R.id.reader_playback_prev).setOnClickListener {
                seekToAdjacentCue(controlCueDelta(-1))
            }
            findViewById<ImageButton>(R.id.reader_playback_next).setOnClickListener {
                seekToAdjacentCue(controlCueDelta(1))
            }
            findViewById<ImageButton>(R.id.reader_playback_toggle).also {
                playbackBarToggleButton = it
                it.setOnClickListener { toggleAudioPlayback() }
            }
        }
    }

    private fun buildSearchMenu(): SearchMenu {
        return SearchMenu(this).apply {
            onPrevious = { navigateSearch(-1) }
            onNext = { navigateSearch(1) }
            onResults = { showSearchPanel(searchQuery.orEmpty()) }
            onMainMenu = {
                hideSearchMenu()
                setReadMenuVisible(true)
            }
            onExit = {
                searchQuery = null
                searchHits = emptyList()
                searchHitIndex = -1
                hideSearchMenu()
                renderCurrentPage()
            }
        }
    }

    private fun buildAudioControlPanel(): View {
        return layoutInflater.inflate(R.layout.dialog_m9_read_aloud, null, false).apply {
            elevation = dp(8).toFloat()
            isClickable = true
            setOnClickListener { }
            findViewById<ImageButton>(R.id.audio_play_prev).setOnClickListener {
                seekToAdjacentCue(controlCueDelta(-1))
            }
            findViewById<TextView>(R.id.audio_play_pause).also {
                audioPlayPauseText = it
                it.setOnClickListener { toggleAudioPlayback() }
            }
            findViewById<ImageButton>(R.id.audio_play_next).setOnClickListener {
                seekToAdjacentCue(controlCueDelta(1))
            }
            val speedValue = findViewById<TextView>(R.id.audio_speed_value)
            val speedSeek = findViewById<SeekBar>(R.id.audio_speed_seek)
            val initialSpeed = currentAudioPlaybackSpeed()
            speedValue.text = String.format(java.util.Locale.US, "%.1fx", initialSpeed)
            speedSeek.progress = ((initialSpeed - 0.5f) * 50f).toInt().coerceIn(0, speedSeek.max)
            speedSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val speed = 0.5f + progress / 50f
                        speedValue.text = String.format(java.util.Locale.US, "%.1fx", speed)
                        setAudioPlaybackSpeed(speed)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
            findViewById<TextView>(R.id.audio_timer_10).setOnClickListener { setAudioTimer(10) }
            findViewById<TextView>(R.id.audio_timer_30).setOnClickListener { setAudioTimer(30) }
            findViewById<TextView>(R.id.audio_timer_off).setOnClickListener {
                audioStopAtMs = null
                Toast.makeText(
                    this@LegadoReaderActivity,
                    readerString(R.string.reader_timer_closed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun buildMoreSettingsPanel(): MoreConfigDialog {
        return MoreConfigDialog(this).apply {
            onHideStatusBarChanged = {
                hideStatusBar = it
                applySystemUiSettings()
                applyReaderInfoConfig()
                persistReaderSettings()
            }
            onHideNavigationBarChanged = {
                hideNavigationBar = it
                applySystemUiSettings()
                persistReaderSettings()
            }
            onReadBodyToLhChanged = {
                readBodyToLh = it
                applySystemUiSettings()
                persistReaderSettings()
            }
            onShowBrightnessViewChanged = {
                showBrightnessView = it
                brightnessPanel.visibility = if (it) View.VISIBLE else View.GONE
                applyBrightnessState()
                persistReaderSettings()
            }
            onShowReadTitleAdditionChanged = {
                showReadTitleAddition = it
                applyReaderInfoConfig()
                requestBookRelayout()
            }
            onUseZhLayoutChanged = {
                useZhLayout = it
                requestBookRelayout()
                if (::moreSettingsPanel.isInitialized) moreSettingsPanel.bind(currentMoreConfigState())
            }
            onTextFullJustifyChanged = {
                textFullJustify = it
                requestBookRelayout()
            }
            onTextBottomJustifyChanged = {
                textBottomJustify = it
                requestBookRelayout()
            }
            onNoAnimScrollPageChanged = {
                noAnimScrollPage = it
                readView.setNoAnimScrollPage(it)
                persistReaderSettings()
            }
            onPreviewImageByClickChanged = {
                previewImageByClick = it
                persistReaderSettings()
            }
            onDisableReturnKeyChanged = {
                disableReturnKey = it
                persistReaderSettings()
            }
            onReadBarStyleFollowPageChanged = {
                readBarStyleFollowPage = it
                applyReadBarStyle()
                persistReaderSettings()
            }
            onScreenOrientationClicked = { showScreenOrientationDialog() }
            onKeepLightClicked = { showKeepLightDialog() }
            onProgressBehaviorClicked = { showProgressBehaviorDialog() }
            onSelectionPrimaryActionClicked = { showSelectionPrimaryActionDialog() }
            onClickRegionalConfigClicked = { showClickRegionDialog() }
            onResetDefaultsClicked = { showResetReaderDefaultsDialog() }
            bind(currentMoreConfigState())
        }
    }

    private fun currentMoreConfigState(): MoreConfigState {
        return MoreConfigState(
            screenOrientationIndex = when (requestedOrientation) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> 1
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> 2
                else -> 0
            },
            keepScreenOn = keepScreenOn,
            progressByChapter = progressByChapter,
            selectionPrimaryActionSummary = currentSelectionPrimaryActionSummary(),
            clickRegionSummary = currentClickRegionSummary(),
            hideStatusBar = hideStatusBar,
            readBodyToLh = readBodyToLh,
            hideNavigationBar = hideNavigationBar,
            showBrightnessView = showBrightnessView,
            showReadTitleAddition = showReadTitleAddition,
            useZhLayout = useZhLayout,
            useZhLayoutLabel = currentZhLayoutToggleLabel(),
            textFullJustify = textFullJustify,
            textBottomJustify = textBottomJustify,
            noAnimScrollPage = noAnimScrollPage,
            previewImageByClick = previewImageByClick,
            disableReturnKey = disableReturnKey,
            readBarStyleFollowPage = readBarStyleFollowPage
        )
    }

    private fun applySystemUiSettings() {
        WindowCompat.setDecorFitsSystemWindows(window, !(hideStatusBar || hideNavigationBar || readBodyToLh))
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val systemBarColor = currentSystemBarColor()
        val useDarkSystemBarIcons = currentSystemBarUsesDarkIcons()
        window.statusBarColor = systemBarColor
        window.navigationBarColor = systemBarColor
        controller.isAppearanceLightStatusBars = useDarkSystemBarIcons
        controller.isAppearanceLightNavigationBars = useDarkSystemBarIcons
        val hideTypes =
            (if (hideStatusBar) WindowInsetsCompat.Type.statusBars() else 0) or
                (if (hideNavigationBar) WindowInsetsCompat.Type.navigationBars() else 0)
        val showTypes =
            (if (!hideStatusBar) WindowInsetsCompat.Type.statusBars() else 0) or
                (if (!hideNavigationBar) WindowInsetsCompat.Type.navigationBars() else 0)
        if (hideTypes != 0) controller.hide(hideTypes)
        if (showTypes != 0) controller.show(showTypes)
        ViewCompat.requestApplyInsets(window.decorView)
    }

    private fun applyReadBarStyle() {
        if (!::readMenu.isInitialized) return
        val isNight = isNightReaderTheme()
        val immersiveMenu = readBarStyleFollowPage
        val menuBgColor = currentMenuBackgroundColor()
        val textColor = when {
            immersiveMenu -> readerTextColor
            isNight -> 0xFFF4F0E6.toInt()
            else -> 0xFF2C241B.toInt()
        }
        val progressColor = if (isNight) NIGHT_ACCENT else textColor
        val titleBar = readMenu.findViewById<View>(R.id.reader_title_bar)
        val bottomPanel = readMenu.findViewById<View>(R.id.reader_bottom_panel)
        titleBar.setBackgroundColor(menuBgColor)
        bottomPanel.setBackgroundColor(menuBgColor)
        applyPlaybackBarStyle()
        tintMenuContent(titleBar, textColor)
        tintMenuContent(bottomPanel, textColor)
        chapterSeekBar.thumb.setTint(progressColor)
        chapterSeekBar.progressTintList = ColorStateList.valueOf(progressColor)
        chapterSeekBar.progressBackgroundTintList = ColorStateList.valueOf(withAlpha(textColor, 0.2f))
        brightnessAutoButton.setColorFilter(if (brightnessAuto) progressColor else withAlpha(textColor, 0.55f))
        brightnessPositionButton.setColorFilter(textColor)
        brightnessPanel.background = GradientDrawable().apply {
            cornerRadius = dp(5).toFloat()
            setColor(if (isNight && !immersiveMenu) NIGHT_BRIGHTNESS_BG else withAlpha(menuBgColor, 0.5f))
        }
        if (::catalogPanel.isInitialized) {
            catalogPanel.setBackgroundColor(menuBgColor)
            tintMenuContent(catalogPanel, textColor)
            updateCatalogTabs()
            catalogSearchInputView.setTextColor(textColor)
            catalogSearchInputView.setHintTextColor(withAlpha(textColor, 0.55f))
        }
        if (::searchPanel.isInitialized) {
            searchPanel.setBackgroundColor(menuBgColor)
            tintMenuContent(searchPanel, textColor)
            searchInputView.setTextColor(textColor)
            searchInputView.setHintTextColor(withAlpha(textColor, 0.55f))
        }
        updateFabStyle(floatingSearchButton, textColor, menuBgColor)
        updateFabStyle(floatingReplaceButton, textColor, menuBgColor)
        updateFabStyle(floatingPlaybackBarButton, textColor, menuBgColor)
        updateFabStyle(floatingNightButton, textColor, menuBgColor)
        floatingNightButton.setImageResource(if (isNightReaderTheme()) R.drawable.reader_ic_daytime else R.drawable.reader_ic_brightness)
        updateSystemBarSurfaces()
    }

    private fun currentMenuBackgroundColor(): Int {
        return when {
            readBarStyleFollowPage -> readerBgColor
            isNightReaderTheme() -> NIGHT_BOTTOM_BG
            else -> 0xFFF8F1E3.toInt()
        }
    }

    private fun currentSystemBarColor(): Int {
        return if (isReaderChromeVisibleForSystemBars()) currentMenuBackgroundColor() else readerBgColor
    }

    private fun isReaderChromeVisibleForSystemBars(): Boolean {
        return isReadMenuVisible() ||
            (::catalogPanel.isInitialized && catalogPanel.visibility == View.VISIBLE) ||
            (::searchPanel.isInitialized && searchPanel.visibility == View.VISIBLE) ||
            (::audioControlPanel.isInitialized && audioControlPanel.visibility == View.VISIBLE) ||
            (::moreSettingsPanel.isInitialized && moreSettingsPanel.visibility == View.VISIBLE)
    }

    private fun currentSystemBarUsesDarkIcons(): Boolean {
        return when {
            !isReaderChromeVisibleForSystemBars() -> readerDarkStatusIcon
            readBarStyleFollowPage -> readerDarkStatusIcon
            else -> !isNightReaderTheme()
        }
    }

    private fun isReadMenuVisible(): Boolean {
        return ::readMenu.isInitialized && readMenu.visibility == View.VISIBLE
    }

    private fun setReadMenuVisible(visible: Boolean, updateSystemBars: Boolean = true) {
        if (!::readMenu.isInitialized) return
        val targetVisibility = if (visible) View.VISIBLE else View.GONE
        if (readMenu.visibility != targetVisibility) {
            readMenu.visibility = targetVisibility
        }
        if (updateSystemBars) updateSystemBarSurfaces()
    }

    private fun toggleReadMenuVisibility() {
        setReadMenuVisible(!isReadMenuVisible())
    }

    private fun updateSystemBarSurfaces() {
        applyPlaybackBarStyle()
        val systemBarColor = currentSystemBarColor()
        if (::statusBarScrim.isInitialized) {
            statusBarScrim.setBackgroundColor(systemBarColor)
        }
        if (::navigationBarScrim.isInitialized) {
            navigationBarScrim.setBackgroundColor(systemBarColor)
        }
        applySystemUiSettings()
    }

    private fun isVerticalControlDirectionReversed(): Boolean {
        return verticalControlDirectionReversed && readerLayoutMode == M9LayoutMode.VERTICAL
    }

    private fun isVerticalProgressDirectionReversed(): Boolean {
        return verticalProgressDirectionReversed && readerLayoutMode == M9LayoutMode.VERTICAL
    }

    private fun controlCueDelta(delta: Int): Int {
        return if (isVerticalControlDirectionReversed()) -delta else delta
    }

    private fun applyDirectionSettings() {
        val progressDirection = if (isVerticalProgressDirectionReversed()) {
            View.LAYOUT_DIRECTION_RTL
        } else {
            View.LAYOUT_DIRECTION_LTR
        }
        if (::chapterControlRow.isInitialized) chapterControlRow.layoutDirection = progressDirection
        if (::chapterSeekBar.isInitialized) chapterSeekBar.layoutDirection = progressDirection
    }

    private fun tintMenuContent(view: View, textColor: Int) {
        when (view) {
            is TextView -> view.setTextColor(textColor)
            is ImageView -> view.setColorFilter(textColor)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                tintMenuContent(view.getChildAt(index), textColor)
            }
        }
    }

    private fun buildCatalogPanel(): View {
        return layoutInflater.inflate(R.layout.view_reader_catalog_panel, null, false).apply {
            findViewById<TextView>(R.id.reader_catalog_title).also {
                catalogTitleView = it
            }
            findViewById<TextView>(R.id.reader_catalog_tab_chapters).also {
                catalogTabChaptersView = it
                it.setOnClickListener {
                    catalogMode = CatalogMode.CHAPTERS
                    bindCatalogList()
                }
            }
            findViewById<TextView>(R.id.reader_catalog_tab_bookmarks).also {
                catalogTabBookmarksView = it
                it.setOnClickListener {
                    catalogMode = CatalogMode.BOOKMARKS
                    bindCatalogList()
                }
            }
            findViewById<EditText>(R.id.reader_catalog_search_input).also { input ->
                catalogSearchInputView = input
                input.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        catalogFilterQuery = s?.toString().orEmpty()
                        bindCatalogList()
                    }
                })
            }
            findViewById<View>(R.id.reader_catalog_close).setOnClickListener { hideCatalogPanel() }
            findViewById<ListView>(R.id.reader_catalog_list).also {
                catalogListView = it
            }
        }
    }

    private fun buildSearchPanel(): View {
        return layoutInflater.inflate(R.layout.view_reader_search_panel, null, false).apply {
            findViewById<EditText>(R.id.reader_search_input).also { input ->
                searchInputView = input
                input.setOnEditorActionListener { _, _, _ ->
                    performSearchFromPanel()
                    true
                }
            }
            findViewById<TextView>(R.id.reader_search_result_info).also {
                searchResultInfoView = it
            }
            findViewById<ListView>(R.id.reader_search_result_list).also {
                searchResultListView = it
            }
            findViewById<View>(R.id.reader_search_submit).setOnClickListener {
                performSearchFromPanel()
            }
            findViewById<View>(R.id.reader_search_close).setOnClickListener {
                hideSearchPanel()
            }
        }
    }

    private fun updateFabStyle(button: ImageButton, iconColor: Int, bgColor: Int) {
        button.backgroundTintList = ColorStateList.valueOf(bgColor)
        button.imageTintList = ColorStateList.valueOf(iconColor)
    }

    private fun restoreBookmarks() {
        bookmarks = loadReaderBookmarks(this, readerBookmarkStoreKey()).toMutableList()
    }

    private fun persistBookmarks() {
        saveReaderBookmarks(this, readerBookmarkStoreKey(), bookmarks)
    }

    private fun readerBookmarkStoreKey(): String {
        val stable = importedBook?.uri?.toString()?.ifBlank { null } ?: currentReaderTitle()
        return "bookmark::$stable"
    }

    private fun addCurrentBookmark() {
        val page = pages.getOrNull(pageIndex) ?: run {
            Toast.makeText(this, R.string.reader_bookmark_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        if (bookmarks.any { it.chapterIndex == page.chapterIndex && it.chapterPosition == page.charStart }) {
            Toast.makeText(this, R.string.reader_bookmark_exists, Toast.LENGTH_SHORT).show()
            return
        }
        val preview = page.text
            .replace('\n', ' ')
            .trim()
            .take(60)
            .ifBlank { page.title.ifBlank { currentReaderTitle() } }
        bookmarks += ReaderBookmark(
            chapterIndex = page.chapterIndex,
            chapterPosition = page.charStart,
            chapterTitle = page.title,
            preview = preview,
            createdAtMs = System.currentTimeMillis()
        )
        bookmarks.sortBy { it.chapterIndex * 1_000_000L + it.chapterPosition }
        persistBookmarks()
        if (::catalogPanel.isInitialized && catalogPanel.visibility == View.VISIBLE && catalogMode == CatalogMode.BOOKMARKS) {
            bindCatalogList()
        }
        Toast.makeText(this, R.string.reader_bookmark_added, Toast.LENGTH_SHORT).show()
    }

    private fun applyBrightnessState() {
        if (!::brightnessSeekBar.isInitialized) return
        brightnessSeekBar.isEnabled = !brightnessAuto
        val clamped = brightnessValue.coerceIn(1, 255)
        if (brightnessSeekBar.progress != clamped) {
            brightnessSeekBar.progress = clamped
        }
        window.attributes = window.attributes.apply {
            screenBrightness = if (brightnessAuto) {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            } else {
                (clamped / 255f).coerceIn(1f / 255f, 1f)
            }
        }
        if (::brightnessAutoButton.isInitialized && ::brightnessPositionButton.isInitialized) {
            applyReadBarStyle()
        }
    }

    private fun applyBrightnessPanelPosition() {
        if (!::brightnessPanel.isInitialized) return
        (brightnessPanel.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            params.gravity = if (brightnessPanelOnRight) {
                Gravity.END or Gravity.CENTER_VERTICAL
            } else {
                Gravity.START or Gravity.CENTER_VERTICAL
            }
            brightnessPanel.layoutParams = params
        }
    }

    private fun closeReaderChrome() {
        audioControlPanel.visibility = View.GONE
        moreSettingsPanel.visibility = View.GONE
        if (::catalogPanel.isInitialized) catalogPanel.visibility = View.GONE
        if (::searchPanel.isInitialized) searchPanel.visibility = View.GONE
        setReadMenuVisible(false, updateSystemBars = false)
        updateSystemBarSurfaces()
    }

    private fun isNightReaderTheme(): Boolean = readerNightMode

    private fun currentMenuTextColor(): Int = if (isNightReaderTheme()) 0xFFF4F0E6.toInt() else 0xFF2C241B.toInt()

    private fun applyPlaybackBarStyle() {
        if (!::playbackBar.isInitialized) return
        val chromeVisible = isReaderChromeVisibleForSystemBars()
        val bgColor = if (chromeVisible) currentMenuBackgroundColor() else readerBgColor
        val iconColor = if (chromeVisible) {
            when {
                readBarStyleFollowPage -> readerTextColor
                isNightReaderTheme() -> 0xFFF4F0E6.toInt()
                else -> 0xFF2C241B.toInt()
            }
        } else {
            readerTextColor
        }
        playbackBar.setBackgroundColor(bgColor)
        tintMenuContent(playbackBar, iconColor)
    }

    private fun withAlpha(color: Int, alphaFraction: Float): Int {
        val alpha = (alphaFraction.coerceIn(0f, 1f) * 255f).toInt()
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    private fun accentColor(): Int = NIGHT_ACCENT

    private fun showScreenOrientationDialog() {
        val items = resources.getStringArray(R.array.reader_screen_direction_titles)
        val checked = when (requestedOrientation) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> 1
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> 2
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_title_screen_direction)
            .setSingleChoiceItems(items, checked) { dialog, which ->
                requestedOrientation = when (which) {
                    1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    2 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
                if (::moreSettingsPanel.isInitialized) moreSettingsPanel.bind(currentMoreConfigState())
                dialog.dismiss()
            }
            .show()
    }

    private fun showKeepLightDialog() {
        val items = resources.getStringArray(R.array.reader_keep_light_titles)
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_title_keep_light)
            .setSingleChoiceItems(items, if (keepScreenOn) 1 else 0) { dialog, which ->
                keepScreenOn = which == 1
                if (keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                persistReaderSettings()
                if (::moreSettingsPanel.isInitialized) moreSettingsPanel.bind(currentMoreConfigState())
                dialog.dismiss()
            }
            .show()
    }

    private fun showProgressBehaviorDialog() {
        val items = resources.getStringArray(R.array.reader_progress_behavior_titles)
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_title_progress_behavior)
            .setSingleChoiceItems(items, if (progressByChapter) 0 else 1) { dialog, which ->
                progressByChapter = which == 0
                persistReaderSettings()
                dialog.dismiss()
                renderCurrentPage()
                if (::moreSettingsPanel.isInitialized) moreSettingsPanel.bind(currentMoreConfigState())
            }
            .show()
    }

    private fun showClickRegionDialog() {
        val root = layoutInflater.inflate(R.layout.dialog_reader_click_action_config, null, false)
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val cellIds = intArrayOf(
            R.id.reader_click_region_top_left,
            R.id.reader_click_region_top_center,
            R.id.reader_click_region_top_right,
            R.id.reader_click_region_middle_left,
            R.id.reader_click_region_middle_center,
            R.id.reader_click_region_middle_right,
            R.id.reader_click_region_bottom_left,
            R.id.reader_click_region_bottom_center,
            R.id.reader_click_region_bottom_right
        )
        root.findViewById<View>(R.id.reader_click_region_root).setOnClickListener {
            dialog.dismiss()
        }
        repeat(ReadView.CLICK_REGION_COUNT) { index ->
            root.findViewById<TextView>(cellIds[index]).apply {
                text = readerTapActionLabel(clickRegionActions[index])
                setOnClickListener {
                    showClickRegionActionDialog(index, clickRegionActions[index]) { action ->
                        clickRegionActions = clickRegionActions.toMutableList().apply {
                            this[index] = action
                        }.toList()
                        text = readerTapActionLabel(action)
                        readView.setClickRegionActions(clickRegionActions)
                        persistReaderSettings()
                        if (::moreSettingsPanel.isInitialized) moreSettingsPanel.bind(currentMoreConfigState())
                    }
                }
            }
        }
        root.findViewById<View>(R.id.reader_click_region_panel).setOnClickListener { }
        root.findViewById<View>(R.id.reader_click_region_close).setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            val normalized = normalizeClickRegionActions(clickRegionActions)
            if (normalized != clickRegionActions) {
                clickRegionActions = normalized
                readView.setClickRegionActions(clickRegionActions)
                persistReaderSettings()
                Toast.makeText(this, R.string.reader_click_region_menu_required, Toast.LENGTH_SHORT).show()
            }
            if (::moreSettingsPanel.isInitialized) moreSettingsPanel.bind(currentMoreConfigState())
        }
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private fun currentZhLayoutToggleLabel(): String {
        return if (useZhLayout) {
            readerString(R.string.reader_convert_state_enabled)
        } else {
            readerString(R.string.reader_convert_state_disabled)
        }
    }

    private fun showResetReaderDefaultsDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_reset_defaults)
            .setMessage(R.string.reader_reset_defaults_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                resetMoreSettingsToDefaults()
            }
            .show()
    }

    private fun resetMoreSettingsToDefaults() {
        val anchor = currentPageAnchor()
        val defaults = LegadoReaderPersistedState()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        hideStatusBar = defaults.hideStatusBar
        readBodyToLh = defaults.readBodyToLh
        hideNavigationBar = defaults.hideNavigationBar
        showBrightnessView = defaults.showBrightnessView
        showReadTitleAddition = defaults.showReadTitleAddition
        bodyTitleMode = defaults.bodyTitleMode
        bodyTitleSizeAddSp = defaults.bodyTitleSizeAddSp
        bodyTitleTopSpacingDp = defaults.bodyTitleTopSpacingDp
        bodyTitleBottomSpacingDp = defaults.bodyTitleBottomSpacingDp
        headerMode = defaults.headerMode
        footerMode = defaults.footerMode
        tipHeaderLeft = defaults.tipHeaderLeft
        tipHeaderMiddle = defaults.tipHeaderMiddle
        tipHeaderRight = defaults.tipHeaderRight
        tipFooterLeft = defaults.tipFooterLeft
        tipFooterMiddle = defaults.tipFooterMiddle
        tipFooterRight = defaults.tipFooterRight
        tipColorMode = defaults.tipColorMode
        tipDividerColorMode = defaults.tipDividerColorMode
        tipDividerColor = defaults.tipDividerColor
        useZhLayout = defaults.useZhLayout
        textFullJustify = defaults.textFullJustify
        textBottomJustify = defaults.textBottomJustify
        clickRegionActions = defaultClickRegionActionsForCurrentLayout()
        selectionPrimaryActionKey = defaults.selectionPrimaryActionKey
        progressByChapter = defaults.progressByChapter
        chapterSourceMode = defaults.chapterSourceMode
        keepScreenOn = defaults.keepScreenOn
        noAnimScrollPage = defaults.noAnimScrollPage
        previewImageByClick = defaults.previewImageByClick
        disableReturnKey = defaults.disableReturnKey
        readBarStyleFollowPage = defaults.readBarStyleFollowPage

        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        readView.setClickRegionActions(clickRegionActions)
        readView.setSelectionPrimaryActionKey(selectionPrimaryActionKey)
        applyReaderInfoConfig()
        readView.setNoAnimScrollPage(noAnimScrollPage)
        brightnessPanel.visibility = if (showBrightnessView) View.VISIBLE else View.GONE
        applyBrightnessState()
        applyReadBarStyle()
        persistReaderSettings()
        if (::moreSettingsPanel.isInitialized) moreSettingsPanel.bind(currentMoreConfigState())
        readView.post { relayoutCurrentDocument(anchor) }
    }

    private fun currentClickRegionSummary(): String {
        return if (clickRegionActions == defaultClickRegionActionsForCurrentLayout()) {
            readerString(R.string.reader_click_region_summary_default)
        } else {
            readerString(R.string.reader_click_region_summary_custom)
        }
    }

    private fun currentSelectionPrimaryActionSummary(): String {
        val options = readView.selectionPrimaryActionOptions()
        return options.firstOrNull { it.key == selectionPrimaryActionKey }?.label
            ?: readerString(R.string.reader_selection_primary_default)
    }

    private fun showSelectionPrimaryActionDialog() {
        val options = readView.selectionPrimaryActionOptions()
        val labels = options.map { it.label }.toTypedArray()
        val selectedIndex = options.indexOfFirst { it.key == selectionPrimaryActionKey }
            .takeIf { it >= 0 }
            ?: 0
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_selection_primary_action)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                selectionPrimaryActionKey = options.getOrNull(which)?.key
                    ?: ReadView.DEFAULT_SELECTION_PRIMARY_ACTION_KEY
                readView.setSelectionPrimaryActionKey(selectionPrimaryActionKey)
                persistReaderSettings(updateAnchor = false)
                if (::moreSettingsPanel.isInitialized) {
                    moreSettingsPanel.bind(currentMoreConfigState())
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun defaultClickRegionActionsForCurrentLayout(): List<ReadView.TapAction> {
        return ReadView.defaultClickRegionActions(readerLayoutMode)
    }

    private fun showClickRegionActionDialog(
        regionIndex: Int,
        currentAction: ReadView.TapAction,
        onSelected: (ReadView.TapAction) -> Unit
    ) {
        val actions = readerTapActionOptions()
        val labels = actions.map(::readerTapActionLabel).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(readerTapRegionLabel(regionIndex))
            .setSingleChoiceItems(labels, actions.indexOf(currentAction)) { dialog, which ->
                onSelected(actions[which])
                dialog.dismiss()
            }
            .show()
    }

    private fun readerTapActionOptions(): List<ReadView.TapAction> = listOf(
        ReadView.TapAction.NONE,
        ReadView.TapAction.MENU,
        ReadView.TapAction.NEXT_PAGE,
        ReadView.TapAction.PREV_PAGE,
        ReadView.TapAction.NEXT_CHAPTER,
        ReadView.TapAction.PREV_CHAPTER,
        ReadView.TapAction.NEXT_AUDIO_CUE,
        ReadView.TapAction.PREV_AUDIO_CUE,
        ReadView.TapAction.ADD_BOOKMARK,
        ReadView.TapAction.TOGGLE_CONVERT,
        ReadView.TapAction.CATALOG,
        ReadView.TapAction.SEARCH
    )

    private fun readerTapActionLabel(action: ReadView.TapAction): String {
        val resId = when (action) {
            ReadView.TapAction.NONE -> R.string.reader_click_action_none
            ReadView.TapAction.MENU -> R.string.reader_click_action_menu
            ReadView.TapAction.NEXT_PAGE -> R.string.reader_click_action_next_page
            ReadView.TapAction.PREV_PAGE -> R.string.reader_click_action_prev_page
            ReadView.TapAction.NEXT_CHAPTER -> R.string.reader_click_action_next_chapter
            ReadView.TapAction.PREV_CHAPTER -> R.string.reader_click_action_prev_chapter
            ReadView.TapAction.NEXT_AUDIO_CUE -> R.string.reader_click_action_next_audio
            ReadView.TapAction.PREV_AUDIO_CUE -> R.string.reader_click_action_prev_audio
            ReadView.TapAction.ADD_BOOKMARK -> R.string.reader_click_action_add_bookmark
            ReadView.TapAction.TOGGLE_CONVERT -> R.string.reader_click_action_toggle_convert
            ReadView.TapAction.CATALOG -> R.string.reader_click_action_catalog
            ReadView.TapAction.SEARCH -> R.string.reader_click_action_search
        }
        return readerString(resId)
    }

    private fun readerTapRegionLabel(index: Int): String {
        val resId = when (index) {
            0 -> R.string.reader_click_region_top_left
            1 -> R.string.reader_click_region_top_center
            2 -> R.string.reader_click_region_top_right
            3 -> R.string.reader_click_region_middle_left
            4 -> R.string.reader_click_region_middle_center
            5 -> R.string.reader_click_region_middle_right
            6 -> R.string.reader_click_region_bottom_left
            7 -> R.string.reader_click_region_bottom_center
            else -> R.string.reader_click_region_bottom_right
        }
        return readerString(resId)
    }

    private fun handleTapRegionAction(action: ReadView.TapAction) {
        when (action) {
            ReadView.TapAction.NONE,
            ReadView.TapAction.MENU,
            ReadView.TapAction.NEXT_PAGE,
            ReadView.TapAction.PREV_PAGE -> Unit
            ReadView.TapAction.NEXT_CHAPTER -> moveChapter(1)
            ReadView.TapAction.PREV_CHAPTER -> moveChapter(-1)
            ReadView.TapAction.NEXT_AUDIO_CUE -> seekToAdjacentCue(1)
            ReadView.TapAction.PREV_AUDIO_CUE -> seekToAdjacentCue(-1)
            ReadView.TapAction.ADD_BOOKMARK -> addCurrentBookmark()
            ReadView.TapAction.TOGGLE_CONVERT -> toggleReaderConvertState()
            ReadView.TapAction.CATALOG -> {
                closeReaderChrome()
                showChapterListDialog()
            }
            ReadView.TapAction.SEARCH -> {
                closeReaderChrome()
                showSearchPanel(searchQuery.orEmpty())
            }
        }
    }

    private fun handleSelectionAction(action: ReadView.SelectionAction, text: String) {
        when (action) {
            ReadView.SelectionAction.PROCESS_TEXT -> startSelectionProcessText(text)
            ReadView.SelectionAction.COPY -> Unit
            ReadView.SelectionAction.SHARE -> {
                runCatching {
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            },
                            text
                        )
                    )
                }
            }
            ReadView.SelectionAction.SEARCH -> {
                closeReaderChrome()
                showSearchPanel(text)
                startSearch(text)
            }
            ReadView.SelectionAction.ADD_BOOKMARK -> addCurrentBookmark()
            ReadView.SelectionAction.BROWSER -> {
                runCatching {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://www.google.com/search?q=${Uri.encode(text)}")
                        )
                    )
                }
            }
        }
    }

    private fun startSelectionProcessText(text: String) {
        launchSelectionProcessText(
            Intent(Intent.ACTION_PROCESS_TEXT).apply {
                type = "text/plain"
            },
            text = text,
            readOnly = true
        )
    }

    private fun handleSelectionProcessText(intent: Intent, text: String) {
        launchSelectionProcessText(intent, text = text, readOnly = false)
    }

    private fun handleTextSelectionStateChanged(active: Boolean) {
        textSelectionActive = active
    }

    private fun launchSelectionProcessText(intent: Intent, text: String, readOnly: Boolean) {
        runCatching {
            startActivity(
                Intent(intent).apply {
                    putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                    putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, readOnly)
                }
            )
        }.onFailure {
            Toast.makeText(this, it.localizedMessage ?: "PROCESS_TEXT", Toast.LENGTH_SHORT).show()
        }
    }

    private fun normalizeClickRegionActions(actions: List<ReadView.TapAction>): List<ReadView.TapAction> {
        if (actions.any { it == ReadView.TapAction.MENU }) return actions
        return actions.toMutableList().apply {
            this[4] = ReadView.TapAction.MENU
        }.toList()
    }

    private fun toggleReaderConvertState() {
        useZhLayout = !useZhLayout
        requestBookRelayout()
        if (::moreSettingsPanel.isInitialized) moreSettingsPanel.bind(currentMoreConfigState())
    }

    private fun showOverflowMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, ReaderOverflowAction.PLAYER.menuId, 0, R.string.reader_menu_player)
            menu.add(0, ReaderOverflowAction.ADD_BOOKMARK.menuId, 1, R.string.reader_menu_add_bookmark)
            if (hasRubySpans()) {
                menu.add(0, ReaderOverflowAction.REMOVE_RUBY.menuId, 2, R.string.del_ruby_tag).apply {
                    isCheckable = true
                    isChecked = !showRubyText
                }
            }
            menu.add(
                0,
                ReaderOverflowAction.SWITCH_LAYOUT.menuId,
                3,
                if (readerLayoutMode == M9LayoutMode.VERTICAL) R.string.reader_menu_switch_horizontal else R.string.reader_menu_switch_vertical
            )
            menu.add(0, ReaderOverflowAction.CHARSET.menuId, 4, R.string.reader_encoding_set)
            menu.add(0, ReaderOverflowAction.HELP.menuId, 5, R.string.reader_menu_help)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    ReaderOverflowAction.PLAYER.menuId -> openPlayerFromReader()
                    ReaderOverflowAction.ADD_BOOKMARK.menuId -> addCurrentBookmark()
                    ReaderOverflowAction.REMOVE_RUBY.menuId -> {
                        item.isChecked = !item.isChecked
                        showRubyText = !item.isChecked
                        requestBookRelayout(immediate = true)
                    }
                    ReaderOverflowAction.SWITCH_LAYOUT.menuId -> {
                        val oldLayoutMode = readerLayoutMode
                        val wasDefaultClickRegions = clickRegionActions == ReadView.defaultClickRegionActions(oldLayoutMode)
                        readerLayoutMode =
                            if (oldLayoutMode == M9LayoutMode.VERTICAL) {
                                M9LayoutMode.HORIZONTAL
                            } else {
                                M9LayoutMode.VERTICAL
                            }
                        readView.setLayoutMode(readerLayoutMode)
                        if (wasDefaultClickRegions) {
                            clickRegionActions = defaultClickRegionActionsForCurrentLayout()
                            readView.setClickRegionActions(clickRegionActions)
                            if (::moreSettingsPanel.isInitialized) moreSettingsPanel.bind(currentMoreConfigState())
                        }
                        readView.setTextUnderline(readerUnderline && readerLayoutMode == M9LayoutMode.HORIZONTAL)
                        applyDirectionSettings()
                        requestBookRelayout()
                    }
                    ReaderOverflowAction.CHARSET.menuId -> showEncodingMenu(anchor)
                }
                true
            }
            show()
        }
    }

    private fun showEncodingMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 0, 0, R.string.reader_charset_auto)
            menu.add("UTF-8")
            menu.add("Shift_JIS")
            menu.add("GBK")
            menu.add("Big5")
            menu.add("UTF-16LE")
            setOnMenuItemClickListener { item ->
                preferredCharsetName = item.title.toString().takeUnless {
                    it == readerString(R.string.reader_charset_auto)
                }
                persistReaderSettings()
                document = null
                loadedDocumentBookUriText = null
                loadedDocumentCharsetName = null
                cueMatchesByCueIndex = emptyMap()
                matchData = null
                audioCueIndex = -1
                activeCueIndex = -1
                loadDisplayedBook(anchor = currentPageAnchor(), forceDocumentReload = true)
                true
            }
            show()
        }
    }

    private fun hasRubySpans(): Boolean {
        return document?.chapters?.any { chapter -> chapter.rubySpans.isNotEmpty() } == true
    }

    private fun text(value: String, sizeSp: Float, color: Int): TextView {
        return TextView(this).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = true
        }
    }

    private fun currentReaderTitle(): String {
        return document?.title ?: importedBook?.title ?: LEGADO_READER_DEFAULT_TITLE
    }

    private fun openPlayerFromReader() {
        val targetAudioUri = audioUri ?: run {
            Toast.makeText(this, R.string.reader_no_audio, Toast.LENGTH_SHORT).show()
            return
        }
        if (bridgeCanReturnToPlayer()) {
            returnToSharedPlayer()
            return
        }
        persistAudioPlaybackSnapshot()
        startPlayerActivity(targetAudioUri)
    }

    private fun startPlayerActivity(targetAudioUri: Uri) {
        val intent = Intent(this, BookReaderActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(BookReaderActivity.EXTRA_BOOK_TITLE, currentReaderTitle())
            putExtra(BookReaderActivity.EXTRA_AUDIO_URI, targetAudioUri.toString())
            putExtra(BookReaderActivity.EXTRA_SRT_URI, srtUri?.toString())
            importedBook?.uri?.let { putExtra(BookReaderActivity.EXTRA_EBOOK_URI, it.toString()) }
            importedBook?.title?.let { putExtra(BookReaderActivity.EXTRA_EBOOK_NAME, it) }
            importedBook?.format?.let { putExtra(BookReaderActivity.EXTRA_EBOOK_FORMAT, it) }
        }
        suppressFloatingOverlayOnStop = true
        startActivity(intent)
    }

    private fun returnToSharedPlayer(): Boolean {
        val targetAudioUri = audioUri ?: return false
        persistReaderSettings(updateAnchor = false)
        persistAudioPlaybackSnapshot()
        startPlayerActivity(targetAudioUri)
        finish()
        return true
    }

    private fun returnToHome() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
        finish()
    }

    private fun returnToPlayerIfShared(): Boolean {
        if (!bridgeCanReturnToPlayer()) return false
        return returnToSharedPlayer()
    }

    private fun updateDisplayedBookTitle() {
        val title = currentReaderTitle()
        if (::toolbarTitleText.isInitialized) toolbarTitleText.text = title
        if (::readView.isInitialized) readView.setBookTitle(title)
    }

    private fun toggleNightMode() {
        readerNightMode = !readerNightMode
        applySelectedReaderStyleFields()
        closeReaderChrome()
        applyReaderVisualStyle()
        persistReaderSettings(updateAnchor = false)
    }

    private fun startSearch(query: String) {
        if (query.isBlank()) {
            Toast.makeText(this, R.string.reader_search_input_required, Toast.LENGTH_SHORT).show()
            return
        }
        val lowerQuery = query.lowercase()
        val hits = buildList {
            document?.chapters?.forEachIndexed { chapterIndex, chapter ->
                val pageText = chapter.text
                val lowerPageText = pageText.lowercase()
                var fromIndex = 0
                while (true) {
                    val hitIndex = lowerPageText.indexOf(lowerQuery, fromIndex)
                    if (hitIndex < 0) break
                    val previewStart = (hitIndex - 12).coerceAtLeast(0)
                    val previewEnd = (hitIndex + query.length + 20).coerceAtMost(pageText.length)
                    add(
                        ReaderSearchHit(
                            chapterIndex = chapterIndex,
                            chapterTitle = chapter.title,
                            preview = pageText.substring(previewStart, previewEnd).replace('\n', ' '),
                            chapterPosition = hitIndex,
                            query = query
                        )
                    )
                    fromIndex = hitIndex + query.length
                }
            }
        }
        searchQuery = query
        if (hits.isEmpty()) {
            searchHits = emptyList()
            searchHitIndex = -1
            bindSearchResultList()
            updateSearchInfo()
            Toast.makeText(this, R.string.reader_search_no_results, Toast.LENGTH_SHORT).show()
            return
        }
        searchHits = hits
        searchHitIndex = -1
        bindSearchResultList()
        updateSearchInfo()
    }

    private fun navigateSearch(delta: Int) {
        if (searchHits.isEmpty()) return
        val startIndex = searchHitIndex.takeIf { it >= 0 } ?: if (delta >= 0) -1 else searchHits.size
        searchHitIndex = (startIndex + delta).let { value ->
            when {
                value < 0 -> searchHits.lastIndex
                value > searchHits.lastIndex -> 0
                else -> value
            }
        }
        navigateToSearchHit(searchHitIndex, showSearchMenu = true)
    }

    private fun updateSearchInfo() {
        if (::searchMenu.isInitialized) {
            val current = searchHits.getOrNull(searchHitIndex)
            val chapterTitle = current?.chapterTitle ?: pages.getOrNull(pageIndex)?.title ?: currentReaderTitle()
            val currentIndex = if (searchHitIndex >= 0) searchHitIndex + 1 else 0
            searchMenu.updateInfo(
                getString(
                    R.string.reader_search_result_current,
                    currentIndex,
                    searchHits.size,
                    chapterTitle
                )
            )
        }
        if (::searchResultInfoView.isInitialized) {
            searchResultInfoView.text = if (searchHits.isEmpty()) {
                readerString(R.string.reader_search_result_none)
            } else {
                getString(R.string.reader_search_result_total, searchHits.size)
            }
        }
    }

    private fun hideSearchMenu() {
        if (::searchMenu.isInitialized) searchMenu.hideMenu()
    }

    private fun showSearchPanel(initialQuery: String = searchQuery.orEmpty()) {
        if (!::searchPanel.isInitialized) return
        hideSearchMenu()
        audioControlPanel.visibility = View.GONE
        moreSettingsPanel.visibility = View.GONE
        catalogPanel.visibility = View.GONE
        setReadMenuVisible(false, updateSystemBars = false)
        searchPanel.visibility = View.VISIBLE
        searchInputView.setText(initialQuery)
        searchInputView.setSelection(searchInputView.text.length)
        bindSearchResultList()
        updateSearchInfo()
        updateSystemBarSurfaces()
    }

    private fun hideSearchPanel() {
        if (::searchPanel.isInitialized) {
            searchPanel.visibility = View.GONE
            updateSystemBarSurfaces()
        }
    }

    private fun performSearchFromPanel() {
        val query = searchInputView.text.toString().trim()
        startSearch(query)
    }

    private fun bindSearchResultList() {
        if (!::searchResultListView.isInitialized) return
        searchResultListView.adapter = object : ArrayAdapter<ReaderSearchHit>(
            this,
            android.R.layout.simple_list_item_1,
            searchHits
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = (convertView as? TextView) ?: TextView(context).apply {
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    textSize = 15f
                }
                val item = getItem(position)
                view.text = "${position + 1}. ${item?.chapterTitle.orEmpty()}\n${item?.preview.orEmpty()}"
                view.setTextColor(if (position == searchHitIndex) accentColor() else currentMenuTextColor())
                return view
            }
        }
        searchResultListView.setOnItemClickListener { _, _, position, _ ->
            navigateToSearchHit(position, showSearchMenu = true)
        }
    }

    private fun hideCatalogPanel() {
        if (::catalogPanel.isInitialized) {
            catalogPanel.visibility = View.GONE
            updateSystemBarSurfaces()
        }
    }

    private fun showChapterListDialog() {
        val chapters = document?.chapters.orEmpty()
        if (chapters.isEmpty() && !useM4bChapterSource()) {
            Toast.makeText(this, R.string.reader_catalog_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        catalogMode = CatalogMode.CHAPTERS
        catalogFilterQuery = ""
        audioControlPanel.visibility = View.GONE
        moreSettingsPanel.visibility = View.GONE
        searchPanel.visibility = View.GONE
        setReadMenuVisible(false, updateSystemBars = false)
        catalogPanel.visibility = View.VISIBLE
        catalogSearchInputView.setText("")
        bindCatalogList()
        updateSystemBarSurfaces()
    }

    private fun navigateToSearchHit(index: Int, showSearchMenu: Boolean) {
        val hit = searchHits.getOrNull(index) ?: return
        searchHitIndex = index
        activeCueIndex = -1
        showAnchorOrLoad(
            anchor = ReaderPageAnchor(hit.chapterIndex, hit.chapterPosition),
            forward = hit.chapterIndex >= (pages.getOrNull(pageIndex)?.chapterIndex ?: 0),
            keepSearchHit = true,
            anchorSource = "search"
        )
        updateSearchInfo()
        hideSearchPanel()
        if (showSearchMenu) {
            searchMenu.showMenu()
        }
    }

    private fun bindCatalogList() {
        if (!::catalogListView.isInitialized) return
        updateCatalogTabs()
        when (catalogMode) {
            CatalogMode.CHAPTERS -> bindChapterCatalogList()
            CatalogMode.BOOKMARKS -> bindBookmarkCatalogList()
        }
    }

    private fun updateCatalogTabs() {
        if (!::catalogTitleView.isInitialized) return
        catalogTitleView.text =
            if (catalogMode == CatalogMode.CHAPTERS) {
                readerString(R.string.reader_catalog_title_chapters)
            } else {
                readerString(R.string.reader_catalog_title_bookmarks)
            }
        val activeBg = accentColor()
        val inactiveBg = 0x00000000
        val activeText = if (isNightReaderTheme()) 0xFF111111.toInt() else 0xFFFFFFFF.toInt()
        val inactiveText = currentMenuTextColor()
        catalogTabChaptersView.backgroundTintList = ColorStateList.valueOf(if (catalogMode == CatalogMode.CHAPTERS) activeBg else inactiveBg)
        catalogTabBookmarksView.backgroundTintList = ColorStateList.valueOf(if (catalogMode == CatalogMode.BOOKMARKS) activeBg else inactiveBg)
        catalogTabChaptersView.setTextColor(if (catalogMode == CatalogMode.CHAPTERS) activeText else inactiveText)
        catalogTabBookmarksView.setTextColor(if (catalogMode == CatalogMode.BOOKMARKS) activeText else inactiveText)
        catalogSearchInputView.hint =
            if (catalogMode == CatalogMode.CHAPTERS) {
                readerString(R.string.reader_catalog_search_chapters)
            } else {
                readerString(R.string.reader_catalog_search_bookmarks)
            }
    }

    private fun bindChapterCatalogList() {
        if (useM4bChapterSource()) {
            bindM4bChapterCatalogList()
            return
        }
        val chapters = document?.chapters.orEmpty()
        val currentChapterIndex = pages.getOrNull(pageIndex)?.chapterIndex ?: 0
        val filtered = chapters.mapIndexed { index, chapter -> index to chapter }.filter { (_, chapter) ->
            val query = catalogFilterQuery.trim()
            query.isBlank() || chapter.title.contains(query, ignoreCase = true)
        }
        catalogListView.adapter = object : ArrayAdapter<Pair<Int, EbookChapter>>(
            this,
            android.R.layout.simple_list_item_1,
            filtered
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = (convertView as? TextView) ?: TextView(context).apply {
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    textSize = 16f
                }
                val item = getItem(position)
                val chapterIndex = item?.first ?: position
                val chapterTitle = item?.second?.title.orEmpty()
                view.text = chapterTitle
                view.setTextColor(if (chapterIndex == currentChapterIndex) accentColor() else currentMenuTextColor())
                return view
            }
        }
        catalogListView.setOnItemClickListener { _, _, which, _ ->
            val chapterIndex = filtered.getOrNull(which)?.first ?: return@setOnItemClickListener
            showAnchorOrLoad(
                anchor = ReaderPageAnchor(chapterIndex, 0),
                forward = chapterIndex >= currentChapterIndex,
                anchorSource = "catalog"
            )
            hideCatalogPanel()
        }
        val selection = filtered.indexOfFirst { it.first == currentChapterIndex }.coerceAtLeast(0)
        catalogListView.post { catalogListView.setSelection(selection) }
    }

    private fun bindM4bChapterCatalogList() {
        val chapters = m4bChapters
        if (chapters.isEmpty()) {
            Toast.makeText(this, R.string.reader_chapter_source_m4b_unavailable, Toast.LENGTH_SHORT).show()
            chapterSourceMode = ReaderChapterSourceMode.BOOK
            if (::moreSettingsPanel.isInitialized) moreSettingsPanel.bind(currentMoreConfigState())
            bindChapterCatalogList()
            return
        }
        val currentChapterIndex = currentM4bChapterIndex()
        val filtered = chapters.mapIndexed { index, chapter -> index to chapter }.filter { (_, chapter) ->
            val query = catalogFilterQuery.trim()
            query.isBlank() || chapter.title.contains(query, ignoreCase = true)
        }
        catalogListView.adapter = object : ArrayAdapter<Pair<Int, M4bChapter>>(
            this,
            android.R.layout.simple_list_item_1,
            filtered
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = (convertView as? TextView) ?: TextView(context).apply {
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    textSize = 16f
                }
                val item = getItem(position)
                val chapterIndex = item?.first ?: position
                val chapterTitle = item?.second?.title.orEmpty()
                view.text = chapterTitle
                view.setTextColor(if (chapterIndex == currentChapterIndex) accentColor() else currentMenuTextColor())
                return view
            }
        }
        catalogListView.setOnItemClickListener { _, _, which, _ ->
            val chapterIndex = filtered.getOrNull(which)?.first ?: return@setOnItemClickListener
            seekToM4bChapter(chapterIndex)
            hideCatalogPanel()
        }
        val selection = filtered.indexOfFirst { it.first == currentChapterIndex }.coerceAtLeast(0)
        catalogListView.post { catalogListView.setSelection(selection) }
    }

    private fun bindBookmarkCatalogList() {
        val filtered = bookmarks.filter { bookmark ->
            val query = catalogFilterQuery.trim()
            query.isBlank() ||
                bookmark.chapterTitle.contains(query, ignoreCase = true) ||
                bookmark.preview.contains(query, ignoreCase = true)
        }
        catalogListView.adapter = object : ArrayAdapter<ReaderBookmark>(
            this,
            android.R.layout.simple_list_item_1,
            filtered
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = (convertView as? TextView) ?: TextView(context).apply {
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    textSize = 15f
                }
                val item = getItem(position)
                view.text = buildString {
                    append(item?.chapterTitle ?: readerString(R.string.reader_unknown_chapter))
                    append('\n')
                    append(item?.preview.orEmpty())
                }
                view.setTextColor(currentMenuTextColor())
                return view
            }
        }
        catalogListView.setOnItemClickListener { _, _, which, _ ->
            val bookmark = filtered.getOrNull(which) ?: return@setOnItemClickListener
            showAnchorOrLoad(
                anchor = ReaderPageAnchor(bookmark.chapterIndex, bookmark.chapterPosition),
                forward = bookmark.chapterIndex >= (pages.getOrNull(pageIndex)?.chapterIndex ?: 0),
                anchorSource = "bookmark"
            )
            hideCatalogPanel()
        }
        catalogListView.setOnItemLongClickListener { _, _, which, _ ->
            val bookmark = filtered.getOrNull(which) ?: return@setOnItemLongClickListener true
            bookmarks.removeAll {
                it.chapterIndex == bookmark.chapterIndex && it.chapterPosition == bookmark.chapterPosition
            }
            persistBookmarks()
            bindCatalogList()
            Toast.makeText(this, R.string.reader_bookmark_deleted, Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun findPageIndexForChapterPosition(chapterIndex: Int, chapterPosition: Int): Int {
        return pages.indexOfFirst { it.chapterIndex == chapterIndex && it.containPos(chapterPosition) }
            .takeIf { it >= 0 }
            ?: pages.indexOfFirst { it.chapterIndex == chapterIndex && chapterPosition <= it.charStart }
                .takeIf { it >= 0 }
            ?: pages.indexOfLast { it.chapterIndex == chapterIndex }
    }

    private fun pagePreviewForDelta(delta: Int): TextPage? {
        if (delta == 0 || pages.isEmpty()) return pages.getOrNull(pageIndex)
        pages.getOrNull(pageIndex + delta)?.let { return it }
        val currentPage = pages.getOrNull(pageIndex) ?: return null
        val targetChapter = currentPage.chapterIndex + delta.coerceIn(-1, 1)
        val loaded = document ?: return null
        if (targetChapter !in loaded.chapters.indices) return null
        val cacheKey = currentChapterPageCacheKey(targetChapter)
        val cachedPages = chapterPageCache[cacheKey].orEmpty()
        return if (delta > 0) cachedPages.firstOrNull() else cachedPages.lastOrNull()
    }

    private fun showAnchorOrLoad(
        anchor: ReaderPageAnchor,
        forward: Boolean = true,
        keepSearchHit: Boolean = false,
        persistAnchor: Boolean = true,
        anchorSource: String = "anchor"
    ) {
        val next = findPageIndexForChapterPosition(anchor.chapterIndex, anchor.charPosition)
        if (!keepSearchHit) searchHitIndex = -1
        activeCueIndex = -1
        if (next >= 0) {
            pageIndex = next
            renderCurrentPage(
                forward = forward,
                persistAnchor = persistAnchor,
                anchorSource = anchorSource
            )
        } else {
            loadDisplayedBook(anchor = anchor, forceDocumentReload = false)
            if (persistAnchor) {
                persistReaderAnchor(anchorSource, anchor)
            }
        }
    }

    private fun showStyleDialog() {
        ReadStyleDialog(
            activity = this,
            state = currentReadStyleState(),
            callback = object : ReadStyleDialog.Callback {
                override fun onTextSizeChanged(valueSp: Int) {
                    readerTextSizeSp = valueSp
                    readView.setTextSizeSp(valueSp.toFloat())
                    updateSelectedReaderStyleLayoutFields()
                }

                override fun onLetterSpacingChanged(value: Int) {
                    readerLetterSpacingDp = value
                    updateSelectedReaderStyleLayoutFields()
                }

                override fun onLineSpacingChanged(valueDp: Int) {
                    readerLineSpacingDp = valueDp
                    updateSelectedReaderStyleLayoutFields()
                }

                override fun onParagraphSpacingChanged(valueDp: Int) {
                    readerParagraphSpacingDp = valueDp
                    updateSelectedReaderStyleLayoutFields()
                }

                override fun onTextSizeChangeFinished(valueSp: Int) {
                    requestBookRelayout(immediate = true)
                }

                override fun onLetterSpacingChangeFinished(value: Int) {
                    requestBookRelayout(immediate = true)
                }

                override fun onLineSpacingChangeFinished(valueDp: Int) {
                    requestBookRelayout(immediate = true)
                }

                override fun onParagraphSpacingChangeFinished(valueDp: Int) {
                    requestBookRelayout(immediate = true)
                }

                override fun onInfoClicked() {
                    showTipConfigDialog()
                }

                override fun onWeightClicked(onChanged: (M9TextWeight) -> Unit) {
                    showTextWeightDialog(onChanged)
                }

                override fun onFontClicked() {
                    showFontDialog()
                }

                override fun onIndentClicked() {
                    showIndentDialog()
                }

                override fun onPaddingClicked() {
                    showPaddingDialog()
                }

                override fun onTipClicked() {
                    showTipConfigDialog()
                }

                override fun onPageAnimClicked(animIndex: Int) {
                    readerPageAnim = when (animIndex) {
                        0 -> M9PageAnim.COVER
                        1 -> M9PageAnim.SLIDE
                        2 -> M9PageAnim.SCROLL
                        else -> M9PageAnim.NONE
                    }
                    readView.setPageAnim(readerPageAnim)
                    persistReaderSettings()
                }

                override fun onBackgroundClicked(index: Int): ReadStyleState {
                    selectReaderStyle(index)
                    return currentReadStyleState()
                }

                override fun onBackgroundLongClicked(index: Int) {
                    selectReaderStyle(index)
                    showReaderStyleConfigDialog(index)
                }

                override fun onBackgroundAddClicked() {
                    readerStyleConfigs.add(defaultReaderStyleConfig().copy(name = "文字"))
                    val index = readerStyleConfigs.lastIndex
                    selectReaderStyle(index)
                    showReaderStyleConfigDialog(index)
                }
            }
        ).show()
    }

    private fun currentReadStyleState(): ReadStyleState {
        return ReadStyleState(
            textSizeSp = readerTextSizeSp,
            letterSpacingDp = readerLetterSpacingDp,
            lineSpacingDp = readerLineSpacingDp,
            paragraphSpacingDp = readerParagraphSpacingDp,
            textWeight = readerTextWeight,
            backgroundStyleIndex = readerStyleSelect.coerceIn(0, readerStyleConfigs.lastIndex),
            backgroundStyles = readerStyleConfigs.map {
                ReadStyleColorItem(
                    name = it.name,
                    bgColor = it.bgColor,
                    textColor = it.textColor,
                    tipColor = it.tipColor,
                    bgAlpha = it.bgAlpha,
                    bgAssetName = it.bgAssetName,
                    bgImageUri = it.bgImageUri
                )
            },
            pageAnim = readerPageAnim
        )
    }

    private fun selectReaderStyle(index: Int) {
        val style = readerStyleConfigs.getOrNull(index) ?: return
        val layoutChanged = styleReaderLayoutDiffers(style)
        readerStyleSelect = index
        applySelectedReaderStyleFields()
        applyReaderTypography()
        applyReaderVisualStyle()
        if (layoutChanged) {
            requestBookRelayout(immediate = true)
        } else {
            persistReaderSettings()
        }
    }

    private fun applySelectedReaderStyleFields() {
        val style = readerStyleConfigs.getOrNull(readerStyleSelect) ?: defaultReaderStyleConfig()
        readerTextSizeSp = style.textSizeSp
        readerLineSpacingDp = style.lineSpacingDp
        readerParagraphSpacingDp = style.paragraphSpacingDp
        readerLetterSpacingDp = style.letterSpacingDp
        readerTextWeight = style.textWeight
        readerTypefaceIndex = style.typefaceIndex
        readerTypeface = readerTypefaceForIndex(readerTypefaceIndex)
        readerParagraphIndentCount = style.paragraphIndentCount
        readerPaddingDp = style.paddingDp
        readerBgColor = style.bgColor
        readerTextColor = style.textColor
        readerTipColor = style.tipColor
        readerBgAlpha = style.bgAlpha
        readerDarkStatusIcon = style.darkStatusIcon
        readerUnderline = style.underline
        readerBgAssetName = style.bgAssetName
        readerBgImageUri = style.bgImageUri
        if (readerNightMode) {
            readerBgColor = 0xFF1F1F1F.toInt()
            readerTextColor = 0xFFD8D2C5.toInt()
            readerTipColor = 0xFF948B7D.toInt()
            readerBgAlpha = 100
            readerDarkStatusIcon = false
            readerBgAssetName = null
            readerBgImageUri = null
        }
    }

    private fun defaultReaderStyleConfig(): LegadoReaderStyleConfig {
        val defaults = defaultLegadoReaderStyleConfigs()
        return defaults.getOrElse(DEFAULT_LEGADO_READER_STYLE_INDEX) { defaults.first() }
    }

    private fun styleReaderLayoutDiffers(style: LegadoReaderStyleConfig): Boolean {
        return readerTextSizeSp != style.textSizeSp ||
            readerLineSpacingDp != style.lineSpacingDp ||
            readerParagraphSpacingDp != style.paragraphSpacingDp ||
            readerLetterSpacingDp != style.letterSpacingDp ||
            readerTextWeight != style.textWeight ||
            readerTypefaceIndex != style.typefaceIndex ||
            readerParagraphIndentCount != style.paragraphIndentCount ||
            readerPaddingDp != style.paddingDp
    }

    private fun updateSelectedReaderStyleLayoutFields() {
        val style = readerStyleConfigs.getOrNull(readerStyleSelect) ?: return
        readerStyleConfigs[readerStyleSelect] = style.copy(
            textSizeSp = readerTextSizeSp,
            lineSpacingDp = readerLineSpacingDp,
            paragraphSpacingDp = readerParagraphSpacingDp,
            letterSpacingDp = readerLetterSpacingDp,
            textWeight = readerTextWeight,
            typefaceIndex = readerTypefaceIndex,
            paragraphIndentCount = readerParagraphIndentCount,
            paddingDp = readerPaddingDp
        )
    }

    private fun applyReaderTypography() {
        if (!::readView.isInitialized) return
        readView.setTextSizeSp(readerTextSizeSp.toFloat())
        readView.setTextWeight(readerTextWeight)
        readView.setReaderTypeface(readerTypeface)
        readView.setReaderPadding(
            dp(readerPaddingDp),
            dp(34),
            dp(readerPaddingDp),
            currentReaderBottomPaddingPx()
        )
    }

    private fun readerTypefaceForIndex(index: Int): Typeface {
        return when (index) {
            1 -> Typeface.SERIF
            2 -> Typeface.MONOSPACE
            else -> Typeface.DEFAULT
        }
    }

    private fun applyReaderVisualStyle() {
        if (::readerRoot.isInitialized) {
            readerRoot.setBackgroundColor(readerBgColor)
        }
        readView.setReaderColors(
            readerBgColor,
            readerTextColor,
            effectiveReaderTipColor(),
            readerBgAssetName,
            readerBgImageUri,
            readerBgAlpha
        )
        readView.setCueHighlightColor(readerCueHighlightColor)
        readView.setTextUnderline(readerUnderline && readerLayoutMode == M9LayoutMode.HORIZONTAL)
        applyReaderInfoConfig()
        applyReadBarStyle()
    }

    private fun effectiveReaderTipColor(): Int {
        return when (tipColorMode) {
            ReaderTipColorMode.FOLLOW_CONTENT -> readerTextColor
            ReaderTipColorMode.CUSTOM -> readerTipColor
        }
    }

    private fun effectiveReaderTipDividerColor(): Int? {
        return when (tipDividerColorMode) {
            ReaderTipDividerColorMode.DEFAULT -> (effectiveReaderTipColor() and 0x00FFFFFF) or 0x33000000
            ReaderTipDividerColorMode.FOLLOW_CONTENT -> effectiveReaderTipColor()
            ReaderTipDividerColorMode.CUSTOM -> tipDividerColor
        }
    }

    private fun applyReaderInfoConfig() {
        if (!::readView.isInitialized) return
        readView.setShowHeaderFooter(showReadTitleAddition)
        readView.setReaderInfoConfig(
            bodyTitleMode = bodyTitleMode,
            bodyTitleSizeAddSp = bodyTitleSizeAddSp,
            bodyTitleTopSpacingDp = bodyTitleTopSpacingDp,
            bodyTitleBottomSpacingDp = bodyTitleBottomSpacingDp,
            headerMode = headerMode,
            footerMode = footerMode,
            headerLeft = tipHeaderLeft,
            headerMiddle = tipHeaderMiddle,
            headerRight = tipHeaderRight,
            footerLeft = tipFooterLeft,
            footerMiddle = tipFooterMiddle,
            footerRight = tipFooterRight,
            statusBarHidden = hideStatusBar,
            dividerColor = effectiveReaderTipDividerColor()
        )
    }

    private fun showReaderStyleConfigDialog(index: Int) {
        val current = readerStyleConfigs.getOrNull(index) ?: return
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            setBackgroundColor(readerBgColor)
        }
        var selectedBgColor = current.bgColor
        var selectedTextColor = current.textColor
        var selectedTipColor = current.tipColor
        var selectedBgAssetName = current.bgAssetName
        var selectedBgImageUri = current.bgImageUri
        val nameInput = addTextInput(container, R.string.reader_style_name, current.name)
        val restoreView = addActionText(container, R.string.reader_style_restore)
        val darkStatusIconCheck = addCheckBox(container, R.string.reader_dark_status_icon, current.darkStatusIcon)
        val underlineCheck = addCheckBox(container, R.string.reader_text_underline, current.underline)
        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val textColorButton = styleConfigButton(R.string.reader_style_text_color, selectedTextColor)
        val bgColorButton = styleConfigButton(R.string.reader_style_bg_color, selectedBgColor)
        colorRow.addView(textColorButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            marginEnd = dp(8)
        })
        colorRow.addView(bgColorButton, LinearLayout.LayoutParams(0, dp(42), 1f))
        container.addView(colorRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        val alphaLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(readerTipColor)
        }
        val alphaSeek = SeekBar(this).apply {
            max = 100
            progress = current.bgAlpha.coerceIn(0, 100)
        }
        fun updateAlphaLabel() {
            alphaLabel.text = getString(R.string.reader_bg_alpha, alphaSeek.progress)
        }
        updateAlphaLabel()
        container.addView(alphaLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        container.addView(alphaSeek, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        fun persistDraftStyle() {
            val previous = readerStyleConfigs.getOrNull(index) ?: current
            readerStyleConfigs[index] = previous.copy(
                name = nameInput.text.toString().trim(),
                bgColor = selectedBgColor,
                textColor = selectedTextColor,
                tipColor = selectedTipColor,
                bgAlpha = alphaSeek.progress,
                darkStatusIcon = darkStatusIconCheck.isChecked,
                underline = underlineCheck.isChecked,
                bgAssetName = selectedBgAssetName,
                bgImageUri = selectedBgImageUri
            )
            selectReaderStyle(index)
        }
        val updateBgImageSelection = addBackgroundImagePicker(
            container = container,
            selectedAssetName = selectedBgAssetName,
            selectedImageUri = selectedBgImageUri,
            onSelected = { nextAssetName, nextImageUri ->
                selectedBgAssetName = nextAssetName
                selectedBgImageUri = nextImageUri
                persistDraftStyle()
            },
            onPickExternal = {
                persistDraftStyle()
                pendingReaderStyleImageIndex = index
                readerStyleImagePicker.launch(arrayOf("image/*"))
            }
        )
        textColorButton.setOnClickListener {
            showReaderColorPicker(READER_TEXT_COLOR_DIALOG_ID, selectedTextColor) { color ->
                selectedTextColor = color
                bindStyleConfigButton(textColorButton, R.string.reader_style_text_color, selectedTextColor)
                persistDraftStyle()
            }
        }
        bgColorButton.setOnClickListener {
            val pickerColor = if (selectedBgAssetName == null && selectedBgImageUri == null) {
                selectedBgColor
            } else {
                LEGADO_COLOR_PICKER_IMAGE_BG_FALLBACK
            }
            showReaderColorPicker(READER_BG_COLOR_DIALOG_ID, pickerColor) { color ->
                selectedBgColor = color
                selectedBgAssetName = null
                selectedBgImageUri = null
                bindStyleConfigButton(bgColorButton, R.string.reader_style_bg_color, selectedBgColor)
                updateBgImageSelection(selectedBgAssetName, selectedBgImageUri)
                persistDraftStyle()
            }
        }
        darkStatusIconCheck.setOnCheckedChangeListener { _, _ -> persistDraftStyle() }
        underlineCheck.setOnCheckedChangeListener { _, _ -> persistDraftStyle() }
        alphaSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateAlphaLabel()
                if (fromUser) persistDraftStyle()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        restoreView.setOnClickListener {
            val defaults = defaultLegadoReaderStyleConfigs()
            val restoreDialog = AlertDialog.Builder(this)
                .setTitle(R.string.reader_style_restore)
                .setItems(defaults.map { it.name }.toTypedArray()) { dialog, which ->
                    val restored = defaults[which]
                    nameInput.setText(restored.name)
                    selectedBgColor = restored.bgColor
                    selectedTextColor = restored.textColor
                    selectedTipColor = restored.tipColor
                    bindStyleConfigButton(bgColorButton, R.string.reader_style_bg_color, selectedBgColor)
                    bindStyleConfigButton(textColorButton, R.string.reader_style_text_color, selectedTextColor)
                    alphaSeek.progress = restored.bgAlpha
                    darkStatusIconCheck.isChecked = restored.darkStatusIcon
                    underlineCheck.isChecked = restored.underline
                    selectedBgAssetName = restored.bgAssetName
                    selectedBgImageUri = restored.bgImageUri
                    updateBgImageSelection(selectedBgAssetName, selectedBgImageUri)
                    readerStyleConfigs[index] = restored
                    selectReaderStyle(index)
                    dialog.dismiss()
                }
                .create()
            restoreDialog.setOnShowListener {
                restoreDialog.window?.run {
                    clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    val attr = attributes
                    attr.dimAmount = 0f
                    attributes = attr
                }
            }
            restoreDialog.show()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.reader_style_config_title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.reader_style_delete, null)
            .setPositiveButton(R.string.reader_dialog_done, null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.run {
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setBackgroundDrawable(ColorDrawable(readerBgColor))
                val attr = attributes
                attr.dimAmount = 0f
                attr.gravity = Gravity.BOTTOM
                attributes = attr
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).apply {
                isEnabled = readerStyleConfigs.size > 1
                setOnClickListener {
                    if (readerStyleConfigs.size <= 1) return@setOnClickListener
                    readerStyleConfigs.removeAt(index)
                    readerStyleSelect = readerStyleSelect.coerceAtMost(readerStyleConfigs.lastIndex)
                    selectReaderStyle(readerStyleSelect)
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val previous = readerStyleConfigs.getOrNull(index) ?: current
                readerStyleConfigs[index] = previous.copy(
                    name = nameInput.text.toString().trim(),
                    bgColor = selectedBgColor,
                    textColor = selectedTextColor,
                    tipColor = selectedTipColor,
                    bgAlpha = alphaSeek.progress,
                    darkStatusIcon = darkStatusIconCheck.isChecked,
                    underline = underlineCheck.isChecked,
                    bgAssetName = selectedBgAssetName,
                    bgImageUri = selectedBgImageUri
                )
                selectReaderStyle(index)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun addTextInput(container: LinearLayout, labelRes: Int, value: String): EditText {
        return addLabeledInput(container, labelRes).apply {
            setText(value)
            selectAll()
        }
    }

    private fun addCheckBox(container: LinearLayout, labelRes: Int, checked: Boolean): CheckBox {
        return CheckBox(this).apply {
            text = getString(labelRes)
            textSize = 14f
            setTextColor(readerTextColor)
            isChecked = checked
        }.also { checkBox ->
            container.addView(checkBox, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun addActionText(container: LinearLayout, labelRes: Int): TextView {
        return TextView(this).apply {
            text = getString(labelRes)
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(readerTextColor)
            setPadding(0, dp(10), 0, dp(10))
        }.also { view ->
            container.addView(view, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun styleConfigButton(labelRes: Int, color: Int): TextView {
        return TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            setPadding(dp(8), 0, dp(8), 0)
            bindStyleConfigButton(this, labelRes, color)
        }
    }

    private fun bindStyleConfigButton(view: TextView, labelRes: Int, color: Int) {
        view.text = getString(labelRes)
        view.setTextColor(readerTextColor)
        view.background = GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setColor(withAlpha(color, 0.18f))
            setStroke(dp(1), color)
        }
    }

    private fun showReaderColorPicker(dialogId: Int, currentColor: Int, onColor: (Int) -> Unit) {
        pendingReaderColorDialogId = dialogId
        pendingReaderColorSelected = onColor
        ColorPickerDialog.newBuilder()
            .setColor(currentColor)
            .setShowAlphaSlider(false)
            .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
            .setDialogId(dialogId)
            .show(this)
    }

    private fun addLabeledInput(container: LinearLayout, labelRes: Int): EditText {
        container.addView(TextView(this).apply {
            text = getString(labelRes)
            textSize = 13f
            setTextColor(readerTipColor)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8)
        })
        return EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
        }.also { input ->
            container.addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun addBackgroundImagePicker(
        container: LinearLayout,
        selectedAssetName: String?,
        selectedImageUri: String?,
        onSelected: (String?, String?) -> Unit,
        onPickExternal: () -> Unit
    ): (String?, String?) -> Unit {
        container.addView(TextView(this).apply {
            text = getString(R.string.reader_bg_image)
            textSize = 13f
            setTextColor(readerTipColor)
            setPadding(0, dp(10), 0, dp(4))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tiles = mutableMapOf<Pair<String?, String?>, LinearLayout>()
        fun addTile(assetName: String?, imageUri: String?, title: String, drawable: Drawable?) {
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(3), dp(3), dp(3), dp(3))
            }
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = ColorDrawable(if (assetName == null && imageUri == null) readerBgColor else 0x00000000)
                setImageDrawable(drawable)
            }
            val label = TextView(this).apply {
                text = title
                textSize = 10f
                setSingleLine(true)
                gravity = Gravity.CENTER
                setTextColor(readerTextColor)
            }
            tile.addView(image, LinearLayout.LayoutParams(dp(54), dp(44)))
            tile.addView(label, LinearLayout.LayoutParams(dp(62), LinearLayout.LayoutParams.WRAP_CONTENT))
            tile.setOnClickListener {
                onSelected(assetName, imageUri)
                bindBgTiles(tiles, assetName, imageUri)
            }
            row.addView(tile, LinearLayout.LayoutParams(dp(66), dp(88)).apply {
                marginEnd = dp(6)
            })
            tiles[assetName to imageUri] = tile
        }
        addTile(null, null, getString(R.string.reader_bg_image_none), null)
        if (!selectedImageUri.isNullOrBlank()) {
            val currentDrawable = runCatching {
                contentResolver.openInputStream(Uri.parse(selectedImageUri))?.use { input ->
                    Drawable.createFromStream(input, selectedImageUri)
                }
            }.getOrNull()
            addTile(null, selectedImageUri, getString(R.string.reader_bg_image_current), currentDrawable)
        }
        val pickTile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(0x00000000)
                setStroke(dp(1), readerTipColor)
            }
            setOnClickListener { onPickExternal() }
        }
        pickTile.addView(TextView(this).apply {
            text = "+"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(readerTextColor)
        }, LinearLayout.LayoutParams(dp(54), dp(44)))
        pickTile.addView(TextView(this).apply {
            text = getString(R.string.reader_select_image)
            textSize = 10f
            setSingleLine(true)
            gravity = Gravity.CENTER
            setTextColor(readerTextColor)
        }, LinearLayout.LayoutParams(dp(62), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(pickTile, LinearLayout.LayoutParams(dp(66), dp(88)).apply {
            marginEnd = dp(6)
        })
        legadoBgAssets().forEach { asset ->
            val drawable = runCatching {
                assets.open("legado_bg/$asset").use { input ->
                    Drawable.createFromStream(input, asset)
                }
            }.getOrNull()
            addTile(asset, null, asset.substringBeforeLast("."), drawable)
        }
        container.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(96)
        ))
        bindBgTiles(tiles, selectedAssetName, selectedImageUri)
        return { nextAssetName, nextImageUri -> bindBgTiles(tiles, nextAssetName, nextImageUri) }
    }

    private fun bindBgTiles(
        tiles: Map<Pair<String?, String?>, LinearLayout>,
        selectedAssetName: String?,
        selectedImageUri: String?
    ) {
        tiles.forEach { (key, tile) ->
            val selected = key.first == selectedAssetName && key.second == selectedImageUri
            tile.background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(0x00000000)
                setStroke(dp(if (selected) 2 else 1), if (selected) 0xFF2E9F6E.toInt() else readerTipColor)
            }
        }
    }

    private fun legadoBgAssets(): List<String> {
        return runCatching {
            assets.list("legado_bg")?.toList().orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun showTextWeightDialog(onChanged: (M9TextWeight) -> Unit = {}) {
        val choices = arrayOf(
            getString(R.string.reader_text_weight_normal),
            getString(R.string.reader_text_weight_bold),
            getString(R.string.reader_text_weight_light)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_text_font_weight_converter)
            .setSingleChoiceItems(choices, readerTextWeight.ordinal) { dialog, which ->
                readerTextWeight = M9TextWeight.fromIndex(which)
                readView.setTextWeight(readerTextWeight)
                onChanged(readerTextWeight)
                updateSelectedReaderStyleLayoutFields()
                dialog.dismiss()
                requestBookRelayout(immediate = true)
            }
            .show()
    }

    private fun showIndentDialog() {
        val choices = resources.getStringArray(R.array.reader_indent_titles)
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_title_indent)
            .setSingleChoiceItems(choices, readerParagraphIndentCount) { dialog, which ->
                readerParagraphIndentCount = which
                updateSelectedReaderStyleLayoutFields()
                dialog.dismiss()
                requestBookRelayout()
            }
            .show()
    }

    private fun showFontDialog() {
        val choices = resources.getStringArray(R.array.reader_font_titles)
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_title_font)
            .setSingleChoiceItems(choices, readerTypefaceIndex) { dialog, which ->
                readerTypefaceIndex = which
                readerTypeface = readerTypefaceForIndex(which)
                readView.setReaderTypeface(readerTypeface)
                updateSelectedReaderStyleLayoutFields()
                dialog.dismiss()
                requestBookRelayout()
            }
            .show()
    }

    private fun showImagePreviewDialog(image: EbookImageRef) {
        val maxPreviewSide = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
            .coerceAtLeast(1)
        val bytes = image.readBytes()
        if (bytes == null) {
            Toast.makeText(this, R.string.reader_image_preview_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val bitmap = decodeSampledBitmap(
            bytes = bytes,
            targetWidthPx = maxPreviewSide,
            targetHeightPx = maxPreviewSide
        )
        if (bitmap == null) {
            Toast.makeText(this, R.string.reader_image_preview_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val imageView = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bitmap)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle(image.altText.ifBlank { image.path.substringAfterLast('/') })
            .setView(imageView)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showPaddingDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), 0)
        }
        val label = text(getString(R.string.reader_margin_label, readerPaddingDp), 15f, MENU_TEXT)
        val seek = SeekBar(this).apply {
            max = 40
            progress = readerPaddingDp.coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        readerPaddingDp = progress
                        label.text = getString(R.string.reader_margin_label, readerPaddingDp)
                        readView.setReaderPadding(
                            dp(readerPaddingDp),
                            dp(34),
                            dp(readerPaddingDp),
                            currentReaderBottomPaddingPx()
                        )
                        updateSelectedReaderStyleLayoutFields()
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    requestBookRelayout(immediate = true)
                }
            })
        }
        container.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)))
        container.addView(seek, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_title_margin)
            .setView(container)
            .setPositiveButton(R.string.reader_dialog_done, null)
            .show()
    }

    private fun showTipConfigDialog() {
        var dialog: AlertDialog? = null
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(18))
        }
        addInfoSectionTitle(content, "正文标题")
        addBodyTitleModeGroup(content)
        addInfoAdjustRow(content, "字号", bodyTitleSizeAddSp, 0, 10) {
            bodyTitleSizeAddSp = it
            applyTipConfigChange(repaginate = true)
        }
        addInfoAdjustRow(content, "上边距", bodyTitleTopSpacingDp, 0, 100) {
            bodyTitleTopSpacingDp = it
            applyTipConfigChange(repaginate = true)
        }
        addInfoAdjustRow(content, "下边距", bodyTitleBottomSpacingDp, 0, 100) {
            bodyTitleBottomSpacingDp = it
            applyTipConfigChange(repaginate = true)
        }

        addInfoSectionTitle(content, "页眉")
        addInfoSelectorRow(content, "显示/隐藏", headerModeLabel(headerMode)) {
            dialog?.dismiss()
            showHeaderModeSelector()
        }
        addInfoSelectorRow(content, "左", tipContentLabel(tipHeaderLeft)) {
            dialog?.dismiss()
            showTipContentSelector(tipHeaderLeft) {
                tipHeaderLeft = it
                applyTipConfigChange(repaginate = true)
            }
        }
        addInfoSelectorRow(content, "中", tipContentLabel(tipHeaderMiddle)) {
            dialog?.dismiss()
            showTipContentSelector(tipHeaderMiddle) {
                tipHeaderMiddle = it
                applyTipConfigChange(repaginate = true)
            }
        }
        addInfoSelectorRow(content, "右", tipContentLabel(tipHeaderRight)) {
            dialog?.dismiss()
            showTipContentSelector(tipHeaderRight) {
                tipHeaderRight = it
                applyTipConfigChange(repaginate = true)
            }
        }

        addInfoSectionTitle(content, "页脚")
        addInfoSelectorRow(content, "显示/隐藏", footerModeLabel(footerMode)) {
            dialog?.dismiss()
            showFooterModeSelector()
        }
        addInfoSelectorRow(content, "左", tipContentLabel(tipFooterLeft)) {
            dialog?.dismiss()
            showTipContentSelector(tipFooterLeft) {
                tipFooterLeft = it
                applyTipConfigChange(repaginate = true)
            }
        }
        addInfoSelectorRow(content, "中", tipContentLabel(tipFooterMiddle)) {
            dialog?.dismiss()
            showTipContentSelector(tipFooterMiddle) {
                tipFooterMiddle = it
                applyTipConfigChange(repaginate = true)
            }
        }
        addInfoSelectorRow(content, "右", tipContentLabel(tipFooterRight)) {
            dialog?.dismiss()
            showTipContentSelector(tipFooterRight) {
                tipFooterRight = it
                applyTipConfigChange(repaginate = true)
            }
        }

        addInfoSectionTitle(content, "页眉&页脚")
        addInfoSelectorRow(content, "文字颜色", tipColorModeLabel(tipColorMode)) {
            dialog?.dismiss()
            showTipColorModeSelector()
        }
        addInfoSelectorRow(content, "分隔线颜色", tipDividerColorModeLabel(tipDividerColorMode)) {
            dialog?.dismiss()
            showTipDividerColorModeSelector()
        }

        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.reader_title_info)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton(R.string.reader_dialog_done, null)
            .show()
    }

    private fun addInfoSectionTitle(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 20f
            setTextColor(0xFFE0483F.toInt())
            includeFontPadding = true
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })
    }

    private fun addBodyTitleModeGroup(parent: LinearLayout) {
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val options = listOf(
            ReaderBodyTitleMode.LEFT to "靠左",
            ReaderBodyTitleMode.CENTER to "居中",
            ReaderBodyTitleMode.HIDE to "隐藏"
        )
        options.forEach { (mode, label) ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 16f
                setTextColor(readerTextColor)
                isChecked = bodyTitleMode == mode
                setOnClickListener {
                    bodyTitleMode = mode
                    applyTipConfigChange(repaginate = true)
                }
            }, RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.WRAP_CONTENT,
                RadioGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(14) })
        }
        parent.addView(group, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun addInfoAdjustRow(
        parent: LinearLayout,
        label: String,
        initialValue: Int,
        min: Int,
        max: Int,
        onChange: (Int) -> Unit
    ) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val valueView = TextView(this).apply {
            text = initialValue.toString()
            textSize = 16f
            gravity = Gravity.END
            setTextColor(readerTextColor)
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 16f
            setTextColor(readerTextColor)
        }, LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(TextView(this).apply {
            text = "－"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(readerTextColor)
            setOnClickListener {
                val value = (valueView.text.toString().toIntOrNull() ?: initialValue).minus(1).coerceIn(min, max)
                valueView.text = value.toString()
                onChange(value)
            }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        row.addView(SeekBar(this).apply {
            this.max = max - min
            progress = (initialValue - min).coerceIn(0, this.max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val value = min + progress
                    valueView.text = value.toString()
                    onChange(value)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        row.addView(TextView(this).apply {
            text = "＋"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(readerTextColor)
            setOnClickListener {
                val value = (valueView.text.toString().toIntOrNull() ?: initialValue).plus(1).coerceIn(min, max)
                valueView.text = value.toString()
                (row.getChildAt(2) as? SeekBar)?.progress = value - min
                onChange(value)
            }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        row.addView(valueView, LinearLayout.LayoutParams(dp(42), LinearLayout.LayoutParams.WRAP_CONTENT))
        parent.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun addInfoSelectorRow(parent: LinearLayout, label: String, value: String, onClick: () -> Unit) {
        parent.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            setOnClickListener { onClick() }
            addView(TextView(this@LegadoReaderActivity).apply {
                text = label
                textSize = 16f
                setTextColor(readerTextColor)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@LegadoReaderActivity).apply {
                text = value
                textSize = 16f
                gravity = Gravity.END
                setTextColor(readerTextColor)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun showHeaderModeSelector() {
        val entries = listOf(
            ReaderHeaderMode.HIDE_WHEN_STATUS_BAR_SHOW to "状态栏显示时隐藏",
            ReaderHeaderMode.SHOW to "显示",
            ReaderHeaderMode.HIDE to "隐藏"
        )
        showSimpleSelector("页眉", entries, headerMode) {
            headerMode = it
            applyTipConfigChange(repaginate = true)
            showTipConfigDialog()
        }
    }

    private fun showFooterModeSelector() {
        val entries = listOf(
            ReaderFooterMode.SHOW to "显示",
            ReaderFooterMode.HIDE to "隐藏"
        )
        showSimpleSelector("页脚", entries, footerMode) {
            footerMode = it
            applyTipConfigChange(repaginate = true)
            showTipConfigDialog()
        }
    }

    private fun showTipContentSelector(current: ReaderTipContent, onSelected: (ReaderTipContent) -> Unit) {
        showSimpleSelector("显示内容", tipContentEntries(), current) {
            onSelected(it)
            showTipConfigDialog()
        }
    }

    private fun showTipColorModeSelector() {
        val entries = listOf(
            ReaderTipColorMode.FOLLOW_CONTENT to "跟随内容",
            ReaderTipColorMode.CUSTOM to "自定义"
        )
        showSimpleSelector("文字颜色", entries, tipColorMode) {
            tipColorMode = it
            if (it == ReaderTipColorMode.CUSTOM) {
                showReaderColorPicker(READER_TIP_COLOR_DIALOG_ID, readerTipColor) { color ->
                    readerTipColor = color
                    applyTipConfigChange(repaginate = false)
                    showTipConfigDialog()
                }
                applyTipConfigChange(repaginate = false)
                return@showSimpleSelector
            }
            applyTipConfigChange(repaginate = false)
            showTipConfigDialog()
        }
    }

    private fun showTipDividerColorModeSelector() {
        val entries = listOf(
            ReaderTipDividerColorMode.DEFAULT to "默认",
            ReaderTipDividerColorMode.FOLLOW_CONTENT to "跟随内容",
            ReaderTipDividerColorMode.CUSTOM to "自定义"
        )
        showSimpleSelector("分隔线颜色", entries, tipDividerColorMode) {
            tipDividerColorMode = it
            if (it == ReaderTipDividerColorMode.CUSTOM) {
                showReaderColorPicker(READER_TIP_DIVIDER_COLOR_DIALOG_ID, tipDividerColor) { color ->
                    tipDividerColor = color
                    applyTipConfigChange(repaginate = false)
                    showTipConfigDialog()
                }
                applyTipConfigChange(repaginate = false)
                return@showSimpleSelector
            }
            applyTipConfigChange(repaginate = false)
            showTipConfigDialog()
        }
    }

    private fun <T> showSimpleSelector(
        title: String,
        entries: List<Pair<T, String>>,
        current: T,
        onSelected: (T) -> Unit
    ) {
        val labels = entries.map { it.second }.toTypedArray()
        val checked = entries.indexOfFirst { it.first == current }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                onSelected(entries[which].first)
            }
            .show()
    }

    private fun applyTipConfigChange(repaginate: Boolean) {
        applyReaderVisualStyle()
        persistReaderSettings(updateAnchor = false)
        if (repaginate) {
            requestBookRelayout()
        } else {
            renderCurrentPage()
        }
    }

    private fun tipContentEntries(): List<Pair<ReaderTipContent, String>> = listOf(
        ReaderTipContent.NONE to "无",
        ReaderTipContent.BOOK_NAME to "标题",
        ReaderTipContent.CHAPTER_TITLE to "章节标题",
        ReaderTipContent.TIME to "时间",
        ReaderTipContent.BATTERY to "电量",
        ReaderTipContent.BATTERY_PERCENTAGE to "电量百分比",
        ReaderTipContent.PAGE to "页数",
        ReaderTipContent.TOTAL_PROGRESS to "总进度",
        ReaderTipContent.CHAPTER_PROGRESS to "章节页数",
        ReaderTipContent.PAGE_AND_TOTAL to "页数及进度",
        ReaderTipContent.TIME_BATTERY to "时间+电量",
        ReaderTipContent.TIME_BATTERY_PERCENTAGE to "时间+电量百分比"
    )

    private fun tipContentLabel(content: ReaderTipContent): String {
        return tipContentEntries().firstOrNull { it.first == content }?.second ?: "无"
    }

    private fun headerModeLabel(mode: ReaderHeaderMode): String = when (mode) {
        ReaderHeaderMode.HIDE_WHEN_STATUS_BAR_SHOW -> "状态栏显示时隐藏"
        ReaderHeaderMode.SHOW -> "显示"
        ReaderHeaderMode.HIDE -> "隐藏"
    }

    private fun footerModeLabel(mode: ReaderFooterMode): String = when (mode) {
        ReaderFooterMode.SHOW -> "显示"
        ReaderFooterMode.HIDE -> "隐藏"
    }

    private fun tipColorModeLabel(mode: ReaderTipColorMode): String = when (mode) {
        ReaderTipColorMode.FOLLOW_CONTENT -> "跟随内容"
        ReaderTipColorMode.CUSTOM -> "自定义"
    }

    private fun tipDividerColorModeLabel(mode: ReaderTipDividerColorMode): String = when (mode) {
        ReaderTipDividerColorMode.DEFAULT -> "默认"
        ReaderTipDividerColorMode.FOLLOW_CONTENT -> "跟随内容"
        ReaderTipDividerColorMode.CUSTOM -> "自定义"
    }

    private fun loadDisplayedBook(anchor: ReaderPageAnchor?, forceDocumentReload: Boolean = false) {
        val pageWidth = readView.contentWidth.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels - dp(44))
        val pageHeight = readView.contentHeight.takeIf { it > 0 } ?: (resources.displayMetrics.heightPixels - dp(220))
        val book = importedBook
        paginationJob?.cancel()
        paginationJob = lifecycleScope.launch {
            val startupStartMs = SystemClock.elapsedRealtime()
            Log.d(
                LEGADO_READER_LOG_TAG,
                "readerStartup start forceReload=$forceDocumentReload anchor=$anchor " +
                    "page=${pageWidth}x$pageHeight book=${book?.uri}"
            )
            runCatching {
                val loadDocumentStartMs = SystemClock.elapsedRealtime()
                val loaded = loadOrReuseDocument(book, forceDocumentReload)
                Log.d(
                    LEGADO_READER_LOG_TAG,
                    "readerStartup loadDocument=${SystemClock.elapsedRealtime() - loadDocumentStartMs}ms " +
                        "chapters=${loaded.chapters.size}"
                )
                document = loaded
                val currentChapterIndex = currentPageAnchor()?.chapterIndex
                    ?: pendingRestoreAnchor?.chapterIndex
                    ?: 0
                val previewChapterIndex = (anchor?.chapterIndex ?: currentChapterIndex).coerceIn(
                    0,
                    loaded.chapters.lastIndex.coerceAtLeast(0)
                )
                val previewStartMs = SystemClock.elapsedRealtime()
                val previewPages = getOrPaginateChapterPages(
                    document = loaded,
                    chapterIndex = previewChapterIndex,
                    contentWidthPx = pageWidth.coerceAtLeast(1),
                    contentHeightPx = pageHeight.coerceAtLeast(dp(120))
                )
                Log.d(
                    LEGADO_READER_LOG_TAG,
                    "readerStartup chapterPaginate=${SystemClock.elapsedRealtime() - previewStartMs}ms " +
                        "chapter=$previewChapterIndex pages=${previewPages.size}"
                )
                pages = previewPages
                pageIndex = anchor
                    ?.let { pageIndexForAnchor(previewPages, it) }
                    ?: 0
                updateDisplayedBookTitle()
                val firstRenderStartMs = SystemClock.elapsedRealtime()
                renderCurrentPage()
                Log.d(
                    LEGADO_READER_LOG_TAG,
                    "readerStartup firstRender=${SystemClock.elapsedRealtime() - firstRenderStartMs}ms " +
                        "firstText=${SystemClock.elapsedRealtime() - startupStartMs}ms pageIndex=$pageIndex"
                )
                if (cueMatchesByCueIndex.isNotEmpty()) {
                    Log.d(LEGADO_READER_LOG_TAG, "readerStartup syncToAudioPosition begin")
                    syncToAudioPosition(allowPageJump = isAudioPlaying())
                } else {
                    Log.d(LEGADO_READER_LOG_TAG, "readerStartup loadSrtSync begin")
                    loadSrtSyncIfNeeded()
                    if (cues.isNotEmpty()) {
                        Log.d(
                            LEGADO_READER_LOG_TAG,
                            "loadDisplayedBook no in-memory matches; trying persisted restore"
                        )
                        restorePersistedMatchIfPossible()
                    }
                }
                preloadAdjacentChapters(
                    document = loaded,
                    centerChapterIndex = previewChapterIndex,
                    contentWidthPx = pageWidth.coerceAtLeast(1),
                    contentHeightPx = pageHeight.coerceAtLeast(dp(120))
                )
            }.onSuccess {
                Log.d(
                    LEGADO_READER_LOG_TAG,
                    "readerStartup done=${SystemClock.elapsedRealtime() - startupStartMs}ms pageIndex=$pageIndex"
                )
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                Log.w(
                    LEGADO_READER_LOG_TAG,
                    "readerStartup failed after ${SystemClock.elapsedRealtime() - startupStartMs}ms",
                    error
                )
                readView.setPage(
                    TextPage(
                        title = currentReaderTitle(),
                        text = getString(
                            R.string.reader_open_ebook_failed,
                            error.message ?: error.javaClass.simpleName
                        ),
                        totalPages = 1
                    )
                )
            }
        }
    }

    private suspend fun loadOrReuseDocument(
        book: LocalReaderBook?,
        forceDocumentReload: Boolean
    ): EbookDocument {
        val bookUriText = book?.uri?.toString()
        val canReuseDocument = !forceDocumentReload &&
            document != null &&
            loadedDocumentBookUriText == bookUriText &&
            loadedDocumentCharsetName == preferredCharsetName
        if (canReuseDocument) {
            Log.d(
                LEGADO_READER_LOG_TAG,
                "readerStartup loadOrReuseDocument reused bookUri=$bookUriText charset=$preferredCharsetName"
            )
            return document!!
        }
        val startMs = SystemClock.elapsedRealtime()
        val loaded = if (book != null) {
            withContext(Dispatchers.IO) {
                loadEbookDocument(
                    context = this@LegadoReaderActivity,
                    book = book,
                    preferredCharsetName = preferredCharsetName
                )
            }
        } else {
            EbookDocument(
                title = LEGADO_READER_DEFAULT_TITLE,
                format = "TXT",
                chapters = listOf(
                    EbookChapter(
                        title = LEGADO_READER_DEFAULT_TITLE,
                        text = LEGADO_READER_DEFAULT_PARAGRAPHS.joinToString("\n\n")
                    )
                )
            )
        }
        Log.d(
            LEGADO_READER_LOG_TAG,
            "readerStartup loadOrReuseDocument parsed=${SystemClock.elapsedRealtime() - startMs}ms " +
                "format=${loaded.format} chapters=${loaded.chapters.size}"
        )
        clearChapterPageCache()
        loadedDocumentBookUriText = bookUriText
        loadedDocumentCharsetName = preferredCharsetName
        if (forceDocumentReload) {
            Log.d(
                LEGADO_READER_LOG_TAG,
                "loadOrReuseDocument force reload resets in-memory match cache bookUri=$bookUriText charset=$preferredCharsetName"
            )
            cueMatchesByCueIndex = emptyMap()
            matchData = null
            audioCueIndex = -1
            activeCueIndex = -1
        }
        return loaded
    }

    private fun paginateDocument(
        document: EbookDocument,
        contentWidthPx: Int,
        contentHeightPx: Int
    ): List<TextPage> {
        return buildTextPageFactory().createPages(
            document = document,
            contentWidthPx = contentWidthPx,
            contentHeightPx = effectiveReaderContentHeightPx(contentHeightPx)
        )
    }

    private fun buildTextPageFactory(): TextPageFactory {
        return TextPageFactory(
            M9ReadBookConfig(
                textSizePx = readView.textSizePx,
                lineSpacingPx = dp(readerLineSpacingDp).toFloat(),
                paragraphSpacingPx = dp(readerParagraphSpacingDp).toFloat(),
                textColor = readerTextColor,
                tipColor = readerTipColor,
                backgroundColor = readerBgColor,
                useZhLayout = useZhLayout,
                textFullJustify = textFullJustify,
                textBottomJustify = textBottomJustify,
                paragraphIndent = "　".repeat(readerParagraphIndentCount),
                letterSpacingPx = dp(readerLetterSpacingDp).toFloat(),
                textWeight = readerTextWeight,
                typeface = readerTypeface,
                paddingLeftPx = dp(readerPaddingDp),
                paddingRightPx = dp(readerPaddingDp),
                layoutMode = readerLayoutMode,
                pageAnim = readerPageAnim,
                showRubyText = showRubyText
            )
        )
    }

    private fun effectiveReaderContentHeightPx(contentHeightPx: Int): Int {
        return (contentHeightPx - readerBodyBottomReservePx() - readerBodyTitleReservePx()).coerceAtLeast(dp(120))
    }

    private fun readerBodyTitleReservePx(): Int {
        if (bodyTitleMode == ReaderBodyTitleMode.HIDE) return 0
        val titleTextSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            (readerTextSizeSp + bodyTitleSizeAddSp).toFloat(),
            resources.displayMetrics
        )
        return (titleTextSizePx * 1.55f).toInt() +
            dp(bodyTitleTopSpacingDp) +
            dp(bodyTitleBottomSpacingDp)
    }

    private suspend fun getOrPaginateChapterPages(
        document: EbookDocument,
        chapterIndex: Int,
        contentWidthPx: Int,
        contentHeightPx: Int
    ): List<TextPage> {
        if (document.chapters.isEmpty()) {
            return paginateDocument(document, contentWidthPx, contentHeightPx)
        }
        val effectiveContentHeightPx = effectiveReaderContentHeightPx(contentHeightPx)
        val safeChapterIndex = chapterIndex.coerceIn(0, document.chapters.lastIndex)
        val cacheKey = ReaderChapterPageCacheKey(
            chapterIndex = safeChapterIndex,
            contentWidthPx = contentWidthPx,
            contentHeightPx = effectiveContentHeightPx
        )
        chapterPageCache[cacheKey]?.let { cachedPages ->
            Log.d(
                LEGADO_READER_LOG_TAG,
                "chapterPageCache hit chapter=$safeChapterIndex pages=${cachedPages.size}"
            )
            return cachedPages
        }
        val factory = buildTextPageFactory()
        val pages = withContext(Dispatchers.Default) {
            factory.createChapterPages(
                document = document,
                chapterIndex = safeChapterIndex,
                contentWidthPx = contentWidthPx,
                contentHeightPx = effectiveContentHeightPx
            )
        }
        chapterPageCache[cacheKey] = pages
        return pages
    }

    private fun currentChapterPageCacheKey(chapterIndex: Int): ReaderChapterPageCacheKey {
        val pageWidth = readView.contentWidth.takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels - dp(44))
        val pageHeight = readView.contentHeight.takeIf { it > 0 }
            ?: (resources.displayMetrics.heightPixels - dp(220))
        return ReaderChapterPageCacheKey(
            chapterIndex = chapterIndex,
            contentWidthPx = pageWidth.coerceAtLeast(1),
            contentHeightPx = effectiveReaderContentHeightPx(pageHeight.coerceAtLeast(dp(120)))
        )
    }

    private fun preloadAdjacentChapters(
        document: EbookDocument,
        centerChapterIndex: Int,
        contentWidthPx: Int,
        contentHeightPx: Int
    ) {
        if (document.chapters.size <= 1) return
        val safeCenter = centerChapterIndex.coerceIn(0, document.chapters.lastIndex)
        pruneChapterPageCache(safeCenter, contentWidthPx, effectiveReaderContentHeightPx(contentHeightPx))
        chapterPreloadJob?.cancel()
        chapterPreloadJob = lifecycleScope.launch {
            listOf(safeCenter - 1, safeCenter + 1)
                .filter { it in document.chapters.indices }
                .forEach { chapterIndex ->
                    val startMs = SystemClock.elapsedRealtime()
                    runCatching {
                        getOrPaginateChapterPages(
                            document = document,
                            chapterIndex = chapterIndex,
                            contentWidthPx = contentWidthPx,
                            contentHeightPx = contentHeightPx
                        )
                    }.onSuccess { loadedPages ->
                        Log.d(
                            LEGADO_READER_LOG_TAG,
                            "chapterPreload done=${SystemClock.elapsedRealtime() - startMs}ms " +
                                "chapter=$chapterIndex pages=${loadedPages.size}"
                        )
                    }.onFailure { error ->
                        if (error is CancellationException) return@launch
                        Log.w(LEGADO_READER_LOG_TAG, "chapterPreload failed chapter=$chapterIndex", error)
                    }
                }
        }
    }

    private fun pruneChapterPageCache(
        centerChapterIndex: Int,
        contentWidthPx: Int,
        effectiveContentHeightPx: Int
    ) {
        chapterPageCache.keys.removeAll { key ->
            key.contentWidthPx != contentWidthPx ||
                key.contentHeightPx != effectiveContentHeightPx ||
                abs(key.chapterIndex - centerChapterIndex) > 2
        }
    }

    private fun clearChapterPageCache() {
        chapterPreloadJob?.cancel()
        chapterPreloadJob = null
        chapterPageCache.clear()
    }

    private fun readerBodyBottomReservePx(): Int {
        if (!showReadTitleAddition) return 0
        return dp(12)
    }

    private fun renderCurrentPage(
        forward: Boolean = true,
        persistAnchor: Boolean = false,
        anchorSource: String = "render"
    ) {
        resumeImagePauseIfPageLeft()
        val normalPage = pages.getOrNull(pageIndex)
        val page = normalPage
        if (page == null) {
            hideCrossPageCueWindow()
            readView.setPage(
                TextPage(
                    title = currentReaderTitle(),
                    text = LEGADO_READER_DEFAULT_PARAGRAPHS.joinToString("\n\n"),
                    totalPages = 1
                )
            )
            return
        }
        val match = currentPageCueMatch(page)
        val highlight = highlightTextPage(page, match)
        val currentSearchHit = searchHits.getOrNull(searchHitIndex)
        val searchHighlight = searchQuery
            ?.takeIf { currentSearchHit?.chapterIndex == page.chapterIndex }
            ?.let { query ->
                val absoluteStart = currentSearchHit?.chapterPosition ?: -1
                val start = if (absoluteStart >= page.charStart && absoluteStart < page.charEnd) {
                    absoluteStart - page.charStart
                } else {
                    page.text.indexOf(query, ignoreCase = true)
                }
                start.takeIf { it >= 0 }?.let { it until (it + query.length) }
            }
        readView.setPage(page, highlight, searchHighlight, forward = forward)
        updateCrossPageCueWindow(page, match)
        updateProgressSeekBar()
        if (persistAnchor) {
            persistReaderAnchorFromPage(anchorSource)
        }
    }

    private fun persistReaderAnchorFromPage(source: String) {
        val anchor = currentPageAnchor(includeCueMatch = false)
        persistReaderAnchor(source, anchor)
    }

    private fun persistReaderAnchor(source: String, anchor: ReaderPageAnchor?) {
        Log.d(
            LEGADO_READER_LOG_TAG,
            "readerProgress save source=$source anchor=$anchor pageIndex=$pageIndex pages=${pages.size}"
        )
        persistReaderSettings(updateAnchor = true, anchorOverride = anchor)
    }

    private fun useM4bChapterSource(): Boolean {
        return chapterSourceMode == ReaderChapterSourceMode.M4B && m4bChapters.isNotEmpty()
    }

    private fun currentChapterSourceSummary(): String {
        return if (chapterSourceMode == ReaderChapterSourceMode.M4B) {
            readerString(R.string.reader_chapter_source_m4b)
        } else {
            readerString(R.string.reader_chapter_source_book)
        }
    }

    private fun ensureM4bChaptersLoaded(onLoaded: ((Boolean) -> Unit)? = null) {
        val uri = audioUri
        if (uri == null) {
            m4bChapters = emptyList()
            onLoaded?.invoke(false)
            return
        }
        if (m4bChapters.isNotEmpty()) {
            onLoaded?.invoke(true)
            return
        }
        if (m4bChapterLoadJob?.isActive == true) {
            val activeJob = m4bChapterLoadJob
            if (onLoaded != null) {
                activeJob?.invokeOnCompletion {
                    lifecycleScope.launch {
                        onLoaded(m4bChapters.isNotEmpty())
                    }
                }
            }
            return
        }
        m4bChapterLoadJob = lifecycleScope.launch {
            val chapters = withContext(Dispatchers.IO) {
                loadM4bChapters(this@LegadoReaderActivity, contentResolver, uri)
            }
            m4bChapters = chapters
            onLoaded?.invoke(chapters.isNotEmpty())
            if (chapters.isNotEmpty()) {
                updateProgressSeekBar()
            }
        }
    }
    private val floatingBridgeController = object : BookReaderFloatingBridge.Controller {
        override fun isPlaying(): Boolean = isAudioPlaying()
        override fun isFavorite(): Boolean = false
        override fun isCueLoopEnabled(): Boolean = false

        override fun togglePlayPause() {
            runOnUiThread { toggleAudioPlayback() }
        }

        override fun setPlaying(play: Boolean) {
            runOnUiThread {
                val currentPlayer = player ?: return@runOnUiThread
                if (play && !currentPlayer.isPlaying) {
                    clearImagePauseResume()
                    currentPlayer.play()
                } else if (!play && currentPlayer.isPlaying) {
                    currentPlayer.pause()
                }
                publishReaderPlaybackBridgeSnapshot(notifyState = true)
                updateAudioControlLabels()
            }
        }

        override fun seekToPosition(targetPositionMs: Long) {
            runOnUiThread {
                player?.seekTo(targetPositionMs.coerceAtLeast(0L))
                publishReaderPlaybackBridgeSnapshot(notifyState = false)
                syncToAudioPosition(allowPageJump = true, forceReveal = true)
            }
        }

        override fun setPlaybackSpeed(speed: Float) {
            runOnUiThread { setAudioPlaybackSpeed(speed) }
        }

        override fun seekPrevious() {
            runOnUiThread { seekToAdjacentCue(if (verticalControlDirectionReversed) 1 else -1) }
        }

        override fun seekNext() {
            runOnUiThread { seekToAdjacentCue(if (verticalControlDirectionReversed) -1 else 1) }
        }

        override fun replayCurrentCue() {
            runOnUiThread {
                val cue = cues.getOrNull(audioCueIndex) ?: cues.getOrNull(activeCueIndex) ?: return@runOnUiThread
                player?.seekTo(cue.startMs)
                publishReaderPlaybackBridgeSnapshot(notifyState = false)
                syncToAudioPosition(allowPageJump = true, forceReveal = true)
            }
        }

        override fun toggleCueLoop() = Unit
        override fun toggleFavorite() = Unit
        override fun returnToPlayer() {
            runOnUiThread { returnToSharedPlayer() }
        }

        override fun lookupCurrentSubtitleAt(offset: Int) = Unit
    }

    private fun currentM4bChapterIndex(): Int {
        if (m4bChapters.isEmpty()) return 0
        val positionMs = currentAudioPositionMs() ?: 0L
        return m4bChapters.indexOfLast { it.startMs <= positionMs }
            .coerceAtLeast(0)
            .coerceAtMost(m4bChapters.lastIndex)
    }

    private fun seekToM4bChapter(targetIndex: Int) {
        val target = m4bChapters.getOrNull(targetIndex) ?: return
        val currentPlayer = player ?: return
        currentPlayer.seekTo(target.startMs)
        activeCueIndex = -1
        publishReaderPlaybackBridgeSnapshot(notifyState = false)
        persistAudioPlaybackSnapshot()
        if (cues.isNotEmpty()) {
            syncToAudioPosition(allowPageJump = true, forceReveal = true)
        } else {
            loadSrtSyncIfNeeded {
                syncToAudioPosition(allowPageJump = true, forceReveal = true)
            }
        }
        updateProgressSeekBar()
    }

    private fun updateProgressSeekBar() {
        if (pages.isEmpty()) return
        if (!progressByChapter && useM4bChapterSource()) {
            chapterSeekBar.max = (m4bChapters.size - 1).coerceAtLeast(0)
            chapterSeekBar.progress = currentM4bChapterIndex().coerceIn(0, chapterSeekBar.max)
            return
        }
        if (!progressByChapter) {
            val lastChapterIndex = document?.chapters?.lastIndex ?: 0
            val currentChapterIndex = pages.getOrNull(pageIndex)?.chapterIndex ?: 0
            chapterSeekBar.max = lastChapterIndex.coerceAtLeast(0)
            chapterSeekBar.progress = currentChapterIndex.coerceIn(0, chapterSeekBar.max)
            return
        }
        val page = pages.getOrNull(pageIndex) ?: return
        val chapterPages = pages.filter { it.chapterIndex == page.chapterIndex }
        chapterSeekBar.max = (chapterPages.size - 1).coerceAtLeast(0)
        chapterSeekBar.progress = page.pageInChapter.coerceIn(0, chapterSeekBar.max)
    }

    private fun seekProgressToPageIndex(progress: Int): Int {
        val currentChapter = pages.getOrNull(pageIndex)?.chapterIndex ?: return pageIndex
        val chapterStart = pages.indexOfFirst { it.chapterIndex == currentChapter }
        if (chapterStart < 0) return pageIndex
        return (chapterStart + progress).coerceIn(chapterStart, pages.lastIndex)
    }

    private fun confirmOrJumpToChapterFromSeekBar(targetChapter: Int) {
        if (useM4bChapterSource()) {
            val safeTarget = targetChapter.coerceIn(0, m4bChapters.lastIndex.coerceAtLeast(0))
            seekToM4bChapter(safeTarget)
            return
        }
        val currentChapter = pages.getOrNull(pageIndex)?.chapterIndex ?: 0
        val lastChapter = document?.chapters?.lastIndex ?: return updateProgressSeekBar()
        val safeTarget = targetChapter.coerceIn(0, lastChapter.coerceAtLeast(0))
        if (safeTarget == currentChapter || confirmSkipToChapter) {
            jumpToChapterFromSeekBar(safeTarget, currentChapter)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_chapter_jump_confirm_title)
            .setMessage(R.string.reader_chapter_jump_confirm_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                confirmSkipToChapter = true
                jumpToChapterFromSeekBar(safeTarget, currentChapter)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                updateProgressSeekBar()
            }
            .setOnCancelListener {
                updateProgressSeekBar()
            }
            .show()
    }

    private fun jumpToChapterFromSeekBar(targetChapter: Int, currentChapter: Int) {
        showAnchorOrLoad(
            anchor = ReaderPageAnchor(targetChapter, 0),
            forward = targetChapter >= currentChapter,
            anchorSource = "chapterSeek"
        )
    }

    private fun highlightTextPage(page: TextPage, match: EbookCueMatch?): IntRange? {
        if (match == null || match.chapterIndex != page.chapterIndex) return null
        val absoluteStart = match.rawStart.coerceAtLeast(page.charStart)
        val absoluteEnd = match.rawEnd.coerceAtMost(page.charEnd)
        if (absoluteEnd <= absoluteStart) return null
        val start = (absoluteStart - page.charStart).coerceIn(0, page.text.length)
        val end = (absoluteEnd - page.charStart).coerceIn(start, page.text.length)
        return (start until end).takeIf { it.first < it.last }
    }

    private fun currentPageCueMatch(page: TextPage): EbookCueMatch? {
        return cueMatchesByCueIndex[activeCueIndex]
            ?.takeIf { it.intersects(page) }
            ?: cueMatchesByCueIndex[audioCueIndex]
                ?.takeIf { it.intersects(page) }
    }

    private fun updateCrossPageCueWindow(page: TextPage?, match: EbookCueMatch?) {
        if (!::readView.isInitialized) return
        if (!crossPageCueWindowEnabled || page == null || match == null || match.chapterIndex != page.chapterIndex) {
            hideCrossPageCueWindow()
            return
        }
        val fullyVisible = match.rawStart >= page.charStart && match.rawEnd <= page.charEnd
        if (fullyVisible) {
            hideCrossPageCueWindow()
            return
        }
        val text = fullCueText(match).takeIf { it.isNotBlank() } ?: run {
            hideCrossPageCueWindow()
            return
        }
        val visibleStart = match.rawStart.coerceAtLeast(page.charStart) - page.charStart
        val visibleEnd = match.rawEnd.coerceAtMost(page.charEnd) - page.charStart
        val visibleRange = (visibleStart until visibleEnd).takeIf { it.first < it.last }
        val verticalPage = if (readerLayoutMode == M9LayoutMode.VERTICAL) {
            buildCrossPageCuePage(text)
        } else {
            null
        }
        readView.showCrossPageCueOverlay(text, visibleRange, verticalPage)
    }

    private fun hideCrossPageCueWindow() {
        if (::readView.isInitialized) {
            readView.hideCrossPageCueOverlay()
        }
    }

    private fun fullCueText(match: EbookCueMatch): String {
        val chapterText = document?.chapters?.getOrNull(match.chapterIndex)?.text ?: return ""
        val start = match.rawStart.coerceIn(0, chapterText.length)
        val end = match.rawEnd.coerceIn(start, chapterText.length)
        return chapterText.substring(start, end).trim()
    }

    private fun buildCrossPageCuePage(text: String): TextPage? {
        val contentWidth = (readView.contentWidth - dp(16)).coerceAtLeast(dp(120))
        val contentHeight = (readView.contentHeight - dp(16)).coerceAtLeast(dp(160))
        val cueDocument = EbookDocument(
            title = currentReaderTitle(),
            format = "TEXT",
            chapters = listOf(EbookChapter(title = "", text = text))
        )
        val page = buildTextPageFactory()
            .createChapterPages(cueDocument, chapterIndex = 0, contentWidth, contentHeight)
            .firstOrNull()
            ?: return null
        page.title = ""
        page.globalIndex = 0
        page.totalPages = 1
        page.pageInChapter = 0
        page.chapterPageCount = 1
        return normalizeCrossPageCuePage(page, contentHeight)
    }

    private fun normalizeCrossPageCuePage(page: TextPage, contentHeight: Int): TextPage? {
        if (page.lines.isEmpty()) return null
        val isVertical = readerLayoutMode == M9LayoutMode.VERTICAL
        val minX = if (isVertical) {
            page.lines.minOf { it.lineTop }
        } else {
            page.lines.minOf { line -> line.columns.minOfOrNull { it.start } ?: line.startX }
        }
        val maxX = if (isVertical) {
            page.lines.maxOf { it.lineBottom }
        } else {
            page.lines.maxOf { line -> line.columns.maxOfOrNull { it.end } ?: line.lineEnd }
        }
        val minY = if (isVertical) {
            page.lines.minOf { line -> line.columns.minOfOrNull { it.start } ?: line.crossStart }
        } else {
            page.lines.minOf { it.lineTop }
        }
        val maxY = if (isVertical) {
            page.lines.maxOf { line -> line.columns.maxOfOrNull { it.end } ?: line.crossEnd }
        } else {
            page.lines.maxOf { it.lineBottom }
        }
        val left = minX.coerceAtLeast(0f)
        val top = minY.coerceAtLeast(0f)
        page.lines.forEach { line ->
            if (isVertical) {
                line.lineTop -= left
                line.lineBase -= left
                line.lineBottom -= left
                line.crossStart -= top
                line.crossEnd -= top
                line.columns.forEach { column ->
                    column.start -= top
                    column.end -= top
                }
            } else {
                line.lineTop -= top
                line.lineBase -= top
                line.lineBottom -= top
                line.crossStart -= top
                line.crossEnd -= top
                line.startX -= left
                line.columns.forEach { column ->
                    column.start -= left
                    column.end -= left
                }
            }
        }
        page.width = (maxX - minX).coerceAtLeast(1f)
        page.height = if (isVertical) {
            contentHeight.toFloat()
        } else {
            (maxY - minY).coerceAtLeast(1f)
        }
        page.charStart = 0
        page.charEnd = page.text.length
        return page
    }

    private fun EbookCueMatch.intersects(page: TextPage): Boolean {
        return chapterIndex == page.chapterIndex &&
            rawStart < page.charEnd &&
            rawEnd > page.charStart
    }

    private fun findTextPageForMatch(match: EbookCueMatch): Int? {
        return pages.indexOfFirst { page ->
            page.chapterIndex == match.chapterIndex &&
                match.rawStart >= page.charStart &&
                match.rawStart < page.charEnd
        }.takeIf { it >= 0 }
            ?: pages.indexOfFirst { it.chapterIndex == match.chapterIndex }.takeIf { it >= 0 }
    }

    private fun movePage(delta: Int) {
        if (pages.isEmpty()) return
        val next = (pageIndex + delta).coerceIn(0, pages.lastIndex)
        if (next != pageIndex) {
            pageIndex = next
            activeCueIndex = -1
            renderCurrentPage(
                forward = delta > 0,
                persistAnchor = true,
                anchorSource = "pageTurn"
            )
            return
        }
        val currentChapter = pages.getOrNull(pageIndex)?.chapterIndex ?: return
        val targetChapter = currentChapter + if (delta > 0) 1 else -1
        val lastChapter = document?.chapters?.lastIndex ?: return
        if (targetChapter in 0..lastChapter) {
            showAnchorOrLoad(
                anchor = ReaderPageAnchor(
                    chapterIndex = targetChapter,
                    charPosition = if (delta > 0) 0 else Int.MAX_VALUE
                ),
                forward = delta > 0,
                anchorSource = "pageTurnChapter"
            )
        }
    }

    private fun moveChapter(delta: Int) {
        if (useM4bChapterSource()) {
            val currentChapter = currentM4bChapterIndex()
            val targetChapter = (currentChapter + delta).coerceIn(0, m4bChapters.lastIndex.coerceAtLeast(0))
            if (targetChapter != currentChapter) {
                seekToM4bChapter(targetChapter)
            }
            return
        }
        if (pages.isEmpty()) return
        val currentChapter = pages.getOrNull(pageIndex)?.chapterIndex ?: return
        val targetChapter = (currentChapter + delta).coerceIn(0, (document?.chapters?.lastIndex ?: 0))
        if (targetChapter != currentChapter) {
            showAnchorOrLoad(
                anchor = ReaderPageAnchor(
                    chapterIndex = targetChapter,
                    charPosition = if (delta > 0) 0 else Int.MAX_VALUE
                ),
                forward = delta > 0,
                anchorSource = "chapterButton"
            )
        }
    }

    private fun initAudioPlayerIfNeeded() {
        val uri = audioUri ?: return
        val playbackKey = currentReaderPlaybackKey()
        val restoredSnapshot = playbackKey?.let { key ->
            loadBookReaderPlaybackSnapshotOrNull(this, key)
        }
        val restoredPositionMs = when {
            pendingAudioRestorePositionMs > 0L -> pendingAudioRestorePositionMs
            restoredSnapshot != null -> restoredSnapshot.positionMs
            else -> 0L
        }.coerceAtLeast(0L)
        audioCueIndex = -1
        player = BookReaderPlaybackSession.prepareAudioIfNeeded(
            context = this,
            audioUri = uri,
            restorePositionMs = restoredPositionMs,
            forceSeekOnSameAudio = false
        )
        publishReaderPlaybackBridgeSnapshot(notifyState = true)
        updateAudioControlLabels()
        startSyncLoop()
    }

    private fun toggleAudioControlPanel() {
        moreSettingsPanel.visibility = View.GONE
        audioControlPanel.visibility =
            if (audioControlPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        if (audioControlPanel.visibility == View.VISIBLE) {
            updateAudioControlLabels()
            loadSrtSyncIfNeeded()
        }
        updateSystemBarSurfaces()
    }

    private fun togglePlaybackBar() {
        playbackBarPinnedVisible = !playbackBarPinnedVisible
        playbackBar.visibility = if (playbackBarPinnedVisible) View.VISIBLE else View.GONE
        if (playbackBarPinnedVisible) {
            setReadMenuVisible(false, updateSystemBars = false)
            playbackBar.post {
                val newHeight = playbackBar.height.coerceAtLeast(0)
                if (newHeight > 0) {
                    playbackBarHeightPx = newHeight
                }
                applyReadViewViewportInset(reload = true)
            }
        } else {
            playbackBarHeightPx = 0
            applyReadViewViewportInset(reload = true)
        }
        persistReaderSettings()
        updateAudioControlLabels()
        updateSystemBarSurfaces()
    }

    private fun toggleAudioPlayback() {
        val currentPlayer = player
        if (currentPlayer == null) {
            Toast.makeText(this, R.string.reader_no_audio, Toast.LENGTH_SHORT).show()
            return
        }
        if (currentPlayer.isPlaying) {
            currentPlayer.pause()
        } else {
            clearImagePauseResume()
            currentPlayer.play()
        }
        publishReaderPlaybackBridgeSnapshot(notifyState = true)
        updateAudioControlLabels()
        if (currentPlayer.isPlaying) {
            syncToAudioPosition(allowPageJump = true, forceReveal = true)
        }
        if (!currentPlayer.isPlaying) {
            persistAudioPlaybackSnapshot()
        }
    }

    private fun updateAudioControlLabels() {
        val isPlaying = isAudioPlaying()
        if (::listenActionText.isInitialized) {
            listenActionText.text =
                if (isPlaying) readerString(R.string.reader_pause) else readerString(R.string.reader_listen)
        }
        if (::audioPlayPauseText.isInitialized) {
            audioPlayPauseText.text =
                if (isPlaying) readerString(R.string.reader_pause) else readerString(R.string.play)
        }
        if (::playbackBarToggleButton.isInitialized) {
            playbackBarToggleButton.setImageResource(
                if (isPlaying) R.drawable.reader_ic_pause_24dp else R.drawable.reader_ic_play_24dp
            )
        }
    }

    private fun setAudioTimer(minutes: Int) {
        audioStopAtMs = System.currentTimeMillis() + minutes * 60_000L
        Toast.makeText(this, getString(R.string.reader_stop_after_minutes, minutes), Toast.LENGTH_SHORT).show()
    }

    private fun seekToAdjacentCue(delta: Int) {
        val currentPlayer = player
        if (currentPlayer == null) {
            Toast.makeText(this, R.string.reader_no_audio, Toast.LENGTH_SHORT).show()
            return
        }
        if (cues.isEmpty()) {
            if (srtUri == null) {
                Toast.makeText(this, R.string.reader_no_srt, Toast.LENGTH_SHORT).show()
                return
            }
            loadSrtSyncIfNeeded(force = true) { success ->
                if (success) {
                    seekToAdjacentCue(delta)
                } else {
                    Toast.makeText(
                        this,
                        srtLoadError ?: readerString(R.string.reader_srt_parse_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            Toast.makeText(this, R.string.reader_srt_loading, Toast.LENGTH_SHORT).show()
            return
        }
        val position = currentAudioPositionMs() ?: 0L
        val exactIndex = findEbookCueIndexAtTime(cues, position)
        val beforeIndex = cues.indexOfLast { it.startMs <= position }
        val baseIndex = if (exactIndex >= 0) exactIndex else beforeIndex
        val targetIndex = if (delta < 0) {
            (baseIndex - 1).coerceAtLeast(0)
        } else {
            (baseIndex + 1).coerceIn(0, cues.lastIndex)
        }
        currentPlayer.seekTo(cues[targetIndex].startMs)
        activeCueIndex = -1
        publishReaderPlaybackBridgeSnapshot(notifyState = false)
        persistAudioPlaybackSnapshot()
        syncToAudioPosition(allowPageJump = true, forceReveal = true)
    }

    private fun currentAudioPositionMs(): Long? {
        return player?.currentPosition?.coerceAtLeast(0L)
    }

    private fun isAudioPlaying(): Boolean {
        return player?.isPlaying == true
    }

    private fun currentAudioPlaybackSpeed(): Float {
        return player?.playbackParameters?.speed ?: 1f
    }

    private fun setAudioPlaybackSpeed(speed: Float) {
        val normalized = speed.coerceIn(0.5f, 3.0f)
        player?.playbackParameters = PlaybackParameters(normalized)
        BookReaderFloatingBridge.notifyPlaybackSpeed(normalized)
    }

    private fun currentReaderPlaybackKey(): String? {
        val uri = audioUri ?: return null
        return buildLegadoReaderPlaybackKey(
            title = currentReaderTitle(),
            audioUri = uri,
            srtUri = srtUri
        )
    }

    private fun persistAudioPlaybackSnapshot(allowZeroPositionWrite: Boolean = false) {
        val currentPlayer = player ?: return
        val playbackKey = currentReaderPlaybackKey() ?: return
        val durationMs = if (currentPlayer.duration > 0L) currentPlayer.duration else pendingAudioRestoreDurationMs
        if (durationMs <= 0L) return
        val positionMs = currentPlayer.currentPosition.coerceAtLeast(0L)
        if (!allowZeroPositionWrite && positionMs == lastSavedPlaybackPositionMs) return
        lastSavedPlaybackPositionMs = positionMs
        saveBookReaderPlaybackPosition(
            context = this,
            bookKey = playbackKey,
            positionMs = positionMs,
            durationMs = durationMs.coerceAtLeast(0L),
            allowZeroPositionWrite = allowZeroPositionWrite
        )
    }

    private fun currentReaderBottomPaddingPx(): Int {
        return dp(22) + playbackBarEffectiveHeightPx()
    }

    private fun playbackBarEffectiveHeightPx(): Int {
        return if (playbackBarPinnedVisible) playbackBarHeightPx.coerceAtLeast(dp(62)) else 0
    }

    private fun currentPageAnchor(includeCueMatch: Boolean = false): ReaderPageAnchor? {
        val page = pages.getOrNull(pageIndex) ?: return null
        return ReaderPageAnchor(
            chapterIndex = page.chapterIndex,
            charPosition = currentAnchorCharPosition(page, includeCueMatch)
        )
    }

    private fun currentAnchorCharPosition(page: TextPage, includeCueMatch: Boolean): Int {
        val cueMatch = currentPageCueMatch(page)
        if (
            includeCueMatch &&
            cueMatch != null &&
            cueMatch.chapterIndex == page.chapterIndex &&
            cueMatch.rawStart >= page.charStart &&
            cueMatch.rawStart < page.charEnd
        ) {
            return cueMatch.rawStart.coerceIn(page.charStart, page.charEnd.coerceAtLeast(page.charStart))
        }
        val middle = page.charStart + ((page.charEnd - page.charStart).coerceAtLeast(0) / 2)
        return middle.coerceIn(page.charStart, page.charEnd.coerceAtLeast(page.charStart))
    }

    private fun pageIndexForAnchor(loadedPages: List<TextPage>, anchor: ReaderPageAnchor): Int {
        if (loadedPages.isEmpty()) return 0
        loadedPages.indexOfFirst { page ->
            page.chapterIndex == anchor.chapterIndex && page.containPos(anchor.charPosition)
        }.takeIf { it >= 0 }?.let { return it }
        loadedPages.indexOfLast { page ->
            page.chapterIndex == anchor.chapterIndex && page.charStart <= anchor.charPosition
        }.takeIf { it >= 0 }?.let { return it }
        loadedPages.indexOfFirst { page ->
            page.chapterIndex >= anchor.chapterIndex
        }.takeIf { it >= 0 }?.let { return it }
        return loadedPages.lastIndex
    }

    private fun applyReadViewViewportInset(reload: Boolean) {
        readView.setReaderPadding(
            dp(readerPaddingDp),
            dp(34),
            dp(readerPaddingDp),
            currentReaderBottomPaddingPx()
        )
        if (reload) {
            if (document == null && pages.isEmpty()) {
                Log.d(LEGADO_READER_LOG_TAG, "readerRelayout skipped pending initial load")
                return
            }
            val anchor = currentPageAnchor() ?: pendingRestoreAnchor
            readView.post {
                relayoutCurrentDocument(anchor)
            }
        }
    }

    private fun requestBookRelayout(immediate: Boolean = false) {
        persistReaderSettings()
        reloadBookJob?.cancel()
        val anchor = currentPageAnchor() ?: pendingRestoreAnchor
        if (immediate) {
            relayoutCurrentDocument(anchor)
            return
        }
        reloadBookJob = lifecycleScope.launch {
            delay(90)
            relayoutCurrentDocument(anchor)
        }
    }

    private fun relayoutCurrentDocument(anchor: ReaderPageAnchor?) {
        val loaded = document
        if (loaded == null) {
            loadDisplayedBook(anchor = anchor, forceDocumentReload = false)
            return
        }
        val pageWidth = readView.contentWidth.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels - dp(44))
        val pageHeight = readView.contentHeight.takeIf { it > 0 } ?: (resources.displayMetrics.heightPixels - dp(220))
        paginationJob?.cancel()
        clearChapterPageCache()
        paginationJob = lifecycleScope.launch {
            val relayoutResult = runCatching {
                val centerChapterIndex = anchor?.chapterIndex
                    ?: pages.getOrNull(pageIndex)?.chapterIndex
                    ?: 0
                getOrPaginateChapterPages(
                    document = loaded,
                    chapterIndex = centerChapterIndex,
                    contentWidthPx = pageWidth.coerceAtLeast(1),
                    contentHeightPx = pageHeight.coerceAtLeast(dp(120))
                )
            }
            val loadedPages = relayoutResult.getOrNull()
            if (loadedPages == null) {
                val error = relayoutResult.exceptionOrNull()
                if (error is CancellationException) return@launch
                Log.w(LEGADO_READER_LOG_TAG, "relayoutCurrentDocument failed", error)
                return@launch
            }
            pages = loadedPages
            pageIndex = anchor?.let { pageIndexForAnchor(loadedPages, it) } ?: pageIndex.coerceIn(0, pages.lastIndex)
            renderCurrentPage()
            if (cueMatchesByCueIndex.isNotEmpty()) {
                syncToAudioPosition(allowPageJump = isAudioPlaying())
            } else {
                Log.d(
                    LEGADO_READER_LOG_TAG,
                    "relayoutCurrentDocument no in-memory matches; trying persisted restore"
                )
                restorePersistedMatchIfPossible()
            }
            val centerChapterIndex = pages.getOrNull(pageIndex)?.chapterIndex ?: anchor?.chapterIndex ?: 0
            preloadAdjacentChapters(
                document = loaded,
                centerChapterIndex = centerChapterIndex,
                contentWidthPx = pageWidth.coerceAtLeast(1),
                contentHeightPx = pageHeight.coerceAtLeast(dp(120))
            )
        }
    }

    private fun currentReaderMatchStoreKey(): String? {
        val bookUri = importedBook?.uri?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val stableSource = buildString {
            append("ebook=").append(bookUri)
            append("|audio=").append(audioUri?.toString().orEmpty())
            append("|srt=").append(srtUri?.toString().orEmpty())
            append("|charset=").append(preferredCharsetName.orEmpty())
        }
        return buildDictionaryCacheKey(stableSource, currentReaderTitle())
    }

    private suspend fun restorePersistedMatchIfPossible() {
        if (document == null) {
            Log.d(LEGADO_READER_LOG_TAG, "restoreMatch skipped document=null")
            return
        }
        if (cues.isEmpty()) {
            Log.d(LEGADO_READER_LOG_TAG, "restoreMatch skipped cues empty")
            return
        }
        if (cueMatchesByCueIndex.isNotEmpty()) {
            Log.d(
                LEGADO_READER_LOG_TAG,
                "restoreMatch skipped alreadyLoaded matches=${cueMatchesByCueIndex.size}"
            )
            return
        }
        val storeKey = currentReaderMatchStoreKey() ?: run {
            Log.d(LEGADO_READER_LOG_TAG, "restoreMatch skipped storeKey=null")
            return
        }
        Log.d(
            LEGADO_READER_LOG_TAG,
            "restoreMatch try key=${storeKey.take(48)} cues=${cues.size} book=${importedBook?.title}"
        )
        val restoreStartMs = SystemClock.elapsedRealtime()
        val snapshot = withContext(Dispatchers.IO) {
            loadLegadoReaderMatchSnapshotOrNull(this@LegadoReaderActivity, storeKey)
        } ?: run {
            Log.d(LEGADO_READER_LOG_TAG, "restoreMatch miss key=${storeKey.take(48)}")
            return
        }
        val matchesByCueIndex = withContext(Dispatchers.Default) {
            snapshot.matches.associateBy { it.cueIndex }
        }
        cueMatchesByCueIndex = matchesByCueIndex
        matchData = EbookMatchData(
            matches = snapshot.matches,
            unmatched = snapshot.unmatched,
            totalCues = snapshot.totalCues
        )
        audioCueIndex = -1
        activeCueIndex = -1
        Log.d(
            LEGADO_READER_LOG_TAG,
            "restoreMatch applied=${SystemClock.elapsedRealtime() - restoreStartMs}ms " +
                "matches=${snapshot.matches.size} totalCues=${snapshot.totalCues} unmatched=${snapshot.unmatched}"
        )
    }

    private fun persistCurrentMatchSnapshot() {
        val current = matchData ?: run {
            Log.d(LEGADO_READER_LOG_TAG, "persistMatch skipped matchData=null")
            return
        }
        if (current.matches.isEmpty() || current.totalCues <= 0) {
            Log.d(
                LEGADO_READER_LOG_TAG,
                "persistMatch skipped invalid matches=${current.matches.size} totalCues=${current.totalCues}"
            )
            return
        }
        val storeKey = currentReaderMatchStoreKey() ?: run {
            Log.d(LEGADO_READER_LOG_TAG, "persistMatch skipped storeKey=null")
            return
        }
        Log.d(
            LEGADO_READER_LOG_TAG,
            "persistMatch saving matches=${current.matches.size} totalCues=${current.totalCues} key=${storeKey.take(48)}"
        )
        saveLegadoReaderMatchSnapshot(
            context = this,
            storeKey = storeKey,
            snapshot = LegadoReaderMatchSnapshot(
                matches = current.matches,
                unmatched = current.unmatched,
                totalCues = current.totalCues
            )
        )
    }

    private fun restoreReaderSettings() {
        val state = loadLegadoReaderPersistedState(this)
        readerTextSizeSp = state.textSizeSp
        readerLineSpacingDp = state.lineSpacingDp
        readerParagraphSpacingDp = state.paragraphSpacingDp
        readerLetterSpacingDp = state.letterSpacingDp
        readerTextWeight = state.textWeight
        readerTypefaceIndex = state.typefaceIndex
        readerTypeface = readerTypefaceForIndex(readerTypefaceIndex)
        readerParagraphIndentCount = state.paragraphIndentCount
        readerPaddingDp = state.paddingDp
        readerLayoutMode = state.layoutMode
        readerPageAnim = state.pageAnim
        readerStyleConfigs = state.readerStyleConfigs
            .takeIf { it.isNotEmpty() }
            ?.toMutableList()
            ?: defaultLegadoReaderStyleConfigs().toMutableList()
        readerStyleSelect = state.readerStyleSelect.coerceIn(0, readerStyleConfigs.lastIndex)
        readerNightMode = state.readerNightMode
        applySelectedReaderStyleFields()
        readerCueHighlightColor = state.cueHighlightColor
        hideStatusBar = state.hideStatusBar
        readBodyToLh = state.readBodyToLh
        hideNavigationBar = state.hideNavigationBar
        showBrightnessView = state.showBrightnessView
        brightnessAuto = state.brightnessAuto
        brightnessValue = state.brightnessValue
        brightnessPanelOnRight = state.brightnessPanelOnRight
        showReadTitleAddition = state.showReadTitleAddition
        bodyTitleMode = state.bodyTitleMode
        bodyTitleSizeAddSp = state.bodyTitleSizeAddSp
        bodyTitleTopSpacingDp = state.bodyTitleTopSpacingDp
        bodyTitleBottomSpacingDp = state.bodyTitleBottomSpacingDp
        headerMode = state.headerMode
        footerMode = state.footerMode
        tipHeaderLeft = state.tipHeaderLeft
        tipHeaderMiddle = state.tipHeaderMiddle
        tipHeaderRight = state.tipHeaderRight
        tipFooterLeft = state.tipFooterLeft
        tipFooterMiddle = state.tipFooterMiddle
        tipFooterRight = state.tipFooterRight
        tipColorMode = state.tipColorMode
        tipDividerColorMode = state.tipDividerColorMode
        tipDividerColor = state.tipDividerColor
        useZhLayout = state.useZhLayout
        textFullJustify = state.textFullJustify
        textBottomJustify = state.textBottomJustify
        clickRegionActions =
            if (state.layoutMode == M9LayoutMode.VERTICAL &&
                state.clickRegionActions == ReadView.defaultClickRegionActions(M9LayoutMode.HORIZONTAL)
            ) {
                ReadView.defaultClickRegionActions(M9LayoutMode.VERTICAL)
            } else {
                state.clickRegionActions
            }
        progressByChapter = state.progressByChapter
        keepScreenOn = state.keepScreenOn
        noAnimScrollPage = state.noAnimScrollPage
        previewImageByClick = state.previewImageByClick
        disableReturnKey = state.disableReturnKey
        readBarStyleFollowPage = state.readBarStyleFollowPage
        playbackBarPinnedVisible = state.playbackBarPinnedVisible
        crossPageCueWindowEnabled = state.crossPageCueWindowEnabled
        stopPlaybackOnImage = state.stopPlaybackOnImage
        imagePauseSeconds = state.imagePauseSeconds
        verticalControlDirectionReversed = state.verticalControlDirectionReversed
        verticalProgressDirectionReversed = state.verticalProgressDirectionReversed
        selectionPrimaryActionKey = state.selectionPrimaryActionKey
        chapterSourceMode = state.chapterSourceMode
        showRubyText = state.showRubyText
        preferredCharsetName = state.preferredCharsetName
        pendingRestoreAnchor = ReaderPageAnchor(
            chapterIndex = state.currentChapterIndex,
            charPosition = state.currentCharPosition
        )
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun attachSavedAnchorIfNeeded() {
        val bookUri = importedBook?.uri?.toString()
        loadLegadoReaderBookAnchor(this, bookUri)?.let { anchor ->
            pendingRestoreAnchor = ReaderPageAnchor(
                chapterIndex = anchor.chapterIndex,
                charPosition = anchor.charPosition
            )
            return
        }
        val persisted = loadLegadoReaderPersistedState(this)
        if (bookUri != null && persisted.currentBookUri == bookUri) return
        pendingRestoreAnchor = null
    }

    private fun persistReaderSettings(
        updateAnchor: Boolean = false,
        anchorOverride: ReaderPageAnchor? = null
    ) {
        val previous = loadLegadoReaderPersistedState(this)
        val bookUri = importedBook?.uri?.toString()
        val anchor = if (updateAnchor) anchorOverride ?: currentPageAnchor(includeCueMatch = false) else null
        if (updateAnchor && anchor != null) {
            saveLegadoReaderBookAnchor(
                this,
                bookUri,
                LegadoReaderBookAnchor(
                    chapterIndex = anchor.chapterIndex,
                    charPosition = anchor.charPosition
                )
            )
        }
        val persistedBookUri = if (updateAnchor && anchor != null) bookUri else previous.currentBookUri
        val persistedChapterIndex = if (updateAnchor && anchor != null) {
            anchor.chapterIndex
        } else {
            previous.currentChapterIndex
        }
        val persistedCharPosition = if (updateAnchor && anchor != null) {
            anchor.charPosition
        } else {
            previous.currentCharPosition
        }
        saveLegadoReaderPersistedState(
            this,
            LegadoReaderPersistedState(
                textSizeSp = readerTextSizeSp,
                lineSpacingDp = readerLineSpacingDp,
                paragraphSpacingDp = readerParagraphSpacingDp,
                letterSpacingDp = readerLetterSpacingDp,
                textWeight = readerTextWeight,
                typefaceIndex = readerTypefaceIndex,
                paragraphIndentCount = readerParagraphIndentCount,
                paddingDp = readerPaddingDp,
                layoutMode = readerLayoutMode,
                pageAnim = readerPageAnim,
                readerStyleSelect = readerStyleSelect,
                readerNightMode = readerNightMode,
                readerStyleConfigs = readerStyleConfigs.toList(),
                cueHighlightColor = readerCueHighlightColor,
                hideStatusBar = hideStatusBar,
                readBodyToLh = readBodyToLh,
                hideNavigationBar = hideNavigationBar,
                showBrightnessView = showBrightnessView,
                brightnessAuto = brightnessAuto,
                brightnessValue = brightnessValue,
                brightnessPanelOnRight = brightnessPanelOnRight,
                showReadTitleAddition = showReadTitleAddition,
                bodyTitleMode = bodyTitleMode,
                bodyTitleSizeAddSp = bodyTitleSizeAddSp,
                bodyTitleTopSpacingDp = bodyTitleTopSpacingDp,
                bodyTitleBottomSpacingDp = bodyTitleBottomSpacingDp,
                headerMode = headerMode,
                footerMode = footerMode,
                tipHeaderLeft = tipHeaderLeft,
                tipHeaderMiddle = tipHeaderMiddle,
                tipHeaderRight = tipHeaderRight,
                tipFooterLeft = tipFooterLeft,
                tipFooterMiddle = tipFooterMiddle,
                tipFooterRight = tipFooterRight,
                tipColorMode = tipColorMode,
                tipDividerColorMode = tipDividerColorMode,
                tipDividerColor = tipDividerColor,
                useZhLayout = useZhLayout,
                textFullJustify = textFullJustify,
                textBottomJustify = textBottomJustify,
                clickRegionActions = clickRegionActions,
                progressByChapter = progressByChapter,
                keepScreenOn = keepScreenOn,
                noAnimScrollPage = noAnimScrollPage,
                previewImageByClick = previewImageByClick,
                disableReturnKey = disableReturnKey,
                readBarStyleFollowPage = readBarStyleFollowPage,
                playbackBarPinnedVisible = playbackBarPinnedVisible,
                crossPageCueWindowEnabled = crossPageCueWindowEnabled,
                stopPlaybackOnImage = stopPlaybackOnImage,
                imagePauseSeconds = imagePauseSeconds,
                verticalControlDirectionReversed = verticalControlDirectionReversed,
                verticalProgressDirectionReversed = verticalProgressDirectionReversed,
                selectionPrimaryActionKey = selectionPrimaryActionKey,
                chapterSourceMode = chapterSourceMode,
                showRubyText = showRubyText,
                preferredCharsetName = preferredCharsetName,
                currentBookUri = persistedBookUri,
                currentChapterIndex = persistedChapterIndex,
                currentCharPosition = persistedCharPosition
            )
        )
    }

    private fun showSasayakiMatchDialog() {
        val loadedDocument = document
        if (loadedDocument == null || pages.isEmpty()) {
            Toast.makeText(this, R.string.reader_ebook_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        if (cues.isEmpty()) {
            if (srtUri == null) {
                Toast.makeText(this, R.string.reader_no_srt, Toast.LENGTH_SHORT).show()
                return
            }
            loadSrtSyncIfNeeded(force = true) { success ->
                if (success) {
                    showSasayakiMatchDialog()
                } else {
                    Toast.makeText(
                        this,
                        srtLoadError ?: readerString(R.string.reader_srt_parse_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            Toast.makeText(this, R.string.reader_srt_loading, Toast.LENGTH_SHORT).show()
            return
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        val summaryText = text(matchSummaryText(), 14f, MENU_TEXT).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        val windowText = text(
            getString(R.string.reader_match_search_window, matchSearchWindow),
            14f,
            MENU_TEXT
        )
        val cueHighlightButton = styleConfigButton(
            R.string.reader_match_highlight_color,
            readerCueHighlightColor
        )
        val crossPageWindowCheck = CheckBox(this).apply {
            text = readerString(R.string.reader_cross_page_cue_window)
            setTextColor(MENU_TEXT)
            textSize = 14f
            isChecked = crossPageCueWindowEnabled
            setOnCheckedChangeListener { _, checked ->
                crossPageCueWindowEnabled = checked
                if (!checked) hideCrossPageCueWindow() else renderCurrentPage()
                persistReaderSettings(updateAnchor = false)
            }
        }
        val stopOnImageCheck = CheckBox(this).apply {
            text = readerString(R.string.reader_stop_on_image)
            setTextColor(MENU_TEXT)
            textSize = 14f
            isChecked = stopPlaybackOnImage
        }
        val imagePauseContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (stopPlaybackOnImage) View.VISIBLE else View.GONE
        }
        val imagePauseLabel = text(
            readerString(R.string.reader_image_pause_seconds),
            14f,
            MENU_TEXT
        )
        val imagePauseInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(imagePauseSeconds.toString())
            setTextColor(MENU_TEXT)
            textSize = 14f
            isEnabled = stopPlaybackOnImage
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val value = s?.toString()?.toIntOrNull() ?: return
                    imagePauseSeconds = value.coerceIn(0, MAX_IMAGE_PAUSE_SECONDS)
                    persistReaderSettings(updateAnchor = false)
                }
            })
        }
        stopOnImageCheck.setOnCheckedChangeListener { _, checked ->
            stopPlaybackOnImage = checked
            imagePauseInput.isEnabled = checked
            imagePauseContainer.visibility = if (checked) View.VISIBLE else View.GONE
            if (!checked) {
                lastImageStopKey = null
                clearImagePauseResume()
            }
            persistReaderSettings(updateAnchor = false)
        }
        val reverseControlCheck = CheckBox(this).apply {
            text = readerString(R.string.reader_reverse_vertical_controls)
            setTextColor(MENU_TEXT)
            textSize = 14f
            isChecked = verticalControlDirectionReversed
            setOnCheckedChangeListener { _, checked ->
                verticalControlDirectionReversed = checked
                applyDirectionSettings()
                persistReaderSettings(updateAnchor = false)
            }
        }
        val reverseProgressCheck = CheckBox(this).apply {
            text = readerString(R.string.reader_reverse_vertical_progress)
            setTextColor(MENU_TEXT)
            textSize = 14f
            isChecked = verticalProgressDirectionReversed
            setOnCheckedChangeListener { _, checked ->
                verticalProgressDirectionReversed = checked
                applyDirectionSettings()
                persistReaderSettings(updateAnchor = false)
            }
        }
        val chapterSourceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val chapterSourceLabel = text(readerString(R.string.reader_chapter_source), 14f, MENU_TEXT)
        val chapterSourceValue = text(currentChapterSourceSummary(), 14f, MENU_TEXT).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        chapterSourceRow.addView(
            chapterSourceLabel,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        chapterSourceRow.addView(
            chapterSourceValue,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        chapterSourceRow.setOnClickListener {
            val options = arrayOf(
                readerString(R.string.reader_chapter_source_book),
                readerString(R.string.reader_chapter_source_m4b)
            )
            AlertDialog.Builder(this)
                .setTitle(R.string.reader_chapter_source)
                .setSingleChoiceItems(
                    options,
                    if (chapterSourceMode == ReaderChapterSourceMode.BOOK) 0 else 1
                ) { sourceDialog, which ->
                    if (which == 1) {
                        ensureM4bChaptersLoaded { success ->
                            runOnUiThread {
                                if (!success) {
                                    chapterSourceMode = ReaderChapterSourceMode.BOOK
                                    chapterSourceValue.text = currentChapterSourceSummary()
                                    Toast.makeText(
                                        this,
                                        R.string.reader_chapter_source_m4b_unavailable,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    chapterSourceMode = ReaderChapterSourceMode.M4B
                                    chapterSourceValue.text = currentChapterSourceSummary()
                                    updateProgressSeekBar()
                                }
                                persistReaderSettings(updateAnchor = false)
                                sourceDialog.dismiss()
                            }
                        }
                    } else {
                        chapterSourceMode = ReaderChapterSourceMode.BOOK
                        chapterSourceValue.text = currentChapterSourceSummary()
                        updateProgressSeekBar()
                        persistReaderSettings(updateAnchor = false)
                        sourceDialog.dismiss()
                    }
                }
                .show()
        }
        val seekBar = SeekBar(this).apply {
            max = MATCH_SEARCH_WINDOW_MAX - MATCH_SEARCH_WINDOW_MIN
            progress = (matchSearchWindow - MATCH_SEARCH_WINDOW_MIN).coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        matchSearchWindow = MATCH_SEARCH_WINDOW_MIN + progress
                        windowText.text = getString(
                            R.string.reader_match_search_window,
                            matchSearchWindow
                        )
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        container.addView(summaryText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))
        container.addView(windowText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)))
        container.addView(seekBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
        container.addView(cueHighlightButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(42)
        ).apply {
            topMargin = dp(8)
        })
        container.addView(crossPageWindowCheck, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44)
        ))
        container.addView(stopOnImageCheck, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44)
        ))
        imagePauseContainer.addView(imagePauseLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(28)
        ))
        imagePauseContainer.addView(imagePauseInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44)
        ))
        container.addView(imagePauseContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        container.addView(reverseControlCheck, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44)
        ))
        container.addView(reverseProgressCheck, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44)
        ))
        container.addView(chapterSourceRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(10)
        })
        cueHighlightButton.setOnClickListener {
            showReaderColorPicker(READER_CUE_HIGHLIGHT_DIALOG_ID, readerCueHighlightColor) { color ->
                readerCueHighlightColor = color
                readView.setCueHighlightColor(readerCueHighlightColor)
                bindStyleConfigButton(
                    cueHighlightButton,
                    R.string.reader_match_highlight_color,
                    readerCueHighlightColor
                )
                renderCurrentPage()
                persistReaderSettings()
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.reader_match_title)
            .setView(container)
            .setPositiveButton(R.string.reader_match_start, null)
            .setNegativeButton(R.string.reader_dialog_done, null)
            .create()
        dialog.setOnShowListener {
            val startButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            startButton.setOnClickListener {
                startButton.isEnabled = false
                summaryText.text = readerString(R.string.reader_match_in_progress)
                lifecycleScope.launch {
                    runCatching {
                        withContext(Dispatchers.Default) {
                            matchEbookCuesData(
                                document = loadedDocument,
                                cues = cues,
                                searchWindow = matchSearchWindow
                            )
                        }
                    }.onSuccess { data ->
                        matchData = data
                        cueMatchesByCueIndex = data.matches.associateBy { it.cueIndex }
                        Log.d(
                            LEGADO_READER_LOG_TAG,
                            "manual match success matches=${data.matches.size} totalCues=${data.totalCues} unmatched=${data.unmatched}"
                        )
                        persistCurrentMatchSnapshot()
                        audioCueIndex = -1
                        activeCueIndex = -1
                        syncToAudioPosition(allowPageJump = isAudioPlaying())
                        renderCurrentPage()
                        summaryText.text = matchSummaryText()
                    }.onFailure { error ->
                        summaryText.text = getString(
                            R.string.reader_match_failed,
                            error.message ?: error.javaClass.simpleName
                        )
                    }
                    startButton.isEnabled = true
                }
            }
        }
        dialog.show()
    }

    private fun matchSummaryText(): String {
        val current = matchData
        return if (current == null) {
            getString(R.string.reader_match_summary_unmatched, cues.size)
        } else {
            getString(
                R.string.reader_match_summary_matched,
                current.matchRateText,
                current.matches.size,
                current.totalCues
            )
        }
    }

    private fun loadSrtSyncIfNeeded(
        force: Boolean = false,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val uri = srtUri ?: return
        val uriText = uri.toString()
        if (!force && loadedSrtUriText == uriText && cues.isNotEmpty()) {
            publishReaderSubtitleBridgeSnapshot(clearWhenMissing = false)
            onComplete?.invoke(true)
            return
        }
        if (srtLoading) {
            onComplete?.invoke(false)
            return
        }
        srtLoading = true
        srtLoadError = null
        lifecycleScope.launch {
            val srtStartMs = SystemClock.elapsedRealtime()
            runCatching {
                val cachedSnapshot = if (force) {
                    null
                } else {
                    withContext(Dispatchers.IO) {
                        loadLegadoReaderSrtSnapshotOrNull(this@LegadoReaderActivity, uri)
                    }
                }
                if (cachedSnapshot != null) {
                    Log.d(
                        LEGADO_READER_LOG_TAG,
                        "loadSrtSyncIfNeeded cacheHit=${SystemClock.elapsedRealtime() - srtStartMs}ms " +
                            "cues=${cachedSnapshot.cues.size} uri=$uriText"
                    )
                    cachedSnapshot.cues
                } else {
                    val loadedCues = parseEbookSrt(contentResolver, uri)
                    withContext(Dispatchers.IO) {
                        saveLegadoReaderSrtSnapshot(this@LegadoReaderActivity, uri, loadedCues)
                    }
                    Log.d(
                        LEGADO_READER_LOG_TAG,
                        "loadSrtSyncIfNeeded parsed=${SystemClock.elapsedRealtime() - srtStartMs}ms " +
                            "cues=${loadedCues.size} uri=$uriText force=$force"
                    )
                    loadedCues
                }
            }.onSuccess { loadedCues ->
                cues = loadedCues
                loadedSrtUriText = uriText
                cueMatchesByCueIndex = emptyMap()
                matchData = null
                audioCueIndex = -1
                activeCueIndex = -1
                Log.d(
                    LEGADO_READER_LOG_TAG,
                    "loadSrtSyncIfNeeded ready=${SystemClock.elapsedRealtime() - srtStartMs}ms " +
                        "cues=${loadedCues.size} uri=$uriText reset in-memory match cache"
                )
                srtLoadError = if (loadedCues.isEmpty()) {
                    readerString(R.string.reader_srt_parse_failed_detail)
                } else {
                    null
                }
                publishReaderSubtitleBridgeSnapshot(clearWhenMissing = true)
                if (loadedCues.isNotEmpty()) {
                    Log.d(
                        LEGADO_READER_LOG_TAG,
                        "loadSrtSyncIfNeeded trying persisted restore after SRT load"
                    )
                    restorePersistedMatchIfPossible()
                }
                onComplete?.invoke(loadedCues.isNotEmpty())
            }.onFailure { error ->
                Log.w(
                    LEGADO_READER_LOG_TAG,
                    "loadSrtSyncIfNeeded failed after ${SystemClock.elapsedRealtime() - srtStartMs}ms uri=$uriText",
                    error
                )
                srtLoadError = getString(
                    R.string.reader_srt_load_failed,
                    error.message ?: error.javaClass.simpleName
                )
                cues = emptyList()
                loadedSrtUriText = null
                publishReaderSubtitleBridgeSnapshot(clearWhenMissing = true)
                onComplete?.invoke(false)
            }
            srtLoading = false
            if (srtLoadError != null && force) {
                Toast.makeText(this@LegadoReaderActivity, srtLoadError, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startSyncLoop() {
        syncJob?.cancel()
        syncJob = lifecycleScope.launch {
            var lastProgressSaveAt = 0L
            while (true) {
                delay(350L)
                audioStopAtMs?.let { stopAt ->
                    if (System.currentTimeMillis() >= stopAt) {
                        player?.pause()
                        BookReaderFloatingBridge.notifyPlaybackState(false)
                        audioStopAtMs = null
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastProgressSaveAt >= 2_500L) {
                    persistAudioPlaybackSnapshot()
                    lastProgressSaveAt = now
                }
                publishReaderPlaybackBridgeSnapshot(notifyState = false)
                updateAudioControlLabels()
                if (isAudioPlaying()) {
                    syncToAudioPosition(allowPageJump = true)
                }
            }
        }
    }

    private fun syncToAudioPosition(
        allowPageJump: Boolean = true,
        forceReveal: Boolean = false
    ) {
        if (cues.isEmpty() || pages.isEmpty()) return
        val currentPosition = currentAudioPositionMs() ?: return
        val cueIndex = findEbookCueIndexAtTime(cues, currentPosition)
        if (cueIndex < 0) {
            val changed = activeCueIndex != -1
            audioCueIndex = -1
            activeCueIndex = -1
            if (textSelectionActive && !forceReveal) {
                updateDisplayedCueHighlightOnly()
                return
            }
            if (changed) {
                renderCurrentPage()
            } else {
                hideCrossPageCueWindow()
            }
            return
        }
        val previousAudioCueIndex = audioCueIndex
        val cueChanged = cueIndex != previousAudioCueIndex
        if (!cueChanged && !forceReveal) return
        val previousMatch = cueMatchesByCueIndex[previousAudioCueIndex]
        audioCueIndex = cueIndex
        if (textSelectionActive && !forceReveal) {
            activeCueIndex = cueIndex
            updateDisplayedCueHighlightOnly()
            return
        }
        val match = cueMatchesByCueIndex[cueIndex] ?: run {
            activeCueIndex = cueIndex
            hideCrossPageCueWindow()
            if (!allowPageJump || forceReveal) {
                renderCurrentPage()
            }
            return
        }
        if (
            stopPlaybackOnImage &&
            allowPageJump &&
            isAudioPlaying() &&
            cueChanged &&
            previousMatch != null
        ) {
            findCrossedImageStopTarget(previousMatch, match)?.let { target ->
                pausePlaybackAtImage(target)
                return
            }
        }
        activeCueIndex = cueIndex
        if (!allowPageJump) {
            renderCurrentPage()
            return
        }
        val startPageIndex = findTextPageForMatch(match)
        if (startPageIndex == null) {
            activeCueIndex = cueIndex
            loadDisplayedBook(
                anchor = ReaderPageAnchor(match.chapterIndex, match.rawStart),
                forceDocumentReload = false
            )
            return
        }
        if (startPageIndex == pageIndex) {
            updateDisplayedCueHighlightOnly()
            return
        }
        val previousPageIndex = pageIndex
        pageIndex = startPageIndex
        renderCurrentPage(forward = startPageIndex >= previousPageIndex)
    }

    private fun updateDisplayedCueHighlightOnly() {
        val page = pages.getOrNull(pageIndex) ?: return
        val match = currentPageCueMatch(page)
        readView.setCueHighlight(highlightTextPage(page, match))
        updateCrossPageCueWindow(page, match)
    }

    private fun findCrossedImageStopTarget(
        previousMatch: EbookCueMatch,
        currentMatch: EbookCueMatch
    ): ReaderImageStopTarget? {
        val loadedDocument = document ?: return null
        if (currentMatch.chapterIndex < previousMatch.chapterIndex) return null
        if (
            currentMatch.chapterIndex == previousMatch.chapterIndex &&
            currentMatch.rawStart <= previousMatch.rawStart
        ) {
            return null
        }
        for (chapterIndex in previousMatch.chapterIndex..currentMatch.chapterIndex) {
            val chapter = loadedDocument.chapters.getOrNull(chapterIndex) ?: continue
            val start = if (chapterIndex == previousMatch.chapterIndex) previousMatch.rawEnd else 0
            val end = if (chapterIndex == currentMatch.chapterIndex) currentMatch.rawStart else Int.MAX_VALUE
            val imagePosition = chapter.images.keys
                .asSequence()
                .filter { position -> position >= start && position < end }
                .minOrNull()
                ?: continue
            val target = ReaderImageStopTarget(chapterIndex, imagePosition)
            if (target.key != lastImageStopKey) return target
        }
        return null
    }

    private fun pausePlaybackAtImage(target: ReaderImageStopTarget) {
        lastImageStopKey = target.key
        player?.pause()
        BookReaderFloatingBridge.notifyPlaybackState(false)
        activeCueIndex = -1
        findImagePageIndex(target)?.let { imagePage ->
            if (imagePage != pageIndex) pageIndex = imagePage
        }
        imagePausePageIndex = pageIndex
        renderCurrentPage()
        updateAudioControlLabels()
        persistAudioPlaybackSnapshot()
        scheduleImagePauseResume()
    }

    private fun scheduleImagePauseResume() {
        imagePauseResumeJob?.cancel()
        imagePauseResumeJob = null
        val delayMs = imagePauseSeconds.coerceIn(0, MAX_IMAGE_PAUSE_SECONDS) * 1000L
        if (delayMs <= 0L) return
        imagePauseResumeJob = lifecycleScope.launch {
            delay(delayMs)
            resumeFromImagePause()
        }
    }

    private fun resumeImagePauseIfPageLeft() {
        val pausedPageIndex = imagePausePageIndex ?: return
        if (pageIndex != pausedPageIndex) {
            resumeFromImagePause()
        }
    }

    private fun resumeFromImagePause() {
        val hadPendingPause = imagePausePageIndex != null
        clearImagePauseResume()
        if (!hadPendingPause) return
        val currentPlayer = player ?: return
        if (!currentPlayer.isPlaying) {
            currentPlayer.play()
            publishReaderPlaybackBridgeSnapshot(notifyState = true)
            updateAudioControlLabels()
        }
    }

    private fun clearImagePauseResume() {
        imagePauseResumeJob?.cancel()
        imagePauseResumeJob = null
        imagePausePageIndex = null
    }

    private fun findImagePageIndex(target: ReaderImageStopTarget): Int? {
        return pages.indexOfFirst { page ->
            page.chapterIndex == target.chapterIndex &&
                target.imagePosition >= page.charStart &&
                target.imagePosition < page.charEnd
        }.takeIf { it >= 0 }
    }

    override fun onColorSelected(dialogId: Int, color: Int) {
        if (dialogId != pendingReaderColorDialogId) return
        val callback = pendingReaderColorSelected ?: return
        pendingReaderColorDialogId = -1
        pendingReaderColorSelected = null
        callback(color)
    }

    override fun onDialogDismissed(dialogId: Int) {
        if (dialogId != pendingReaderColorDialogId) return
        pendingReaderColorDialogId = -1
        pendingReaderColorSelected = null
    }

    override fun onDestroy() {
        reloadBookJob?.cancel()
        paginationJob?.cancel()
        chapterPreloadJob?.cancel()
        m4bChapterLoadJob?.cancel()
        syncJob?.cancel()
        imagePauseResumeJob?.cancel()
        floatingOverlayStartJob?.cancel()
        persistAudioPlaybackSnapshot()
        BookReaderFloatingBridge.removePlaybackStateListener(sharedPlaybackStateListener)
        BookReaderFloatingBridge.removePlaybackPositionListener(sharedPlaybackPositionListener)
        BookReaderFloatingBridge.detach(floatingBridgeController)
        player = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_EBOOK_TITLE = "extra_ebook_title"
        const val EXTRA_EBOOK_URI = "extra_ebook_uri"
        const val EXTRA_EBOOK_NAME = "extra_ebook_name"
        const val EXTRA_EBOOK_FORMAT = "extra_ebook_format"
        const val EXTRA_AUDIO_URI = "extra_audio_uri"
        const val EXTRA_SRT_URI = "extra_srt_uri"
        const val EXTRA_AUDIO_POSITION_MS = "extra_audio_position_ms"
        const val EXTRA_AUDIO_DURATION_MS = "extra_audio_duration_ms"
        private const val DEFAULT_MATCH_SEARCH_WINDOW = 200
        private const val MATCH_SEARCH_WINDOW_MIN = 50
        private const val MATCH_SEARCH_WINDOW_MAX = 1000
        private const val READER_PAGE_BG = 0xFFF3E7CF.toInt()
        private const val READER_TEXT = 0xFF2C241B.toInt()
        private const val READER_TIP = 0xFF7D6E5C.toInt()
        private const val READER_TEXT_COLOR_DIALOG_ID = 121
        private const val READER_BG_COLOR_DIALOG_ID = 122
        private const val READER_CUE_HIGHLIGHT_DIALOG_ID = 123
        private const val READER_TIP_COLOR_DIALOG_ID = 124
        private const val READER_TIP_DIVIDER_COLOR_DIALOG_ID = 125
        private const val LEGADO_COLOR_PICKER_IMAGE_BG_FALLBACK = 0xFF015A86.toInt()
        private const val MENU_BG = 0xFFF7F0E2.toInt()
        private const val MENU_TEXT = 0xFF2C241B.toInt()
        private const val SUBTLE_TEXT = 0xFF7D6E5C.toInt()
    }
}

private fun isLegadoAppProcessInForeground(): Boolean {
    val processInfo = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(processInfo)
    return processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
        processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
}

private fun buildLegadoReaderPlaybackKey(
    title: String,
    audioUri: Uri,
    srtUri: Uri?
): String {
    val stableSource = audioUri.toString().ifBlank {
        "title=$title|srt=${srtUri?.toString().orEmpty()}"
    }
    return buildDictionaryCacheKey(stableSource, title.ifBlank { "book" })
}

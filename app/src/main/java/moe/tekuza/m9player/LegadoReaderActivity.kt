package moe.tekuza.m9player

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
import android.util.Log
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
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
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

private data class ReaderPageAnchor(
    val chapterIndex: Int,
    val charPosition: Int
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
    private lateinit var toolbarTitleText: TextView
    private lateinit var chapterSeekBar: SeekBar
    private lateinit var listenActionText: TextView
    private lateinit var audioPlayPauseText: TextView
    private lateinit var playbackBarToggleButton: ImageButton
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
    private var activeCueIndex: Int = -1
    private var temporaryCuePage: TextPage? = null
    private var player: ExoPlayer? = null
    private var syncJob: Job? = null
    private var reloadBookJob: Job? = null
    private var paginationJob: Job? = null
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
    private var readerStyleSelect: Int = 0
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
    private var useZhLayout: Boolean = true
    private var textFullJustify: Boolean = true
    private var textBottomJustify: Boolean = true
    private var clickRegionActions: List<ReadView.TapAction> = ReadView.defaultClickRegionActions()
    private var progressByChapter: Boolean = true
    private var keepScreenOn: Boolean = false
    private var noAnimScrollPage: Boolean = false
    private var previewImageByClick: Boolean = false
    private var disableReturnKey: Boolean = false
    private var readBarStyleFollowPage: Boolean = false
    private var playbackBarPinnedVisible: Boolean = false
    private var showRubyText: Boolean = true
    private var playbackBarHeightPx: Int = 0
    private var pendingRestoreAnchor: ReaderPageAnchor? = null
    private var loadedDocumentBookUriText: String? = null
    private var loadedDocumentCharsetName: String? = null

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
        if (notifyState) {
            BookReaderFloatingBridge.notifyPlaybackState(isAudioPlaying())
        }
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
        volumeControlStream = AudioManager.STREAM_MUSIC
        setContentView(buildLegadoReaderShell())
        applySystemUiSettings()
        updateDisplayedBookTitle()
        BookReaderFloatingBridge.addPlaybackStateListener(sharedPlaybackStateListener)
        BookReaderFloatingBridge.addPlaybackPositionListener(sharedPlaybackPositionListener)
        initAudioPlayerIfNeeded()
        readView.post { loadDisplayedBook(anchor = pendingRestoreAnchor) }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (disableReturnKey) {
            readMenu.visibility = View.VISIBLE
            return
        }
        when {
            ::searchPanel.isInitialized && searchPanel.visibility == View.VISIBLE -> {
                hideSearchPanel()
                return
            }
            ::catalogPanel.isInitialized && catalogPanel.visibility == View.VISIBLE -> {
                hideCatalogPanel()
                return
            }
            ::searchMenu.isInitialized && searchMenu.visibility == View.VISIBLE -> {
                searchQuery = null
                searchHits = emptyList()
                searchHitIndex = -1
                hideSearchMenu()
                renderCurrentPage()
                return
            }
            ::audioControlPanel.isInitialized && audioControlPanel.visibility == View.VISIBLE -> {
                audioControlPanel.visibility = View.GONE
                return
            }
            ::moreSettingsPanel.isInitialized && moreSettingsPanel.visibility == View.VISIBLE -> {
                moreSettingsPanel.visibility = View.GONE
                return
            }
            ::readMenu.isInitialized && readMenu.visibility == View.VISIBLE -> {
                readMenu.visibility = View.GONE
                return
            }
        }
        if (returnToPlayerIfShared()) {
            return
        }
        super.onBackPressed()
    }

    override fun onPause() {
        persistAudioPlaybackSnapshot()
        persistReaderSettings()
        super.onPause()
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
            setBackgroundColor(READER_PAGE_BG)
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

        root.setOnClickListener {
            when {
                searchPanel.visibility == View.VISIBLE -> hideSearchPanel()
                catalogPanel.visibility == View.VISIBLE -> hideCatalogPanel()
                audioControlPanel.visibility == View.VISIBLE -> audioControlPanel.visibility = View.GONE
                moreSettingsPanel.visibility == View.VISIBLE -> moreSettingsPanel.visibility = View.GONE
                else -> {
                    readMenu.visibility = if (readMenu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
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
            setReaderColors(readerBgColor, readerTextColor, readerTipColor, readerBgAssetName, readerBgImageUri, readerBgAlpha)
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
            onPagePreview = { delta -> pages.getOrNull(pageIndex + delta) }
            onMovePages = { delta -> movePage(delta) }
            onPrevPage = { movePage(-1) }
            onNextPage = { movePage(1) }
            onTapAction = { handleTapRegionAction(it) }
            onSelectionAction = { action, text -> handleSelectionAction(action, text) }
            onSelectionProcessText = { intent, text -> handleSelectionProcessText(intent, text) }
            onImageClick = { image ->
                if (previewImageByClick) {
                    showImagePreviewDialog(image)
                }
            }
            onMenu = {
                when {
                    audioControlPanel.visibility == View.VISIBLE -> audioControlPanel.visibility = View.GONE
                    moreSettingsPanel.visibility == View.VISIBLE -> moreSettingsPanel.visibility = View.GONE
                    else -> readMenu.visibility =
                        if (readMenu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
            }
        }
    }

    private fun buildReadMenu(): View {
        return layoutInflater.inflate(R.layout.view_m9_legado_read_menu, null, false).apply {
            findViewById<View>(R.id.reader_menu_scrim).setOnClickListener {
                audioControlPanel.visibility = View.GONE
                moreSettingsPanel.visibility = View.GONE
                readMenu.visibility = View.GONE
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
            findViewById<SeekBar>(R.id.reader_page_seek).also { seek ->
                chapterSeekBar = seek
                seek.max = 0
                seek.progress = 0
                seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser && pages.isNotEmpty()) {
                            pageIndex = seekProgressToPageIndex(progress)
                            activeCueIndex = -1
                            renderCurrentPage()
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
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
            }
        }
    }

    private fun buildPlaybackBar(): View {
        return layoutInflater.inflate(R.layout.view_reader_playback_bar, null, false).apply {
            elevation = dp(8).toFloat()
            isClickable = true
            setOnClickListener { }
            findViewById<ImageButton>(R.id.reader_playback_prev).setOnClickListener { seekToAdjacentCue(-1) }
            findViewById<ImageButton>(R.id.reader_playback_next).setOnClickListener { seekToAdjacentCue(1) }
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
                readMenu.visibility = View.VISIBLE
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
            findViewById<TextView>(R.id.audio_prev_sentence_text).setOnClickListener { seekToAdjacentCue(-1) }
            findViewById<ImageButton>(R.id.audio_play_prev).setOnClickListener { seekToAdjacentCue(-1) }
            findViewById<TextView>(R.id.audio_play_pause).also {
                audioPlayPauseText = it
                it.setOnClickListener { toggleAudioPlayback() }
            }
            findViewById<ImageButton>(R.id.audio_play_next).setOnClickListener { seekToAdjacentCue(1) }
            findViewById<TextView>(R.id.audio_next_sentence_text).setOnClickListener { seekToAdjacentCue(1) }
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
                readView.setShowHeaderFooter(it)
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
        window.statusBarColor = systemBarColor
        window.navigationBarColor = systemBarColor
        controller.isAppearanceLightStatusBars = readerDarkStatusIcon
        controller.isAppearanceLightNavigationBars = readerDarkStatusIcon
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
        val menuBgColor = when {
            immersiveMenu -> readerBgColor
            isNight -> NIGHT_BOTTOM_BG
            else -> 0xFFF8F1E3.toInt()
        }
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
        playbackBar.setBackgroundColor(menuBgColor)
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
        if (::statusBarScrim.isInitialized) {
            statusBarScrim.setBackgroundColor(currentSystemBarColor())
        }
        if (::navigationBarScrim.isInitialized) {
            navigationBarScrim.setBackgroundColor(currentSystemBarColor())
        }
        applySystemUiSettings()
    }

    private fun currentSystemBarColor(): Int {
        return readerBgColor
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
        readMenu.visibility = View.GONE
        audioControlPanel.visibility = View.GONE
        moreSettingsPanel.visibility = View.GONE
        hideCatalogPanel()
        hideSearchPanel()
    }

    private fun isNightReaderTheme(): Boolean = readerBgColor == 0xFF1F1F1F.toInt()

    private fun currentMenuTextColor(): Int = if (isNightReaderTheme()) 0xFFF4F0E6.toInt() else 0xFF2C241B.toInt()

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
                resetReaderSettingsToDefaults()
            }
            .show()
    }

    private fun resetReaderSettingsToDefaults() {
        val anchor = currentPageAnchor()
        val defaults = LegadoReaderPersistedState(
            currentBookUri = importedBook?.uri?.toString(),
            currentChapterIndex = anchor?.chapterIndex ?: 0,
            currentCharPosition = anchor?.charPosition ?: 0
        )
        saveLegadoReaderPersistedState(this, defaults)
        recreate()
    }

    private fun currentClickRegionSummary(): String {
        return if (clickRegionActions == defaultClickRegionActionsForCurrentLayout()) {
            readerString(R.string.reader_click_region_summary_default)
        } else {
            readerString(R.string.reader_click_region_summary_custom)
        }
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

    private fun handleSelectionProcessText(intent: Intent, text: String) {
        runCatching {
            startActivity(
                Intent(intent).apply {
                    putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                    putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
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
        persistReaderSettings()
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
                        requestBookRelayout()
                    }
                    ReaderOverflowAction.CHARSET.menuId -> showEncodingMenu(anchor)
                }
                persistReaderSettings()
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
            BookReaderFloatingBridge.returnToPlayer()
            finish()
            return
        }
        persistAudioPlaybackSnapshot()
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
        startActivity(intent)
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
        BookReaderFloatingBridge.returnToPlayer()
        finish()
        return true
    }

    private fun updateDisplayedBookTitle() {
        val title = currentReaderTitle()
        if (::toolbarTitleText.isInitialized) toolbarTitleText.text = title
    }

    private fun toggleNightMode() {
        if (isNightReaderTheme()) {
            readerBgColor = READER_PAGE_BG
            readerTextColor = READER_TEXT
            readerTipColor = READER_TIP
        } else {
            readerBgColor = 0xFF1F1F1F.toInt()
            readerTextColor = 0xFFD8D2C5.toInt()
            readerTipColor = 0xFF948B7D.toInt()
        }
        closeReaderChrome()
        readView.setReaderColors(readerBgColor, readerTextColor, readerTipColor, readerBgAssetName, readerBgImageUri, readerBgAlpha)
        applyReadBarStyle()
        persistReaderSettings()
        requestBookRelayout()
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
        readMenu.visibility = View.GONE
        searchPanel.visibility = View.VISIBLE
        searchInputView.setText(initialQuery)
        searchInputView.setSelection(searchInputView.text.length)
        bindSearchResultList()
        updateSearchInfo()
    }

    private fun hideSearchPanel() {
        if (::searchPanel.isInitialized) {
            searchPanel.visibility = View.GONE
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
        }
    }

    private fun showChapterListDialog() {
        val chapters = document?.chapters.orEmpty()
        if (chapters.isEmpty()) {
            Toast.makeText(this, R.string.reader_catalog_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        catalogMode = CatalogMode.CHAPTERS
        catalogFilterQuery = ""
        audioControlPanel.visibility = View.GONE
        moreSettingsPanel.visibility = View.GONE
        searchPanel.visibility = View.GONE
        readMenu.visibility = View.GONE
        catalogPanel.visibility = View.VISIBLE
        catalogSearchInputView.setText("")
        bindCatalogList()
    }

    private fun navigateToSearchHit(index: Int, showSearchMenu: Boolean) {
        val hit = searchHits.getOrNull(index) ?: return
        val nextPage = findPageIndexForChapterPosition(hit.chapterIndex, hit.chapterPosition)
        if (nextPage < 0) return
        searchHitIndex = index
        pageIndex = nextPage
        activeCueIndex = -1
        renderCurrentPage()
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
                view.text = "${chapterIndex + 1}. $chapterTitle"
                view.setTextColor(if (chapterIndex == currentChapterIndex) accentColor() else currentMenuTextColor())
                return view
            }
        }
        catalogListView.setOnItemClickListener { _, _, which, _ ->
            val chapterIndex = filtered.getOrNull(which)?.first ?: return@setOnItemClickListener
            val next = pages.indexOfFirst { it.chapterIndex == chapterIndex }
            if (next >= 0) {
                pageIndex = next
                activeCueIndex = -1
                renderCurrentPage()
                hideCatalogPanel()
            }
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
            val next = findPageIndexForChapterPosition(bookmark.chapterIndex, bookmark.chapterPosition)
            if (next >= 0) {
                pageIndex = next
                activeCueIndex = -1
                renderCurrentPage()
                hideCatalogPanel()
            }
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

    private fun showStyleDialog() {
        ReadStyleDialog(
            activity = this,
            state = ReadStyleState(
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
            ),
            callback = object : ReadStyleDialog.Callback {
                override fun onTextSizeChanged(valueSp: Int) {
                    readerTextSizeSp = valueSp
                    readView.setTextSizeSp(valueSp.toFloat())
                }

                override fun onLetterSpacingChanged(value: Int) {
                    readerLetterSpacingDp = value
                }

                override fun onLineSpacingChanged(valueDp: Int) {
                    readerLineSpacingDp = valueDp
                }

                override fun onParagraphSpacingChanged(valueDp: Int) {
                    readerParagraphSpacingDp = valueDp
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
                    Toast.makeText(
                        this@LegadoReaderActivity,
                        getString(
                            R.string.reader_info_debug,
                            pages.size,
                            document?.chapters?.size ?: 0
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
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

                override fun onBackgroundClicked(index: Int) {
                    selectReaderStyle(index)
                }

                override fun onBackgroundLongClicked(index: Int) {
                    selectReaderStyle(index)
                    showReaderStyleConfigDialog(index)
                }

                override fun onBackgroundAddClicked() {
                    readerStyleConfigs.add(defaultLegadoReaderStyleConfigs().first().copy(name = "文字"))
                    val index = readerStyleConfigs.lastIndex
                    selectReaderStyle(index)
                    showReaderStyleConfigDialog(index)
                }
            }
        ).show()
    }

    private fun selectReaderStyle(index: Int) {
        val style = readerStyleConfigs.getOrNull(index) ?: return
        readerStyleSelect = index
        readerBgColor = style.bgColor
        readerTextColor = style.textColor
        readerTipColor = style.tipColor
        readerBgAlpha = style.bgAlpha
        readerDarkStatusIcon = style.darkStatusIcon
        readerUnderline = style.underline
        readerBgAssetName = style.bgAssetName
        readerBgImageUri = style.bgImageUri
        applyReaderVisualStyle()
        persistReaderSettings()
    }

    private fun applyReaderVisualStyle() {
        readView.setReaderColors(readerBgColor, readerTextColor, readerTipColor, readerBgAssetName, readerBgImageUri, readerBgAlpha)
        readView.setCueHighlightColor(readerCueHighlightColor)
        readView.setTextUnderline(readerUnderline && readerLayoutMode == M9LayoutMode.HORIZONTAL)
        applyReadBarStyle()
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
            readerStyleConfigs[index] = LegadoReaderStyleConfig(
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
                    persistDraftStyle()
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
                readerStyleConfigs[index] = LegadoReaderStyleConfig(
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
                readerTypeface = when (which) {
                    1 -> Typeface.SERIF
                    2 -> Typeface.MONOSPACE
                    else -> Typeface.DEFAULT
                }
                readView.setReaderTypeface(readerTypeface)
                dialog.dismiss()
                requestBookRelayout()
            }
            .show()
    }

    private fun showImagePreviewDialog(image: EbookImageRef) {
        val maxPreviewSide = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
            .coerceAtLeast(1)
        val bitmap = decodeSampledBitmap(
            bytes = image.bytes,
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
                        persistReaderSettings()
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
        val choices = resources.getStringArray(R.array.reader_info_titles)
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_title_info)
            .setSingleChoiceItems(choices, if (showReadTitleAddition) 0 else 1) { dialog, which ->
                showReadTitleAddition = which == 0
                readView.setShowHeaderFooter(showReadTitleAddition)
                persistReaderSettings()
                dialog.dismiss()
                requestBookRelayout()
                Toast.makeText(this, R.string.reader_info_saved, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun loadDisplayedBook(anchor: ReaderPageAnchor?, forceDocumentReload: Boolean = false) {
        val pageWidth = readView.contentWidth.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels - dp(44))
        val pageHeight = readView.contentHeight.takeIf { it > 0 } ?: (resources.displayMetrics.heightPixels - dp(220))
        val book = importedBook
        paginationJob?.cancel()
        paginationJob = lifecycleScope.launch {
            runCatching {
                val loaded = loadOrReuseDocument(book, forceDocumentReload)
                val loadedPages = withContext(Dispatchers.Default) {
                    paginateDocument(
                        document = loaded,
                        contentWidthPx = pageWidth.coerceAtLeast(1),
                        contentHeightPx = pageHeight.coerceAtLeast(dp(120))
                    )
                }
                loaded to loadedPages
            }.onSuccess { (loaded, loadedPages) ->
                document = loaded
                pages = loadedPages
                temporaryCuePage = null
                pageIndex = anchor
                    ?.let { pageIndexForAnchor(loadedPages, it) }
                    ?: pageIndex.coerceIn(0, pages.lastIndex)
                updateDisplayedBookTitle()
                renderCurrentPage()
                if (cueMatchesByCueIndex.isNotEmpty()) {
                    syncToAudioPosition(allowPageJump = isAudioPlaying())
                } else {
                    loadSrtSyncIfNeeded()
                    Log.d(
                        LEGADO_READER_LOG_TAG,
                        "loadDisplayedBook no in-memory matches; trying persisted restore"
                    )
                    restorePersistedMatchIfPossible()
                }
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
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
            return document!!
        }
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
        loadedDocumentBookUriText = bookUriText
        loadedDocumentCharsetName = preferredCharsetName
        if (forceDocumentReload) {
            Log.d(
                LEGADO_READER_LOG_TAG,
                "loadOrReuseDocument force reload resets in-memory match cache bookUri=$bookUriText charset=$preferredCharsetName"
            )
            cueMatchesByCueIndex = emptyMap()
            matchData = null
            activeCueIndex = -1
            temporaryCuePage = null
        }
        return loaded
    }

    private fun paginateDocument(
        document: EbookDocument,
        contentWidthPx: Int,
        contentHeightPx: Int
    ): List<TextPage> {
        val effectiveContentHeightPx = (
            contentHeightPx - readerBodyBottomReservePx()
        ).coerceAtLeast(dp(120))
        val config = M9ReadBookConfig(
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
        return TextPageFactory(config).createPages(
            document = document,
            contentWidthPx = contentWidthPx,
            contentHeightPx = effectiveContentHeightPx
        )
    }

    private fun readerBodyBottomReservePx(): Int {
        if (!showReadTitleAddition) return 0
        return dp(12)
    }

    private fun renderCurrentPage(forward: Boolean = true) {
        val normalPage = pages.getOrNull(pageIndex)
        val page = temporaryCuePage ?: normalPage
        if (page == null) {
            readView.setPage(
                TextPage(
                    title = currentReaderTitle(),
                    text = LEGADO_READER_DEFAULT_PARAGRAPHS.joinToString("\n\n"),
                    totalPages = 1
                )
            )
            return
        }
        val match = cueMatchesByCueIndex[activeCueIndex]
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
        updateProgressSeekBar()
        persistReaderSettings()
    }

    private fun updateProgressSeekBar() {
        if (pages.isEmpty()) return
        if (!progressByChapter) {
            chapterSeekBar.max = pages.lastIndex.coerceAtLeast(0)
            chapterSeekBar.progress = pageIndex.coerceIn(0, chapterSeekBar.max)
            return
        }
        val page = pages.getOrNull(pageIndex) ?: return
        val chapterPages = pages.filter { it.chapterIndex == page.chapterIndex }
        chapterSeekBar.max = (chapterPages.size - 1).coerceAtLeast(0)
        chapterSeekBar.progress = page.pageInChapter.coerceIn(0, chapterSeekBar.max)
    }

    private fun seekProgressToPageIndex(progress: Int): Int {
        if (!progressByChapter) return progress.coerceIn(0, pages.lastIndex)
        val currentChapter = pages.getOrNull(pageIndex)?.chapterIndex ?: return pageIndex
        val chapterStart = pages.indexOfFirst { it.chapterIndex == currentChapter }
        if (chapterStart < 0) return pageIndex
        return (chapterStart + progress).coerceIn(chapterStart, pages.lastIndex)
    }

    private fun highlightTextPage(page: TextPage, match: EbookCueMatch?): IntRange? {
        if (match == null || match.chapterIndex != page.chapterIndex) return null
        val start = (match.rawStart - page.charStart).coerceIn(0, page.text.length)
        val end = (match.rawEnd - page.charStart).coerceIn(start, page.text.length)
        if (end <= start) return null
        return start until end
    }

    private fun findTextPageForMatch(match: EbookCueMatch): Int {
        return pages.indexOfFirst { page ->
            page.chapterIndex == match.chapterIndex &&
                match.rawStart >= page.charStart &&
                match.rawStart < page.charEnd
        }.takeIf { it >= 0 }
            ?: pages.indexOfFirst { it.chapterIndex == match.chapterIndex }.coerceAtLeast(0)
    }

    private fun buildTemporaryCuePage(match: EbookCueMatch, startPageIndex: Int): Pair<Int, TextPage>? {
        val chapter = document?.chapters?.getOrNull(match.chapterIndex) ?: return null
        val startPage = pages.getOrNull(startPageIndex) ?: return null
        if (match.rawEnd <= startPage.charEnd) return null
        val pageWidth = readView.contentWidth.takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels - dp(44))
        val pageHeight = readView.contentHeight.takeIf { it > 0 }
            ?: (resources.displayMetrics.heightPixels - dp(220))
        var low = startPage.charStart.coerceIn(0, match.rawStart)
        var high = match.rawStart.coerceIn(low, chapter.text.length)
        var bestStart = high
        var bestPage: TextPage? = null
        while (low <= high) {
            val mid = (low + high) ushr 1
            val candidate = buildTemporaryNormalPageContainingCue(
                chapter = chapter,
                textStart = mid,
                rawStart = match.rawStart,
                rawEnd = match.rawEnd,
                pageWidth = pageWidth,
                pageHeight = pageHeight
            )
            if (candidate != null) {
                bestStart = mid
                bestPage = candidate.second
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        val temporaryPage = bestPage ?: return null
        val templateIndex = pages.indexOfFirst { candidate ->
            candidate.chapterIndex == match.chapterIndex &&
            candidate.charStart <= match.rawEnd &&
                match.rawEnd <= candidate.charEnd
        }.takeIf { it >= 0 } ?: (startPageIndex + 1).coerceAtMost(pages.lastIndex)
        val templatePage = pages.getOrNull(templateIndex) ?: return null
        temporaryPage.index = templatePage.index
        temporaryPage.pageInChapter = templatePage.pageInChapter
        temporaryPage.chapterPageCount = templatePage.chapterPageCount
        temporaryPage.chapterIndex = templatePage.chapterIndex
        temporaryPage.chapterSize = templatePage.chapterSize
        temporaryPage.globalIndex = templatePage.globalIndex
        temporaryPage.totalPages = templatePage.totalPages
        temporaryPage.title = templatePage.title
        temporaryPage.charStart += bestStart
        temporaryPage.charEnd += bestStart
        if (temporaryPage.charStart > match.rawStart || temporaryPage.charEnd < match.rawEnd) return null
        return templateIndex to temporaryPage
    }

    private fun buildTemporaryNormalPageContainingCue(
        chapter: EbookChapter,
        textStart: Int,
        rawStart: Int,
        rawEnd: Int,
        pageWidth: Int,
        pageHeight: Int
    ): Pair<Int, TextPage>? {
        val safeStart = textStart.coerceIn(0, rawEnd.coerceIn(0, chapter.text.length))
        val text = chapter.text.substring(safeStart)
        if (text.isBlank()) return null
        val shiftedImages = chapter.images.entries
            .asSequence()
            .filter { it.key >= safeStart }
            .associate { (position, image) -> (position - safeStart) to image }
        val shiftedRubySpans = chapter.rubySpans
            .asSequence()
            .filter { it.end > safeStart }
            .mapNotNull { span ->
                val start = (span.start - safeStart).coerceAtLeast(0)
                val end = span.end - safeStart
                if (end > start) span.copy(start = start, end = end) else null
            }
            .toList()
        val temporaryDocument = EbookDocument(
            title = document?.title ?: currentReaderTitle(),
            format = document?.format ?: "EPUB",
            chapters = listOf(
                EbookChapter(
                    title = chapter.title,
                    text = text,
                    sourcePath = chapter.sourcePath,
                    images = shiftedImages,
                    rubySpans = shiftedRubySpans
                )
            )
        )
        val temporaryPages = paginateDocument(
            document = temporaryDocument,
            contentWidthPx = pageWidth,
            contentHeightPx = pageHeight
        )
        val localCueStart = rawStart - safeStart
        val localCueEnd = rawEnd - safeStart
        val pageIndex = temporaryPages.indexOfFirst { page ->
            page.charStart <= localCueStart && localCueEnd <= page.charEnd
        }
        if (pageIndex < 0) return null
        return pageIndex to temporaryPages[pageIndex]
    }

    private fun movePage(delta: Int) {
        if (pages.isEmpty()) return
        val next = (pageIndex + delta).coerceIn(0, pages.lastIndex)
        if (next != pageIndex) {
            pageIndex = next
            activeCueIndex = -1
            temporaryCuePage = null
            renderCurrentPage(forward = delta > 0)
        }
    }

    private fun moveChapter(delta: Int) {
        if (pages.isEmpty()) return
        val currentChapter = pages.getOrNull(pageIndex)?.chapterIndex ?: return
        val targetChapter = (currentChapter + delta).coerceIn(0, (document?.chapters?.lastIndex ?: 0))
        val next = pages.indexOfFirst { it.chapterIndex == targetChapter }
        if (next >= 0) {
            pageIndex = next
            activeCueIndex = -1
            temporaryCuePage = null
            renderCurrentPage(forward = delta > 0)
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

    private fun togglePlaybackBar() {
        playbackBarPinnedVisible = !playbackBarPinnedVisible
        playbackBar.visibility = if (playbackBarPinnedVisible) View.VISIBLE else View.GONE
        if (playbackBarPinnedVisible) {
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
    }

    private fun toggleAudioControlPanel() {
        moreSettingsPanel.visibility = View.GONE
        audioControlPanel.visibility =
            if (audioControlPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        if (audioControlPanel.visibility == View.VISIBLE) {
            updateAudioControlLabels()
            loadSrtSyncIfNeeded()
        }
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
            currentPlayer.play()
        }
        publishReaderPlaybackBridgeSnapshot(notifyState = true)
        updateAudioControlLabels()
        if (currentPlayer.isPlaying) {
            syncToAudioPosition(allowPageJump = true)
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
        syncToAudioPosition(allowPageJump = true)
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

    private fun currentPageAnchor(): ReaderPageAnchor? {
        val page = pages.getOrNull(pageIndex) ?: return null
        return ReaderPageAnchor(
            chapterIndex = page.chapterIndex,
            charPosition = currentAnchorCharPosition(page)
        )
    }

    private fun currentAnchorCharPosition(page: TextPage): Int {
        val cueMatch = cueMatchesByCueIndex[activeCueIndex]
        if (cueMatch != null && cueMatch.chapterIndex == page.chapterIndex) {
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
            val anchor = currentPageAnchor()
            readView.post {
                relayoutCurrentDocument(anchor)
            }
        }
    }

    private fun requestBookRelayout(immediate: Boolean = false) {
        persistReaderSettings()
        reloadBookJob?.cancel()
        val anchor = currentPageAnchor()
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
        paginationJob = lifecycleScope.launch {
            val relayoutResult = runCatching {
                withContext(Dispatchers.Default) {
                    paginateDocument(
                        document = loaded,
                        contentWidthPx = pageWidth.coerceAtLeast(1),
                        contentHeightPx = pageHeight.coerceAtLeast(dp(120))
                    )
                }
            }
            val loadedPages = relayoutResult.getOrNull()
            if (loadedPages == null) {
                val error = relayoutResult.exceptionOrNull()
                if (error is CancellationException) return@launch
                Log.w(LEGADO_READER_LOG_TAG, "relayoutCurrentDocument failed", error)
                return@launch
            }
            pages = loadedPages
            temporaryCuePage = null
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

    private fun restorePersistedMatchIfPossible() {
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
        val snapshot = loadLegadoReaderMatchSnapshotOrNull(this, storeKey) ?: run {
            Log.d(LEGADO_READER_LOG_TAG, "restoreMatch miss key=${storeKey.take(48)}")
            return
        }
        cueMatchesByCueIndex = snapshot.matches.associateBy { it.cueIndex }
        matchData = EbookMatchData(
            matches = snapshot.matches,
            unmatched = snapshot.unmatched,
            totalCues = snapshot.totalCues
        )
        activeCueIndex = -1
        Log.d(
            LEGADO_READER_LOG_TAG,
            "restoreMatch applied matches=${snapshot.matches.size} totalCues=${snapshot.totalCues} unmatched=${snapshot.unmatched}"
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
        readerTypeface = when (readerTypefaceIndex) {
            1 -> Typeface.SERIF
            2 -> Typeface.MONOSPACE
            else -> Typeface.DEFAULT
        }
        readerParagraphIndentCount = state.paragraphIndentCount
        readerPaddingDp = state.paddingDp
        readerLayoutMode = state.layoutMode
        readerPageAnim = state.pageAnim
        readerStyleConfigs = state.readerStyleConfigs
            .takeIf { it.isNotEmpty() }
            ?.toMutableList()
            ?: defaultLegadoReaderStyleConfigs().toMutableList()
        readerStyleSelect = state.readerStyleSelect.coerceIn(0, readerStyleConfigs.lastIndex)
        readerStyleConfigs[readerStyleSelect].let { style ->
            readerBgColor = style.bgColor
            readerTextColor = style.textColor
            readerTipColor = style.tipColor
            readerBgAlpha = style.bgAlpha
            readerDarkStatusIcon = style.darkStatusIcon
            readerUnderline = style.underline
            readerBgAssetName = style.bgAssetName
            readerBgImageUri = style.bgImageUri
        }
        readerCueHighlightColor = state.cueHighlightColor
        hideStatusBar = state.hideStatusBar
        readBodyToLh = state.readBodyToLh
        hideNavigationBar = state.hideNavigationBar
        showBrightnessView = state.showBrightnessView
        brightnessAuto = state.brightnessAuto
        brightnessValue = state.brightnessValue
        brightnessPanelOnRight = state.brightnessPanelOnRight
        showReadTitleAddition = state.showReadTitleAddition
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
        val persisted = loadLegadoReaderPersistedState(this)
        if (bookUri != null && persisted.currentBookUri == bookUri) return
        pendingRestoreAnchor = null
    }

    private fun persistReaderSettings() {
        val anchor = currentPageAnchor()
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
                showRubyText = showRubyText,
                preferredCharsetName = preferredCharsetName,
                currentBookUri = importedBook?.uri?.toString(),
                currentChapterIndex = anchor?.chapterIndex ?: 0,
                currentCharPosition = anchor?.charPosition ?: 0
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
            bottomMargin = dp(10)
            topMargin = dp(8)
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
            runCatching {
                val loadedCues = parseEbookSrt(contentResolver, uri)
                loadedCues
            }.onSuccess { loadedCues ->
                cues = loadedCues
                loadedSrtUriText = uriText
                cueMatchesByCueIndex = emptyMap()
                matchData = null
                temporaryCuePage = null
                Log.d(
                    LEGADO_READER_LOG_TAG,
                    "loadSrtSyncIfNeeded loaded cues=${loadedCues.size} uri=$uriText reset in-memory match cache"
                )
                srtLoadError = if (loadedCues.isEmpty()) {
                    readerString(R.string.reader_srt_parse_failed_detail)
                } else {
                    null
                }
                if (loadedCues.isNotEmpty()) {
                    Log.d(
                        LEGADO_READER_LOG_TAG,
                        "loadSrtSyncIfNeeded trying persisted restore after SRT load"
                    )
                    restorePersistedMatchIfPossible()
                }
                onComplete?.invoke(loadedCues.isNotEmpty())
            }.onFailure { error ->
                srtLoadError = getString(
                    R.string.reader_srt_load_failed,
                    error.message ?: error.javaClass.simpleName
                )
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

    private fun syncToAudioPosition(allowPageJump: Boolean = true) {
        if (cues.isEmpty() || pages.isEmpty()) return
        val currentPosition = currentAudioPositionMs() ?: return
        val cueIndex = findEbookCueIndexAtTime(cues, currentPosition)
        if (cueIndex < 0) {
            val changed = activeCueIndex != -1 || temporaryCuePage != null
            activeCueIndex = -1
            temporaryCuePage = null
            if (changed) {
                renderCurrentPage()
            }
            return
        }
        if (cueIndex == activeCueIndex) return
        val match = cueMatchesByCueIndex[cueIndex] ?: run {
            activeCueIndex = cueIndex
            temporaryCuePage = null
            if (!allowPageJump) {
                renderCurrentPage()
            }
            return
        }
        activeCueIndex = cueIndex
        if (!allowPageJump) {
            temporaryCuePage = null
            renderCurrentPage()
            return
        }
        val startPageIndex = findTextPageForMatch(match)
        val temporaryPagePair = buildTemporaryCuePage(match, startPageIndex)
        temporaryCuePage = temporaryPagePair?.second
        val nextPage = temporaryPagePair?.first ?: startPageIndex
        if (nextPage != pageIndex) pageIndex = nextPage
        renderCurrentPage()
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
        syncJob?.cancel()
        persistAudioPlaybackSnapshot()
        BookReaderFloatingBridge.removePlaybackStateListener(sharedPlaybackStateListener)
        BookReaderFloatingBridge.removePlaybackPositionListener(sharedPlaybackPositionListener)
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
        private const val LEGADO_COLOR_PICKER_IMAGE_BG_FALLBACK = 0xFF015A86.toInt()
        private const val MENU_BG = 0xFFF7F0E2.toInt()
        private const val MENU_TEXT = 0xFF2C241B.toInt()
        private const val SUBTLE_TEXT = 0xFF7D6E5C.toInt()
    }
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

package moe.tekuza.m9player

import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.text.Editable
import android.text.TextWatcher
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.tekuza.m9player.legado.reader.M9PageAnim
import moe.tekuza.m9player.legado.reader.M9ReadBookConfig
import moe.tekuza.m9player.legado.reader.M9LayoutMode
import moe.tekuza.m9player.legado.reader.SearchMenu
import moe.tekuza.m9player.legado.reader.config.MoreConfigDialog
import moe.tekuza.m9player.legado.reader.config.MoreConfigState
import moe.tekuza.m9player.legado.reader.config.ReadStyleDialog
import moe.tekuza.m9player.legado.reader.config.ReadStyleState
import moe.tekuza.m9player.legado.reader.entities.TextPage
import moe.tekuza.m9player.legado.reader.page.ReadView
import moe.tekuza.m9player.legado.reader.provider.TextPageFactory

private const val LEGADO_READER_PROTOTYPE_TITLE = "吾輩は猫である"
private const val LEGADO_READER_PROTOTYPE_LOG_TAG = "LegadoReaderPrototype"
private const val NIGHT_BAR_BG = 0xFF677C8B.toInt()
private const val NIGHT_BOTTOM_BG = 0xFF3A3A3A.toInt()
private const val NIGHT_FLOATING_BG = 0xFF303030.toInt()
private const val NIGHT_BRIGHTNESS_BG = 0x80303030.toInt()
private const val NIGHT_ACCENT = 0xFFE36A3C.toInt()
private val LEGADO_READER_PROTOTYPE_PARAGRAPHS = listOf(
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
    REMOVE_H(4),
    SWITCH_LAYOUT(5),
    HELP(6)
}

class LegadoReaderPrototypeActivity : AppCompatActivity() {
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
    private lateinit var toolbarBackButton: TextView
    private lateinit var toolbarEncodingButton: TextView
    private lateinit var toolbarOverflowButton: TextView
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
    private var readerTextBold: Boolean = false
    private var readerTypefaceIndex: Int = 0
    private var readerTypeface: Typeface? = Typeface.DEFAULT
    private var readerParagraphIndentCount: Int = 0
    private var readerPaddingDp: Int = 22
    private var readerLayoutMode: M9LayoutMode = M9LayoutMode.HORIZONTAL
    private var readerPageAnim: M9PageAnim = M9PageAnim.NONE
    private var readerBgColor: Int = READER_PAGE_BG
    private var readerTextColor: Int = READER_TEXT
    private var readerTipColor: Int = READER_TIP
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
    private var clickMode: ReadView.ClickMode = ReadView.ClickMode.LEFT_CENTER_RIGHT
    private var progressByChapter: Boolean = true
    private var keepScreenOn: Boolean = false
    private var mouseWheelPage: Boolean = true
    private var keyPageOnLongPress: Boolean = false
    private var noAnimScrollPage: Boolean = false
    private var previewImageByClick: Boolean = false
    private var optimizeRender: Boolean = false
    private var disableReturnKey: Boolean = false
    private var readBarStyleFollowPage: Boolean = false
    private var playbackBarPinnedVisible: Boolean = false
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

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyPageOnLongPress) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    moveChapter(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    moveChapter(1)
                    return true
                }
            }
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (mouseWheelPage && event.action == MotionEvent.ACTION_SCROLL) {
            val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            when {
                vScroll > 0f -> movePage(-1)
                vScroll < 0f -> movePage(1)
            }
            if (vScroll != 0f) return true
        }
        return super.onGenericMotionEvent(event)
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
            ?: LEGADO_READER_PROTOTYPE_TITLE
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
                dp(360),
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
            setReaderColors(readerBgColor, readerTextColor, readerTipColor)
            setTextSizeSp(readerTextSizeSp.toFloat())
            setFakeBoldText(readerTextBold)
            setReaderTypeface(readerTypeface)
            setReaderPadding(dp(readerPaddingDp), dp(34), dp(readerPaddingDp), currentReaderBottomPaddingPx())
            setPageAnim(readerPageAnim)
            setClickMode(clickMode)
            setShowHeaderFooter(showReadTitleAddition)
            onPrevPage = { movePage(-1) }
            onNextPage = { movePage(1) }
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
                toolbarBackButton = it
                it.setOnClickListener {
                    returnToHome()
                }
            }
            findViewById<TextView>(R.id.reader_toolbar_title).also {
                toolbarTitleText = it
                it.text = currentReaderTitle()
            }
            findViewById<TextView>(R.id.reader_encoding).also {
                toolbarEncodingButton = it
                it.setOnClickListener { view -> showEncodingMenu(view) }
            }
            findViewById<TextView>(R.id.reader_overflow).also {
                toolbarOverflowButton = it
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
                    this@LegadoReaderPrototypeActivity,
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
            }
            onTextFullJustifyChanged = {
                textFullJustify = it
                requestBookRelayout()
            }
            onTextBottomJustifyChanged = {
                textBottomJustify = it
                requestBookRelayout()
            }
            onMouseWheelPageChanged = {
                mouseWheelPage = it
                persistReaderSettings()
            }
            onKeyPageOnLongPressChanged = {
                keyPageOnLongPress = it
                persistReaderSettings()
            }
            onNoAnimScrollPageChanged = {
                noAnimScrollPage = it
                if (it && readerPageAnim == M9PageAnim.SCROLL) {
                    readerPageAnim = M9PageAnim.NONE
                    readView.setPageAnim(readerPageAnim)
                }
                persistReaderSettings()
            }
            onPreviewImageByClickChanged = {
                previewImageByClick = it
                persistReaderSettings()
            }
            onOptimizeRenderChanged = {
                optimizeRender = it
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
            onDoublePageClicked = {
                showChoiceToastDialog(
                    readerString(R.string.double_page_horizontal),
                    resources.getStringArray(R.array.reader_double_page_titles)
                )
            }
            onProgressBehaviorClicked = { showProgressBehaviorDialog() }
            onPageTouchSlopClicked = {
                showNumberInputDialog(readerString(R.string.page_touch_slop_title), "8")
            }
            onClickRegionalConfigClicked = { showClickRegionDialog() }
            onCustomPageKeyClicked = { showPageKeyDialog() }
            bind(currentMoreConfigState())
        }
    }

    private fun currentMoreConfigState(): MoreConfigState {
        return MoreConfigState(
            hideStatusBar = hideStatusBar,
            readBodyToLh = readBodyToLh,
            hideNavigationBar = hideNavigationBar,
            showBrightnessView = showBrightnessView,
            showReadTitleAddition = showReadTitleAddition,
            useZhLayout = useZhLayout,
            textFullJustify = textFullJustify,
            textBottomJustify = textBottomJustify,
            mouseWheelPage = mouseWheelPage,
            keyPageOnLongPress = keyPageOnLongPress,
            noAnimScrollPage = noAnimScrollPage,
            previewImageByClick = previewImageByClick,
            optimizeRender = optimizeRender,
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
        val lightIcons = !isColorDark(systemBarColor)
        controller.isAppearanceLightStatusBars = lightIcons
        controller.isAppearanceLightNavigationBars = lightIcons
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
        val isNight = isNightReaderTheme()
        return when {
            readBarStyleFollowPage -> readerBgColor
            isNight -> NIGHT_BOTTOM_BG
            else -> 0xFFF8F1E3.toInt()
        }
    }

    private fun isColorDark(color: Int): Boolean {
        val red = (color shr 16) and 0xFF
        val green = (color shr 8) and 0xFF
        val blue = color and 0xFF
        val luminance = (0.299 * red + 0.587 * green + 0.114 * blue) / 255.0
        return luminance < 0.5
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
            chapterTitle = page.title.ifBlank { "Chapter ${page.chapterIndex + 1}" },
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

    private fun showChoiceToastDialog(title: String, items: Array<String>) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items) { _, which ->
                Toast.makeText(this, "$title：${items[which]}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

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
            }
            .show()
    }

    private fun showNumberInputDialog(title: String, initial: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(initial)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(R.string.reader_dialog_confirm) { _, _ ->
                Toast.makeText(this, "$title：${input.text}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.reader_dialog_cancel, null)
            .show()
    }

    private fun showClickRegionDialog() {
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
            resources.getStringArray(R.array.reader_click_region_titles).forEachIndexed { index, label ->
                addView(RadioButton(this@LegadoReaderPrototypeActivity).apply {
                    id = View.generateViewId()
                    text = label
                    isChecked = index == 0
                })
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_title_click_region)
            .setView(group)
            .setPositiveButton(R.string.reader_dialog_confirm) { _, _ ->
                val checkedIndex = (0 until group.childCount).indexOfFirst {
                    group.getChildAt(it).id == group.checkedRadioButtonId
                }.coerceAtLeast(0)
                clickMode = when (checkedIndex) {
                    1 -> ReadView.ClickMode.TOP_CENTER_BOTTOM
                    2 -> ReadView.ClickMode.FULL_NEXT
                    else -> ReadView.ClickMode.LEFT_CENTER_RIGHT
                }
                readView.setClickMode(clickMode)
                persistReaderSettings()
            }
            .setNegativeButton(R.string.reader_dialog_cancel, null)
            .show()
    }

    private fun showPageKeyDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
            resources.getStringArray(R.array.reader_page_key_titles).forEach { label ->
                addView(CheckBox(this@LegadoReaderPrototypeActivity).apply {
                    text = label
                    isChecked = true
                })
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_title_page_key)
            .setView(container)
            .setPositiveButton(R.string.reader_dialog_confirm) { _, _ ->
                Toast.makeText(this, R.string.reader_page_key_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.reader_dialog_cancel, null)
            .show()
    }

    private fun showOverflowMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, ReaderOverflowAction.PLAYER.menuId, 0, R.string.reader_menu_player)
            menu.add(0, ReaderOverflowAction.ADD_BOOKMARK.menuId, 1, R.string.reader_menu_add_bookmark)
            menu.add(0, ReaderOverflowAction.REMOVE_RUBY.menuId, 2, R.string.del_ruby_tag)
            menu.add(0, ReaderOverflowAction.REMOVE_H.menuId, 3, R.string.del_h_tag)
            menu.add(
                0,
                ReaderOverflowAction.SWITCH_LAYOUT.menuId,
                4,
                if (readerLayoutMode == M9LayoutMode.VERTICAL) R.string.reader_menu_switch_horizontal else R.string.reader_menu_switch_vertical
            )
            menu.add(0, ReaderOverflowAction.HELP.menuId, 5, R.string.reader_menu_help)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    ReaderOverflowAction.PLAYER.menuId -> openPlayerFromReader()
                    ReaderOverflowAction.ADD_BOOKMARK.menuId -> addCurrentBookmark()
                    ReaderOverflowAction.SWITCH_LAYOUT.menuId -> {
                        readerLayoutMode =
                            if (readerLayoutMode == M9LayoutMode.VERTICAL) {
                                M9LayoutMode.HORIZONTAL
                            } else {
                                M9LayoutMode.VERTICAL
                            }
                        requestBookRelayout()
                    }
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
        return document?.title ?: importedBook?.title ?: LEGADO_READER_PROTOTYPE_TITLE
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
        readView.setReaderColors(readerBgColor, readerTextColor, readerTipColor)
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
                            chapterTitle = chapter.title.ifBlank { currentReaderTitle() },
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
                val chapterTitle = item?.second?.title?.ifBlank { "Chapter ${chapterIndex + 1}" } ?: "Chapter ${chapterIndex + 1}"
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
                        this@LegadoReaderPrototypeActivity,
                        getString(
                            R.string.reader_info_debug,
                            pages.size,
                            document?.chapters?.size ?: 0
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onWeightClicked() {
                    readerTextBold = !readerTextBold
                    readView.setFakeBoldText(readerTextBold)
                    requestBookRelayout()
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
                        2 -> if (noAnimScrollPage) M9PageAnim.NONE else M9PageAnim.SCROLL
                        else -> M9PageAnim.NONE
                    }
                    readView.setPageAnim(readerPageAnim)
                    persistReaderSettings()
                }

                override fun onBackgroundClicked(index: Int) {
                    val colors = listOf(
                        READER_PAGE_BG to READER_TEXT,
                        0xFFFFFFFF.toInt() to 0xFF111111.toInt(),
                        0xFFD9E6D2.toInt() to 0xFF25301F.toInt(),
                        0xFF1F1F1F.toInt() to 0xFFD8D2C5.toInt()
                    )
                    val (bg, text) = colors.getOrElse(index) { colors.first() }
                    readerBgColor = bg
                    readerTextColor = text
                    readerTipColor = if (index == 3) 0xFF948B7D.toInt() else READER_TIP
                    readView.setReaderColors(readerBgColor, readerTextColor, readerTipColor)
                    applyReadBarStyle()
                    requestBookRelayout()
                }
            }
        ).show()
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
        val bitmap = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)
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
        lifecycleScope.launch {
            runCatching {
                val loaded = loadOrReuseDocument(book, forceDocumentReload)
                loaded to paginateDocument(
                    document = loaded,
                    contentWidthPx = pageWidth.coerceAtLeast(1),
                    contentHeightPx = pageHeight.coerceAtLeast(dp(120))
                )
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
                        LEGADO_READER_PROTOTYPE_LOG_TAG,
                        "loadDisplayedBook no in-memory matches; trying persisted restore"
                    )
                    restorePersistedMatchIfPossible()
                }
            }.onFailure { error ->
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
            loadEbookDocument(
                context = this@LegadoReaderPrototypeActivity,
                book = book,
                preferredCharsetName = preferredCharsetName
            )
        } else {
            EbookDocument(
                title = LEGADO_READER_PROTOTYPE_TITLE,
                format = "TXT",
                chapters = listOf(
                    EbookChapter(
                        title = LEGADO_READER_PROTOTYPE_TITLE,
                        text = LEGADO_READER_PROTOTYPE_PARAGRAPHS.joinToString("\n\n")
                    )
                )
            )
        }
        loadedDocumentBookUriText = bookUriText
        loadedDocumentCharsetName = preferredCharsetName
        if (forceDocumentReload) {
            Log.d(
                LEGADO_READER_PROTOTYPE_LOG_TAG,
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
            textBold = readerTextBold,
            typeface = readerTypeface,
            paddingLeftPx = dp(readerPaddingDp),
            paddingRightPx = dp(readerPaddingDp),
            layoutMode = readerLayoutMode,
            pageAnim = readerPageAnim
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

    private fun renderCurrentPage() {
        val normalPage = pages.getOrNull(pageIndex)
        val page = temporaryCuePage ?: normalPage
        if (page == null) {
            readView.setPage(
                TextPage(
                    title = currentReaderTitle(),
                    text = LEGADO_READER_PROTOTYPE_PARAGRAPHS.joinToString("\n\n"),
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
        readView.setPage(page, highlight, searchHighlight)
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
        val temporaryDocument = EbookDocument(
            title = document?.title ?: currentReaderTitle(),
            format = document?.format ?: "EPUB",
            chapters = listOf(
                EbookChapter(
                    title = chapter.title,
                    text = text,
                    sourcePath = chapter.sourcePath,
                    images = shiftedImages
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
            renderCurrentPage()
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
            renderCurrentPage()
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
            charPosition = page.charStart
        )
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
        val loadedPages = paginateDocument(
            document = loaded,
            contentWidthPx = pageWidth.coerceAtLeast(1),
            contentHeightPx = pageHeight.coerceAtLeast(dp(120))
        )
        pages = loadedPages
        temporaryCuePage = null
        pageIndex = anchor?.let { pageIndexForAnchor(loadedPages, it) } ?: pageIndex.coerceIn(0, pages.lastIndex)
        renderCurrentPage()
        if (cueMatchesByCueIndex.isNotEmpty()) {
            syncToAudioPosition(allowPageJump = isAudioPlaying())
        } else {
            Log.d(
                LEGADO_READER_PROTOTYPE_LOG_TAG,
                "relayoutCurrentDocument no in-memory matches; trying persisted restore"
            )
            restorePersistedMatchIfPossible()
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
            Log.d(LEGADO_READER_PROTOTYPE_LOG_TAG, "restoreMatch skipped document=null")
            return
        }
        if (cues.isEmpty()) {
            Log.d(LEGADO_READER_PROTOTYPE_LOG_TAG, "restoreMatch skipped cues empty")
            return
        }
        if (cueMatchesByCueIndex.isNotEmpty()) {
            Log.d(
                LEGADO_READER_PROTOTYPE_LOG_TAG,
                "restoreMatch skipped alreadyLoaded matches=${cueMatchesByCueIndex.size}"
            )
            return
        }
        val storeKey = currentReaderMatchStoreKey() ?: run {
            Log.d(LEGADO_READER_PROTOTYPE_LOG_TAG, "restoreMatch skipped storeKey=null")
            return
        }
        Log.d(
            LEGADO_READER_PROTOTYPE_LOG_TAG,
            "restoreMatch try key=${storeKey.take(48)} cues=${cues.size} book=${importedBook?.title}"
        )
        val snapshot = loadLegadoReaderMatchSnapshotOrNull(this, storeKey) ?: run {
            Log.d(LEGADO_READER_PROTOTYPE_LOG_TAG, "restoreMatch miss key=${storeKey.take(48)}")
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
            LEGADO_READER_PROTOTYPE_LOG_TAG,
            "restoreMatch applied matches=${snapshot.matches.size} totalCues=${snapshot.totalCues} unmatched=${snapshot.unmatched}"
        )
    }

    private fun persistCurrentMatchSnapshot() {
        val current = matchData ?: run {
            Log.d(LEGADO_READER_PROTOTYPE_LOG_TAG, "persistMatch skipped matchData=null")
            return
        }
        if (current.matches.isEmpty() || current.totalCues <= 0) {
            Log.d(
                LEGADO_READER_PROTOTYPE_LOG_TAG,
                "persistMatch skipped invalid matches=${current.matches.size} totalCues=${current.totalCues}"
            )
            return
        }
        val storeKey = currentReaderMatchStoreKey() ?: run {
            Log.d(LEGADO_READER_PROTOTYPE_LOG_TAG, "persistMatch skipped storeKey=null")
            return
        }
        Log.d(
            LEGADO_READER_PROTOTYPE_LOG_TAG,
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
        readerTextBold = state.textBold
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
        readerBgColor = state.bgColor
        readerTextColor = state.textColor
        readerTipColor = state.tipColor
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
        clickMode = state.clickMode
        progressByChapter = state.progressByChapter
        keepScreenOn = state.keepScreenOn
        mouseWheelPage = state.mouseWheelPage
        keyPageOnLongPress = state.keyPageOnLongPress
        noAnimScrollPage = state.noAnimScrollPage
        previewImageByClick = state.previewImageByClick
        optimizeRender = state.optimizeRender
        disableReturnKey = state.disableReturnKey
        readBarStyleFollowPage = state.readBarStyleFollowPage
        playbackBarPinnedVisible = state.playbackBarPinnedVisible
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
                textBold = readerTextBold,
                typefaceIndex = readerTypefaceIndex,
                paragraphIndentCount = readerParagraphIndentCount,
                paddingDp = readerPaddingDp,
                layoutMode = readerLayoutMode,
                pageAnim = readerPageAnim,
                bgColor = readerBgColor,
                textColor = readerTextColor,
                tipColor = readerTipColor,
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
                clickMode = clickMode,
                progressByChapter = progressByChapter,
                keepScreenOn = keepScreenOn,
                mouseWheelPage = mouseWheelPage,
                keyPageOnLongPress = keyPageOnLongPress,
                noAnimScrollPage = noAnimScrollPage,
                previewImageByClick = previewImageByClick,
                optimizeRender = optimizeRender,
                disableReturnKey = disableReturnKey,
                readBarStyleFollowPage = readBarStyleFollowPage,
                playbackBarPinnedVisible = playbackBarPinnedVisible,
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
                            LEGADO_READER_PROTOTYPE_LOG_TAG,
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
                    LEGADO_READER_PROTOTYPE_LOG_TAG,
                    "loadSrtSyncIfNeeded loaded cues=${loadedCues.size} uri=$uriText reset in-memory match cache"
                )
                srtLoadError = if (loadedCues.isEmpty()) {
                    readerString(R.string.reader_srt_parse_failed_detail)
                } else {
                    null
                }
                if (loadedCues.isNotEmpty()) {
                    Log.d(
                        LEGADO_READER_PROTOTYPE_LOG_TAG,
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
                Toast.makeText(this@LegadoReaderPrototypeActivity, srtLoadError, Toast.LENGTH_LONG).show()
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

    override fun onDestroy() {
        reloadBookJob?.cancel()
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

package moe.tekuza.m9player

import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.graphics.Typeface
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
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
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
import androidx.media3.common.MediaItem
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
private val LEGADO_READER_PROTOTYPE_PARAGRAPHS = listOf(
    "吾輩は猫である。名前はまだ無い。",
    "どこで生れたかとんと見當がつかぬ。何でも薄暗いじめじめした所でニャーニャー泣いていた事だけは記憶している。",
    "吾輩はここで始めて人間というものを見た。しかもあとで聞くとそれは書生という人間中で一番獰悪な種族であったそうだ。",
    "この書生というのは時々我々を捕えて煮て食うという話である。しかしその當時は何という考もなかったから別段恐しいとも思わなかった。"
)

private data class ReaderBookmark(
    val pageIndex: Int,
    val title: String,
    val preview: String
)

private data class ReaderPageAnchor(
    val chapterIndex: Int,
    val charPosition: Int
)

class LegadoReaderPrototypeActivity : AppCompatActivity() {
    private lateinit var readMenu: View
    private lateinit var moreSettingsPanel: MoreConfigDialog
    private lateinit var audioControlPanel: View
    private lateinit var playbackBar: View
    private lateinit var searchMenu: SearchMenu
    private lateinit var readView: ReadView
    private lateinit var toolbarTitleText: TextView
    private lateinit var chapterSeekBar: SeekBar
    private lateinit var listenActionText: TextView
    private lateinit var audioPlayPauseText: TextView
    private lateinit var playbackBarToggleButton: ImageButton
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
    private var useSharedPlaybackSession: Boolean = false
    private val sharedPlaybackStateListener = object : BookReaderFloatingBridge.PlaybackStateListener {
        override fun onPlaybackStateChanged(isPlaying: Boolean) {
            runOnUiThread { updateAudioControlLabels() }
        }
    }
    private val sharedPlaybackPositionListener = object : BookReaderFloatingBridge.PlaybackPositionListener {
        override fun onPlaybackPositionChanged(positionMs: Long) {
            runOnUiThread {
                if (useSharedPlaybackSession) {
                    syncToAudioPosition()
                }
            }
        }
    }
    private var preferredCharsetName: String? = null
    private var searchQuery: String? = null
    private var searchHitPages: List<Int> = emptyList()
    private var searchHitIndex: Int = -1
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
    private val bookmarks = mutableListOf<ReaderBookmark>()
    private var hideStatusBar: Boolean = false
    private var readBodyToLh: Boolean = true
    private var hideNavigationBar: Boolean = false
    private var showBrightnessView: Boolean = true
    private var volumeKeyPage: Boolean = true
    private var showReadTitleAddition: Boolean = true
    private var useZhLayout: Boolean = true
    private var textFullJustify: Boolean = true
    private var textBottomJustify: Boolean = true
    private var clickMode: ReadView.ClickMode = ReadView.ClickMode.LEFT_CENTER_RIGHT
    private var progressByChapter: Boolean = true
    private var keepScreenOn: Boolean = false
    private var mouseWheelPage: Boolean = true
    private var volumeKeyPageOnPlay: Boolean = false
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
    private val openBookDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        keepReadPermission(this, uri)
        val displayName = queryDisplayName(contentResolver, uri)
        val format = inferLocalReaderBookFormat(displayName, contentResolver.getType(uri))
        if (format == null) {
            Toast.makeText(this, "请选择 EPUB 或 TXT 文件", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val book = LocalReaderBook(
            title = localReaderBookTitleFromDisplayName(displayName),
            uri = uri,
            format = format,
            importedAtMs = System.currentTimeMillis()
        )
        saveLastLocalReaderBook(this, book)
        importedBook = book
        pendingRestoreAnchor = null
        document = null
        loadedDocumentBookUriText = null
        loadedDocumentCharsetName = null
        cueMatchesByCueIndex = emptyMap()
        matchData = null
        activeCueIndex = -1
        updateDisplayedBookTitle()
        loadDisplayedBook(anchor = null, forceDocumentReload = true)
        Toast.makeText(this, "已导入 ${book.title}", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreReaderSettings()
        importedBook = intentLocalReaderBook() ?: loadLastLocalReaderBook(this)
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val allowVolumePage = if (player?.isPlaying == true) volumeKeyPageOnPlay else volumeKeyPage
        if (allowVolumePage) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    movePage(-1)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    movePage(1)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyPageOnLongPress) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    moveChapter(-1)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN,
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
        super.onBackPressed()
    }

    override fun onPause() {
        if (!useSharedPlaybackSession) {
            persistAudioPlaybackSnapshot()
        }
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
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val top = if (hideStatusBar && readBodyToLh) 0 else bars.top
            val bottom = if (hideNavigationBar) 0 else bars.bottom
            view.setPadding(0, top, 0, bottom)
            insets
        }

        root.addView(buildStaticPage())

        readMenu = buildReadMenu()
        root.addView(readMenu)

        searchMenu = buildSearchMenu()
        root.addView(searchMenu)

        moreSettingsPanel = buildMoreSettingsPanel().apply {
            visibility = View.GONE
        }
        root.addView(
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
        root.addView(
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
        root.addView(
            playbackBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )

        root.setOnClickListener {
            when {
                audioControlPanel.visibility == View.VISIBLE -> audioControlPanel.visibility = View.GONE
                moreSettingsPanel.visibility == View.VISIBLE -> moreSettingsPanel.visibility = View.GONE
                else -> {
                    readMenu.visibility = if (readMenu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
            }
        }
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
            findViewById<TextView>(R.id.reader_back).setOnClickListener { finish() }
            findViewById<TextView>(R.id.reader_toolbar_title).also {
                toolbarTitleText = it
                it.text = currentReaderTitle()
            }
            findViewById<TextView>(R.id.reader_import).setOnClickListener {
                openBookDocument.launch(arrayOf("application/epub+zip", "text/plain", "application/octet-stream"))
            }
            findViewById<TextView>(R.id.reader_encoding).setOnClickListener {
                showEncodingMenu(it)
            }
            findViewById<TextView>(R.id.reader_overflow).setOnClickListener {
                showOverflowMenu(it)
            }
            findViewById<ImageButton>(R.id.reader_search).setOnClickListener {
                showSearchDialog()
            }
            findViewById<ImageButton>(R.id.reader_replace).setOnClickListener {
                showSasayakiMatchDialog()
            }
            findViewById<ImageButton>(R.id.reader_playback_bar).setOnClickListener {
                togglePlaybackBar()
            }
            findViewById<ImageButton>(R.id.reader_night).setOnClickListener {
                toggleNightMode()
            }
            findViewById<View>(R.id.reader_brightness).visibility =
                if (showBrightnessView) View.VISIBLE else View.GONE
            findViewById<SeekBar>(R.id.reader_brightness_seek).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        window.attributes = window.attributes.apply {
                            screenBrightness = (progress / 255f).coerceIn(0.05f, 1f)
                        }
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
            findViewById<View>(R.id.reader_brightness_auto).setOnClickListener {
                window.attributes = window.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
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
                showCatalogDialog()
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
            onResults = { showSearchResultsDialog() }
            onMainMenu = {
                hideSearchMenu()
                readMenu.visibility = View.VISIBLE
            }
            onExit = {
                searchQuery = null
                searchHitPages = emptyList()
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
            findViewById<SeekBar>(R.id.audio_speed_seek).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val speed = 0.5f + progress / 50f
                        speedValue.text = String.format(java.util.Locale.US, "%.1fx", speed)
                        player?.playbackParameters = PlaybackParameters(speed)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
            findViewById<TextView>(R.id.audio_timer_10).setOnClickListener { setAudioTimer(10) }
            findViewById<TextView>(R.id.audio_timer_30).setOnClickListener { setAudioTimer(30) }
            findViewById<TextView>(R.id.audio_timer_off).setOnClickListener {
                audioStopAtMs = null
                Toast.makeText(this@LegadoReaderPrototypeActivity, "已关闭定时", Toast.LENGTH_SHORT).show()
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
                readMenu.findViewById<View>(R.id.reader_brightness).visibility =
                    if (it) View.VISIBLE else View.GONE
                persistReaderSettings()
            }
            onVolumeKeyPageChanged = {
                volumeKeyPage = it
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
            onVolumeKeyPageOnPlayChanged = {
                volumeKeyPageOnPlay = it
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
            onDoublePageClicked = { showChoiceToastDialog("平板/横屏双页", arrayOf("全局单页", "横屏双页", "平板双页")) }
            onProgressBehaviorClicked = { showProgressBehaviorDialog() }
            onPageTouchSlopClicked = { showNumberInputDialog("滑动翻页阈值", "8") }
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
            volumeKeyPage = volumeKeyPage,
            showReadTitleAddition = showReadTitleAddition,
            useZhLayout = useZhLayout,
            textFullJustify = textFullJustify,
            textBottomJustify = textBottomJustify,
            mouseWheelPage = mouseWheelPage,
            volumeKeyPageOnPlay = volumeKeyPageOnPlay,
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
        val color = if (readBarStyleFollowPage) readerBgColor else 0xFFF8F1E3.toInt()
        readMenu.findViewById<View>(R.id.reader_title_bar).setBackgroundColor(color)
        readMenu.findViewById<View>(R.id.reader_bottom_panel).setBackgroundColor(color)
    }

    private fun showChoiceToastDialog(title: String, items: Array<String>) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items) { _, which ->
                Toast.makeText(this, "$title：${items[which]}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showScreenOrientationDialog() {
        val items = arrayOf("跟随系统", "竖屏", "横屏")
        val checked = when (requestedOrientation) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> 1
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> 2
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle("屏幕方向")
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
        val items = arrayOf("默认", "常亮")
        AlertDialog.Builder(this)
            .setTitle("屏幕超时")
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
        val items = arrayOf("调整本章页数", "调整全书进度")
        AlertDialog.Builder(this)
            .setTitle("进度条行为")
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
            .setPositiveButton("确定") { _, _ ->
                Toast.makeText(this, "$title：${input.text}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showClickRegionDialog() {
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
            arrayOf("左/中/右默认区域", "上/中/下区域", "全屏点击下一页").forEachIndexed { index, label ->
                addView(RadioButton(this@LegadoReaderPrototypeActivity).apply {
                    id = View.generateViewId()
                    text = label
                    isChecked = index == 0
                })
            }
        }
        AlertDialog.Builder(this)
            .setTitle("点击区域设置")
            .setView(group)
            .setPositiveButton("确定") { _, _ ->
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
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showPageKeyDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
            arrayOf("音量上键上一页", "音量下键下一页", "方向键翻页").forEach { label ->
                addView(CheckBox(this@LegadoReaderPrototypeActivity).apply {
                    text = label
                    isChecked = true
                })
            }
        }
        AlertDialog.Builder(this)
            .setTitle("自定义翻页按键")
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                Toast.makeText(this, "翻页按键配置已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showOverflowMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("删除ruby标签")
            menu.add("删除h标签")
            menu.add(if (readerLayoutMode == M9LayoutMode.VERTICAL) "切换横排" else "切换纵书")
            menu.add("帮助")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "切换纵书" -> {
                        readerLayoutMode = M9LayoutMode.VERTICAL
                        requestBookRelayout()
                    }
                    "切换横排" -> {
                        readerLayoutMode = M9LayoutMode.HORIZONTAL
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
            menu.add("自动")
            menu.add("UTF-8")
            menu.add("Shift_JIS")
            menu.add("GBK")
            menu.add("Big5")
            menu.add("UTF-16LE")
            setOnMenuItemClickListener { item ->
                preferredCharsetName = item.title.toString().takeUnless { it == "自动" }
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

    private fun updateDisplayedBookTitle() {
        val title = currentReaderTitle()
        if (::toolbarTitleText.isInitialized) toolbarTitleText.text = title
    }

    private fun toggleNightMode() {
        val isDark = readerBgColor == 0xFF1F1F1F.toInt()
        if (isDark) {
            readerBgColor = READER_PAGE_BG
            readerTextColor = READER_TEXT
            readerTipColor = READER_TIP
        } else {
            readerBgColor = 0xFF1F1F1F.toInt()
            readerTextColor = 0xFFD8D2C5.toInt()
            readerTipColor = 0xFF948B7D.toInt()
        }
        readView.setReaderColors(readerBgColor, readerTextColor, readerTipColor)
        applyReadBarStyle()
        persistReaderSettings()
        requestBookRelayout()
    }

    private fun showSearchDialog() {
        val input = EditText(this).apply {
            setSingleLine(true)
            hint = "搜索正文"
            setText(searchQuery.orEmpty())
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("搜索")
            .setView(input)
            .setPositiveButton("搜索") { _, _ ->
                startSearch(input.text.toString().trim())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startSearch(query: String) {
        if (query.isBlank()) return
        val hits = pages.mapIndexedNotNull { index, page ->
            index.takeIf { page.text.contains(query, ignoreCase = true) }
        }
        if (hits.isEmpty()) {
            Toast.makeText(this, "没有搜索结果", Toast.LENGTH_SHORT).show()
            return
        }
        searchQuery = query
        searchHitPages = hits
        searchHitIndex = 0
        pageIndex = hits.first()
        activeCueIndex = -1
        renderCurrentPage()
        updateSearchInfo()
        searchMenu.showMenu()
        readMenu.visibility = View.GONE
    }

    private fun navigateSearch(delta: Int) {
        if (searchHitPages.isEmpty()) return
        searchHitIndex = (searchHitIndex + delta).let { value ->
            when {
                value < 0 -> searchHitPages.lastIndex
                value > searchHitPages.lastIndex -> 0
                else -> value
            }
        }
        pageIndex = searchHitPages[searchHitIndex].coerceIn(0, pages.lastIndex)
        activeCueIndex = -1
        renderCurrentPage()
        updateSearchInfo()
    }

    private fun updateSearchInfo() {
        if (::searchMenu.isInitialized) {
            searchMenu.updateInfo("搜索结果: ${searchHitIndex + 1}/${searchHitPages.size} / 当前章节: ${pages.getOrNull(pageIndex)?.title ?: currentReaderTitle()}")
        }
    }

    private fun hideSearchMenu() {
        if (::searchMenu.isInitialized) searchMenu.hideMenu()
    }

    private fun showSearchResultsDialog() {
        if (searchHitPages.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("搜索结果")
            .setItems(searchHitPages.mapIndexed { index, page ->
                val text = pages.getOrNull(page)?.text?.toString()?.replace('\n', ' ') ?: ""
                "${index + 1}. ${text.take(40)}"
            }.toTypedArray()) { _, which ->
                searchHitIndex = which
                pageIndex = searchHitPages[which]
                renderCurrentPage()
                updateSearchInfo()
            }
            .show()
    }

    private fun showCatalogDialog() {
        AlertDialog.Builder(this)
            .setTitle("目录")
            .setItems(arrayOf("章节目录", "书签", "添加书签", "进度跳转")) { _, which ->
                when (which) {
                    0 -> showChapterListDialog()
                    1 -> showBookmarkDialog()
                    2 -> addCurrentBookmark()
                    3 -> showProgressJumpDialog()
                }
            }
            .show()
    }

    private fun showChapterListDialog() {
        val chapters = document?.chapters.orEmpty()
        if (chapters.isEmpty()) {
            Toast.makeText(this, "目录还没有加载完成", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("目录")
            .setItems(chapters.mapIndexed { index, chapter ->
                "${index + 1}. ${chapter.title.ifBlank { "Chapter ${index + 1}" }}"
            }.toTypedArray()) { _, which ->
                val next = pages.indexOfFirst { it.chapterIndex == which }
                if (next >= 0) {
                    pageIndex = next
                    activeCueIndex = -1
                    renderCurrentPage()
                    readMenu.visibility = View.GONE
                }
            }
            .show()
    }

    private fun addCurrentBookmark() {
        val page = pages.getOrNull(pageIndex) ?: return
        val preview = page.text.replace('\n', ' ').take(40)
        bookmarks.removeAll { it.pageIndex == pageIndex }
        bookmarks += ReaderBookmark(pageIndex, page.title.ifBlank { currentReaderTitle() }, preview)
        Toast.makeText(this, "已添加书签", Toast.LENGTH_SHORT).show()
    }

    private fun showBookmarkDialog() {
        if (bookmarks.isEmpty()) {
            Toast.makeText(this, "暂无书签", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("书签")
            .setItems(bookmarks.mapIndexed { index, bookmark ->
                "${index + 1}. ${bookmark.title}  ${bookmark.preview}"
            }.toTypedArray()) { _, which ->
                pageIndex = bookmarks[which].pageIndex.coerceIn(0, pages.lastIndex)
                activeCueIndex = -1
                renderCurrentPage()
                readMenu.visibility = View.GONE
            }
            .show()
    }

    private fun showProgressJumpDialog() {
        if (pages.isEmpty()) return
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), 0)
        }
        val label = text("第 ${pageIndex + 1} / ${pages.size} 页", 15f, MENU_TEXT)
        val seek = SeekBar(this).apply {
            max = pages.lastIndex.coerceAtLeast(0)
            progress = pageIndex.coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) label.text = "第 ${progress + 1} / ${pages.size} 页"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        container.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)))
        container.addView(seek, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
        AlertDialog.Builder(this)
            .setTitle("进度跳转")
            .setView(container)
            .setPositiveButton("跳转") { _, _ ->
                pageIndex = seek.progress.coerceIn(0, pages.lastIndex)
                activeCueIndex = -1
                renderCurrentPage()
                readMenu.visibility = View.GONE
            }
            .setNegativeButton("取消", null)
            .show()
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
                        "页数 ${pages.size}，章节 ${document?.chapters?.size ?: 0}",
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
        val choices = arrayOf("无缩进", "一字缩进", "两字缩进")
        AlertDialog.Builder(this)
            .setTitle("缩进")
            .setSingleChoiceItems(choices, readerParagraphIndentCount) { dialog, which ->
                readerParagraphIndentCount = which
                dialog.dismiss()
                requestBookRelayout()
            }
            .show()
    }

    private fun showFontDialog() {
        val choices = arrayOf("默认字体", "衬线字体", "等宽字体")
        AlertDialog.Builder(this)
            .setTitle("字体")
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
            Toast.makeText(this, "图片无法预览", Toast.LENGTH_SHORT).show()
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
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun showPaddingDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), 0)
        }
        val label = text("边距：${readerPaddingDp}dp", 15f, MENU_TEXT)
        val seek = SeekBar(this).apply {
            max = 40
            progress = readerPaddingDp.coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        readerPaddingDp = progress
                        label.text = "边距：${readerPaddingDp}dp"
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
            .setTitle("边距")
            .setView(container)
            .setPositiveButton("完成", null)
            .show()
    }

    private fun showTipConfigDialog() {
        val choices = arrayOf("显示页眉页脚", "隐藏页眉页脚")
        AlertDialog.Builder(this)
            .setTitle("提示信息")
            .setSingleChoiceItems(choices, if (showReadTitleAddition) 0 else 1) { dialog, which ->
                showReadTitleAddition = which == 0
                readView.setShowHeaderFooter(showReadTitleAddition)
                persistReaderSettings()
                dialog.dismiss()
                requestBookRelayout()
                Toast.makeText(this, "页眉页脚显示状态已保存", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun loadDisplayedBook() {
        loadDisplayedBook(anchor = null, forceDocumentReload = false)
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
                    syncToAudioPosition()
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
                        text = "打开 ebook 失败：${error.message ?: error.javaClass.simpleName}",
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
                "loadOrReuseDocument force reload clears match cache bookUri=$bookUriText charset=$preferredCharsetName"
            )
            cueMatchesByCueIndex = emptyMap()
            matchData = null
            activeCueIndex = -1
            temporaryCuePage = null
            clearLegadoReaderMatchSnapshot(this, currentReaderMatchStoreKey())
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
        val searchHighlight = searchQuery
            ?.takeIf { searchHitPages.getOrNull(searchHitIndex) == pageIndex }
            ?.let { query ->
                page.text.indexOf(query, ignoreCase = true)
                    .takeIf { it >= 0 }
                    ?.let { it until (it + query.length) }
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
        val uriText = uri.toString()
        useSharedPlaybackSession = BookReaderFloatingBridge.currentAudioUri() == uriText
        val playbackKey = currentReaderPlaybackKey()
        val restoredSnapshot = playbackKey?.let { key ->
            loadBookReaderPlaybackSnapshotOrNull(this, key)
        }
        val restoredPositionMs = when {
            pendingAudioRestorePositionMs > 0L -> pendingAudioRestorePositionMs
            restoredSnapshot != null -> restoredSnapshot.positionMs
            else -> 0L
        }.coerceAtLeast(0L)
        if (useSharedPlaybackSession) {
            player = null
            if (restoredPositionMs > 0L && kotlin.math.abs(BookReaderFloatingBridge.currentPlaybackPositionMs() - restoredPositionMs) > 800L) {
                BookReaderFloatingBridge.seekToPosition(restoredPositionMs)
            }
        } else {
            player = ExoPlayer.Builder(this).build().apply {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                seekTo(restoredPositionMs)
            }
        }
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
        if (useSharedPlaybackSession) {
            BookReaderFloatingBridge.togglePlayPause()
            updateAudioControlLabels()
            return
        }
        val currentPlayer = player
        if (currentPlayer == null) {
            Toast.makeText(this, "这本书没有绑定音频", Toast.LENGTH_SHORT).show()
            return
        }
        if (currentPlayer.isPlaying) {
            currentPlayer.pause()
        } else {
            currentPlayer.play()
        }
        updateAudioControlLabels()
        if (!currentPlayer.isPlaying) {
            persistAudioPlaybackSnapshot()
        }
    }

    private fun updateAudioControlLabels() {
        val isPlaying = if (useSharedPlaybackSession) {
            BookReaderFloatingBridge.isPlaying()
        } else {
            player?.isPlaying == true
        }
        if (::listenActionText.isInitialized) {
            listenActionText.text = if (isPlaying) "暂停" else "听书"
        }
        if (::audioPlayPauseText.isInitialized) {
            audioPlayPauseText.text = if (isPlaying) "暂停" else "播放"
        }
        if (::playbackBarToggleButton.isInitialized) {
            playbackBarToggleButton.setImageResource(
                if (isPlaying) R.drawable.reader_ic_pause_24dp else R.drawable.reader_ic_play_24dp
            )
        }
    }

    private fun setAudioTimer(minutes: Int) {
        audioStopAtMs = System.currentTimeMillis() + minutes * 60_000L
        Toast.makeText(this, "${minutes}分钟后暂停", Toast.LENGTH_SHORT).show()
    }

    private fun seekToAdjacentCue(delta: Int) {
        val currentPlayer = player
        if (!useSharedPlaybackSession && currentPlayer == null) {
            Toast.makeText(this, "这本书没有绑定音频", Toast.LENGTH_SHORT).show()
            return
        }
        if (cues.isEmpty()) {
            if (srtUri == null) {
                Toast.makeText(this, "这本书没有绑定 SRT", Toast.LENGTH_SHORT).show()
                return
            }
            loadSrtSyncIfNeeded(force = true) { success ->
                if (success) {
                    seekToAdjacentCue(delta)
                } else {
                    Toast.makeText(this, srtLoadError ?: "SRT 没有解析出字幕", Toast.LENGTH_LONG).show()
                }
            }
            Toast.makeText(this, "正在加载 SRT...", Toast.LENGTH_SHORT).show()
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
        if (useSharedPlaybackSession) {
            BookReaderFloatingBridge.seekToPosition(cues[targetIndex].startMs)
        } else {
            currentPlayer?.seekTo(cues[targetIndex].startMs)
        }
        activeCueIndex = -1
        if (!useSharedPlaybackSession) {
            persistAudioPlaybackSnapshot()
        }
        syncToAudioPosition()
    }

    private fun currentAudioPositionMs(): Long? {
        return if (useSharedPlaybackSession) {
            BookReaderFloatingBridge.currentPlaybackPositionMs().coerceAtLeast(0L)
        } else {
            player?.currentPosition?.coerceAtLeast(0L)
        }
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
        if (useSharedPlaybackSession) return
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
            syncToAudioPosition()
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
        volumeKeyPage = state.volumeKeyPage
        showReadTitleAddition = state.showReadTitleAddition
        useZhLayout = state.useZhLayout
        textFullJustify = state.textFullJustify
        textBottomJustify = state.textBottomJustify
        clickMode = state.clickMode
        progressByChapter = state.progressByChapter
        keepScreenOn = state.keepScreenOn
        mouseWheelPage = state.mouseWheelPage
        volumeKeyPageOnPlay = state.volumeKeyPageOnPlay
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
                volumeKeyPage = volumeKeyPage,
                showReadTitleAddition = showReadTitleAddition,
                useZhLayout = useZhLayout,
                textFullJustify = textFullJustify,
                textBottomJustify = textBottomJustify,
                clickMode = clickMode,
                progressByChapter = progressByChapter,
                keepScreenOn = keepScreenOn,
                mouseWheelPage = mouseWheelPage,
                volumeKeyPageOnPlay = volumeKeyPageOnPlay,
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
            Toast.makeText(this, "ebook 还没有加载完成", Toast.LENGTH_SHORT).show()
            return
        }
        if (cues.isEmpty()) {
            if (srtUri == null) {
                Toast.makeText(this, "这本书没有绑定 SRT", Toast.LENGTH_SHORT).show()
                return
            }
            loadSrtSyncIfNeeded(force = true) { success ->
                if (success) {
                    showSasayakiMatchDialog()
                } else {
                    Toast.makeText(
                        this,
                        srtLoadError ?: "SRT 没有解析出字幕",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            Toast.makeText(this, "正在加载 SRT...", Toast.LENGTH_SHORT).show()
            return
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        val summaryText = text(matchSummaryText(), 14f, MENU_TEXT).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        val windowText = text("Search Window: $matchSearchWindow", 14f, MENU_TEXT)
        val seekBar = SeekBar(this).apply {
            max = MATCH_SEARCH_WINDOW_MAX - MATCH_SEARCH_WINDOW_MIN
            progress = (matchSearchWindow - MATCH_SEARCH_WINDOW_MIN).coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        matchSearchWindow = MATCH_SEARCH_WINDOW_MIN + progress
                        windowText.text = "Search Window: $matchSearchWindow"
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
            .setTitle("Match")
            .setView(container)
            .setPositiveButton("开始匹配", null)
            .setNegativeButton("完成", null)
            .create()
        dialog.setOnShowListener {
            val startButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            startButton.setOnClickListener {
                startButton.isEnabled = false
                summaryText.text = "Matching..."
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
                        syncToAudioPosition()
                        renderCurrentPage()
                        summaryText.text = matchSummaryText()
                    }.onFailure { error ->
                        summaryText.text = "匹配失败：${error.message ?: error.javaClass.simpleName}"
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
            "当前 SRT：${cues.size} 条，尚未匹配"
        } else {
            "匹配率 ${current.matchRateText}，已匹配 ${current.matches.size}/${current.totalCues} 条"
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
                val previousSrtUriText = loadedSrtUriText
                cues = loadedCues
                loadedSrtUriText = uriText
                cueMatchesByCueIndex = emptyMap()
                matchData = null
                temporaryCuePage = null
                Log.d(
                    LEGADO_READER_PROTOTYPE_LOG_TAG,
                    "loadSrtSyncIfNeeded loaded cues=${loadedCues.size} uri=$uriText reset in-memory match cache"
                )
                if (previousSrtUriText != null && previousSrtUriText != uriText) {
                    clearLegadoReaderMatchSnapshot(
                        this@LegadoReaderPrototypeActivity,
                        currentReaderMatchStoreKey()
                    )
                }
                srtLoadError = if (loadedCues.isEmpty()) {
                    "SRT 没有解析出字幕，请确认文件是标准 .srt。"
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
                srtLoadError = "SRT 加载失败：${error.message ?: error.javaClass.simpleName}"
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
                        audioStopAtMs = null
                    }
                }
                val now = System.currentTimeMillis()
                if (!useSharedPlaybackSession && now - lastProgressSaveAt >= 2_500L) {
                    persistAudioPlaybackSnapshot()
                    lastProgressSaveAt = now
                }
                updateAudioControlLabels()
                syncToAudioPosition()
            }
        }
    }

    private fun syncToAudioPosition() {
        if (cues.isEmpty() || pages.isEmpty()) return
        val currentPosition = currentAudioPositionMs() ?: return
        val cueIndex = findEbookCueIndexAtTime(cues, currentPosition)
        if (cueIndex < 0) {
            temporaryCuePage = null
            return
        }
        if (cueIndex == activeCueIndex) return
        val match = cueMatchesByCueIndex[cueIndex] ?: run {
            temporaryCuePage = null
            return
        }
        activeCueIndex = cueIndex
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
        if (!useSharedPlaybackSession) {
            persistAudioPlaybackSnapshot()
        }
        BookReaderFloatingBridge.removePlaybackStateListener(sharedPlaybackStateListener)
        BookReaderFloatingBridge.removePlaybackPositionListener(sharedPlaybackPositionListener)
        player?.release()
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

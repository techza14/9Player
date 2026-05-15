package moe.tekuza.m9player

import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Space
import android.widget.ScrollView
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
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LEGADO_READER_PROTOTYPE_TITLE = "吾輩は猫である"
private val LEGADO_READER_PROTOTYPE_PARAGRAPHS = listOf(
    "吾輩は猫である。名前はまだ無い。",
    "どこで生れたかとんと見當がつかぬ。何でも薄暗いじめじめした所でニャーニャー泣いていた事だけは記憶している。",
    "吾輩はここで始めて人間というものを見た。しかもあとで聞くとそれは書生という人間中で一番獰悪な種族であったそうだ。",
    "この書生というのは時々我々を捕えて煮て食うという話である。しかしその當時は何という考もなかったから別段恐しいとも思わなかった。"
)

class LegadoReaderPrototypeActivity : AppCompatActivity() {
    private lateinit var readMenu: View
    private lateinit var moreSettingsPanel: View
    private lateinit var audioControlPanel: View
    private lateinit var pageTitleText: TextView
    private lateinit var toolbarTitleText: TextView
    private lateinit var pageTextView: TextView
    private lateinit var footerPageText: TextView
    private lateinit var footerProgressText: TextView
    private lateinit var chapterSeekBar: SeekBar
    private lateinit var listenActionText: TextView
    private lateinit var audioPlayPauseText: TextView
    private var importedBook: LocalReaderBook? = null
    private var audioUri: Uri? = null
    private var srtUri: Uri? = null
    private var document: EbookDocument? = null
    private var pages: List<EbookPage> = emptyList()
    private var pageIndex: Int = 0
    private var cues: List<EbookSrtCue> = emptyList()
    private var srtLoading: Boolean = false
    private var srtLoadError: String? = null
    private var loadedSrtUriText: String? = null
    private var cueMatchesByCueIndex: Map<Int, EbookCueMatch> = emptyMap()
    private var matchData: EbookMatchData? = null
    private var matchSearchWindow: Int = DEFAULT_MATCH_SEARCH_WINDOW
    private var activeCueIndex: Int = -1
    private var player: ExoPlayer? = null
    private var syncJob: Job? = null
    private var preferredCharsetName: String? = null
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
        updateDisplayedBookTitle()
        loadDisplayedBook()
        Toast.makeText(this, "已导入 ${book.title}", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importedBook = intentLocalReaderBook() ?: loadLastLocalReaderBook(this)
        audioUri = intent.getStringExtra(EXTRA_AUDIO_URI)?.trim()?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        srtUri = intent.getStringExtra(EXTRA_SRT_URI)?.trim()?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        volumeControlStream = AudioManager.STREAM_MUSIC
        setContentView(buildLegadoReaderShell())
        updateDisplayedBookTitle()
        initAudioPlayerIfNeeded()
        pageTextView.post { loadDisplayedBook() }
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
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        root.addView(buildStaticPage())

        readMenu = buildReadMenu()
        root.addView(readMenu)

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
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(34), dp(22), dp(22))
            addView(buildPageHeader())
            addView(buildPageText(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(buildPageFooter())
        }
    }

    private fun buildPageHeader(): View {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(
                text(currentReaderTitle(), 12f, READER_TIP).apply {
                    pageTitleText = this
                    maxLines = 1
                },
                LinearLayout.LayoutParams(0, dp(36), 1f)
            )
            addView(text("23:41", 12f, READER_TIP), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)))
        }
    }

    private fun buildPageText(): View {
        return TextView(this).apply {
            pageTextView = this
            gravity = Gravity.TOP or Gravity.START
            textSize = 20f
            setTextColor(READER_TEXT)
            includeFontPadding = true
            setLineSpacing(dp(8).toFloat(), 1f)
            setPadding(0, dp(18), 0, dp(18))
            text = LEGADO_READER_PROTOTYPE_PARAGRAPHS.joinToString("\n\n")
            setOnTouchListener { view, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    when {
                        event.x < view.width * 0.32f -> movePage(-1)
                        event.x > view.width * 0.68f -> movePage(1)
                        audioControlPanel.visibility == View.VISIBLE -> audioControlPanel.visibility = View.GONE
                        moreSettingsPanel.visibility == View.VISIBLE -> moreSettingsPanel.visibility = View.GONE
                        else -> readMenu.visibility =
                            if (readMenu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    }
                    true
                } else {
                    true
                }
            }
        }
    }

    private fun buildPageFooter(): View {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text("1 / 1", 12f, READER_TIP).apply {
                footerPageText = this
            }, LinearLayout.LayoutParams(0, dp(36), 1f))
            addView(text("0.0%", 12f, READER_TIP).apply {
                footerProgressText = this
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)))
        }
    }

    private fun buildReadMenu(): View {
        return FrameLayout(this).apply {
            addView(View(this@LegadoReaderPrototypeActivity).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener {
                    audioControlPanel.visibility = View.GONE
                    moreSettingsPanel.visibility = View.GONE
                    readMenu.visibility = View.GONE
                }
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

            addView(buildTitleBar(), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(58), Gravity.TOP))
            addView(buildBrightnessBar(), FrameLayout.LayoutParams(dp(52), dp(260), Gravity.START or Gravity.CENTER_VERTICAL).apply {
                leftMargin = dp(16)
            })
            addView(buildBottomMenu(), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
    }

    private fun buildTitleBar(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(4), 0)
            setBackgroundColor(MENU_BG)

            addView(actionText("<", 28f).apply { setOnClickListener { finish() } }, LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.MATCH_PARENT))
            addView(text(currentReaderTitle(), 18f, MENU_TEXT).apply {
                toolbarTitleText = this
                maxLines = 1
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(actionText("导入", 14f).apply {
                setOnClickListener { openBookDocument.launch(arrayOf("application/epub+zip", "text/plain", "application/octet-stream")) }
            }, LinearLayout.LayoutParams(dp(54), LinearLayout.LayoutParams.MATCH_PARENT))
            addView(actionText("设置编码", 14f).apply {
                setOnClickListener { showEncodingMenu(this) }
            }, LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.MATCH_PARENT))
            addView(actionText("⋮", 28f).apply {
                setOnClickListener { showOverflowMenu(this) }
            }, LinearLayout.LayoutParams(dp(46), LinearLayout.LayoutParams.MATCH_PARENT))
        }
    }

    private fun buildBrightnessBar(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setBackgroundColor(0x66333333)
            addView(text("A", 16f, Color.WHITE), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32)))
            addView(SeekBar(this@LegadoReaderPrototypeActivity).apply {
                max = 255
                progress = 160
                rotation = -90f
                isEnabled = false
            }, LinearLayout.LayoutParams(dp(180), 0, 1f))
            addView(text("↔", 18f, Color.WHITE), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32)))
        }
    }

    private fun buildBottomMenu(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(LinearLayout(this@LegadoReaderPrototypeActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                addView(roundFab("⌕"), LinearLayout.LayoutParams(dp(48), dp(64)))
                addView(Space(context), LinearLayout.LayoutParams(0, 1, 1f))
                addView(roundFab("替").apply {
                    setOnClickListener { showSasayakiMatchDialog() }
                }, LinearLayout.LayoutParams(dp(48), dp(64)))
                addView(Space(context), LinearLayout.LayoutParams(0, 1, 1f))
                addView(roundFab("☾"), LinearLayout.LayoutParams(dp(48), dp(64)))
            })

            addView(LinearLayout(this@LegadoReaderPrototypeActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(MENU_BG)
                setPadding(0, dp(5), 0, dp(7))
                addView(buildChapterProgressRow())
                addView(buildReadActionRow())
            })
        }
    }

    private fun buildChapterProgressRow(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            addView(text("上一章", 14f, MENU_TEXT).apply {
                setOnClickListener { moveChapter(-1) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(42)))
            addView(SeekBar(this@LegadoReaderPrototypeActivity).apply {
                chapterSeekBar = this
                max = 0
                progress = 0
                isEnabled = true
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser && pages.isNotEmpty()) {
                            pageIndex = progress.coerceIn(0, pages.lastIndex)
                            activeCueIndex = -1
                            renderCurrentPage()
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            }, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(text("下一章", 14f, MENU_TEXT).apply {
                setOnClickListener { moveChapter(1) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(42)))
        }
    }

    private fun buildReadActionRow(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(bottomAction("目录"), LinearLayout.LayoutParams(0, dp(58), 1f))
            addView(bottomAction("听书").apply {
                listenActionText = this
                setOnClickListener { toggleAudioControlPanel() }
            }, LinearLayout.LayoutParams(0, dp(58), 1f))
            addView(bottomAction("界面"), LinearLayout.LayoutParams(0, dp(58), 1f))
            addView(bottomAction("设置").apply {
                setOnClickListener {
                    audioControlPanel.visibility = View.GONE
                    moreSettingsPanel.visibility =
                        if (moreSettingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
            }, LinearLayout.LayoutParams(0, dp(58), 1f))
        }
    }

    private fun buildAudioControlPanel(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MENU_BG)
            setPadding(dp(18), dp(14), dp(18), dp(18))
            elevation = dp(8).toFloat()
            isClickable = true
            setOnClickListener { }

            addView(text("听书", 16f, MENU_TEXT).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34)))

            addView(LinearLayout(this@LegadoReaderPrototypeActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(audioControlButton("上一句").apply {
                    setOnClickListener { seekToAdjacentCue(-1) }
                }, LinearLayout.LayoutParams(0, dp(58), 1f))
                addView(audioControlButton("播放").apply {
                    audioPlayPauseText = this
                    textSize = 18f
                    setOnClickListener { toggleAudioPlayback() }
                }, LinearLayout.LayoutParams(0, dp(58), 1.2f))
                addView(audioControlButton("下一句").apply {
                    setOnClickListener { seekToAdjacentCue(1) }
                }, LinearLayout.LayoutParams(0, dp(58), 1f))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(66)))
        }
    }

    private fun buildMoreSettingsPanel(): View {
        return ScrollView(this).apply {
            setBackgroundColor(MENU_BG)
            isFillViewport = false
            addView(
                LinearLayout(this@LegadoReaderPrototypeActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), dp(12), dp(18), dp(12))
                    addView(text("阅读界面设置", 18f, MENU_TEXT), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)))
                    addView(settingLine("屏幕方向", "跟随系统"))
                    addView(settingLine("屏幕超时", "默认"))
                    addView(settingLine("隐藏状态栏"))
                    addView(settingLine("隐藏导航栏"))
                    addView(settingLine("扩展到刘海"))
                    addView(settingLine("平板/横屏双页", "全局单页"))
                    addView(settingLine("进度条行为", "调整本章页数"))
                    addView(settingLine("使用自定义中文分行"))
                    addView(settingLine("文字两端对齐"))
                    addView(settingLine("文字底部对齐"))
                    addView(settingLine("鼠标滚轮翻页"))
                    addView(settingLine("音量键翻页"))
                    addView(settingLine("按键长按翻页"))
                    addView(settingLine("滑动翻页阈值", "8"))
                    addView(settingLine("长按选择文本"))
                    addView(settingLine("显示亮度调节控件"))
                    addView(settingLine("禁用滚动点击动画"))
                    addView(settingLine("点击预览图片"))
                    addView(settingLine("启用绘制优化"))
                    addView(settingLine("点击区域设置"))
                    addView(settingLine("禁用返回键"))
                    addView(settingLine("自定义翻页按键"))
                    addView(settingLine("展开文本选择菜单"))
                    addView(settingLine("展示顶部工具栏附加区域"))
                    addView(settingLine("工具栏样式跟随页面"))
                },
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun showOverflowMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("删除ruby标签")
            menu.add("删除h标签")
            menu.add("帮助")
            setOnMenuItemClickListener { true }
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
                loadDisplayedBook()
                true
            }
            show()
        }
    }

    private fun settingLine(label: String, value: String? = null): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(label, 15f, MENU_TEXT), LinearLayout.LayoutParams(0, dp(38), 1f))
            if (value != null) {
                addView(text(value, 14f, SUBTLE_TEXT), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)))
            }
        }
    }

    private fun bottomAction(label: String): TextView {
        return text("◇\n$label", 13f, MENU_TEXT).apply {
            gravity = Gravity.CENTER
            setOnClickListener { }
        }
    }

    private fun roundFab(label: String): TextView {
        return text(label, 18f, MENU_TEXT).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(MENU_BG)
            setOnClickListener { }
        }
    }

    private fun actionText(label: String, sizeSp: Float): TextView {
        return text(label, sizeSp, MENU_TEXT).apply {
            gravity = Gravity.CENTER
            setOnClickListener { }
        }
    }

    private fun audioControlButton(label: String): TextView {
        return text(label, 15f, MENU_TEXT).apply {
            gravity = Gravity.CENTER
            setOnClickListener { }
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
        if (::pageTitleText.isInitialized) pageTitleText.text = title
        if (::toolbarTitleText.isInitialized) toolbarTitleText.text = title
    }

    private fun loadDisplayedBook() {
        val pageWidth = pageTextView.width.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels - dp(44))
        val pageHeight = pageTextView.height.takeIf { it > 0 } ?: (resources.displayMetrics.heightPixels - dp(220))
        val book = importedBook
        cues = emptyList()
        srtLoading = false
        srtLoadError = null
        loadedSrtUriText = null
        cueMatchesByCueIndex = emptyMap()
        matchData = null
        activeCueIndex = -1
        pageTextView.text = "正在加载..."
        lifecycleScope.launch {
            runCatching {
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
                val layout = EbookReaderLayout(
                    contentWidthPx = (pageWidth - pageTextView.paddingLeft - pageTextView.paddingRight)
                        .coerceAtLeast(1),
                    contentHeightPx = (pageHeight - pageTextView.paddingTop - pageTextView.paddingBottom)
                        .coerceAtLeast(dp(120)),
                    textSizePx = pageTextView.textSize,
                    lineSpacingPx = dp(8).toFloat(),
                    paragraphSpacingPx = dp(14).toFloat()
                )
                loaded to paginateEbookDocument(loaded, layout)
            }.onSuccess { (loaded, loadedPages) ->
                document = loaded
                pages = loadedPages
                pageIndex = pageIndex.coerceIn(0, pages.lastIndex)
                updateDisplayedBookTitle()
                renderCurrentPage()
                loadSrtSyncIfNeeded()
            }.onFailure { error ->
                pageTextView.text = "打开 ebook 失败：${error.message ?: error.javaClass.simpleName}"
                footerPageText.text = "0 / 0"
                footerProgressText.text = "0.0%"
            }
        }
    }

    private fun renderCurrentPage() {
        val page = pages.getOrNull(pageIndex)
        if (page == null) {
            pageTextView.text = LEGADO_READER_PROTOTYPE_PARAGRAPHS.joinToString("\n\n")
            footerPageText.text = "1 / 1"
            footerProgressText.text = "0.0%"
            return
        }
        val match = cueMatchesByCueIndex[activeCueIndex]
        val highlight = highlightPageText(page, match)
        pageTextView.text = if (highlight != null) {
            val span = SpannableString(page.text)
            span.setSpan(
                BackgroundColorSpan(0x66E53935),
                highlight.first,
                highlight.second,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            span.setSpan(
                ForegroundColorSpan(READER_TEXT),
                highlight.first,
                highlight.second,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            span
        } else {
            page.text
        }
        pageTitleText.text = page.title.ifBlank { currentReaderTitle() }
        footerPageText.text = "${page.globalIndex + 1} / ${page.totalPages}"
        footerProgressText.text = page.progressText
        chapterSeekBar.max = pages.lastIndex.coerceAtLeast(0)
        chapterSeekBar.progress = pageIndex.coerceIn(0, chapterSeekBar.max)
    }

    private fun movePage(delta: Int) {
        if (pages.isEmpty()) return
        val next = (pageIndex + delta).coerceIn(0, pages.lastIndex)
        if (next != pageIndex) {
            pageIndex = next
            activeCueIndex = -1
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
            renderCurrentPage()
        }
    }

    private fun initAudioPlayerIfNeeded() {
        val uri = audioUri ?: return
        player = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
        }
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
    }

    private fun toggleAudioPlayback() {
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
    }

    private fun updateAudioControlLabels() {
        val isPlaying = player?.isPlaying == true
        if (::listenActionText.isInitialized) {
            listenActionText.text = if (isPlaying) "◇\n暂停" else "◇\n听书"
        }
        if (::audioPlayPauseText.isInitialized) {
            audioPlayPauseText.text = if (isPlaying) "暂停" else "播放"
        }
    }

    private fun seekToAdjacentCue(delta: Int) {
        val currentPlayer = player
        if (currentPlayer == null) {
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
        val position = currentPlayer.currentPosition.coerceAtLeast(0L)
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
        syncToAudioPosition()
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
                cues = loadedCues
                loadedSrtUriText = uriText
                cueMatchesByCueIndex = emptyMap()
                matchData = null
                srtLoadError = if (loadedCues.isEmpty()) {
                    "SRT 没有解析出字幕，请确认文件是标准 .srt。"
                } else {
                    null
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
            while (true) {
                delay(350L)
                updateAudioControlLabels()
                syncToAudioPosition()
            }
        }
    }

    private fun syncToAudioPosition() {
        val currentPlayer = player ?: return
        if (cues.isEmpty() || pages.isEmpty()) return
        val cueIndex = findEbookCueIndexAtTime(cues, currentPlayer.currentPosition.coerceAtLeast(0L))
        if (cueIndex < 0 || cueIndex == activeCueIndex) return
        val match = cueMatchesByCueIndex[cueIndex] ?: return
        activeCueIndex = cueIndex
        val nextPage = findEbookPageForMatch(pages, match)
        if (nextPage != pageIndex) pageIndex = nextPage
        renderCurrentPage()
    }

    override fun onDestroy() {
        syncJob?.cancel()
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

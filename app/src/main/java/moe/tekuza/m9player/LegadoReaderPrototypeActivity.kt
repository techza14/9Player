package moe.tekuza.m9player

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Space
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(buildLegadoReaderShell())
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

        root.setOnClickListener {
            if (moreSettingsPanel.visibility == View.VISIBLE) {
                moreSettingsPanel.visibility = View.GONE
            } else {
                readMenu.visibility = if (readMenu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
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
                text(LEGADO_READER_PROTOTYPE_TITLE, 12f, READER_TIP).apply {
                    maxLines = 1
                },
                LinearLayout.LayoutParams(0, dp(36), 1f)
            )
            addView(text("23:41", 12f, READER_TIP), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)))
        }
    }

    private fun buildPageText(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(0, dp(18), 0, dp(18))
            LEGADO_READER_PROTOTYPE_PARAGRAPHS.forEach { paragraph ->
                addView(
                    text(paragraph, 20f, READER_TEXT).apply {
                        setLineSpacing(dp(8).toFloat(), 1f)
                    },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(14)
                    }
                )
            }
        }
    }

    private fun buildPageFooter(): View {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text("12 / 248", 12f, READER_TIP), LinearLayout.LayoutParams(0, dp(36), 1f))
            addView(text("4.8%", 12f, READER_TIP), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)))
        }
    }

    private fun buildReadMenu(): View {
        return FrameLayout(this).apply {
            addView(View(this@LegadoReaderPrototypeActivity).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener {
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
            addView(text(LEGADO_READER_PROTOTYPE_TITLE, 18f, MENU_TEXT), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(actionText("设置编码", 14f), LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.MATCH_PARENT))
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
                addView(roundFab("替"), LinearLayout.LayoutParams(dp(48), dp(64)))
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
            addView(text("上一章", 14f, MENU_TEXT), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(42)))
            addView(SeekBar(this@LegadoReaderPrototypeActivity).apply {
                max = 247
                progress = 12
                isEnabled = false
            }, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(text("下一章", 14f, MENU_TEXT), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(42)))
        }
    }

    private fun buildReadActionRow(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(bottomAction("目录"), LinearLayout.LayoutParams(0, dp(58), 1f))
            addView(bottomAction("听书"), LinearLayout.LayoutParams(0, dp(58), 1f))
            addView(bottomAction("界面"), LinearLayout.LayoutParams(0, dp(58), 1f))
            addView(bottomAction("设置").apply {
                setOnClickListener {
                    moreSettingsPanel.visibility =
                        if (moreSettingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
            }, LinearLayout.LayoutParams(0, dp(58), 1f))
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
                    addView(settingLine("朗读时音量键翻页"))
                    addView(settingLine("按键长按翻页"))
                    addView(settingLine("滑动翻页阈值", "8"))
                    addView(settingLine("自动换源"))
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

    private fun text(value: String, sizeSp: Float, color: Int): TextView {
        return TextView(this).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = true
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val READER_PAGE_BG = 0xFFF3E7CF.toInt()
        private const val READER_TEXT = 0xFF2C241B.toInt()
        private const val READER_TIP = 0xFF7D6E5C.toInt()
        private const val MENU_BG = 0xFFF7F0E2.toInt()
        private const val MENU_TEXT = 0xFF2C241B.toInt()
        private const val SUBTLE_TEXT = 0xFF7D6E5C.toInt()
    }
}

package com.stocklite.plugin.ui

import com.stocklite.plugin.service.ApiLogEntry
import com.stocklite.plugin.service.ApiLogger
import org.json.JSONArray
import org.json.JSONObject
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.border.EmptyBorder

class ApiLogPanel : JPanel(BorderLayout()) {

    private val PAGE_SIZE = 20

    private var allEntries: List<ApiLogEntry> = emptyList()
    private var currentPage = 0
    private val expandedIds = mutableSetOf<Long>()

    // ── toolbar ──────────────────────────────────────────────────────────
    private val countLabel  = JLabel("共 0 条").apply { font = font.deriveFont(11f); foreground = Color(0x888aaa) }
    private val clearBtn    = JButton("清除").apply   { font = font.deriveFont(11f) }
    private val failOnlyBtn = JButton("仅失败").apply {
        font           = font.deriveFont(11f)
        isFocusPainted = false
    }
    private var showFailOnly = false

    // ── list ─────────────────────────────────────────────────────────────
    private val listPanel  = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val scrollPane = JScrollPane(listPanel).apply {
        border = null
        verticalScrollBarPolicy   = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }

    // ── pager ─────────────────────────────────────────────────────────────
    private val prevBtn  = JButton("‹ 上一页").apply { font = font.deriveFont(11f) }
    private val nextBtn  = JButton("下一页 ›").apply { font = font.deriveFont(11f) }
    private val pageLbl  = JLabel("").apply        { font = font.deriveFont(11f); foreground = Color(0x888aaa) }

    // 用户是否"正在查看"：有展开项 或 不在第 0 页
    private val userEngaged get() = expandedIds.isNotEmpty() || currentPage > 0

    private val visibleEntries get() =
        if (showFailOnly) allEntries.filter { !it.success } else allEntries

    private val logListener: () -> Unit = {
        allEntries = ApiLogger.getAll()
        if (userEngaged) {
            // 用户正在查看：仅刷新计数，不重绘列表、不重置页码/滚动
            val failCount = allEntries.count { !it.success }
            countLabel.text = buildString {
                append("共 ${allEntries.size} 条")
                if (failCount > 0) append("  失败 $failCount")
            }
        } else {
            renderPage()
        }
    }

    init {
        buildUI()
        ApiLogger.addListener(logListener)
        allEntries = ApiLogger.getAll()
        renderPage()
    }

    private fun buildUI() {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(JLabel("API 日志").apply { font = font.deriveFont(Font.BOLD, 12f) })
            add(countLabel)
            add(failOnlyBtn)
            add(clearBtn)
        }
        updateFailOnlyStyle()
        failOnlyBtn.addActionListener {
            showFailOnly = !showFailOnly
            currentPage  = 0
            expandedIds.clear()
            updateFailOnlyStyle()
            renderPage()
        }
        clearBtn.addActionListener { ApiLogger.clear(); expandedIds.clear() }

        val pagerBar = JPanel(FlowLayout(FlowLayout.CENTER, 8, 4))
        pagerBar.add(prevBtn); pagerBar.add(pageLbl); pagerBar.add(nextBtn)
        prevBtn.addActionListener { if (currentPage > 0) { currentPage--; renderPage() } }
        nextBtn.addActionListener {
            val max = ((allEntries.size - 1) / PAGE_SIZE).coerceAtLeast(0)
            if (currentPage < max) { currentPage++; renderPage() }
        }

        add(toolbar,    BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(pagerBar,   BorderLayout.SOUTH)
    }

    private fun updateFailOnlyStyle() {
        if (showFailOnly) {
            failOnlyBtn.foreground = Color(0xf38ba8)
            failOnlyBtn.font       = failOnlyBtn.font.deriveFont(Font.BOLD, 11f)
        } else {
            failOnlyBtn.foreground = null
            failOnlyBtn.font       = failOnlyBtn.font.deriveFont(Font.PLAIN, 11f)
        }
    }

    private fun renderPage() {
        listPanel.removeAll()

        val entries = visibleEntries
        val total   = entries.size
        val maxPage = if (total == 0) 0 else (total - 1) / PAGE_SIZE
        currentPage = currentPage.coerceIn(0, maxPage)

        val failCount = allEntries.count { !it.success }
        countLabel.text = buildString {
            append("共 ${allEntries.size} 条")
            if (failCount > 0) append("  失败 $failCount")
        }
        pageLbl.text      = if (total == 0) "无数据" else "第 ${currentPage + 1} / ${maxPage + 1} 页"
        prevBtn.isEnabled = currentPage > 0
        nextBtn.isEnabled = currentPage < maxPage

        val start = currentPage * PAGE_SIZE
        val end   = minOf(start + PAGE_SIZE, total)

        if (start >= total) {
            listPanel.add(emptyHint())
        } else {
            for (entry in entries.subList(start, end)) {
                listPanel.add(LogItemPanel(entry))
                listPanel.add(Box.createVerticalStrut(2))
            }
        }

        listPanel.revalidate()
        listPanel.repaint()
        // 只有非 engaged 状态才重置滚动（此时 userEngaged 一定是 false）
        scrollPane.verticalScrollBar.value = 0
    }

    private fun emptyHint() = JLabel("暂无日志，等待接口请求…").apply {
        font = font.deriveFont(12f)
        foreground = Color(0x888aaa)
        horizontalAlignment = SwingConstants.CENTER
    }

    // ── 单条日志行 ────────────────────────────────────────────────────────

    inner class LogItemPanel(private val entry: ApiLogEntry) : JPanel(BorderLayout()) {

        private val formattedBody: String = formatBody(entry.responseBody)

        private val arrowLbl = JLabel("▸").apply {
            font      = font.deriveFont(10f)
            foreground = Color(0x6c7086)
        }

        private val bodyArea = JTextArea(formattedBody).apply {
            isEditable    = false
            lineWrap      = true
            wrapStyleWord = true
            font          = Font(Font.MONOSPACED, Font.PLAIN, 11)
            foreground    = Color(0xaaaaaa)
            background    = Color(0x1e1e2e)
            border        = EmptyBorder(6, 10, 6, 10)
            rows          = 8
        }

        private val bodyContainer = buildBodyContainer()

        init {
            border     = EmptyBorder(0, 4, 0, 4)
            background = Color(0x181825)
            isOpaque   = true

            val header = buildHeader()
            add(header,        BorderLayout.NORTH)
            add(bodyContainer, BorderLayout.CENTER)

            val expanded = entry.id in expandedIds
            arrowLbl.text        = if (expanded) "▾" else "▸"
            bodyContainer.isVisible = expanded

            header.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            header.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val nowExpanded = entry.id !in expandedIds
                    if (nowExpanded) expandedIds.add(entry.id) else expandedIds.remove(entry.id)
                    arrowLbl.text           = if (nowExpanded) "▾" else "▸"
                    bodyContainer.isVisible = nowExpanded
                    revalidate(); repaint()
                    SwingUtilities.getAncestorOfClass(JScrollPane::class.java, this@LogItemPanel)
                        ?.let { it.revalidate(); it.repaint() }
                }
            })
        }

        private fun buildHeader(): JPanel {
            val p = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
                background = Color(0x181825); isOpaque = true
            }
            val icon = JLabel(if (entry.success) "✓" else "✗").apply {
                font      = font.deriveFont(Font.BOLD, 12f)
                foreground = if (entry.success) Color(0xa6e3a1) else Color(0xf38ba8)
            }
            val time = JLabel(entry.time).apply {
                font      = font.deriveFont(11f)
                foreground = Color(0x6c7086)
            }
            val chip = JLabel(" ${entry.label} ").apply {
                font       = font.deriveFont(Font.BOLD, 11f)
                foreground = Color(0xcdd6f4)
                background = Color(0x313244)
                isOpaque   = true
                border     = EmptyBorder(1, 4, 1, 4)
            }
            val dur = JLabel("${entry.durationMs}ms").apply {
                font      = font.deriveFont(11f)
                foreground = when {
                    entry.durationMs < 500  -> Color(0xa6e3a1)
                    entry.durationMs < 2000 -> Color(0xf9e2af)
                    else                    -> Color(0xf38ba8)
                }
            }
            val status = JLabel(if (entry.statusCode == -1) "ERR" else "HTTP ${entry.statusCode}").apply {
                font      = font.deriveFont(10f)
                foreground = if (entry.success) Color(0x6c7086) else Color(0xeba0ac)
            }
            p.add(icon); p.add(time); p.add(chip); p.add(dur); p.add(status); p.add(arrowLbl)
            return p
        }

        private fun buildBodyContainer(): JPanel {
            val copyBtn = JButton("复制").apply {
                font           = font.deriveFont(10f)
                isFocusPainted = false
                cursor         = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText    = "复制响应内容到剪贴板"
            }
            copyBtn.addActionListener {
                Toolkit.getDefaultToolkit().systemClipboard
                    .setContents(StringSelection(formattedBody), null)
                copyBtn.text = "已复制 ✓"
                Timer(1500) { copyBtn.text = "复制" }.also { it.isRepeats = false; it.start() }
            }

            val bodyToolbar = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 2)).apply {
                background = Color(0x1e1e2e); isOpaque = true
                add(copyBtn)
            }

            val bodyScroll = JScrollPane(bodyArea).apply { border = null }

            val container = JPanel(BorderLayout()).apply {
                background = Color(0x1e1e2e); isOpaque = true
                add(bodyToolbar, BorderLayout.NORTH)
                add(bodyScroll,  BorderLayout.CENTER)
            }
            return container
        }
    }

    fun dispose() { ApiLogger.removeListener(logListener) }

    companion object {
        private fun formatBody(raw: String?): String {
            if (raw.isNullOrBlank()) return "(无响应体)"
            val trimmed = raw.trim()
            if (trimmed.startsWith("{")) {
                return try { JSONObject(trimmed).toString(2) } catch (_: Exception) { raw }
            }
            if (trimmed.startsWith("[")) {
                return try { JSONArray(trimmed).toString(2) } catch (_: Exception) { raw }
            }
            return raw
        }
    }
}

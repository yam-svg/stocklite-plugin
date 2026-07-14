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
import javax.swing.border.MatteBorder

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
    private val prevBtn = JButton("‹ 上一页").apply { font = font.deriveFont(11f) }
    private val nextBtn = JButton("下一页 ›").apply { font = font.deriveFont(11f) }
    private val pageLbl = JLabel("").apply         { font = font.deriveFont(11f); foreground = Color(0x888aaa) }

    private val userEngaged get() = expandedIds.isNotEmpty() || currentPage > 0

    private val visibleEntries get() =
        if (showFailOnly) allEntries.filter { !it.success } else allEntries

    private val logListener: () -> Unit = {
        allEntries = ApiLogger.getAll()
        if (userEngaged) {
            val failCount = allEntries.count { !it.success }
            countLabel.text = countText(allEntries.size, failCount)
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
            val max = ((visibleEntries.size - 1) / PAGE_SIZE).coerceAtLeast(0)
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

    private fun countText(total: Int, fail: Int) = buildString {
        append("共 $total 条")
        if (fail > 0) append("  失败 $fail")
    }

    private fun renderPage() {
        listPanel.removeAll()

        val entries = visibleEntries
        val total   = entries.size
        val maxPage = if (total == 0) 0 else (total - 1) / PAGE_SIZE
        currentPage = currentPage.coerceIn(0, maxPage)

        val failCount = allEntries.count { !it.success }
        countLabel.text   = countText(allEntries.size, failCount)
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
        scrollPane.verticalScrollBar.value = 0
    }

    private fun emptyHint() = JLabel("暂无日志，等待接口请求…").apply {
        font = font.deriveFont(12f)
        foreground = Color(0x888aaa)
        horizontalAlignment = SwingConstants.CENTER
    }

    // ── 单条日志行 ────────────────────────────────────────────────────────

    inner class LogItemPanel(private val entry: ApiLogEntry) : JPanel(BorderLayout()) {

        private val formattedBody = formatBody(entry.responseBody)

        private val arrowLbl = JLabel("▸").apply {
            font      = font.deriveFont(10f)
            foreground = Color(0x6c7086)
        }

        init {
            border     = MatteBorder(0, 0, 1, 0, Color(0x313244))
            background = Color(0x181825)
            isOpaque   = true

            val header = buildHeader()
            val detail = buildDetail()
            add(header, BorderLayout.NORTH)
            add(detail, BorderLayout.CENTER)

            val expanded = entry.id in expandedIds
            arrowLbl.text    = if (expanded) "▾" else "▸"
            detail.isVisible = expanded

            header.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            header.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val nowExpanded = entry.id !in expandedIds
                    if (nowExpanded) expandedIds.add(entry.id) else expandedIds.remove(entry.id)
                    arrowLbl.text    = if (nowExpanded) "▾" else "▸"
                    detail.isVisible = nowExpanded
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
            p.add(JLabel(if (entry.success) "✓" else "✗").apply {
                font      = font.deriveFont(Font.BOLD, 12f)
                foreground = if (entry.success) Color(0xa6e3a1) else Color(0xf38ba8)
            })
            p.add(JLabel(entry.time).apply {
                font      = font.deriveFont(11f)
                foreground = Color(0x6c7086)
            })
            p.add(JLabel(" ${entry.method} ").apply {
                font       = font.deriveFont(Font.BOLD, 10f)
                foreground = Color(0x89b4fa)
                background = Color(0x1e1e2e)
                isOpaque   = true
                border     = EmptyBorder(1, 3, 1, 3)
            })
            p.add(JLabel(" ${entry.label} ").apply {
                font       = font.deriveFont(Font.BOLD, 11f)
                foreground = Color(0xcdd6f4)
                background = Color(0x313244)
                isOpaque   = true
                border     = EmptyBorder(1, 4, 1, 4)
            })
            p.add(JLabel("${entry.durationMs}ms").apply {
                font      = font.deriveFont(11f)
                foreground = when {
                    entry.durationMs < 500  -> Color(0xa6e3a1)
                    entry.durationMs < 2000 -> Color(0xf9e2af)
                    else                    -> Color(0xf38ba8)
                }
            })
            p.add(JLabel(if (entry.statusCode == -1) "ERR" else "HTTP ${entry.statusCode}").apply {
                font      = font.deriveFont(10f)
                foreground = if (entry.success) Color(0x6c7086) else Color(0xeba0ac)
            })
            p.add(arrowLbl)
            return p
        }

        private fun buildDetail(): JPanel {
            val bg = Color(0x1e1e2e)

            fun sectionLabel(text: String) = JLabel(text).apply {
                font       = font.deriveFont(Font.BOLD, 10f)
                foreground = Color(0x6c7086)
                border     = EmptyBorder(6, 10, 2, 10)
            }

            fun textArea(content: String, rows: Int = 4) = JTextArea(content).apply {
                isEditable    = false
                lineWrap      = true
                wrapStyleWord = true
                font          = Font(Font.MONOSPACED, Font.PLAIN, 11)
                foreground    = Color(0xcdd6f4)
                background    = bg
                border        = EmptyBorder(2, 10, 6, 10)
                this.rows     = rows
            }

            val detail = JPanel().apply {
                layout     = BoxLayout(this, BoxLayout.Y_AXIS)
                background = bg
                isOpaque   = true
                border     = EmptyBorder(0, 0, 4, 0)
            }

            // ── 完整 URL ──
            detail.add(sectionLabel("URL"))
            val urlArea = textArea(entry.url, rows = 2)
            detail.add(JScrollPane(urlArea).apply { border = null; maximumSize = Dimension(Int.MAX_VALUE, 60) })

            // ── Referer（若存在）──
            if (!entry.referer.isNullOrBlank()) {
                detail.add(sectionLabel("Referer"))
                detail.add(textArea(entry.referer, rows = 1).also {
                    it.rows = 1
                })
            }

            // ── 请求体（POST）──
            if (!entry.requestBody.isNullOrBlank()) {
                detail.add(sectionLabel("请求体"))
                val reqFmt = formatBody(entry.requestBody)
                val reqArea = textArea(reqFmt, rows = 5)
                detail.add(JScrollPane(reqArea).apply { border = null; maximumSize = Dimension(Int.MAX_VALUE, 120) })
            }

            // ── 响应体 ──
            detail.add(sectionLabel("响应体"))
            val respArea = textArea(formattedBody, rows = 8)
            detail.add(JScrollPane(respArea).apply { border = null; maximumSize = Dimension(Int.MAX_VALUE, 200) })

            // ── 操作按钮栏 ──
            val btnBar = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 4)).apply {
                background = bg; isOpaque = true
            }

            val curlBtn = JButton("复制为 cURL").apply { font = font.deriveFont(10f); isFocusPainted = false }
            val copyBtn = JButton("复制响应体").apply  { font = font.deriveFont(10f); isFocusPainted = false }

            curlBtn.addActionListener {
                copyToClipboard(buildCurl(entry))
                flashButton(curlBtn, "已复制 ✓", "复制为 cURL")
            }
            copyBtn.addActionListener {
                copyToClipboard(formattedBody)
                flashButton(copyBtn, "已复制 ✓", "复制响应体")
            }

            btnBar.add(curlBtn); btnBar.add(copyBtn)
            detail.add(btnBar)

            return detail
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

        private fun buildCurl(e: ApiLogEntry): String = buildString {
            append("curl -X ${e.method}")
            append(" \\\n  '${e.url}'")
            append(" \\\n  -H 'User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'")
            if (!e.referer.isNullOrBlank()) append(" \\\n  -H 'Referer: ${e.referer}'")
            if (e.method == "POST" && !e.requestBody.isNullOrBlank()) {
                append(" \\\n  -H 'Content-Type: application/json'")
                val escaped = e.requestBody.replace("'", "'\\''")
                append(" \\\n  -d '${escaped}'")
            }
        }

        private fun copyToClipboard(text: String) {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }

        private fun flashButton(btn: JButton, flash: String, original: String) {
            btn.text = flash
            Timer(1500) { btn.text = original }.also { it.isRepeats = false; it.start() }
        }
    }
}

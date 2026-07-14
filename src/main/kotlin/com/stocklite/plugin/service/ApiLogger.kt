package com.stocklite.plugin.service

import javax.swing.SwingUtilities

data class ApiLogEntry(
    val id: Long,
    val time: String,
    val label: String,
    val url: String,
    val success: Boolean,
    val statusCode: Int,
    val durationMs: Long,
    val responseBody: String?
)

object ApiLogger {

    private const val MAX_ENTRIES = 500
    private const val MAX_BODY_LEN = 4096

    private val entries = ArrayDeque<ApiLogEntry>()
    private var counter = 0L
    private val listeners = mutableListOf<() -> Unit>()

    @Synchronized
    fun log(label: String, url: String, success: Boolean, statusCode: Int,
            durationMs: Long, body: String?) {
        val truncated = body?.let {
            if (it.length > MAX_BODY_LEN) it.substring(0, MAX_BODY_LEN) + "\n…(truncated)" else it
        }
        val now = java.time.LocalTime.now()
        val entry = ApiLogEntry(
            id          = ++counter,
            time        = String.format("%02d:%02d:%02d", now.hour, now.minute, now.second),
            label       = label,
            url         = url,
            success     = success,
            statusCode  = statusCode,
            durationMs  = durationMs,
            responseBody = truncated
        )
        entries.addFirst(entry)
        while (entries.size > MAX_ENTRIES) entries.removeLast()
        notifyListeners()
    }

    @Synchronized
    fun getAll(): List<ApiLogEntry> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
        notifyListeners()
    }

    fun addListener(fn: () -> Unit) { listeners.add(fn) }
    fun removeListener(fn: () -> Unit) { listeners.remove(fn) }

    private fun notifyListeners() {
        val copy = listeners.toList()
        if (copy.isEmpty()) return
        SwingUtilities.invokeLater { copy.forEach { it() } }
    }
}

package com.stocklite.plugin.util

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

object HttpUtil {

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    private const val TIMEOUT = 10_000

    fun get(url: String, referer: String? = null, charset: String = "UTF-8"): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", UA)
            if (referer != null) conn.setRequestProperty("Referer", referer)
            conn.setRequestProperty("Accept", "*/*")
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT
            val bytes = conn.inputStream.readBytes()
            conn.disconnect()
            String(bytes, Charset.forName(charset))
        } catch (_: Exception) {
            null
        }
    }

    /** 读取 GBK 编码响应（新浪 API 专用） */
    fun getGbk(url: String, referer: String? = null): String? =
        get(url, referer, "GBK")

    /**
     * JSON POST 请求，用于调用 AI API 等需要发送 Body 的接口。
     * @param timeoutMs  读取超时（AI 响应通常较慢，默认 45 秒）
     * @return Pair(statusCode, body)；-1 表示网络异常；errorStream 的内容作为 body 返回，便于展示错误信息
     */
    fun post(url: String, json: String, authHeader: String? = null, timeoutMs: Int = 45_000): Pair<Int, String?> {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.setRequestProperty("Accept", "application/json")
            if (authHeader != null) conn.setRequestProperty("Authorization", authHeader)
            conn.connectTimeout = TIMEOUT
            conn.readTimeout    = timeoutMs
            conn.doOutput = true
            val bodyBytes = json.toByteArray(Charsets.UTF_8)
            conn.setRequestProperty("Content-Length", bodyBytes.size.toString())
            conn.outputStream.use { it.write(bodyBytes) }
            val code   = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body   = stream?.use { String(it.readBytes(), Charsets.UTF_8) }
            conn.disconnect()
            code to body
        } catch (e: Exception) {
            -1 to e.message
        }
    }

    /**
     * 带 HTTP 状态码的 GET，用于检测限流（429）等非 200 响应。
     * @return Pair(statusCode, body)，网络异常时 statusCode = -1
     */
    fun getWithStatus(url: String, referer: String? = null, charset: String = "UTF-8",
                      authHeader: String? = null): Pair<Int, String?> {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", UA)
            if (referer     != null) conn.setRequestProperty("Referer",       referer)
            if (authHeader  != null) conn.setRequestProperty("Authorization", authHeader)
            conn.setRequestProperty("Accept", "*/*")
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT
            val code = conn.responseCode
            val body = if (code == 200) {
                String(conn.inputStream.readBytes(), Charset.forName(charset))
            } else null
            conn.disconnect()
            code to body
        } catch (_: Exception) {
            -1 to null
        }
    }
}

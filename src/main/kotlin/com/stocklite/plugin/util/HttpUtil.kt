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
     * 带 HTTP 状态码的 GET，用于检测限流（429）等非 200 响应。
     * @return Pair(statusCode, body)，网络异常时 statusCode = -1
     */
    fun getWithStatus(url: String, referer: String? = null, charset: String = "UTF-8"): Pair<Int, String?> {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", UA)
            if (referer != null) conn.setRequestProperty("Referer", referer)
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

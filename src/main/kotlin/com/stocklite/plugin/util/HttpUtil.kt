package com.stocklite.plugin.util

import com.stocklite.plugin.service.ApiLogger
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

object HttpUtil {

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    private const val TIMEOUT = 10_000

    fun get(url: String, referer: String? = null, charset: String = "UTF-8",
            label: String? = null): String? {
        val body = getHttp(url, referer, charset, label)
        if (body != null) return body
        // 东财在部分网络下 IPv4 握手后被重置，而 IPv6 正常；Java 默认优先 IPv4，故失败后改走 IPv6 直连
        return if (supportsIpv6Fallback(url)) getViaIpv6(url, referer, charset, label) else null
    }

    private fun getHttp(url: String, referer: String?, charset: String, label: String?): String? {
        val t0 = System.currentTimeMillis()
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", UA)
            if (referer != null) conn.setRequestProperty("Referer", referer)
            conn.setRequestProperty("Accept", "*/*")
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT
            val code = conn.responseCode
            val body = if (code == 200) String(conn.inputStream.readBytes(), Charset.forName(charset)) else null
            conn.disconnect()
            ApiLogger.log(label ?: urlLabel(url), "GET", url, referer, null,
                code == 200, code, System.currentTimeMillis() - t0, body)
            body
        } catch (e: Exception) {
            ApiLogger.log(label ?: urlLabel(url), "GET", url, referer, null,
                false, -1, System.currentTimeMillis() - t0, e.message)
            null
        }
    }

    private fun supportsIpv6Fallback(url: String): Boolean = try {
        URI(url).host?.endsWith("eastmoney.com") == true
    } catch (_: Exception) { false }

    /**
     * 手动解析 IPv6 地址后用 SSLSocket 直连（SNI/Host 仍用域名，证书按域名校验）。
     * HttpURLConnection 无法指定地址族，Java 优先取 IPv4 地址，这里绕过它。
     */
    private fun getViaIpv6(url: String, referer: String?, charset: String, label: String?): String? {
        val t0 = System.currentTimeMillis()
        var socket: SSLSocket? = null
        return try {
            val uri  = URI(url)
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 443
            val addr = InetAddress.getAllByName(host).filterIsInstance<Inet6Address>().firstOrNull()
                ?: return null
            val path = uri.rawPath.ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
            val s = SSLContext.getDefault().socketFactory.createSocket() as SSLSocket
            socket = s
            s.sslParameters = s.sslParameters.apply {
                serverNames = listOf<SNIServerName>(SNIHostName(host))
                endpointIdentificationAlgorithm = "HTTPS"
            }
            s.connect(InetSocketAddress(addr, port), TIMEOUT)
            s.soTimeout = TIMEOUT
            s.startHandshake()
            val req = buildString {
                append("GET $path HTTP/1.1\r\n")
                append("Host: $host\r\n")
                append("User-Agent: $UA\r\n")
                if (referer != null) append("Referer: $referer\r\n")
                append("Accept: */*\r\n")
                append("Accept-Encoding: identity\r\n")
                append("Connection: close\r\n\r\n")
            }
            s.outputStream.write(req.toByteArray(Charsets.UTF_8))
            s.outputStream.flush()
            val raw  = String(s.inputStream.readBytes(), Charset.forName(charset))
            val split = raw.indexOf("\r\n\r\n")
            if (split < 0) return null
            val head = raw.substring(0, split)
            if (!head.startsWith("HTTP/1.1 200") && !head.startsWith("HTTP/1.0 200")) return null
            var body = raw.substring(split + 4)
            if (head.contains("Transfer-Encoding: chunked", ignoreCase = true)) body = decodeChunked(body)
            ApiLogger.log(label ?: urlLabel(url), "GET(IPv6)", url, referer, null,
                true, 200, System.currentTimeMillis() - t0, body)
            body
        } catch (e: Exception) {
            ApiLogger.log(label ?: urlLabel(url), "GET(IPv6)", url, referer, null,
                false, -1, System.currentTimeMillis() - t0, e.message)
            null
        } finally {
            try { socket?.close() } catch (_: Exception) { }
        }
    }

    private fun decodeChunked(body: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < body.length) {
            val lineEnd = body.indexOf("\r\n", i)
            if (lineEnd < 0) break
            val size = body.substring(i, lineEnd).trim().substringBefore(';').toIntOrNull(16) ?: break
            if (size == 0) break
            val start = lineEnd + 2
            val end   = minOf(start + size, body.length)
            sb.append(body, start, end)
            i = end + 2
        }
        return sb.toString()
    }

    /** 读取 GBK 编码响应（新浪 API 专用） */
    fun getGbk(url: String, referer: String? = null, label: String? = null): String? =
        get(url, referer, "GBK", label)

    /**
     * JSON POST 请求，用于调用 AI API 等需要发送 Body 的接口。
     * @param timeoutMs  读取超时（AI 响应通常较慢，默认 45 秒）
     * @return Pair(statusCode, body)；-1 表示网络异常；errorStream 的内容作为 body 返回，便于展示错误信息
     */
    fun post(url: String, json: String, authHeader: String? = null, timeoutMs: Int = 45_000,
             label: String? = null, headers: Map<String, String> = emptyMap()): Pair<Int, String?> {
        val t0 = System.currentTimeMillis()
        // Authorization header 中的 Bearer token 脱敏：只保留前8位
        val sanitizedReqBody = if (authHeader != null)
            json.replace(Regex("\"(sk-[A-Za-z0-9]{4})[A-Za-z0-9]+\""), "\"$1…\"")
        else json
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.setRequestProperty("Accept", "application/json")
            if (authHeader != null) conn.setRequestProperty("Authorization", authHeader)
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
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
            ApiLogger.log(label ?: urlLabel(url), "POST", url, null, sanitizedReqBody,
                code in 200..299, code, System.currentTimeMillis() - t0, body)
            code to body
        } catch (e: Exception) {
            ApiLogger.log(label ?: urlLabel(url), "POST", url, null, sanitizedReqBody,
                false, -1, System.currentTimeMillis() - t0, e.message)
            -1 to e.message
        }
    }

    /**
     * 带 HTTP 状态码的 GET，用于检测限流（429）等非 200 响应。
     * @return Pair(statusCode, body)，网络异常时 statusCode = -1
     */
    fun getWithStatus(url: String, referer: String? = null, charset: String = "UTF-8",
                      authHeader: String? = null, label: String? = null,
                      headers: Map<String, String> = emptyMap()): Pair<Int, String?> {
        val t0 = System.currentTimeMillis()
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", UA)
            if (referer    != null) conn.setRequestProperty("Referer",       referer)
            if (authHeader != null) conn.setRequestProperty("Authorization", authHeader)
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.setRequestProperty("Accept", "*/*")
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT
            val code = conn.responseCode
            val body = if (code == 200) String(conn.inputStream.readBytes(), Charset.forName(charset)) else null
            conn.disconnect()
            ApiLogger.log(label ?: urlLabel(url), "GET", url, referer, null,
                code == 200, code, System.currentTimeMillis() - t0, body)
            code to body
        } catch (e: Exception) {
            ApiLogger.log(label ?: urlLabel(url), "GET", url, referer, null,
                false, -1, System.currentTimeMillis() - t0, e.message)
            -1 to null
        }
    }

    /** DELETE 请求（Supabase 等 REST 接口用），返回 Pair(statusCode, body)，网络异常时 -1 */
    fun delete(url: String, label: String? = null,
               headers: Map<String, String> = emptyMap()): Pair<Int, String?> {
        val t0 = System.currentTimeMillis()
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "DELETE"
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { String(it.readBytes(), Charsets.UTF_8) }
            conn.disconnect()
            ApiLogger.log(label ?: urlLabel(url), "DELETE", url, null, null,
                code in 200..299, code, System.currentTimeMillis() - t0, body)
            code to body
        } catch (e: Exception) {
            ApiLogger.log(label ?: urlLabel(url), "DELETE", url, null, null,
                false, -1, System.currentTimeMillis() - t0, e.message)
            -1 to e.message
        }
    }

    private fun urlLabel(url: String): String {
        val path = try { java.net.URL(url).path } catch (_: Exception) { url }
        return path.substringAfterLast("/").substringBefore("?").ifEmpty { path }
    }
}

package com.stocklite.plugin.service

import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.util.HttpUtil
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 意见反馈（可回复）：基于 Supabase PostgREST 的匿名反馈通道。
 *
 * 表结构见 docs/feedback-supabase-setup.md。用户不登录：本机生成匿名 UUID 作为
 * device_id，凭它查询自己提交的反馈及开发者回复。anon key 随插件公开分发，
 * 权限由 Supabase RLS 策略限制（仅允许插入与按 device_id 查询）。
 *
 * 所有方法均为阻塞网络调用，须在后台线程执行。
 */
object FeedbackService {

    private const val SUPABASE_URL      = "https://wudikstpbcpfkbonmfxp.supabase.co"   // 例：https://xxxxxxxx.supabase.co
    private const val SUPABASE_ANON_KEY = "sb_publishable_CBWJFeu3z8XtT2koZ-mvCw_MseWTReV"   // anon/public role key，可随插件公开
    /**
     * 自有域名反代根地址（例：https://feedback.fanshu.xyz/api），留空则只直连 Supabase。
     * 大陆网络对 *.supabase.co 做 SNI 定向拦截：TCP 可连、TLS 握手被重置，直连必然失败。
     * 反代部署方式见 feedback-relay/api/rest/v1/feedback.ts，指向同一个 Supabase 项目，
     * 数据/密钥/RLS/回复查询均不变，只是出口域名换成自己的。
     */
    private const val SUPABASE_RELAY_URL = ""

    /** 是否已配置 Supabase 反馈后端 */
    val configured: Boolean get() = SUPABASE_URL.isNotBlank() && SUPABASE_ANON_KEY.isNotBlank()

    /**
     * 候选 API 根地址，按序尝试（反代优先，直连兜底，境外网络两者都能通）。
     * -Dstocklite.feedback.baseUrl=... 可临时覆盖反代地址，免重新编译。
     */
    private fun baseUrls(): List<String> {
        val override = System.getProperty("stocklite.feedback.baseUrl")?.trim()?.trimEnd('/')
        val relay = (override ?: SUPABASE_RELAY_URL).trim().trimEnd('/')
        return listOfNotNull(relay.takeIf { it.isNotBlank() }, SUPABASE_URL).distinct()
    }

    /** 一条反馈及其回复 */
    data class FeedbackItem(
        val id: String,
        val time: String,      // 已转本地时区的展示文本
        val content: String,
        val email: String,
        val reply: String,     // 空串表示尚未回复
        val repliedTime: String
    )

    /** 本机匿名设备标识：首次使用生成并持久化，删除重装后视为新用户 */
    private fun ensureDeviceId(): String {
        val state = StockliteState.getInstance()
        if (state.feedbackDeviceId.isBlank()) {
            state.feedbackDeviceId = UUID.randomUUID().toString()
        }
        return state.feedbackDeviceId
    }

    /**
     * apikey 为 Supabase publishable key（可公开）。新版 sb_publishable_ 密钥不是 JWT，
     * 只能放在 apikey 头；再发 Authorization: Bearer 会被判为 invalid JWT 直接 401。
     * x-device-id 供服务端 RLS 策略校验：匿名 role 只能插入/读取 device_id 等于该头的行，
     * 拿到 key 但没有具体设备 UUID 的人无法遍历他人反馈。
     */
    private fun authHeaders(deviceId: String) = mapOf(
        "apikey"      to SUPABASE_ANON_KEY,
        "x-device-id" to deviceId
    )

    /** 提交一条反馈；成功返回 true */
    fun submit(content: String, email: String): Boolean {
        val deviceId = ensureDeviceId()
        val payload = JSONObject()
            .put("id",        UUID.randomUUID().toString())
            .put("device_id", deviceId)
            .put("email",     email)
            .put("content",   content)
            .toString()
        return baseUrls().any { base ->
            HttpUtil.post(
                "$base/rest/v1/feedback", payload,
                timeoutMs = 15_000, label = "feedback-submit",
                headers = authHeaders(deviceId) + ("Prefer" to "return=minimal")
            ).first in 200..299
        }
    }

    /** 查询本机的反馈历史（含开发者回复），按时间倒序；失败返回 null */
    fun fetchMine(): List<FeedbackItem>? {
        val deviceId = StockliteState.getInstance().feedbackDeviceId
        if (deviceId.isBlank()) return emptyList()   // 还没提交过，无需请求
        val query = "?device_id=eq.$deviceId" +
            "&select=id,content,email,reply,replied_at,created_at" +
            "&order=created_at.desc&limit=50"
        for (base in baseUrls()) {
            val (code, body) = HttpUtil.getWithStatus(
                "$base/rest/v1/feedback$query", label = "feedback-list", headers = authHeaders(deviceId)
            )
            if (code != 200 || body.isNullOrBlank()) continue
            return try {
                val arr = JSONArray(body)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    FeedbackItem(
                        id          = o.optString("id"),
                        time        = formatTime(o.optString("created_at")),
                        content     = o.optString("content"),
                        email       = o.optString("email"),
                        reply       = o.optString("reply"),
                        repliedTime = formatTime(o.optString("replied_at"))
                    )
                }
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    /** 删除自己的一条反馈（id 必须属于本机 device_id，由服务端 RLS 保证）；成功返回 true */
    fun delete(id: String): Boolean {
        val deviceId = StockliteState.getInstance().feedbackDeviceId
        if (deviceId.isBlank() || id.isBlank()) return false
        // return=representation：RLS 无删除权限时 DELETE 会静默删 0 行返回空数组，据此判定
        return baseUrls().any { base ->
            val (code, body) = HttpUtil.delete(
                "$base/rest/v1/feedback?id=eq.$id",
                label = "feedback-delete",
                headers = authHeaders(deviceId) + ("Prefer" to "return=representation")
            )
            code in 200..299 && body?.contains("\"id\"") == true
        }
    }

    /** Supabase timestamptz -> 本地时区 "MM-dd HH:mm"；解析失败时退回原文截断 */
    private fun formatTime(raw: String): String {
        if (raw.isBlank()) return ""
        return try {
            OffsetDateTime.parse(raw)
                .atZoneSameInstant(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        } catch (_: Exception) {
            raw.replace('T', ' ').take(16)
        }
    }
}

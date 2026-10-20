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

    /** 是否已配置 Supabase 反馈后端 */
    val configured: Boolean get() = SUPABASE_URL.isNotBlank() && SUPABASE_ANON_KEY.isNotBlank()

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
     * apikey/Authorization 为 Supabase anon key（可公开）；
     * x-device-id 供服务端 RLS 策略校验：匿名 role 只能插入/读取 device_id 等于该头的行，
     * 拿到 anon key 但没有具体设备 UUID 的人无法遍历他人反馈。
     */
    private fun authHeaders(deviceId: String) = mapOf(
        "apikey"      to SUPABASE_ANON_KEY,
        "Authorization" to "Bearer $SUPABASE_ANON_KEY",
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
        val (code, _) = HttpUtil.post(
            "$SUPABASE_URL/rest/v1/feedback", payload,
            timeoutMs = 15_000, label = "feedback-submit",
            headers = authHeaders(deviceId) + ("Prefer" to "return=minimal")
        )
        return code in 200..299
    }

    /** 查询本机的反馈历史（含开发者回复），按时间倒序；失败返回 null */
    fun fetchMine(): List<FeedbackItem>? {
        val deviceId = StockliteState.getInstance().feedbackDeviceId
        if (deviceId.isBlank()) return emptyList()   // 还没提交过，无需请求
        val url = "$SUPABASE_URL/rest/v1/feedback" +
            "?device_id=eq.$deviceId" +
            "&select=id,content,email,reply,replied_at,created_at" +
            "&order=created_at.desc&limit=50"
        val (code, body) = HttpUtil.getWithStatus(url, label = "feedback-list", headers = authHeaders(deviceId))
        if (code != 200 || body.isNullOrBlank()) return null
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

    /** 删除自己的一条反馈（id 必须属于本机 device_id，由服务端 RLS 保证）；成功返回 true */
    fun delete(id: String): Boolean {
        val deviceId = StockliteState.getInstance().feedbackDeviceId
        if (deviceId.isBlank() || id.isBlank()) return false
        // return=representation：RLS 无删除权限时 DELETE 会静默删 0 行返回空数组，据此判定
        val (code, body) = HttpUtil.delete(
            "$SUPABASE_URL/rest/v1/feedback?id=eq.$id",
            label = "feedback-delete",
            headers = authHeaders(deviceId) + ("Prefer" to "return=representation")
        )
        return code in 200..299 && body?.contains("\"id\"") == true
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

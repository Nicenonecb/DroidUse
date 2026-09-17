package dev.droiduse.agent

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** No automatic retries or redirects: a request may already have consumed tokens. */
class ModelClient(private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var connection: HttpURLConnection? = null
    fun cancel() { cancelled.set(true); connection?.disconnect() }
    fun probe(profile: ModelProfile): String = request(profile, "Reply with OK only.", 16)
    fun plan(profile: ModelProfile, task: String): String {
        require(task.isNotBlank() && task.length <= 4000) { "任务长度须为1～4000字" }
        return request(profile, "请用简短中文列出完成下述手机任务的步骤。这只是计划，不要声称已操作手机：\n$task", 512)
    }
    fun evaluate(profile: ModelProfile, prompt: String, pngBase64: String): String {
        require(pngBase64.length in 1..4_194_304)
        return request(profile,prompt,1024,pngBase64)
    }
    fun payload(profile: ModelProfile, prompt: String, tokens: Int, image: String? = null): JSONObject = JSONObject()
        .apply { if (java.net.URI(profile.baseUrl).host == "dashscope.aliyuncs.com" && profile.model.startsWith("qwen3.8")) { put("enable_thinking",false); if (image != null) put("response_format",JSONObject().put("type","json_object")) } }
        .put("model", profile.model).put("max_tokens", tokens).put("stream", false)
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", if(image == null) prompt else {
            val parts=JSONArray()
            when(profile.protocol) {
                Protocol.CHAT_COMPLETIONS -> parts.put(JSONObject().put("type","image_url").put("image_url",JSONObject().put("url","data:image/png;base64,$image")))
                Protocol.ANTHROPIC -> parts.put(JSONObject().put("type","image").put("source",JSONObject().put("type","base64").put("media_type","image/png").put("data",image)))
            }
            parts.put(JSONObject().put("type","text").put("text",prompt))
        })))
    fun decode(profile: ModelProfile, body: String): String {
        val json = JSONObject(body)
        val text = when (profile.protocol) {
            Protocol.CHAT_COMPLETIONS -> json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            Protocol.ANTHROPIC -> json.getJSONArray("content").let { blocks ->
                (0 until blocks.length()).map { blocks.getJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString("\n") { it.getString("text") }
            }
        }
        require(text.isNotBlank()) { "服务返回了空内容" }
        return text.take(16000)
    }
    private fun request(profile: ModelProfile, prompt: String, tokens: Int, image: String? = null): String {
        check(!cancelled.get()) { "已取消" }
        val http = openConnection(URL(profile.endpoint()))
        connection = http
        try {
            check(!cancelled.get()) { "已取消" }
            http.requestMethod = "POST"; http.instanceFollowRedirects = false
            http.connectTimeout = 15000; http.readTimeout = 30000; http.doOutput = true
            http.setRequestProperty("Content-Type", "application/json")
            when (profile.protocol) {
                Protocol.CHAT_COMPLETIONS -> http.setRequestProperty("Authorization", "Bearer ${profile.apiKey}")
                Protocol.ANTHROPIC -> { http.setRequestProperty("x-api-key", profile.apiKey); http.setRequestProperty("anthropic-version", "2023-06-01") }
            }
            http.outputStream.use { it.write(payload(profile, prompt, tokens, image).toString().toByteArray(Charsets.UTF_8)) }
            val status = http.responseCode
            check(status in 200..299) { "HTTP $status：请检查地址、协议、模型和额度" }
            val bytes = http.inputStream.use { it.readNBytes(1_048_577) }
            check(bytes.size <= 1_048_576) { "响应超过大小限制" }
            check(!cancelled.get()) { "已取消" }
            return decode(profile, String(bytes, Charsets.UTF_8))
        } finally { http.disconnect(); connection = null }
    }
}

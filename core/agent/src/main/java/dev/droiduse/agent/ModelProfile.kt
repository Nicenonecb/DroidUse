package dev.droiduse.agent

import java.net.URI
import java.util.UUID

enum class Protocol(val label: String, val suffix: String) {
    CHAT_COMPLETIONS("Chat Completions 兼容", "/chat/completions"),
    ANTHROPIC("Anthropic Messages 兼容", "/messages")
}
data class ModelProfile(val id: String = UUID.randomUUID().toString(), val name: String,
                        val baseUrl: String, val model: String, val apiKey: String,
                        val protocol: Protocol = Protocol.CHAT_COMPLETIONS) {
    override fun toString() = "ModelProfile(id=$id, name=$name, protocol=$protocol, apiKey=[REDACTED])"
    fun validate() {
        require(name.isNotBlank() && name.length <= 80) { "请输入配置名称（最多80字）" }
        require(model.isNotBlank() && model.length <= 200) { "请输入模型名称" }
        require(apiKey.isNotBlank() && apiKey.length <= 8192 && !apiKey.contains('\n') && !apiKey.contains('\r')) { "请输入有效 API Key" }
        val uri = try { URI(baseUrl) } catch (_: Exception) { throw IllegalArgumentException("服务地址格式不正确") }
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "服务地址须为 HTTPS，不能包含账号、查询参数或片段"
        }
        require(uri.port == -1 || uri.port in 1..65535) { "端口无效" }
    }
    fun endpoint(): String {
        validate()
        val url = baseUrl.trimEnd('/')
        return if (url.endsWith(protocol.suffix)) url else url + protocol.suffix
    }
}

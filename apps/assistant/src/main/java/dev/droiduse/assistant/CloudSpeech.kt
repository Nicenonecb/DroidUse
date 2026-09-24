package dev.droiduse.assistant

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import dev.droiduse.agent.ModelProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

/** Short, user-started recordings for the configured Alibaba Cloud ASR endpoint. */
internal object CloudSpeech {
    private const val sampleRate = 16000
    private const val maxPcmBytes = sampleRate * 2 * 15

    fun supports(profile: ModelProfile?): Boolean {
        if (profile == null || profile.apiKey.isBlank()) return false
        val uri = runCatching { URI(profile.baseUrl) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme == "https" && uri.rawUserInfo == null && uri.rawQuery == null &&
            uri.rawFragment == null && (host == "dashscope.aliyuncs.com" ||
            host == "dashscope-intl.aliyuncs.com" || host.endsWith(".maas.aliyuncs.com")) &&
            uri.path.trimEnd('/') == "/compatible-mode/v1"
    }

    fun record(stopRequested: AtomicBoolean): ByteArray {
        val minimumBufferSize = AudioRecord.getMinBufferSize(sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minimumBufferSize > 0) { "麦克风参数不可用" }
        val bufferSize = maxOf(4096, minimumBufferSize)
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        require(recorder.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
        val pcm = ByteArrayOutputStream()
        val buffer = ByteArray(bufferSize)
        try {
            recorder.startRecording()
            while (!stopRequested.get() && pcm.size() < maxPcmBytes) {
                val count = recorder.read(buffer, 0, minOf(buffer.size, maxPcmBytes - pcm.size()),
                    AudioRecord.READ_BLOCKING)
                require(count >= 0) { "麦克风读取失败" }
                if (count > 0) pcm.write(buffer, 0, count)
            }
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            recorder.release()
        }
        require(pcm.size() >= sampleRate / 2) { "录音太短，请至少说半秒。" }
        val samples = pcm.toByteArray()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + samples.size)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1.toShort()); putShort(1.toShort()); putInt(sampleRate); putInt(sampleRate * 2)
            putShort(2.toShort()); putShort(16.toShort()); put("data".toByteArray(Charsets.US_ASCII)); putInt(samples.size)
        }.array()
        return header + samples
    }

    fun transcribe(profile: ModelProfile, wav: ByteArray): String {
        require(supports(profile)) { "当前模型配置不支持云端中文识别" }
        require(wav.size <= 1_000_000) { "录音超出 15 秒限制" }
        val audio = "data:audio/wav;base64," + Base64.encodeToString(wav, Base64.NO_WRAP)
        val payload = JSONObject().put("model", "qwen3-asr-flash")
            .put("messages", JSONArray().put(JSONObject().put("role", "user")
                .put("content", JSONArray().put(JSONObject().put("type", "input_audio")
                    .put("input_audio", JSONObject().put("data", audio))))))
            .put("stream", false)
            .put("asr_options", JSONObject().put("language", "zh").put("enable_itn", false))
            .toString().toByteArray(Charsets.UTF_8)
        val endpoint = profile.baseUrl.trimEnd('/') + "/chat/completions"
        val connection = (URL(endpoint).openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 40000
            instanceFollowRedirects = false
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${profile.apiKey}")
            setRequestProperty("Content-Type", "application/json")
            setFixedLengthStreamingMode(payload.size)
        }
        try {
            connection.outputStream.use { it.write(payload) }
            require(connection.responseCode == 200) { "云端识别失败（HTTP ${connection.responseCode}）" }
            val response = connection.inputStream.use { input ->
                val bytes = input.readNBytes(1_000_001)
                require(bytes.size <= 1_000_000) { "语音识别响应过大" }
                String(bytes, Charsets.UTF_8)
            }
            return JSONObject(response).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
                .also { require(it.isNotBlank()) { "未识别到文字" } }
        } finally { connection.disconnect() }
    }
}

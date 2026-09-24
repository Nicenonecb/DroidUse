package dev.droiduse.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.droiduse.agent.ModelProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground, push-to-talk speech. The user confirms each transcription before any task is sent. */
class VoiceController(private val context: Context, private val onTranscript: (String) -> Unit) {
    var voiceMode by mutableStateOf(false)
    var listening by mutableStateOf(false)
        private set
    var partialText by mutableStateOf("")
        private set
    var notice by mutableStateOf("")
        private set
    var speechReady by mutableStateOf(false)
        private set
    var cloudRecording by mutableStateOf(false)
        private set
    var cloudBusy by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cloudStop = AtomicBoolean(false)
    private var cloudJob: Job? = null
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val availability = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                speechReady = availability != TextToSpeech.LANG_MISSING_DATA &&
                    availability != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
    }

    fun startListening() {
        if (listening) return
        voiceMode = true
        partialText = ""
        notice = ""
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            notice = "此设备没有可用的语音识别服务。"
            return
        }
        val service = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            recognizer = it
            it.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { notice = "正在听，请说话…" }
                override fun onBeginningOfSpeech() { notice = "正在记录语音…" }
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { notice = "正在识别…" }
                override fun onError(error: Int) {
                    listening = false
                    notice = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听清，请再试一次。"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别网络不可用。"
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "当前语音服务不支持系统语言，请安装中文语音识别服务。"
                        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "中文语音包尚未下载，请连接网络并安装语音包。"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "请允许麦克风权限。"
                        else -> "语音识别暂时不可用（$error）。"
                    }
                }
                override fun onResults(results: Bundle?) {
                    listening = false
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    if (text.isBlank()) notice = "没有听清，请再试一次。"
                    else {
                        partialText = text
                        notice = "已转成文字，请确认后发送。"
                        onTranscript(text)
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    partialText = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
        listening = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try { service.startListening(intent) }
        catch (_: Exception) { listening = false; notice = "无法启动语音识别。" }
    }

    fun stopListening() {
        if (listening) recognizer?.stopListening()
    }

    fun permissionDenied() {
        voiceMode = true
        notice = "需要允许麦克风权限才能使用语音模式。"
    }

    fun cloudUnavailable() {
        voiceMode = true
        notice = "请先在设置中选择阿里云 DashScope 模型配置。"
    }

    fun startCloud(profile: ModelProfile) {
        if (cloudRecording || cloudBusy) return
        if (!CloudSpeech.supports(profile)) {
            notice = "请先在设置中选择阿里云 DashScope 模型配置。"
            return
        }
        recognizer?.cancel()
        listening = false
        tts?.stop()
        voiceMode = true
        partialText = ""
        cloudStop.set(false)
        cloudRecording = true
        notice = "正在录音，点击停止后发送到阿里云识别…"
        cloudJob = scope.launch {
            try {
                val wav = withContext(Dispatchers.IO) { CloudSpeech.record(cloudStop) }
                cloudRecording = false
                cloudBusy = true
                notice = "正在识别中文语音…"
                val transcript = withContext(Dispatchers.IO) { CloudSpeech.transcribe(profile, wav) }
                partialText = transcript
                notice = "已转成文字，请确认后发送。"
                onTranscript(transcript)
            } catch (_: CancellationException) {
                // Closing the sheet or leaving the app discards the in-memory recording.
            } catch (error: Exception) {
                notice = error.message?.take(100) ?: "云端识别失败。"
            } finally {
                cloudRecording = false
                cloudBusy = false
                cloudJob = null
            }
        }
    }

    fun stopCloud() {
        if (cloudRecording) cloudStop.set(true)
    }

    fun stopAudio() {
        recognizer?.cancel()
        listening = false
        cloudStop.set(true)
        cloudJob?.cancel()
        tts?.stop()
    }

    fun speak(text: String) {
        if (!voiceMode || text.isBlank()) return
        if (!speechReady) {
            notice = "此设备的中文语音播报不可用，可查看屏幕文字。"
            return
        }
        tts?.speak(text.take(1200), TextToSpeech.QUEUE_FLUSH, null, "xiaoxiong-response")
    }

    fun close() {
        stopAudio()
        voiceMode = false
        notice = ""
    }

    fun destroy() {
        stopAudio()
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
        scope.cancel()
    }
}

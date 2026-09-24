package dev.droiduse.assistant

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import dev.droiduse.agent.ModelProfile

internal val Ink = Color(0xFF202329)
internal val Muted = Color(0xFF858C98)
internal val Green = Color(0xFF198F87)
internal val Canvas = Color(0xFFF7F9FD)
internal val Line = Color(0xFFE8ECF1)
internal val SoftGreen = Color(0xFFE8F5F2)
internal val Amber = Color(0xFFAF6C2A)
internal val SoftAmber = Color(0xFFFFF3E6)
internal val Corners = RoundedCornerShape(22.dp)

@Composable
fun AssistantApp(state: AssistantState, page: Int, onPageChange: (Int) -> Unit) {
    val context = LocalContext.current
    val voice = remember { VoiceController(context) { state.task = it.take(4000) } }
    var pendingCloudProfile by remember { mutableStateOf<ModelProfile?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val profile = pendingCloudProfile
        pendingCloudProfile = null
        if (granted) {
            if (profile != null) voice.startCloud(profile) else voice.startListening()
        } else voice.permissionDenied()
    }
    fun startVoice() {
        voice.voiceMode = true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            voice.startListening()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun startCloudVoice() {
        val profile = state.profiles.profiles.firstOrNull { it.id == state.profiles.activeId }
        if (profile == null || !CloudSpeech.supports(profile)) {
            voice.cloudUnavailable()
            return
        }
        voice.voiceMode = true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            voice.startCloud(profile)
        else {
            pendingCloudProfile = profile
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    DisposableEffect(voice) {
        val owner = context as LifecycleOwner
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) voice.stopAudio() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); voice.destroy() }
    }
    var lastResult by remember { mutableStateOf(state.result) }
    LaunchedEffect(state.result) {
        if (state.result.isNotBlank() && state.result != lastResult) voice.speak(state.result)
        lastResult = state.result
    }
    MaterialTheme(colorScheme = lightColorScheme(
        primary = Ink, onPrimary = Color.White, background = Canvas, onBackground = Ink,
        surface = Color.White, onSurface = Ink, surfaceVariant = SoftGreen,
        secondaryContainer = SoftGreen, onSecondaryContainer = Green, outline = Line
    )) {
        if (state.manual) HandoffDialog(state)
        var submitted by remember { mutableStateOf("") }
        fun send() {
            if (state.task.isBlank() || state.busy || state.taskRunning) return
            submitted = state.task
            state.model(state.profiles.profiles.firstOrNull { it.id == state.profiles.activeId }, false)
        }
        if (page == 0) ChatHomeScreen(state, voice, submitted, onSettings = { onPageChange(1) },
            onSend = ::send, onVoice = ::startVoice)
        else SettingsScreen(state, onBack = { onPageChange(0) })
        if (voice.voiceMode) VoiceBottomSheet(voice, state, onSend = ::send,
            onDismiss = voice::close, onStart = ::startVoice, onCloud = ::startCloudVoice)
    }
}

@Composable
internal fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = Corners, color = Color.White, border = BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
internal fun SectionHeading(number: String, title: String, aside: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(number, color = Green, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Spacer(Modifier.width(10.dp))
        Text(title, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        if (aside != null) { Spacer(Modifier.weight(1f)); Text(aside, color = Muted, fontSize = 11.sp) }
    }
}

@Composable
internal fun FieldLabel(text: String) { Text(text, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium) }

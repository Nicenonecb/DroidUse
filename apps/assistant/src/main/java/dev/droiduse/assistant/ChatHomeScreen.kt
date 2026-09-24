package dev.droiduse.assistant

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ChatHomeScreen(state: AssistantState, voice: VoiceController, submitted: String,
    onSettings: () -> Unit, onSend: () -> Unit, onVoice: () -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val backgroundTouches = remember { MutableInteractionSource() }
    Scaffold(containerColor = Canvas, bottomBar = { ChatComposer(state, onSend, onVoice) }) { padding ->
        val scroll = rememberScrollState()
        LaunchedEffect(submitted, state.result) { scroll.animateScrollTo(scroll.maxValue) }
        Column(Modifier.fillMaxSize().padding(padding)
            .clickable(indication = null, interactionSource = backgroundTouches) {
                focusManager.clearFocus()
                keyboard?.hide()
            }
            .verticalScroll(scroll).padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("小熊助手", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(40.dp).clickable(onClick = onSettings), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.ic_settings), contentDescription = "设置", Modifier.size(23.dp))
                }
            }
            if (submitted.isEmpty()) {
                Spacer(Modifier.height(27.dp))
                Image(painterResource(R.drawable.bear_assistant_mascot), contentDescription = "小熊助手吉祥物",
                    modifier = Modifier.size(118.dp).offset(x = (-12).dp), contentScale = ContentScale.Fit)
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("你好，我是小熊助手", color = Ink, fontWeight = FontWeight.Bold, fontSize = 25.sp)
                    Text("告诉我想做什么，我会先整理计划。", color = Muted, fontSize = 13.sp)
                }
                Spacer(Modifier.height(4.dp))
                SuggestionPrompt("在番茄小说找本爽文，读前三章并总结", "番茄小说") {
                    state.task = "在番茄小说找都市脑洞爽文，比较可见数据，选择综合数据较高的一本，阅读前三章并分别总结。"
                }
                SuggestionPrompt("在美团按预算、配送时间和评分筛选外卖", "美团") {
                    state.task = "在美团外卖按预算、配送时间和评分筛选合适的餐食，列出可见价格、配送费和预计送达时间。"
                }
                SuggestionPrompt("通话时协助转写、整理要点并提醒待办", "通话") {
                    Toast.makeText(context, "通话功能暂未开放", Toast.LENGTH_SHORT).show()
                }
            } else {
                Spacer(Modifier.height(28.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Surface(shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp), color = SoftGreen,
                        modifier = Modifier.widthIn(max = 310.dp)) {
                        Text(submitted, Modifier.padding(16.dp), color = Ink, fontSize = 14.sp, lineHeight = 21.sp)
                    }
                }
                Surface(shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp), color = Color.White,
                    border = BorderStroke(1.dp, Line), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(painterResource(R.drawable.ic_bear_chat), contentDescription = null, Modifier.size(22.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("小熊助手", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(if (state.taskRunning) state.runtimeMessage.ifBlank { state.message }
                            else state.result.ifBlank { state.message },
                            color = Ink, fontSize = 14.sp, lineHeight = 22.sp)
                        if (state.result.isNotBlank() && !state.taskRunning) {
                            HorizontalDivider(color = Line)
                            Button(onClick = { state.runTask() }, enabled = !state.busy && state.connected && state.inferredTargetPackage() != null,
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) { Text("确认并运行任务") }
                            if (state.inferredTargetPackage() == null)
                                Text("请在任务中写明应用名称后再运行。", color = Muted, fontSize = 11.sp)
                            Text("运行会向所选模型发送后台截图；系统未就绪时不会执行。", color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
            if (state.busy || state.taskRunning || state.session.isNotEmpty()) {
                SectionCard {
                    SectionHeading("进行中", "任务控制")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.taskRunning || state.session.isNotEmpty()) {
                            OutlinedButton(onClick = { if (state.taskRunning) state.pauseTask() else state.togglePause() }) {
                                Text(if (state.paused || state.taskPaused) "恢复" else "暂停")
                            }
                        }
                        OutlinedButton(onClick = state::stop) { Text("停止") }
                    }
                    if (state.taskRunning && !state.manual) TextButton(onClick = state::enterHandoff) { Text("查看后台并接管") }
                }
            }
            if (state.recoverable && !state.taskRunning) {
                SectionCard {
                    SectionHeading("恢复", "发现中断任务")
                    Text("恢复前会重新观察现场；上下文仅加密保存在本机。", color = Muted, fontSize = 12.sp)
                    Row {
                        Button(onClick = state::recoverTask) { Text("恢复任务") }
                        TextButton(onClick = state::discardRecovery) { Text("丢弃记录") }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SuggestionPrompt(text: String, accent: String, onClick: () -> Unit) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val start = text.indexOf(accent)
    val styled = buildAnnotatedString {
        if (start < 0) append(text)
        else {
            append(text.take(start))
            withStyle(SpanStyle(color = Ink, fontWeight = FontWeight.Bold)) { append(accent) }
            append(text.drop(start + accent.length))
        }
    }
    Row(Modifier.fillMaxWidth().clickable {
        focusManager.clearFocus()
        keyboard?.hide()
        onClick()
    }.padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(styled, Modifier.weight(1f), color = Ink, fontSize = 14.sp, lineHeight = 21.sp)
        Spacer(Modifier.width(10.dp))
        Text("↗", color = Muted, fontSize = 14.sp)
    }
}

@Composable
private fun ChatComposer(state: AssistantState, onSend: () -> Unit, onVoice: () -> Unit) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Surface(color = Canvas) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 48.dp)) {
            Surface(shape = RoundedCornerShape(23.dp), color = Color.White, border = BorderStroke(1.dp, Line), shadowElevation = 4.dp) {
                Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                        if (state.task.isBlank()) Text("发消息或点按说话…", color = Muted, fontSize = 13.sp)
                        BasicTextField(value = state.task, onValueChange = { state.task = it.take(4000) },
                            textStyle = TextStyle(color = Ink, fontSize = 13.sp, lineHeight = 20.sp),
                            minLines = 1, maxLines = 5,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                focusManager.clearFocus()
                                keyboard?.hide()
                            }),
                            modifier = Modifier.fillMaxWidth())
                    }
                    Box(Modifier.align(Alignment.Bottom).padding(bottom = 9.dp).size(40.dp).clickable {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        onVoice()
                    }, contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.ic_voice_mic), contentDescription = "语音模式", Modifier.size(22.dp))
                    }
                    Box(Modifier.align(Alignment.Bottom).padding(bottom = 9.dp).size(40.dp).clickable(enabled = state.task.isNotBlank()) {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        onSend()
                    },
                        contentAlignment = Alignment.Center) {
                        Surface(Modifier.size(31.dp), shape = CircleShape,
                            color = if (state.task.isNotBlank()) Ink else Line) {
                            Box(contentAlignment = Alignment.Center) {
                                Image(painterResource(R.drawable.ic_arrow_up), contentDescription = "发送消息",
                                    modifier = Modifier.size(19.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceBottomSheet(voice: VoiceController, state: AssistantState, onSend: () -> Unit,
    onDismiss: () -> Unit, onStart: () -> Unit, onCloud: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("语音模式", color = Ink, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text("点击麦克风说话，确认文字后发送。回复可语音播报。", color = Muted, fontSize = 12.sp)
            Text("系统识别由设备语音服务处理，是否联网取决于该服务。", color = Muted, fontSize = 11.sp)
            Surface(shape = CircleShape, color = if (voice.listening) SoftGreen else Canvas,
                modifier = Modifier.size(100.dp).clickable(enabled = !voice.cloudRecording && !voice.cloudBusy) {
                    if (voice.listening) voice.stopListening() else onStart()
                }) {
                Box(contentAlignment = Alignment.Center) {
                    if (voice.listening) Text("■", color = Green, fontSize = 32.sp)
                    else Image(painterResource(R.drawable.ic_voice_mic), contentDescription = "开始语音输入",
                        modifier = Modifier.size(44.dp))
                }
            }
            Text(voice.notice.ifBlank { if (voice.listening) "正在听…" else "点击开始说话" }, color = Green, fontSize = 13.sp)
            OutlinedButton(onClick = { if (voice.cloudRecording) voice.stopCloud() else onCloud() },
                enabled = !voice.listening && !voice.cloudBusy,
                modifier = Modifier.fillMaxWidth()) {
                Text(if (voice.cloudRecording) "停止录音并识别" else "使用阿里云中文识别")
            }
            Text("点击云端识别后，最多 15 秒录音会发送给所选的阿里云 DashScope 服务，可能消耗额度。",
                color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            if (voice.cloudBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (voice.partialText.isNotBlank()) Text(voice.partialText, color = Ink, fontSize = 14.sp)
            OutlinedTextField(state.task, { state.task = it.take(4000) }, Modifier.fillMaxWidth(),
                label = { Text("识别的文字") }, minLines = 2, maxLines = 4)
            Button(onClick = onSend, enabled = state.task.isNotBlank() && !state.busy && !state.taskRunning,
                modifier = Modifier.fillMaxWidth()) { Text("发送并等待回复") }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.busy || (state.result.isBlank() && state.message.isNotBlank()))
                Text(state.message, color = Muted, fontSize = 12.sp)
            if (state.result.isNotBlank()) Text(state.result.take(500), color = Ink, fontSize = 13.sp, lineHeight = 20.sp)
            TextButton(onClick = onDismiss) { Text("退出语音模式") }
        }
    }
}

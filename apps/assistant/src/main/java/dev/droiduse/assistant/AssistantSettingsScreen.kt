package dev.droiduse.assistant

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.droiduse.agent.ModelProfile
import dev.droiduse.agent.Protocol
import java.util.UUID

@Composable
fun SettingsScreen(state: AssistantState, onBack: () -> Unit) {
    var section by remember { mutableIntStateOf(0) }
    Scaffold(containerColor = Canvas) { padding ->
        key(section) {
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clickable { if (section == 0) onBack() else section = 0 },
                        contentAlignment = Alignment.CenterStart) {
                        Image(painterResource(R.drawable.ic_back), contentDescription = "返回", Modifier.size(22.dp))
                    }
                    Text(if (section == 0) "设置" else listOf("", "模型连接", "运行诊断", "执行与实验")[section],
                        color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }
                when (section) {
                    0 -> {
                        Text("连接、设备状态和实验选项", color = Muted, fontSize = 13.sp)
                        Column {
                            SettingsEntry("模型连接", state.profiles.profiles.firstOrNull { it.id == state.profiles.activeId }?.name ?: "尚未配置") { section = 1 }
                            SettingsEntry("运行诊断", if (state.connected && state.romReady) "系统已就绪" else "查看执行服务与 ROM 状态") { section = 2 }
                            SettingsEntry("执行与实验", "文字识别和调试入口") { section = 3 }
                        }
                    }
                    1 -> ModelPage(state)
                    2 -> DiagnosticsPage(state)
                    else -> AdvancedPage(state)
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun SettingsEntry(title: String, subtitle: String, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(subtitle, color = Muted, fontSize = 12.sp)
            }
            Image(painterResource(R.drawable.ic_chevron_right), contentDescription = null, Modifier.size(18.dp))
        }
        HorizontalDivider(color = Line)
    }
}

@Composable
private fun SettingsSection(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        HorizontalDivider(color = Line)
    }
}

@Composable
private fun AdvancedPage(state: AssistantState) {
    SettingsSection {
        SectionHeading("01", "执行范围")
        Text("运行任务时，会根据文字中的应用名称确定操作范围。", color = Muted, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("允许任务调整整机设置", modifier = Modifier.weight(1f), color = Ink)
            Switch(checked = state.allowGlobalSettings, onCheckedChange = state::setGlobalSettings,
                enabled = !state.taskRunning)
        }
        Text("开启后可调整整台手机的 Wi-Fi、媒体音量和亮度。通知和权限仍限当前任务应用。", color = Muted, fontSize = 12.sp)
        FieldLabel("本地文字识别")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OcrKind.entries.forEach { kind ->
                FilterChip(selected = state.ocrKind == kind, onClick = { state.selectOcr(kind) },
                    enabled = !state.taskRunning, label = { Text(kind.label, fontSize = 12.sp) })
            }
        }
    }
    SettingsSection {
        SectionHeading("02", "执行服务")
        Text("当前 ROM 任务限定在识别出的应用内。", color = Muted, fontSize = 12.sp)
        OutlinedButton(onClick = state::execute,
            enabled = state.connected && state.session.isEmpty() && !state.busy && !state.taskRunning) {
            Text("执行检查")
        }
    }
    if (BuildConfig.DEBUG) DebugExperiments(state)
}

@Composable
private fun DebugExperiments(state: AssistantState) {
    var expanded by remember { mutableStateOf(false) }
    SettingsSection {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("手机本地实验", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("开发调试入口 · 番茄小说", color = Muted, fontSize = 11.sp)
            }
            Text(if (expanded) "收起" else "展开", color = Green, fontSize = 12.sp)
        }
        if (expanded) {
            Text("需先通过 ADB 启动调试执行进程；运行时无需电脑。", color = Muted, fontSize = 12.sp)
            OutlinedButton(onClick = { state.runTask(true) },
                enabled = !state.busy && !state.taskRunning && state.session.isEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text("运行手机本地实验")
            }
            OutlinedButton(onClick = { state.runTask(true, true) },
                enabled = !state.busy && !state.taskRunning && state.session.isEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text("运行纯视觉实验")
            }
        }
    }
}

@Composable
internal fun ModelPage(state: AssistantState) {
    var editing by remember { mutableStateOf<ModelProfile?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var draftId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf(Protocol.CHAT_COMPLETIONS) }
    var show by remember { mutableStateOf(false) }
    var deletion by remember { mutableStateOf<ModelProfile?>(null) }
    fun draft() = ModelProfile(editing?.id ?: draftId, name, url, model, key, protocol)
    fun clearDraft() {
        editing = null; draftId = UUID.randomUUID().toString()
        name = ""; url = ""; model = ""; key = ""; show = false
        protocol = Protocol.CHAT_COMPLETIONS
    }

    SettingsSection {
        Text("安全存储", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text("连接配置由设备密钥加密，仅保存在本机。", color = Muted, fontSize = 11.sp)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("已保存的模型", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
            modifier = Modifier.weight(1f))
        TextButton(onClick = { clearDraft(); editorOpen = true }) { Text("＋ 添加模型") }
    }
    if (!editorOpen) {
        if (state.profiles.profiles.isEmpty()) {
            SettingsSection { Text("还没有模型连接。添加配置后即可生成计划和执行任务。", color = Muted, fontSize = 13.sp) }
        }
        state.profiles.profiles.forEach { profile ->
            SettingsSection {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(profile.name, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(profile.model, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (profile.id == state.profiles.activeId) {
                        Text("当前使用", color = Green, fontSize = 11.sp,
                            modifier = Modifier.background(SoftGreen, RoundedCornerShape(9.dp)).padding(horizontal = 9.dp, vertical = 5.dp))
                    }
                }
                HorizontalDivider(color = Line)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (profile.id != state.profiles.activeId) {
                        TextButton(onClick = { state.select(profile.id) }, enabled = !state.busy && !state.taskRunning) { Text("使用") }
                    }
                    TextButton(onClick = {
                        editing = profile; editorOpen = true; name = profile.name; url = profile.baseUrl
                        model = profile.model; key = profile.apiKey; protocol = profile.protocol; show = false
                    }) { Text("编辑") }
                    TextButton(onClick = { deletion = profile }, enabled = !state.busy && !state.taskRunning) {
                        Text("删除", color = Amber)
                    }
                }
            }
        }
    }
    if (editorOpen) {
        SettingsSection {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (editing == null) "添加模型" else "编辑模型", color = Ink, fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { editorOpen = false }) { Text("收起") }
            }
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("配置名称") }, singleLine = true)
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("HTTPS 服务地址") },
                placeholder = { Text("https://服务域名/v1") }, supportingText = { Text("包含服务要求的版本路径") }, singleLine = true)
            OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("模型名称") }, singleLine = true)
            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("API Key") }, singleLine = true,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "隐藏" else "显示") } })
            FieldLabel("兼容协议")
            Protocol.entries.forEach { choice ->
                Row(Modifier.fillMaxWidth().clickable { protocol = choice }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = protocol == choice, onClick = { protocol = choice })
                    Text(choice.label, color = Ink, fontSize = 13.sp)
                }
            }
            Text("测试连接会向该地址发送短请求，可能消耗额度。", color = Muted, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { state.save(draft()) }, enabled = state.storageReady && !state.busy && !state.taskRunning) { Text("保存配置") }
                OutlinedButton(onClick = { state.model(draft(), true) }, enabled = !state.busy && !state.taskRunning) { Text("测试连接") }
            }
            if (editing != null) TextButton(onClick = { clearDraft() }) { Text("新建另一套配置") }
        }
    }
    deletion?.let { profile ->
        AlertDialog(onDismissRequest = { deletion = null }, title = { Text("删除 ${profile.name}？") },
            text = { Text("将移除此设备保存的连接配置。") },
            confirmButton = { TextButton(onClick = {
                state.delete(profile.id)
                if (editing?.id == profile.id) { clearDraft(); editorOpen = false }
                deletion = null
            }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deletion = null }) { Text("取消") } })
    }
}

@Composable
internal fun DiagnosticsPage(state: AssistantState) {
    val rows = listOf(
        Triple("执行服务", if (state.connected) "已连接" else "未连接", state.connected),
        Triple("ROM 基础执行", if (state.romReady) "已就绪" else "未就绪", state.romReady),
        Triple("配置存储", if (state.storageReady) "设备密钥加密" else "不可用", state.storageReady),
        Triple("协议版本", "3", true)
    )
    SettingsSection {
        SectionHeading("01", "系统状态")
        rows.forEachIndexed { index, (label, value, good) ->
            if (index > 0) HorizontalDivider(color = Line)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(if (good) Green else Amber, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(label, modifier = Modifier.weight(1f), color = Ink, fontSize = 13.sp)
                Text(value, color = if (good) Green else Amber, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
    SettingsSection {
        Text("当前能力范围", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text("通知与权限限定在任务应用内。Wi-Fi、媒体音量和亮度需在高级设置中开启整机设置权限；可用操作由当前 ROM 返回。电话与通话音频尚未接入。",
            color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
    }
    if (!state.connected) {
        Button(onClick = state::reconnect, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) {
            Text("重新连接执行服务")
        }
    }
    SettingsSection {
        SectionHeading("02", "最近消息")
        Text(state.runtimeMessage.ifBlank { state.message }, color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
    }
}

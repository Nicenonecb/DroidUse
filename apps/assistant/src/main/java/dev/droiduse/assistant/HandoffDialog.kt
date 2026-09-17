package dev.droiduse.assistant

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import org.json.JSONObject
import kotlin.math.abs

@Composable fun HandoffDialog(state: AssistantState) {
    Dialog(onDismissRequest={},properties=DialogProperties(usePlatformDefaultWidth=false,
        dismissOnBackPress=false,dismissOnClickOutside=false,securePolicy=SecureFlagPolicy.SecureOn)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("人工接管",style=MaterialTheme.typography.titleLarge)
                Text(if(state.manualBusy && state.manualImage==null) "正在请求暂停，确认后显示画面…" else "AI 已暂停。可点击、滑动或返回；此处画面与操作不写入日志。")
                Text(state.runtimeMessage,style=MaterialTheme.typography.bodySmall)
                val bitmap=remember(state.manualImage) {
                    state.manualImage?.let { runCatching {
                        val bytes=Base64.decode(it,Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                    }.getOrNull() }
                }
                DisposableEffect(bitmap) { onDispose { bitmap?.recycle() } }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
                    if(bitmap!=null) {
                        val h=minOf(maxHeight,maxWidth*bitmap.height/bitmap.width)
                        val w=h*bitmap.width/bitmap.height
                        Image(bitmap.asImageBitmap(),contentDescription="后台页面",modifier=Modifier.width(w).height(h)
                            .pointerInput(state.manualImage,state.manualBusy) {
                                if(!state.manualBusy) awaitEachGesture {
                                    val down=awaitFirstDown();down.consume()
                                    var end=down.position
                                    var duration=0L
                                    do {
                                        val event=awaitPointerEvent()
                                        val change=event.changes.firstOrNull { it.id==down.id } ?: break
                                        end=change.position;duration=change.uptimeMillis-down.uptimeMillis;change.consume()
                                    } while(event.changes.any { it.pressed })
                                    fun x(value: Float)=(value/size.width*bitmap.width).toInt().coerceIn(0,bitmap.width-1)
                                    fun y(value: Float)=(value/size.height*bitmap.height).toInt().coerceIn(0,bitmap.height-1)
                                    val action=if(abs(end.x-down.position.x)+abs(end.y-down.position.y)<12)
                                        JSONObject().put("kind","tap").put("x",x(end.x)).put("y",y(end.y))
                                    else JSONObject().put("kind","swipe").put("x1",x(down.position.x)).put("y1",y(down.position.y))
                                        .put("x2",x(end.x)).put("y2",y(end.y)).put("durationMs",duration.toInt().coerceIn(100,2000))
                                    state.handoffAction(action)
                                }
                            })
                    } else Text(if(state.manualBusy) "正在等待 AI 停稳并获取页面…" else "暂无画面，请刷新")
                }
                if(state.manualBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={state.handoffAction(null)},enabled=!state.manualBusy) { Text("刷新") }
                    OutlinedButton(onClick={state.handoffAction(JSONObject().put("kind","back"))},enabled=!state.manualBusy && bitmap!=null) { Text("返回") }
                    Button(onClick=state::returnToAi,enabled=!state.manualBusy) { Text("交回 AI") }
                }
                OutlinedButton(onClick=state::stop) { Text("终止任务") }
            }
        }
    }
}

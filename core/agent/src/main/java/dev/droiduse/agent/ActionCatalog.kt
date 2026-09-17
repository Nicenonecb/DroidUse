package dev.droiduse.agent

/** Same names drive model schemas and execution admission. Unknown capabilities are never advertised. */
object ActionCatalog {
    fun kind(action: TaskLoop.Action): String = when(action) {
        is TaskLoop.Action.Target -> action.operation.actionName
        is TaskLoop.Action.MultiTouch -> "multi_touch"
        is TaskLoop.Action.Tap -> "tap"
        is TaskLoop.Action.DoubleTap -> "double_tap"
        is TaskLoop.Action.LongPress -> "long_press"
        is TaskLoop.Action.Drag -> "drag"
        is TaskLoop.Action.Swipe -> "swipe"
        is TaskLoop.Action.Edit -> action.operation.actionName
        is TaskLoop.Action.Text -> "text"
        TaskLoop.Action.Back -> "back"
        is TaskLoop.Action.Wait -> "wait"
        TaskLoop.Action.ReadChapters -> "read_chapters"
    }
    private val definitions = linkedMapOf(
        "multi_touch" to """{"kind":"multi_touch","fingers":[[{"x":整数,"y":整数},{"x":整数,"y":整数}],[{"x":整数,"y":整数},{"x":整数,"y":整数}]],"durationMs":100到3000}：两指同步轨迹，每指2到32点且点数相同；可表达缩放、旋转、平移""",
        "tap" to "{\"kind\":\"tap\",\"x\":整数,\"y\":整数}",
        "double_tap" to "{\"kind\":\"double_tap\",\"x\":整数,\"y\":整数}",
        "long_press" to "{\"kind\":\"long_press\",\"x\":整数,\"y\":整数,\"durationMs\":500到3000}",
        "drag" to "{\"kind\":\"drag\",\"x1\":整数,\"y1\":整数,\"x2\":整数,\"y2\":整数,\"holdMs\":0到1500,\"durationMs\":100到3000}",
        "swipe" to "{\"kind\":\"swipe\",\"x1\":整数,\"y1\":整数,\"x2\":整数,\"y2\":整数,\"durationMs\":100到2000}",
        "text" to "{\"kind\":\"text\",\"value\":\"文本\",\"editorGeneration\":当前编辑代次}",
        "back" to "{\"kind\":\"back\"}",
        "wait" to "{\"kind\":\"wait\",\"durationMs\":200到2000}，加载中可等待",
        "read_chapters" to "{\"kind\":\"read_chapters\"}，仅第一章第一页可启动完整前三章采集"
    )
    fun forEditorFrame(declared: Set<String>, current: Set<String>, generation: Long?): Set<String> = declared.filter { kind ->
        if(kind=="text" || kind in EditorOperation.actionNames) generation!=null && generation>0 && kind in current
        else true
    }.toSet()
    val editorSchemas = EditorOperation.entries.associate { op ->
        val extra=when(op) {
            EditorOperation.SELECT -> ",\"start\":UTF16起点整数,\"end\":UTF16终点整数（相同则移动光标）"
            EditorOperation.DELETE -> ",\"before\":向前删除数量,\"after\":向后删除数量"
            else -> ""
        }
        op.actionName to "{\"kind\":\"${op.actionName}\",\"editorGeneration\":当前编辑代次$extra}"
    }
    private val targetSchemas=TargetOperation.entries.associate {
        it.actionName to "{\"kind\":\"${it.actionName}\",\"targetId\":\"当前画面候选目标的targetId\"}"
    }
    fun schemas(supported: Set<String>): String = (definitions+editorSchemas+targetSchemas).filterKeys { it in supported }.values.joinToString("\n")
}

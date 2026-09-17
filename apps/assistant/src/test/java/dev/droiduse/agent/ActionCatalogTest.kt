package dev.droiduse.agent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ActionCatalogTest {
    @Test fun multiTouchRejectsMismatchedPathsAndNonIntegerCoordinates() {
        val mismatch=JSONObject("""{"kind":"multi_touch","durationMs":500,"fingers":[[{"x":1,"y":2},{"x":2,"y":3}],[{"x":3,"y":4}]]}""")
        assertThrows(IllegalArgumentException::class.java) { VisionTaskModel.parseDecision(mismatch) }
        val valid=JSONObject("""{"kind":"multi_touch","durationMs":500,"fingers":[[{"x":1,"y":2},{"x":2,"y":3}],[{"x":3,"y":4},{"x":4,"y":5}]]}""")
        val decision=VisionTaskModel.parseDecision(valid) as TaskLoop.Decision.Act
        assertEquals("multi_touch",ActionCatalog.kind(decision.action))
        valid.getJSONArray("fingers").getJSONArray(0).getJSONObject(0).put("x",1.5)
        assertThrows(IllegalArgumentException::class.java) { VisionTaskModel.parseDecision(valid) }
    }

    @Test fun onlyBackendDeclaredActionsAreAdvertised() {
        val prompt=ActionCatalog.schemas(setOf("tap","long_press","unknown_future_action"))
        assertTrue(prompt.contains("long_press"));assertFalse(prompt.contains("double_tap"))
        assertFalse(prompt.contains("editorGeneration"));assertFalse(prompt.contains("unknown_future_action"))
    }
    @Test fun gesturesDecodeWithoutConflatingDragAndSwipe() {
        assertEquals(TaskLoop.Decision.Act(TaskLoop.Action.DoubleTap(10,20)),
            VisionTaskModel.parseDecision(JSONObject("""{"kind":"double_tap","x":10,"y":20}""")))
        assertEquals(TaskLoop.Decision.Act(TaskLoop.Action.Drag(1,2,3,4,600,900)),
            VisionTaskModel.parseDecision(JSONObject("""{"kind":"drag","x1":1,"y1":2,"x2":3,"y2":4,"holdMs":600,"durationMs":900}""")))
    }
}

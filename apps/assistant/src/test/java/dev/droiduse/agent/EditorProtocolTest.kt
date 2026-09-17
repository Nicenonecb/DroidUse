package dev.droiduse.agent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EditorProtocolTest {
    @Test fun editorActionsNeedBothDeclarationsAndLiveGeneration() {
        val declared=setOf("tap","text","edit_copy","ime_search")
        assertEquals(setOf("tap"),ActionCatalog.forEditorFrame(declared,declared,null))
        assertEquals(setOf("tap"),ActionCatalog.forEditorFrame(declared,declared,0))
        assertEquals(setOf("tap","ime_search"),ActionCatalog.forEditorFrame(declared,setOf("ime_search","edit_paste"),7))
        assertEquals(setOf("tap"),ActionCatalog.forEditorFrame(declared,emptySet(),7))
    }

    private fun decision(kind: String, extra: String="") = VisionTaskModel.parseDecision(
        JSONObject("""{"kind":"$kind","editorGeneration":7$extra}""")) as TaskLoop.Decision.Act
    @Test fun allEditorOperationsHaveDistinctCapabilityNamesAndSchemas() {
        for(operation in EditorOperation.entries) {
            val extra=when(operation) {
                EditorOperation.SELECT -> ",\"start\":2,\"end\":2"
                EditorOperation.DELETE -> ",\"before\":1,\"after\":0"
                else -> ""
            }
            assertEquals(operation.actionName,ActionCatalog.kind(decision(operation.actionName,extra).action))
            assertTrue(ActionCatalog.schemas(setOf(operation.actionName)).contains(operation.actionName))
        }
        assertFalse(ActionCatalog.schemas(setOf("tap","swipe")).contains("editorGeneration"))
    }
    @Test fun editRejectsUnexpectedPlaintextAndMissingSelection() {
        assertThrows(IllegalArgumentException::class.java) { decision("edit_copy",",\"value\":\"password\"") }
        assertThrows(org.json.JSONException::class.java) { decision("edit_select") }
    }
    @Test fun staleGenerationAndExcessDeletionNeverReachExecutor() {
        for(action in listOf(TaskLoop.Action.Edit(EditorOperation.SELECT,6,1,2),
            TaskLoop.Action.Edit(EditorOperation.DELETE,7,before=5000),TaskLoop.Action.Edit(EditorOperation.DELETE,7))) {
            var submitted=false
            val executor=object : TaskLoop.Executor {
                override fun begin()=true
                override fun observe()=TaskLoop.Frame("f",2,100,200,0,100,"test","png",7,
                    supportedActions=EditorOperation.actionNames)
                override fun submit(requestId: String,frame: TaskLoop.Frame,action: TaskLoop.Action): TaskLoop.Outcome { submitted=true;return TaskLoop.Outcome.EXECUTED }
                override fun cancel()=Unit
            }
            val model=object : TaskLoop.Model {
                override fun decide(task: String,frame: TaskLoop.Frame)=TaskLoop.Decision.Act(action)
                override fun verify(task: String,claim: String,frame: TaskLoop.Frame)=error("not expected")
                override fun cancel()=Unit
            }
            assertEquals(TaskLoop.State.FAILED,TaskLoop(executor,model,{100},"编辑").step())
            assertFalse(submitted)
        }
    }
}

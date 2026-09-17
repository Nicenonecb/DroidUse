package dev.droiduse.agent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VisionProtocolTest {
    @Test fun qwenFastJsonModeDoesNotLeakToOtherProviders() {
        val p=ModelProfile(name="test",baseUrl="https://dashscope.aliyuncs.com/compatible-mode/v1",model="qwen3.8-max-0902",apiKey="synthetic-secret")
        val body=ModelClient().payload(p,"task",100,"cG5n")
        assertFalse(body.getBoolean("enable_thinking"));assertEquals("json_object",body.getJSONObject("response_format").getString("type"))
        assertFalse(ModelClient().payload(p.copy(baseUrl="https://example.com/v1"),"task",100,"cG5n").has("enable_thinking"))
        assertFalse(ModelClient().payload(p.copy(model="other-model"),"task",100,"cG5n").has("response_format"))
    }
    @Test fun parsesReadingWaitAndSemanticTarget() {
        assertEquals(TaskLoop.Decision.Act(TaskLoop.Action.ReadChapters),VisionTaskModel.parseDecision(JSONObject("""{"kind":"read_chapters"}""")))
        assertEquals(TaskLoop.Decision.Act(TaskLoop.Action.Wait(500)),VisionTaskModel.parseDecision(JSONObject("""{"kind":"wait","durationMs":500}""")))
        assertEquals(TaskLoop.Decision.Act(TaskLoop.Action.Tap(2,3,"分类")),VisionTaskModel.parseDecision(JSONObject("""{"kind":"tap","x":2,"y":3,"target":"分类"}""")))
    }
    @Test fun parsesAllowlistedActionsOnlyAndRefusesCoercedCoordinates() {
        assertEquals(TaskLoop.Decision.Act(TaskLoop.Action.Tap(2,3)),VisionTaskModel.parseDecision(JSONObject("""{"kind":"tap","x":2,"y":3}""")))
        for(raw in listOf("""{"kind":"shell","command":"anything"}""", """{"kind":"tap","x":2.3,"y":3}""", """{"kind":"tap","x":"2","y":3}"""))
            assertTrue(runCatching { VisionTaskModel.parseDecision(JSONObject(raw)) }.isFailure)
    }
    @Test fun screenshotsUseProviderSpecificImageParts() {
        val p=ModelProfile(name="test",baseUrl="https://example.com/v1",model="fake",apiKey="synthetic-secret")
        for(protocol in Protocol.entries) {
            val data=ModelClient().payload(p.copy(protocol=protocol),"task",100,"cG5n")
            val content=data.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
            assertEquals(2,content.length());assertEquals("text",content.getJSONObject(1).getString("type"))
            assertEquals(if(protocol==Protocol.ANTHROPIC) "image" else "image_url",content.getJSONObject(0).getString("type"))
            assertFalse(data.toString().contains(p.apiKey))
        }
    }
}

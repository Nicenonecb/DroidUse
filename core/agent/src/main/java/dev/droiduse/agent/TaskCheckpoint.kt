package dev.droiduse.agent

import org.json.JSONObject

/** Durable progress metadata only. Never a replay queue or proof that a business step succeeded. */
data class TaskCheckpoint(val step: Int=0,val executedActions: Int=0,val frameId: String?=null,
    val requestId: String?=null,val outcome: String?=null,val state: String?=null) {
    fun advance(event: JSONObject): TaskCheckpoint {
        val nextStep=event.getInt("step");require(nextStep in step..101)
        val next=copy(step=nextStep)
        return when(event.getString("event")) {
            "OBSERVED","VERIFICATION_FRAME" -> next.copy(frameId=identifier(event.getString("frameId")))
            "ACTION_PENDING" -> next.copy(requestId=identifier(event.getString("requestId")),outcome="PENDING")
            "ACTION_RESULT" -> {
                require(event.getString("requestId")==requestId)
                val result=event.getString("outcome");require(result in outcomes)
                require(outcome=="PENDING" || outcome==result)
                next.copy(outcome=result,executedActions=executedActions+if(result=="EXECUTED" && outcome!="EXECUTED") 1 else 0)
            }
            "TASK_STATE" -> next.copy(state=event.getString("state").also { require(it in states) })
            else -> next
        }
    }
    fun json()=JSONObject().put("version",1).put("step",step).put("executedActions",executedActions)
        .put("frameId",frameId ?: JSONObject.NULL).put("requestId",requestId ?: JSONObject.NULL)
        .put("outcome",outcome ?: JSONObject.NULL).put("state",state ?: JSONObject.NULL)

    fun interruptedSummary(): String {
        val receipt=when(outcome) {
            "PENDING","UNKNOWN_OUTCOME" -> "最后一次动作结果未知，需人工检查，不能重发。"
            "EXECUTED" -> "最后一次动作收到执行回执，不代表任务完成。"
            null -> "尚无动作回执。"
            else -> "最后一次动作未获执行确认。"
        }
        return "中断记录：第${step}步，${executedActions}次动作收到执行回执。$receipt"
    }
    companion object {
        private val outcomes=TaskLoop.Outcome.entries.map { it.name }.toSet()
        private val states=TaskLoop.State.entries.map { it.name }.toSet()
        private fun identifier(value: String): String = value.also { require(it.matches(Regex("[A-Za-z0-9_-]{1,100}"))) }
        fun parse(json: JSONObject): TaskCheckpoint {
            require(json.getInt("version")==1)
            fun optional(name: String)=if(json.isNull(name)) null else json.getString(name)
            val result=TaskCheckpoint(json.getInt("step"),json.getInt("executedActions"),
                optional("frameId")?.let(::identifier),optional("requestId")?.let(::identifier),optional("outcome"),optional("state"))
            require(result.step in 0..101 && result.executedActions in 0..minOf(result.step,100))
            require(result.outcome==null || result.outcome in outcomes || result.outcome=="PENDING")
            require((result.requestId==null)==(result.outcome==null))
            require(result.state==null || result.state in states)
            return result
        }
    }
}

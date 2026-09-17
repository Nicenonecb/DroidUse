package dev.droiduse.agent

/** Pagination evidence is deterministic; a model cannot declare skipped pages complete. */
class ReadingProgress {
    /** Coordinates are original screenshot pixels; absent confidence stays unknown. */
    data class Row(val text: String,val x: Int,val y: Int,
        val confidence: Float? = null, val left: Int = x, val top: Int = y,
        val right: Int = x, val bottom: Int = y)
    var page=0; private set
    var chapter=0; private set
    private val covered=mutableSetOf<Int>()
    fun accept(rows: List<Row>): Boolean {
        val numbers=rows.filter { it.y>1050 }.mapNotNull {
            Regex("(\\d+)\\s*/\\s*(\\d+)").matchEntire(it.text.trim())?.groupValues?.get(1)?.toIntOrNull()
        }.toSet()
        require(numbers.size==1) { "无法确认唯一阅读页码" }
        val nextPage=numbers.single()
        require(nextPage==page+1) { "阅读页码重复或跳页：期望${page+1}，实际$nextPage" }
        val nextChapter=rows.filter { it.y<350 }.mapNotNull {
            Regex("^[<＜〈《‹く]?\\s*第\\s*([一二三四1234])\\s*章").find(it.text.trim())?.groupValues?.get(1)?.let { c ->
                c.toIntOrNull() ?: ("一二三四".indexOf(c)+1)
            }
        }.maxOrNull() ?: chapter
        require(page!=0 || nextChapter==1) { "必须从第一章第一页开始" }
        require(nextChapter in chapter..chapter+1) { "章节不连续" }
        if(nextChapter==4) require(covered==setOf(1,2,3)) { "前三章证据不全" }
        page=nextPage;chapter=nextChapter
        if(chapter==4) return true
        covered.add(chapter)
        return false
    }
}

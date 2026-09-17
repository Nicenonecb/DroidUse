package dev.droiduse.agent

/** Validates sequence of MODEL-reported page numbers; pixels remain the source for later auditing. */
class VisualReadingLedger {
    data class Page(val page: Int,val chapter: Int,val note: String,val frameId: String,val screenshot: String)
    val pages=mutableListOf<Page>()
    var complete=false; private set
    fun accept(value: Page) {
        require(value.page>0 && value.chapter in 1..4 && value.note.isNotBlank())
        val previous=pages.lastOrNull()
        if(previous!=null && previous.page==value.page) { require(previous.chapter==value.chapter);return }
        check(!complete)
        require(value.page==(previous?.page ?: 0)+1) { "视觉模型报告页码不连续" }
        require(if(previous==null) value.chapter==1 else value.chapter in previous.chapter..previous.chapter+1) { "视觉模型报告章节不连续" }
        if(value.chapter==4) require(pages.map { it.chapter }.toSet()==setOf(1,2,3))
        pages.add(value);complete=value.chapter==4
    }
}

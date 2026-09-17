package dev.droiduse.agent
import org.junit.Assert.*
import org.junit.Test

class ReadingProgressTest {
    private fun rows(page: Int,chapter: Int?=null,body: String="正文") = buildList {
        add(ReadingProgress.Row("$page/100",660,1180));add(ReadingProgress.Row(body,360,550))
        chapter?.let { add(ReadingProgress.Row("＜第${it}章 标题",300,150)) }
    }
    @Test fun mustStartAtFirstChapterAndFirstPage() {
        assertTrue(runCatching { ReadingProgress().accept(rows(2,1)) }.isFailure)
        assertTrue(runCatching { ReadingProgress().accept(rows(1,2)) }.isFailure)
    }
    @Test fun rejectsMissingDuplicateAndSkippedPages() {
        val p=ReadingProgress();p.accept(rows(1,1))
        for(r in listOf(rows(1,1),rows(3,1),listOf(ReadingProgress.Row("正文",100,200)))) assertTrue(runCatching { p.accept(r) }.isFailure)
        assertEquals(1,p.page)
    }
    @Test fun bodyChapterReferenceDoesNotFinishReading() {
        val p=ReadingProgress();assertFalse(p.accept(rows(1,1,"第4章 正文里的章节引用")));assertEquals(1,p.chapter)
    }
    @Test fun chaptersCannotSkipAndRejectionDoesNotMutateState() {
        val p=ReadingProgress();p.accept(rows(1,1));assertTrue(runCatching { p.accept(rows(2,3)) }.isFailure)
        assertEquals(1,p.page);assertEquals(1,p.chapter)
    }
    @Test fun onlyFourthChapterBoundaryProvesThreeCompleteChapters() {
        val p=ReadingProgress();assertFalse(p.accept(rows(1,1)));assertFalse(p.accept(rows(2)))
        assertFalse(p.accept(rows(3,2)));assertFalse(p.accept(rows(4,3)));assertTrue(p.accept(rows(5,4)))
    }
}

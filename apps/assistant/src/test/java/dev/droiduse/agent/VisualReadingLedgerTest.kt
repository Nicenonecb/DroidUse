package dev.droiduse.agent
import org.junit.Assert.*
import org.junit.Test
class VisualReadingLedgerTest {
    private fun page(p:Int,c:Int)=VisualReadingLedger.Page(p,c,"observed page","frame-$p","$p.png")
    @Test fun rejectsSkippedStartAndMissingChapters() {
        val l=VisualReadingLedger()
        assertTrue(runCatching { l.accept(page(2,1)) }.isFailure)
        l.accept(page(1,1));assertTrue(runCatching { l.accept(page(2,3)) }.isFailure)
        assertFalse(l.complete)
    }
    @Test fun repeatedObservationDoesNotInventAnotherPage() {
        val l=VisualReadingLedger();l.accept(page(1,1));l.accept(page(1,1));assertEquals(1,l.pages.size)
        assertTrue(runCatching { l.accept(page(1,2)) }.isFailure)
    }
    @Test fun fourthChapterBoundaryRequiresContinuousCoverage() {
        val l=VisualReadingLedger();l.accept(page(1,1));l.accept(page(2,2));l.accept(page(3,3));assertFalse(l.complete)
        l.accept(page(4,4));assertTrue(l.complete)
    }
}

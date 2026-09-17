package dev.droiduse.agent

import org.junit.Assert.*
import org.junit.Test

class TextTargetTest {
    private val wanted="直播签到：全网最抽象主播"
    private val row=ReadingProgress.Row("直擂签到:全网最抽象主播",282,942)
    @Test fun oneOcrErrorAndFullWidthColonAtSamePoint() {
        assertEquals(row,TextTarget.resolve(wanted,282,942,listOf(row)))
    }
    @Test fun doesNotClickSimilarTextElsewhere() {
        assertNull(TextTarget.resolve(wanted,282,900,listOf(row)))
    }
    @Test fun rejectsAmbiguousNearbyCandidates() {
        assertNull(TextTarget.resolve(wanted,282,942,listOf(row,row.copy(x=285))))
    }
    @Test fun doesNotFuzzShortOrUnrelatedLabels() {
        assertNull(TextTarget.resolve("购买",282,942,listOf(row.copy(text="购头"))))
        assertNull(TextTarget.resolve("直播签到：别的小说标题",282,942,listOf(row)))
    }
    @Test fun exactTargetStillChoosesNearestInstance() {
        assertEquals(row,TextTarget.resolve(row.text,282,942,listOf(row.copy(y=20),row)))
    }
}

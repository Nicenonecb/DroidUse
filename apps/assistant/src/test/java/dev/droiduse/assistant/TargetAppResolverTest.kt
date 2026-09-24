package dev.droiduse.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TargetAppResolverTest {
    private val apps = listOf(
        TargetApp("com.fanqie", "番茄免费小说"),
        TargetApp("com.meituan", "美团"),
        TargetApp("com.meituan.takeout", "美团外卖")
    )

    @Test fun prefersSpecificAppName() {
        assertEquals("com.meituan.takeout", inferTargetPackage("在美团外卖筛选午餐", apps))
    }

    @Test fun recognizesFanqieAliasAndDoesNotReuseTarget() {
        assertEquals("com.fanqie", inferTargetPackage("在番茄小说读前三章", apps))
        assertNull(inferTargetPackage("帮我找一本书", apps))
    }
}

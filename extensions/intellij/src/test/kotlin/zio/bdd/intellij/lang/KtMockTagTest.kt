package zio.bdd.intellij.lang

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class KtMockTagTest : BasePlatformTestCase() {

    fun testSingleNameOffsets() {
        assertEquals(
            listOf(KtMockTag.Ref("userService", 8, 19)),
            KtMockTag.refs("  @mock(userService)"),
        )
    }

    fun testMultipleNamesSkipWhitespaceAfterCommas() {
        assertEquals(
            listOf(KtMockTag.Ref("userService", 6, 17), KtMockTag.Ref("paymentGw", 19, 28)),
            KtMockTag.refs("@mock(userService, paymentGw)"),
        )
    }

    fun testEmptyParensYieldNoRefs() {
        assertTrue(KtMockTag.refs("@mock()").isEmpty())
    }

    fun testEmptyCommaSegmentsDropped() {
        assertEquals(
            listOf(KtMockTag.Ref("a", 6, 7), KtMockTag.Ref("b", 9, 10)),
            KtMockTag.refs("@mock(a,,b)"),
        )
    }

    fun testTwoTagsOnOneLine() {
        assertEquals(
            listOf(KtMockTag.Ref("a", 6, 7), KtMockTag.Ref("b", 15, 16), KtMockTag.Ref("c", 18, 19)),
            KtMockTag.refs("@mock(a) @mock(b, c)"),
        )
    }

    fun testIsInsideMockCall() {
        assertTrue(KtMockTag.isInsideMockCall("@mock("))
        assertTrue(KtMockTag.isInsideMockCall("@mock(user"))
        assertTrue(KtMockTag.isInsideMockCall("@mock(a) @mock("))
        assertFalse(KtMockTag.isInsideMockCall("@mock(a)"))
        assertFalse(KtMockTag.isInsideMockCall("@"))
        assertFalse(KtMockTag.isInsideMockCall("@mock(a) done"))
    }

    fun testPartialAtCaret() {
        assertEquals("", KtMockTag.partialAtCaret("@mock("))
        assertEquals("user", KtMockTag.partialAtCaret("@mock(user"))
        assertEquals("pay", KtMockTag.partialAtCaret("@mock(a, pay"))
        assertEquals("", KtMockTag.partialAtCaret("@mock(a, "))
    }
}

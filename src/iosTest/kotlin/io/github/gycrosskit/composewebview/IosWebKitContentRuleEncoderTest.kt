package io.github.gycrosskit.composewebview

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** WebKit 规则编码属于 iOS 实现细节，测试同样留在 iosTest。 */
class IosWebKitContentRuleEncoderTest {
    @Test
    fun normalizesHostsAndProtectsSuffixBoundary() {
        val encoded = listOf(
            WebViewUrlRule.ExactHost("EXAMPLE.COM."),
            WebViewUrlRule.HostSuffix(".SHOP.EXAMPLE."),
        ).toWebKitContentRuleList()

        assertTrue("example\\\\.com(:[0-9]+)?(/.*)?$" in encoded)
        assertTrue("([^./]+\\\\.)*shop\\\\.example(:[0-9]+)?(/.*)?$" in encoded)
    }

    @Test fun encodesHttpsSubdomainsWithoutUnsupportedAlternation() {
        val encoded = listOf(WebViewUrlRule.HostSuffix("jd.com", "https", false)).toWebKitContentRuleList()
        assertTrue("^https://([^./]+\\\\.)+jd\\\\.com(:[0-9]+)?(/.*)?$" in encoded)
        assertFalse('|' in encoded)
    }

    @Test
    fun escapesJsonControlCharactersAndCaseSensitivity() {
        val encoded = listOf(
            WebViewUrlRule.Contains("quote\"\nvalue", ignoreCase = true),
        ).toWebKitContentRuleList()

        assertTrue("""quote\"\nvalue""" in encoded)
        assertTrue("\"url-filter-is-case-sensitive\":false" in encoded)
        assertFalse(encoded.contains('\n'))
    }
}

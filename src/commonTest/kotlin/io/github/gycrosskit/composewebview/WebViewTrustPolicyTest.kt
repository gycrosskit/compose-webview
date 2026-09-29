package io.github.gycrosskit.composewebview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WebViewTrustPolicyTest {
    @Test
    fun requiresHttpsAndExactOrigin() {
        val policy = WebViewTrustPolicy(listOf("https://trusted.example/path"))

        assertTrue(policy.isTrusted("https://trusted.example/next"))
        assertFalse(policy.isTrusted("http://trusted.example/next"))
        assertFalse(policy.isTrusted("https://evil.example/next"))
        assertFalse(policy.isTrusted("https://trusted.example:8443/next"))
    }

    @Test
    fun normalizesDefaultHttpsPortAndTrailingDot() {
        val policy = WebViewTrustPolicy(listOf("https://trusted.example.:443/path"))

        assertTrue(policy.isTrusted("https://trusted.example/next"))
    }

    @Test
    fun trustedSuffixDoesNotAllowLookalikeHost() {
        val policy = WebViewTrustPolicy(trustedHostSuffixes = setOf(".shop.example."))

        assertTrue(policy.isTrusted("https://sale.shop.example/product"))
        assertTrue(policy.isTrusted("https://shop.example:8443/product"))
        assertFalse(policy.isTrusted("https://shop.example.evil.example/product"))
    }

    @Test
    fun invalidSuffixCannotEnterJavascriptTrustPolicy() {
        val policy = WebViewTrustPolicy(trustedHostSuffixes = setOf("safe.example');alert(1)//"))

        assertTrue(policy.isEmpty)
        assertEquals("false", policy.javascriptTrustExpression())
        assertTrue(policy.documentStartOriginRules().isEmpty())
    }

    @Test
    fun policyEqualityUsesNormalizedTrustedSources() {
        assertEquals(
            WebViewTrustPolicy(listOf("https://TRUSTED.example/path"), setOf(".SHOP.EXAMPLE")),
            WebViewTrustPolicy(listOf("https://trusted.example/other"), setOf("shop.example")),
        )
        assertNotEquals(
            WebViewTrustPolicy(listOf("https://trusted.example")),
            WebViewTrustPolicy(listOf("https://trusted.example:8443")),
        )
    }
}

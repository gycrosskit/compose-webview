package io.github.gycrosskit.composewebview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WebViewTrustPolicyTest {
    @Test fun rejectsCredentialsInvalidPortsAndUnsafeHostSyntax() {
        val policy = WebViewTrustPolicy(listOf("https://trusted.example"))
        for (value in listOf("https://user@trusted.example", "https://@trusted.example", "https://:@trusted.example", "https://user:secret@trusted.example", "https://trusted.example:0", "https://trusted.example:65536", "https://safe'.example", "https://trusted.example..", "https://sub..trusted.example", "https://[::1].")) {
            assertFalse(policy.isTrusted(value), value)
            assertTrue(WebViewTrustPolicy(listOf(value)).isEmpty, value)
        }
    }
    @Test fun ipv6ExactOriginUsesBrowserCanonicalRepresentation() {
        val pairs = listOf(
            "2001:0db8:0000:0:0:0:0:1" to "2001:db8::1",
            "::ffff:192.0.2.1" to "::ffff:c000:201",
            "0:0:0:0:0:0:0:0" to "::",
            "1:0:0:2:0:0:3:4" to "1::2:0:0:3:4",
        )
        for ((raw, canonical) in pairs) {
            val policy = WebViewTrustPolicy(listOf("https://[$raw]:8443"))
            assertTrue(policy.isTrusted("https://[$canonical]:8443/path"), raw)
            assertEquals(setOf("https://[$canonical]:8443"), policy.urls)
            assertTrue(policy.javascriptTrustExpression().contains("=== '[$canonical]'"))
            assertEquals(policy, WebViewTrustPolicy(listOf("https://[$canonical]:8443")))
        }
        for (host in listOf("1::2::3", "1:2:3", "::ffff:192.00.2.1", "::ffff:256.0.0.1", "1:2:3:4:5:6:7:8:9"))
            assertTrue(WebViewTrustPolicy(listOf("https://[$host]")).isEmpty, host)
    }

    @Test fun keepsIpv6ExactOriginAndValidCustomPorts() {
        val policy = WebViewTrustPolicy(listOf("https://[2001:db8::1]:8443/path", "https://trusted.example:65535"))
        assertTrue(policy.isTrusted("https://[2001:db8::1]:8443/next"))
        assertFalse(policy.isTrusted("https://[2001:db8::2]:8443/next"))
        assertFalse(policy.isTrusted("https://[2001:db8::1]/next"))
        assertTrue(policy.isTrusted("https://trusted.example:65535/next"))
        assertEquals(setOf("https://[2001:db8::1]:8443", "https://trusted.example:65535"), policy.urls)
        assertTrue(WebViewTrustPolicy(listOf("https://[::1]:443")).isTrusted("https://[::1]/next"))
        assertTrue(WebViewTrustPolicy(listOf("https://[::ffff:192.0.2.1]:8443")).isTrusted("https://[::ffff:192.0.2.1]:8443/path"))
        assertTrue(WebViewTrustPolicy(trustedHostSuffixes = setOf("trusted.example")).isTrusted("https://sub.trusted.example:65535/path"))
    }

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
        assertTrue(policy.javascriptTrustExpression().contains("location.hostname === 'trusted.example.'"))
        assertEquals(setOf("https://trusted.example", "https://trusted.example."), policy.documentStartOriginRules())
    }

    @Test
    fun trustedSuffixDoesNotAllowLookalikeHost() {
        val policy = WebViewTrustPolicy(trustedHostSuffixes = setOf(".shop.example."))

        assertTrue(policy.isTrusted("https://sale.shop.example/product"))
        assertTrue(policy.isTrusted("https://shop.example:8443/product"))
        assertFalse(policy.isTrusted("https://shop.example.evil.example/product"))
        assertFalse(policy.isTrusted("https://shop.example../product"))
        assertEquals(setOf("*"), policy.documentStartOriginRules())
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

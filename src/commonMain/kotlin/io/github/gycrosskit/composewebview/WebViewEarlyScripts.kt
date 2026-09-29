package io.github.gycrosskit.composewebview

/** 生成可重复执行的业务脚本包装；document-start 不可用时可在首次可见和完成回调安全兜底。 */
internal fun WebViewRequest.earlyScriptSource(): String? {
    val scripts = scripts.filter { it.injectionTime != WebViewScriptInjectionTime.DOCUMENT_FINISHED }
    if (scripts.isEmpty()) return null
    val trustExpression = security.trustedOrigins.javascriptTrustExpression()
    return buildString {
        appendLine("(function() {")
        appendLine("  window.__COMPOSE_WEBVIEW_NATIVE_SCRIPT_IDS__ = window.__COMPOSE_WEBVIEW_NATIVE_SCRIPT_IDS__ || {};")
        scripts.forEach { script ->
            val escapedId = script.id.escapeJavascriptSingleQuoted()
            val originGate = if (script.onlyForTrustedMainFrame) "($trustExpression) && " else ""
            appendLine("  if (${originGate}!window.__COMPOSE_WEBVIEW_NATIVE_SCRIPT_IDS__['$escapedId']) {")
            appendLine("    window.__COMPOSE_WEBVIEW_NATIVE_SCRIPT_IDS__['$escapedId'] = true;")
            val body = "try {\n${script.source}\n} catch (_) {}"
            if (script.injectionTime == WebViewScriptInjectionTime.DOM_READY) {
                appendLine("    (function(run) {")
                appendLine("      if (document.readyState === 'loading') {")
                appendLine("        document.addEventListener('DOMContentLoaded', run, { once: true });")
                appendLine("      } else { run(); }")
                appendLine("    })(function() { $body });")
            } else {
                appendLine(body.prependIndent("    "))
            }
            appendLine("  }")
        }
        appendLine("})();")
    }
}

internal fun WebViewRequest.earlyScriptOriginRules(): Set<String> {
    val early = scripts.filter { it.injectionTime != WebViewScriptInjectionTime.DOCUMENT_FINISHED }
    return when {
        early.isEmpty() -> emptySet()
        early.any { !it.onlyForTrustedMainFrame } -> setOf("*")
        else -> security.trustedOrigins.documentStartOriginRules()
    }
}

internal fun WebViewRequest.finishedScriptsAt(url: String?): List<WebViewScript> = scripts.filter { script ->
    script.injectionTime == WebViewScriptInjectionTime.DOCUMENT_FINISHED && canInject(script, url)
}

/** 固定协议只接收无符号毫秒数，不携带 URL、DOM 或业务数据。 */
internal fun parseWebViewPerformanceMessage(raw: String): Pair<WebViewPerformanceMetric, Long>? {
    val parts = raw.split(':')
    if (parts.size != 3 || parts[0] != PERFORMANCE_MESSAGE_PREFIX) return null
    val metric = when (parts[1]) {
        "dns" -> WebViewPerformanceMetric.DNS_LOOKUP
        "tcp" -> WebViewPerformanceMetric.TCP_CONNECT
        "tls" -> WebViewPerformanceMetric.TLS_HANDSHAKE
        "request" -> WebViewPerformanceMetric.REQUEST
        "response" -> WebViewPerformanceMetric.RESPONSE
        "ttfb" -> WebViewPerformanceMetric.TIME_TO_FIRST_BYTE
        "dom" -> WebViewPerformanceMetric.DOM_CONTENT_LOADED
        "visible" -> WebViewPerformanceMetric.FIRST_CONTENT_VISIBLE
        "fcp" -> WebViewPerformanceMetric.FIRST_CONTENTFUL_PAINT
        "lcp" -> WebViewPerformanceMetric.LARGEST_CONTENTFUL_PAINT
        else -> return null
    }
    val duration = parts[2].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
    return metric to duration.toLong()
}

internal const val PERFORMANCE_MESSAGE_PREFIX = "perf"
internal const val PERFORMANCE_MESSAGE_HANDLER = "ComposeWebViewPerformance"

/** Native 预置的首屏指标采集器，不要求业务 H5 增加任何代码。 */
internal val WEB_VIEW_PERFORMANCE_SCRIPT = """
    (function() {
      if (window.__COMPOSE_WEBVIEW_PERFORMANCE_INSTALLED__) return;
      window.__COMPOSE_WEBVIEW_PERFORMANCE_INSTALLED__ = true;
      function publish(name, value) {
        try {
          var target =
            (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.ComposeWebViewEvent) ||
            window.ComposeWebViewPerformance;
          if (!target || typeof target.postMessage !== 'function') return;
          target.postMessage('perf:' + name + ':' + Math.max(0, Number(value) || 0));
        } catch (_) {}
      }
      function duration(start, end) {
        var value = Number(end) - Number(start);
        return Number.isFinite(value) ? Math.max(0, value) : 0;
      }
      var navigationStartPhasesPublished = false;
      function navigationEntry() {
        try {
          return performance.getEntriesByType('navigation')[0];
        } catch (_) {
          return null;
        }
      }
      function publishNavigationStartPhases() {
        if (navigationStartPhasesPublished) return;
        var navigation = navigationEntry();
        if (!navigation) return;
        var secureConnectionStart = Number(navigation.secureConnectionStart) || 0;
        publish('dns', duration(navigation.domainLookupStart, navigation.domainLookupEnd));
        if (secureConnectionStart > 0) {
          publish('tcp', duration(navigation.connectStart, secureConnectionStart));
          publish('tls', duration(secureConnectionStart, navigation.connectEnd));
        } else {
          publish('tcp', duration(navigation.connectStart, navigation.connectEnd));
          publish('tls', 0);
        }
        publish('request', duration(navigation.requestStart, navigation.responseStart));
        publish('ttfb', navigation.responseStart);
        navigationStartPhasesPublished = true;
      }
      function publishNavigationResponsePhase() {
        publishNavigationStartPhases();
        var navigation = navigationEntry();
        if (navigation) {
          publish('response', duration(navigation.responseStart, navigation.responseEnd));
        }
      }
      function domReady() {
        publishNavigationResponsePhase();
        publish('dom', performance.now());
        requestAnimationFrame(function() {
          requestAnimationFrame(function() { publish('visible', performance.now()); });
        });
      }
      publishNavigationStartPhases();
      if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', domReady, { once: true });
      } else {
        domReady();
      }
      try {
        new PerformanceObserver(function(list) {
          var entries = list.getEntriesByName('first-contentful-paint');
          if (entries.length) publish('fcp', entries[0].startTime);
        }).observe({ type: 'paint', buffered: true });
      } catch (_) {}
      try {
        new PerformanceObserver(function(list) {
          var entries = list.getEntries();
          if (entries.length) publish('lcp', entries[entries.length - 1].startTime);
        }).observe({ type: 'largest-contentful-paint', buffered: true });
      } catch (_) {}
    })();
""".trimIndent()

private fun String.escapeJavascriptSingleQuoted(): String = replace("\\", "\\\\").replace("'", "\\'")

package io.github.gycrosskit.composewebview

import kotlinx.serialization.json.*

/** UI 无关的 wire 编解码；原生收到不合法请求时必须拒绝整个请求。 */
object WebViewWire {
    fun encodeRequest(request: WebViewRequest): String = buildJsonObject {
        put("content", when (val content = request.content) {
            is WebViewContent.Url -> buildJsonObject {
                put("type", "url"); put("url", content.url)
                put("additionalHeaders", buildJsonObject { content.additionalHeaders.forEach { (key, value) -> put(key, value) } })
            }
            is WebViewContent.Html -> buildJsonObject {
                put("type", "html"); put("html", content.html); put("baseUrl", content.baseUrl)
                put("mimeType", content.mimeType); put("encoding", content.encoding); put("historyUrl", content.historyUrl)
            }
        })
        put("settings", settings(request.settings))
        put("security", buildJsonObject {
            val security = request.security
            put("trustedOrigins", buildJsonObject {
                put("urls", strings(security.trustedOrigins.urls))
                put("trustedHostSuffixes", strings(security.trustedOrigins.trustedHostSuffixes))
            })
            put("appBridgeEnabled", security.appBridgeEnabled); put("pageBridgeEnabled", security.pageBridgeEnabled)
            put("fileChooserEnabled", security.fileChooserEnabled); put("mediaCaptureEnabled", security.mediaCaptureEnabled)
        })
        put("scripts", JsonArray(request.scripts.map { script -> buildJsonObject {
            put("id", script.id); put("source", script.source); put("injectionTime", script.injectionTime.name)
            put("onlyForTrustedMainFrame", script.onlyForTrustedMainFrame)
        } }))
        put("blockedResourceRules", rules(request.blockedResourceRules))
        put("navigationPolicy", buildJsonObject {
            put("allowedSchemes", strings(request.navigationPolicy.allowedSchemes))
            put("allowedOrigins", strings(request.navigationPolicy.allowedOrigins))
            put("blockedRules", rules(request.navigationPolicy.blockedRules))
            put("allowNewWindows", request.navigationPolicy.allowNewWindows)
        })
    }.toString()

    fun decodeRequest(raw: String): WebViewRequest {
        val value = Json.parseToJsonElement(raw).jsonObject
        val content = value.getValue("content").jsonObject
        val security = value["security"]?.jsonObject ?: JsonObject(emptyMap())
        val origins = security["trustedOrigins"]?.jsonObject ?: JsonObject(emptyMap())
        val policy = value["navigationPolicy"]?.jsonObject ?: JsonObject(emptyMap())
        return WebViewRequest(
            content = when (content.requiredString("type")) {
                "url" -> WebViewContent.Url(content.requiredString("url"), content["additionalHeaders"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap())
                "html" -> WebViewContent.Html(content.requiredString("html"), content.stringOrNull("baseUrl"), content.string("mimeType", "text/html"), content.string("encoding", "UTF-8"), content.stringOrNull("historyUrl"))
                else -> error("Unsupported WebView content")
            },
            settings = decodeSettings(value["settings"]?.jsonObject ?: JsonObject(emptyMap())),
            security = WebViewSecurity(
                WebViewTrustPolicy(origins.strings("urls"), origins.strings("trustedHostSuffixes").toSet()),
                security.boolean("appBridgeEnabled", false), security.boolean("pageBridgeEnabled", false),
                security.boolean("fileChooserEnabled", false), security.boolean("mediaCaptureEnabled", false),
            ),
            scripts = value["scripts"]?.jsonArray?.map { item ->
                val script = item.jsonObject
                WebViewScript(script.requiredString("id"), script.requiredString("source"), script.enum("injectionTime", WebViewScriptInjectionTime.DOM_READY), script.boolean("onlyForTrustedMainFrame", true))
            } ?: emptyList(),
            blockedResourceRules = decodeRules(value["blockedResourceRules"]),
            navigationPolicy = WebViewNavigationPolicy(
                if ("allowedSchemes" in policy) policy.strings("allowedSchemes").toSet() else setOf("http", "https"),
                decodeRules(policy["blockedRules"]), policy.boolean("allowNewWindows", false),
                policy.strings("allowedOrigins").toSet(),
            ),
        )
    }

    fun eventValues(event: WebViewEvent): Map<String, Any?> = when (event) {
        is WebViewEvent.PageStarted -> mapOf("type" to "pageStarted", "url" to event.url)
        is WebViewEvent.FirstContentVisible -> mapOf("type" to "firstContentVisible", "url" to event.url)
        is WebViewEvent.PageFinished -> mapOf("type" to "pageFinished", "url" to event.url)
        is WebViewEvent.TitleChanged -> mapOf("type" to "titleChanged", "title" to event.title)
        is WebViewEvent.ProgressChanged -> mapOf("type" to "progressChanged", "progress" to event.progress)
        is WebViewEvent.BridgeMessage -> mapOf("type" to "bridgeMessage", "handlerName" to event.value.handlerName, "data" to event.value.data)
        is WebViewEvent.FullscreenChanged -> mapOf("type" to "fullscreenChanged", "isFullscreen" to event.isFullscreen)
        is WebViewEvent.LoadFailed -> with(event.error) { mapOf("type" to "loadFailed", "kind" to kind.name, "message" to message, "url" to url, "errorCode" to errorCode, "httpStatus" to httpStatus, "isMainFrame" to isMainFrame) }
        is WebViewEvent.CapabilityUnsupported -> mapOf("type" to "capabilityUnsupported", "capability" to event.capability.name)
        is WebViewEvent.PermissionSettingsRequired -> mapOf("type" to "permissionSettingsRequired", "permissions" to event.permissions.map { it.name })
        is WebViewEvent.PerformanceMetric -> mapOf("type" to "performanceMetric", "name" to event.name.name, "navigationDurationMillis" to event.navigationDurationMillis)
        is WebViewEvent.Navigation -> with(event.request) { mapOf("type" to "navigation", "url" to url, "isMainFrame" to isMainFrame, "hasUserGesture" to hasUserGesture, "target" to target.name, "blocked" to event.blocked) }
        is WebViewEvent.HistoryChanged -> mapOf("type" to "historyChanged", "canGoBack" to event.canGoBack, "canGoForward" to event.canGoForward, "url" to event.url)
    }

    fun decodeEvent(raw: String): WebViewEvent {
        val value = Json.parseToJsonElement(raw).jsonObject
        return when (value.requiredString("type")) {
            "pageStarted" -> WebViewEvent.PageStarted(value.stringOrNull("url"))
            "firstContentVisible" -> WebViewEvent.FirstContentVisible(value.stringOrNull("url"))
            "pageFinished" -> WebViewEvent.PageFinished(value.stringOrNull("url"))
            "titleChanged" -> WebViewEvent.TitleChanged(value.stringOrNull("title"))
            "progressChanged" -> WebViewEvent.ProgressChanged(value.integer("progress", 0))
            "bridgeMessage" -> WebViewEvent.BridgeMessage(WebViewBridgeMessage(value.requiredString("handlerName"), value.requiredString("data")))
            "fullscreenChanged" -> WebViewEvent.FullscreenChanged(value.boolean("isFullscreen", false))
            "loadFailed" -> WebViewEvent.LoadFailed(WebViewLoadError(value.enum("kind", WebViewErrorKind.UNKNOWN), value.string("message", ""), value.stringOrNull("url"), value["errorCode"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.int, value["httpStatus"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.int, value.boolean("isMainFrame", true)))
            "capabilityUnsupported" -> WebViewEvent.CapabilityUnsupported(enumValueOf(value.requiredString("capability")))
            "permissionSettingsRequired" -> WebViewEvent.PermissionSettingsRequired(value.strings("permissions").map { enumValueOf<WebViewPermission>(it) }.toSet())
            "performanceMetric" -> WebViewEvent.PerformanceMetric(enumValueOf(value.requiredString("name")), value.getValue("navigationDurationMillis").jsonPrimitive.long)
            "navigation" -> WebViewEvent.Navigation(WebViewNavigationRequest(value.requiredString("url"), value.boolean("isMainFrame", true), value.boolean("hasUserGesture", false), value.enum("target", WebViewNavigationTarget.CURRENT_WINDOW)), value.boolean("blocked", true))
            "historyChanged" -> WebViewEvent.HistoryChanged(value.boolean("canGoBack", false), value.boolean("canGoForward", false), value.stringOrNull("url"))
            else -> error("Unsupported WebView event")
        }
    }

    private fun settings(value: WebViewSettings) = buildJsonObject {
            put("javaScriptEnabled", value.javaScriptEnabled)
            put("domStorageEnabled", value.domStorageEnabled)
            put("allowFileAccess", value.allowFileAccess)
            put("allowContentAccess", value.allowContentAccess)
            put("mixedContentPolicy", value.mixedContentPolicy.name)
            put("cachePolicy", value.cachePolicy.name)
            put("acceptsThirdPartyCookies", value.acceptsThirdPartyCookies)
            put("supportMultipleWindows", value.supportMultipleWindows)
            put("javaScriptCanOpenWindowsAutomatically", value.javaScriptCanOpenWindowsAutomatically)
            put("mediaPlaybackRequiresUserGesture", value.mediaPlaybackRequiresUserGesture)
            put("loadsImagesAutomatically", value.loadsImagesAutomatically)
            put("blockNetworkImage", value.blockNetworkImage)
            put("builtInZoomControls", value.builtInZoomControls)
            put("displayZoomControls", value.displayZoomControls)
            put("supportZoom", value.supportZoom)
            put("useWideViewPort", value.useWideViewPort)
            put("loadWithOverviewMode", value.loadWithOverviewMode)
            put("followSystemFontScale", value.followSystemFontScale)
            put("minimumTextZoomPercent", value.minimumTextZoomPercent)
            put("maximumTextZoomPercent", value.maximumTextZoomPercent)
            put("algorithmicDarkeningAllowed", value.algorithmicDarkeningAllowed)
            put("userAgentSuffix", value.userAgentSuffix)
    }

    private fun decodeSettings(value: JsonObject): WebViewSettings {
        val defaults = WebViewSettings()
        return WebViewSettings(
            javaScriptEnabled = value.boolean("javaScriptEnabled", defaults.javaScriptEnabled),
            domStorageEnabled = value.boolean("domStorageEnabled", defaults.domStorageEnabled),
            allowFileAccess = value.boolean("allowFileAccess", defaults.allowFileAccess),
            allowContentAccess = value.boolean("allowContentAccess", defaults.allowContentAccess),
            mixedContentPolicy = value.enum<WebViewMixedContentPolicy>("mixedContentPolicy", defaults.mixedContentPolicy),
            cachePolicy = value.enum<WebViewCachePolicy>("cachePolicy", defaults.cachePolicy),
            acceptsThirdPartyCookies = value.boolean("acceptsThirdPartyCookies", defaults.acceptsThirdPartyCookies),
            supportMultipleWindows = value.boolean("supportMultipleWindows", defaults.supportMultipleWindows),
            javaScriptCanOpenWindowsAutomatically = value.boolean("javaScriptCanOpenWindowsAutomatically", defaults.javaScriptCanOpenWindowsAutomatically),
            mediaPlaybackRequiresUserGesture = value.boolean("mediaPlaybackRequiresUserGesture", defaults.mediaPlaybackRequiresUserGesture),
            loadsImagesAutomatically = value.boolean("loadsImagesAutomatically", defaults.loadsImagesAutomatically),
            blockNetworkImage = value.boolean("blockNetworkImage", defaults.blockNetworkImage),
            builtInZoomControls = value.boolean("builtInZoomControls", defaults.builtInZoomControls),
            displayZoomControls = value.boolean("displayZoomControls", defaults.displayZoomControls),
            supportZoom = value.boolean("supportZoom", defaults.supportZoom),
            useWideViewPort = value.boolean("useWideViewPort", defaults.useWideViewPort),
            loadWithOverviewMode = value.boolean("loadWithOverviewMode", defaults.loadWithOverviewMode),
            followSystemFontScale = value.boolean("followSystemFontScale", defaults.followSystemFontScale),
            minimumTextZoomPercent = value.integer("minimumTextZoomPercent", defaults.minimumTextZoomPercent),
            maximumTextZoomPercent = value.integer("maximumTextZoomPercent", defaults.maximumTextZoomPercent),
            algorithmicDarkeningAllowed = value.boolean("algorithmicDarkeningAllowed", defaults.algorithmicDarkeningAllowed),
            userAgentSuffix = value.stringOrNull("userAgentSuffix"),
        )
    }

    private fun rules(values: List<WebViewUrlRule>) = JsonArray(values.map { rule -> buildJsonObject {
        when (rule) {
            is WebViewUrlRule.Contains -> { put("type", "contains"); put("value", rule.value); put("ignoreCase", rule.ignoreCase) }
            is WebViewUrlRule.ExactHost -> { put("type", "exactHost"); put("host", rule.host) }
            is WebViewUrlRule.HostSuffix -> { put("type", "hostSuffix"); put("suffix", rule.suffix) }
        }
    } })

    private fun decodeRules(value: JsonElement?): List<WebViewUrlRule> = value?.jsonArray?.map {
        val rule = it.jsonObject
        when (rule.requiredString("type")) {
            "contains" -> WebViewUrlRule.Contains(rule.requiredString("value"), rule.boolean("ignoreCase", false))
            "exactHost" -> WebViewUrlRule.ExactHost(rule.requiredString("host"))
            "hostSuffix" -> WebViewUrlRule.HostSuffix(rule.requiredString("suffix"))
            else -> error("Unsupported URL rule")
        }
    } ?: emptyList()

    private fun strings(values: Collection<String>) = JsonArray(values.map(::JsonPrimitive))
    private fun JsonObject.strings(key: String): List<String> = get(key)?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
    private fun JsonObject.requiredString(key: String): String = getValue(key).jsonPrimitive.let { require(it.isString); it.content }
    private fun JsonObject.stringOrNull(key: String): String? = get(key)?.takeUnless { it == JsonNull }?.jsonPrimitive?.let { require(it.isString); it.content }
    private fun JsonObject.string(key: String, default: String): String = stringOrNull(key) ?: default
    private fun JsonObject.boolean(key: String, default: Boolean): Boolean = get(key)?.jsonPrimitive?.boolean ?: default
    private fun JsonObject.integer(key: String, default: Int): Int = get(key)?.jsonPrimitive?.int ?: default
    private inline fun <reified T : Enum<T>> JsonObject.enum(key: String, default: T): T = stringOrNull(key)?.let { enumValueOf<T>(it) } ?: default
}

package io.github.gycrosskit.composewebview

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.internal.WebViewGlueCommunicator
import androidx.webkit.internal.WebViewProviderFactory
import org.chromium.support_lib_boundary.ScriptHandlerBoundaryInterface
import org.chromium.support_lib_boundary.WebViewProviderBoundaryInterface
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.nio.file.Files
import javax.tools.ToolProvider

@SuppressLint("RestrictedApi")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [DocumentStartFeatures::class, DocumentStartPublicApi::class, DocumentStartFactory::class])
class AndroidDocumentStartScriptsTest {
    private val temporary = Files.createTempDirectory("document-start-v0-").toFile()
    private lateinit var legacyLoader: URLClassLoader
    private val request = WebViewRequest(WebViewContent.Url("https://page.test/initial"),
        settings = WebViewSettings(javaScriptEnabled = true),
        security = WebViewSecurity(WebViewTrustPolicy(listOf("https://page.test")), geolocationEnabled = true),
        pageMessageChannels = setOf("PageReady"),
    ).withNativeGeolocation()

    @Before fun prepare() {
        // 97 的接口名称及继承关系必须原样保留，不能用 app 包下同形状的接口掩盖 declaringClass 差异。
        val fixtures = mapOf(
            "ScriptReferenceBoundaryInterface" to "public interface ScriptReferenceBoundaryInterface { void remove(); }",
            "ScriptHandlerBoundaryInterface" to "public interface ScriptHandlerBoundaryInterface extends ScriptReferenceBoundaryInterface {}",
            "WebViewProviderBoundaryInterface" to "public interface WebViewProviderBoundaryInterface { java.lang.reflect.InvocationHandler addDocumentStartJavaScript(String script, String[] allowedOriginRules); }",
        )
        val sources = fixtures.map { (name, source) -> temporary.resolve("$name.java").apply {
            writeText("package org.chromium.support_lib_boundary;\n$source\n")
        } }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-classpath", "",
            "-d", temporary.absolutePath, *sources.map { it.absolutePath }.toTypedArray()))
        legacyLoader = object : URLClassLoader(arrayOf(temporary.toURI().toURL()), javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (!name.startsWith("org.chromium.support_lib_boundary.")) return super.loadClass(name, resolve)
                return findLoadedClass(name) ?: findClass(name).also { if (resolve) resolveClass(it) }
            }
        }
        DocumentStartBackend.reset(legacyLoader)
    }

    @After fun cleanup() {
        if (::legacyLoader.isInitialized) legacyLoader.close()
        temporary.deleteRecursively()
    }

    private fun view() = WebView(RuntimeEnvironment.getApplication())

    @Test fun `stable public API wins without reading the internal factory`() {
        DocumentStartBackend.stable = true
        val handle = AndroidDocumentStartScripts.resolve()!!.add(view(), "stable", setOf("https://page.test"))
        assertEquals(0, DocumentStartBackend.factoryReads)
        assertEquals(listOf(false), DocumentStartBackend.added.map { it.legacy })
        handle.remove(); handle.remove()
        assertEquals(1, DocumentStartBackend.added.single().removes)
    }

    @Test fun `v0 preserves the old remove declaring class instead of spoofing version one`() {
        val handle = AndroidDocumentStartScripts.resolve()!!.add(view(), "v0", setOf("https://page.test:443"))
        val added = DocumentStartBackend.added.single()
        assertTrue(added.legacy)
        assertEquals("v0", added.source)
        assertEquals(setOf("https://page.test:443"), added.origins)
        // 与 97 的 jf.invoke 一样，getDeclaredMethod 不会在旧 Handler 的父接口搜索 remove。
        assertThrows(NoSuchMethodException::class.java) {
            added.boundary!!.invoke(null, ScriptHandlerBoundaryInterface::class.java.getDeclaredMethod("remove"), emptyArray())
        }
        handle.remove(); handle.remove()
        assertEquals(1, added.removes)
        assertEquals("org.chromium.support_lib_boundary.ScriptReferenceBoundaryInterface", added.removalDeclaringClass)
    }

    @Test fun `v0 requires the exact bare feature not a version or development suffix`() {
        for (feature in listOf("DOCUMENT_START_SCRIPT:1", "DOCUMENT_START_SCRIPT:dev", "DOCUMENT_START_SCRIPT:1:dev", "DOCUMENT_START_SCRIPT_OTHER")) {
            DocumentStartBackend.features = arrayOf(feature)
            assertNull(feature, AndroidDocumentStartScripts.resolve())
        }
        assertTrue(DocumentStartBackend.added.isEmpty())
    }

    @Test fun `missing legacy reference fails closed before adding any script`() {
        DocumentStartBackend.loader = object : ClassLoader(legacyLoader) {
            override fun loadClass(name: String): Class<*> {
                if (name.endsWith(".ScriptReferenceBoundaryInterface")) throw ClassNotFoundException(name)
                return super.loadClass(name)
            }
        }
        assertNull(AndroidDocumentStartScripts.resolve())
        assertTrue(DocumentStartBackend.added.isEmpty())
    }

    @Test fun `legacy feature with a mismatched add return signature fails closed`() {
        DocumentStartBackend.loader = object : ClassLoader(legacyLoader) {
            override fun loadClass(name: String): Class<*> =
                if (name.endsWith(".WebViewProviderBoundaryInterface")) WrongDocumentStartProvider::class.java
                else super.loadClass(name)
        }
        assertNull(AndroidDocumentStartScripts.resolve())
        assertTrue(DocumentStartBackend.added.isEmpty())
    }

    @Test fun `specific unsupported capability round trips through the existing wire enum`() {
        val event = WebViewEvent.CapabilityUnsupported(WebViewCapability.NATIVE_GEOLOCATION)
        assertEquals(event, WebViewWire.decodeEvent("""{"type":"capabilityUnsupported","capability":"NATIVE_GEOLOCATION"}"""))
        assertEquals(mapOf("type" to "capabilityUnsupported", "capability" to "NATIVE_GEOLOCATION"), WebViewWire.eventValues(event))
    }

    @Test fun `old channel constructor never assumes that advertised document start was installed`() {
        DocumentStartBackend.stable = true
        val owner = view()
        val events = mutableListOf<WebViewEvent>()
        val channels = AndroidPageMessageChannels(owner, { request }, { true }, events::add)
        assertEquals(listOf(WebViewEvent.CapabilityUnsupported(WebViewCapability.NATIVE_GEOLOCATION)), events)
        assertEquals(setOf("PageReady"), DocumentStartBackend.listeners.getValue(owner).keys)
        assertEquals(0, DocumentStartBackend.factoryReads)
        channels.revoke()
    }

    @Test fun `successful v0 installation enables geolocation with original frame and origin gates`() {
        val owner = view()
        val installer = AndroidEarlyScriptInstaller { _, _, _ -> }
        installer.install(owner, request)
        val events = mutableListOf<WebViewEvent>()
        val channels = AndroidPageMessageChannels(owner, { request }, { true },
            documentStartInstalled = installer.hasDocumentStartScripts(owner), onEvent = events::add)
        val listener = DocumentStartBackend.listeners.getValue(owner).getValue(NATIVE_GEOLOCATION_CHANNEL)
        val reply = PageChannelReply()
        listener.onPostMessage(owner, WebMessageCompat("foreign"), Uri.parse("https://foreign.test"), true, reply)
        listener.onPostMessage(owner, WebMessageCompat("iframe"), Uri.parse("https://page.test"), false, reply)
        assertTrue(events.isEmpty())
        listener.onPostMessage(owner, WebMessageCompat("get"), Uri.parse("https://page.test"), true, reply)
        val message = events.single() as WebViewEvent.PageMessage
        assertEquals(NATIVE_GEOLOCATION_CHANNEL, message.channel)
        assertTrue(channels.reply(message.replyId, "position"))
        channels.revoke(); installer.remove(owner)
        assertFalse(channels.reply(message.replyId, "late"))
        assertEquals(listOf("position"), reply.messages)
        assertTrue(DocumentStartBackend.added.all { it.legacy && it.removes == 1 })
    }

    @Test fun `registration and removal require UI thread without consuming a rejected removal`() {
        val registrar = AndroidDocumentStartScripts.resolve()!!
        val owner = view()
        val handle = registrar.add(owner, "v0", setOf("https://page.test"))
        var addError: Throwable? = null
        var removeError: Throwable? = null
        Thread {
            try { registrar.add(owner, "background", setOf("https://page.test")) } catch (error: Throwable) { addError = error }
            try { handle.remove() } catch (error: Throwable) { removeError = error }
        }.apply { start(); join() }
        assertTrue(addError is IllegalStateException)
        assertTrue(removeError is IllegalStateException)
        assertEquals(1, DocumentStartBackend.added.size)
        handle.remove()
        assertEquals(1, DocumentStartBackend.added.single().removes)
    }

    @Test fun `partial add failure rolls back itself while preserving reentrant fresh owner and old channels`() {
        val old = view()
        val fresh = view()
        val installer = AndroidEarlyScriptInstaller { _, _, _ -> }
        DocumentStartBackend.failAdd = 2
        DocumentStartBackend.onRemove = { added -> if (added.owner === old) installer.install(fresh, request) }
        installer.install(old, request)
        assertFalse(installer.hasDocumentStartScripts(old))
        assertTrue(installer.hasDocumentStartScripts(fresh))
        assertEquals(1, DocumentStartBackend.added.single { it.owner === old }.removes)
        assertTrue(DocumentStartBackend.added.filter { it.owner === fresh }.all { it.removes == 0 })
        assertFalse(DocumentStartBackend.listeners.containsKey(old))
        assertTrue(DocumentStartBackend.listeners.containsKey(fresh))
        installer.remove(old)
        assertTrue(installer.hasDocumentStartScripts(fresh))

        val events = mutableListOf<WebViewEvent>()
        val channels = AndroidPageMessageChannels(old, { request }, { true },
            documentStartInstalled = installer.hasDocumentStartScripts(old), onEvent = events::add)
        assertEquals(listOf(WebViewEvent.CapabilityUnsupported(WebViewCapability.NATIVE_GEOLOCATION)), events)
        assertEquals(setOf("PageReady"), DocumentStartBackend.listeners.getValue(old).keys)
        val reply = PageChannelReply()
        DocumentStartBackend.listeners.getValue(old).getValue("PageReady").onPostMessage(old,
            WebMessageCompat("ready"), Uri.parse("https://page.test"), true, reply)
        assertTrue(channels.reply((events.last() as WebViewEvent.PageMessage).replyId, "ok"))
        assertEquals(listOf("ok"), reply.messages)
        channels.revoke(); installer.remove(fresh); installer.remove(fresh)
        assertTrue(DocumentStartBackend.added.all { it.removes == 1 })
    }

    @Test fun `removal during add reclaims a late old handle without touching the new owner`() {
        val old = view()
        val fresh = view()
        val installer = AndroidEarlyScriptInstaller { _, _, _ -> }
        DocumentStartBackend.onAdd = { added -> if (added.owner === old) {
            installer.remove(old)
            installer.install(fresh, request)
        } }
        installer.install(old, request)
        assertFalse(installer.hasDocumentStartScripts(old))
        assertTrue(installer.hasDocumentStartScripts(fresh))
        assertEquals(1, DocumentStartBackend.added.single { it.owner === old }.removes)
        assertTrue(DocumentStartBackend.added.filter { it.owner === fresh }.all { it.removes == 0 })
        installer.remove(old); installer.remove(fresh)
        assertTrue(DocumentStartBackend.added.all { it.removes == 1 })
    }

    @Test fun `repeated installation does not orphan the first set of scripts`() {
        val owner = view()
        val installer = AndroidEarlyScriptInstaller { _, _, _ -> }
        installer.install(owner, request); installer.install(owner, request)
        assertEquals(2, DocumentStartBackend.added.size)
        assertTrue(installer.hasDocumentStartScripts(owner))
        installer.remove(owner); installer.remove(owner)
        assertTrue(DocumentStartBackend.added.all { it.removes == 1 })
    }
}

private interface WrongDocumentStartProvider {
    fun addDocumentStartJavaScript(source: String, origins: Array<String>)
}

@SuppressLint("RestrictedApi")
private object DocumentStartBackend {
    data class Added(val owner: WebView, val source: String, val origins: Set<String>, val legacy: Boolean,
        var removes: Int = 0, var boundary: InvocationHandler? = null, var removalDeclaringClass: String? = null)
    var stable = false
    var features = arrayOf("DOCUMENT_START_SCRIPT")
    lateinit var loader: ClassLoader
    var factoryReads = 0
    var attempts = 0
    var failAdd = -1
    var onAdd: (Added) -> Unit = {}
    var onRemove: (Added) -> Unit = {}
    val added = mutableListOf<Added>()
    val listeners = mutableMapOf<WebView, MutableMap<String, WebViewCompat.WebMessageListener>>()

    fun reset(legacy: ClassLoader) {
        stable = false; features = arrayOf("DOCUMENT_START_SCRIPT"); loader = legacy
        factoryReads = 0; attempts = 0; failAdd = -1; onAdd = {}; onRemove = {}; added.clear(); listeners.clear()
    }

    fun add(owner: WebView, source: String, origins: Set<String>, legacy: Boolean): Added {
        attempts++
        check(attempts != failAdd) { "provider add failed" }
        return Added(owner, source, origins, legacy).also { added += it; onAdd(it) }
    }

    fun factory(): WebViewProviderFactory = Proxy.newProxyInstance(WebViewProviderFactory::class.java.classLoader,
        arrayOf(WebViewProviderFactory::class.java)) { _, method, args ->
        when (method.name) {
            "getWebViewFeatures" -> features
            "createWebView" -> Proxy.newProxyInstance(WebViewProviderBoundaryInterface::class.java.classLoader,
                arrayOf(WebViewProviderBoundaryInterface::class.java)) { _, operation, values ->
                check(operation.name == "addDocumentStartJavaScript")
                @Suppress("UNCHECKED_CAST")
                val entry = add(args!![0] as WebView, values!![0] as String, (values[1] as Array<String>).toSet(), true)
                val target = Proxy.newProxyInstance(loader,
                    arrayOf(loader.loadClass("org.chromium.support_lib_boundary.ScriptHandlerBoundaryInterface"))) { _, _, _ ->
                    entry.removes++; onRemove(entry); null
                }
                InvocationHandler { _, incoming, arguments ->
                    val declaring = Class.forName(incoming.declaringClass.name, true, loader)
                    val duplicate = declaring.getDeclaredMethod(incoming.name, *incoming.parameterTypes)
                    entry.removalDeclaringClass = duplicate.declaringClass.name
                    duplicate.invoke(target, *(arguments ?: emptyArray()))
                }.also { entry.boundary = it }
            }
            else -> error("Unexpected factory method ${method.name}")
        }
    } as WebViewProviderFactory
}

@Implements(value = WebViewFeature::class, isInAndroidSdk = false)
class DocumentStartFeatures {
    companion object {
        @JvmStatic @Implementation fun isFeatureSupported(feature: String): Boolean =
            feature == WebViewFeature.WEB_MESSAGE_LISTENER || feature == WebViewFeature.DOCUMENT_START_SCRIPT && DocumentStartBackend.stable
    }
}

@Implements(value = WebViewCompat::class, isInAndroidSdk = false)
class DocumentStartPublicApi {
    companion object {
        @JvmStatic @Implementation fun addDocumentStartJavaScript(owner: WebView, source: String, origins: Set<String>): ScriptHandler {
            val entry = DocumentStartBackend.add(owner, source, origins, false)
            return ScriptHandler { entry.removes++; DocumentStartBackend.onRemove(entry) }
        }
        @JvmStatic @Implementation fun addWebMessageListener(owner: WebView, name: String, origins: Set<String>, listener: WebViewCompat.WebMessageListener) {
            DocumentStartBackend.listeners.getOrPut(owner) { mutableMapOf() }[name] = listener
        }
        @JvmStatic @Implementation fun removeWebMessageListener(owner: WebView, name: String) {
            DocumentStartBackend.listeners[owner]?.let { it.remove(name); if (it.isEmpty()) DocumentStartBackend.listeners.remove(owner) }
        }
    }
}

@SuppressLint("RestrictedApi")
@Implements(value = WebViewGlueCommunicator::class, isInAndroidSdk = false)
class DocumentStartFactory {
    companion object {
        @JvmStatic @Implementation fun getFactory(): WebViewProviderFactory {
            DocumentStartBackend.factoryReads++
            return DocumentStartBackend.factory()
        }
        @JvmStatic @Implementation fun getWebViewClassLoader(): ClassLoader = DocumentStartBackend.loader
    }
}

package io.github.gycrosskit.composewebview

import android.annotation.SuppressLint
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.internal.WebViewGlueCommunicator
import java.lang.reflect.InvocationHandler

/** v0 只兼容原 provider 的 boundary 合同，不更改 AndroidX 的 feature 判定。 */
@SuppressLint("RestrictedApi")
internal object AndroidDocumentStartScripts {
    fun interface Registrar {
        fun add(view: WebView, source: String, origins: Set<String>): ScriptHandler
    }

    fun resolve(): Registrar? {
        checkUiThread()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            return Registrar { view, source, origins ->
                checkUiThread()
                val handler = WebViewCompat.addDocumentStartJavaScript(view, source, origins)
                once(handler::remove)
            }
        }
        return try {
            val factory = WebViewGlueCommunicator.getFactory()
            if ("DOCUMENT_START_SCRIPT" !in factory.webViewFeatures) return null
            val loader = WebViewGlueCommunicator.getWebViewClassLoader()
            val provider = loader.loadClass("org.chromium.support_lib_boundary.WebViewProviderBoundaryInterface")
            val handler = loader.loadClass("org.chromium.support_lib_boundary.ScriptHandlerBoundaryInterface")
            val reference = loader.loadClass("org.chromium.support_lib_boundary.ScriptReferenceBoundaryInterface")
            val add = provider.getDeclaredMethod("addDocumentStartJavaScript", String::class.java, Array<String>::class.java)
            val remove = reference.getDeclaredMethod("remove")
            if (!provider.isInterface || !handler.isInterface || !reference.isInterface ||
                add.returnType != InvocationHandler::class.java || remove.returnType != Void.TYPE ||
                !reference.isAssignableFrom(handler) || handler.declaredMethods.any { it.name == "remove" }) return null
            Registrar { view, source, origins ->
                checkUiThread()
                val script = requireNotNull(factory.createWebView(view).addDocumentStartJavaScript(source, origins.toTypedArray()))
                // Chromium v0 按 Method 的 declaringClass 查 getDeclaredMethod；新版 Handler.remove 不兼容。
                once { script.invoke(null, remove, emptyArray()) }
            }
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    private fun once(remove: () -> Unit): ScriptHandler {
        var removed = false
        return ScriptHandler {
            checkUiThread()
            if (!removed) {
                removed = true
                remove()
            }
        }
    }

    fun checkUiThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Document-start scripts require the UI thread" }
    }
}

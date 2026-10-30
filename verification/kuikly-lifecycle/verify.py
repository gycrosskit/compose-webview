"""Run the production KuiklyCompose lifecycle without an SDK or Renderer."""
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory

ROOT = Path(__file__).resolve().parents[2]
CACHE = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle'))) / 'caches/modules-2/files-2.1'
VERSION = '2.2.21-1.0.0'


def jar(group, name, version):
    matches = list((CACHE / group / name / version).rglob(f'{name}-{version}.jar'))
    assert len(matches) == 1, (name, matches)
    return str(matches[0])


compiler = [jar('org.jetbrains.kotlin', name, VERSION) for name in
            ('kotlin-compiler-embeddable', 'kotlin-stdlib', 'kotlin-script-runtime')]
for module in ('org.jetbrains.kotlin/kotlin-reflect', 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm', 'org.jetbrains/annotations'):
    compiler.append(str(next(path for path in (CACHE / module).rglob('*.jar')
                             if not any(suffix in path.name for suffix in ('-sources', '-javadoc', '-all')))))

fixtures = {
    'runtime.kt': '''package androidx.compose.runtime
import kotlin.reflect.KProperty
@Target(AnnotationTarget.FUNCTION) annotation class Composable
class State<T>(var value: T)
operator fun <T> State<T>.getValue(owner: Any?, property: KProperty<*>): T = value
object Runtime {
    val slots = mutableListOf<Any?>()
    var cursor = 0
    val effects = mutableMapOf<Int, Pair<List<Any?>, () -> Unit>>()
    val pending = mutableListOf<() -> Unit>()
    fun compose(block: () -> Unit) { cursor = 0; block(); pending.toList().also { pending.clear() }.forEach { it() } }
    fun dispose() { effects.values.toList().forEach { it.second() }; effects.clear() }
    fun reset() { slots.clear(); effects.clear(); pending.clear(); cursor = 0 }
}
@Suppress("UNCHECKED_CAST") fun <T> remember(vararg keys: Any?, calculation: () -> T): T {
    val index = Runtime.cursor++
    if (index == Runtime.slots.size) Runtime.slots.add(calculation())
    return Runtime.slots[index] as T
}
fun <T> rememberUpdatedState(value: T): State<T> = remember { State(value) }.also { it.value = value }
class DisposableEffectScope { fun onDispose(block: () -> Unit) = block }
fun DisposableEffect(vararg keys: Any?, effect: DisposableEffectScope.() -> (() -> Unit)) {
    val index = Runtime.cursor++
    if (index == Runtime.slots.size) Runtime.slots.add(Unit)
    if (Runtime.effects[index]?.first != keys.toList()) Runtime.pending.add {
        Runtime.effects.remove(index)?.second?.invoke()
        Runtime.effects[index] = keys.toList() to DisposableEffectScope().effect()
    }
}
''',
    'ui.kt': '''package com.tencent.kuikly.compose.ui
open class Modifier { companion object : Modifier() { val Default = this } }
''',
    'node.kt': '''package com.tencent.kuikly.compose.extension
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.core.base.DeclarativeBaseView
object Node {
    var current: Any? = null
    fun reset() { current = null }
}
fun <T : DeclarativeBaseView<*, *>> MakeKuiklyComposeNode(factory: () -> T, modifier: Any,
    viewInit: T.() -> Unit, viewUpdate: (T) -> Unit) {
    val view = factory()
    if (Node.current !== view) { Node.current = view; view.viewInit() }
    viewUpdate(view)
}
''',
    'base.kt': '''package com.tencent.kuikly.core.base
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
object Trace { val values = mutableListOf<String>() }
open class Attr {
    val props = linkedMapOf<String, Any?>()
    infix fun String.with(value: Any?) { props[this] = value; Trace.values.add("$this=$value") }
    fun setProp(key: String, value: Any?) { key with value }
}
open class DeclarativeBaseView<A : Attr, E : com.tencent.kuikly.core.base.event.Event> {
    private val attr by lazy { createAttr() }
    private val event by lazy { createEvent() }
    open fun createAttr(): A = error("attr")
    open fun createEvent(): E = error("event")
    open fun viewName(): String = ""
    fun getViewAttr(): A = attr
    fun getViewEvent(): E = event
    val renderView: Renderer? = Renderer()
    fun performTaskWhenRenderViewDidLoad(block: () -> Unit) = block()
}
class Renderer {
    fun callMethod(method: String, params: String?, callback: (Any?) -> Unit) {
        Trace.values.add(method); callback(JSONObject())
    }
}
open class ViewContainer<A, E> {
    fun <T> addChild(view: T, init: T.() -> Unit): T = view.apply(init)
}
''',
    'event.kt': '''package com.tencent.kuikly.core.base.event
open class Event {
    val handlers = mutableMapOf<String, (Any?) -> Unit>()
    var registrations = 0
    fun register(name: String, handler: (Any?) -> Unit) {
        registrations++; handlers[name] = handler
        com.tencent.kuikly.core.base.Trace.values.add(name)
    }
}
''',
    'json.kt': '''package com.tencent.kuikly.core.nvi.serialization.json
class JSONObject {
    fun put(key: String, value: Any?) = this
    fun opt(key: String): Any? = null
}
''',
    'models.kt': '''package io.github.gycrosskit.composewebview
data class WebViewRequest(val id: String)
class WebViewEvent
object WebViewWire {
    fun encodeRequest(request: WebViewRequest) = request.id
    fun decodeEvent(value: String) = WebViewEvent()
}
''',
    'probe.kt': '''package io.github.gycrosskit.composewebview.kuikly
import androidx.compose.runtime.Runtime
import com.tencent.kuikly.compose.extension.Node
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.core.base.Trace
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import io.github.gycrosskit.composewebview.*

fun main() {
    val request = WebViewRequest("one")
    val changes = mutableListOf<GYWebView?>()
    val controller: (GYWebView?) -> Unit = { changes.add(it) }
    fun reset() { Runtime.reset(); Node.reset(); Trace.values.clear(); changes.clear() }
    reset()
    // Keep positional and trailing-lambda source behavior of the original five-argument API.
    Runtime.compose { AppWebView(request, true, Modifier.Default, {}, controller) }
    check(changes.single() === Node.current)
    Runtime.dispose()
    check(changes.last() == null)
    reset()
    Runtime.compose { AppWebView(request) { changes.add(it) } }
    check(changes.single() === Node.current)
    check(Trace.values.none { it.startsWith("pageOwnerActive=") })
    Runtime.dispose()
    reset()
    var owner = true
    var visible = true
    var pip = true
    var currentRequest = request
    var firstEvents = 0
    var latestEvents = 0
    var event: (WebViewEvent) -> Unit = { firstEvents++ }
    val render = {
        AppWebView(currentRequest, visible, Modifier.Default, event, controller,
            pageOwnerActive = owner, pictureInPicturePageActive = pip,
            onViewUpdate = { it.getViewAttr().setProp("host", "configured") })
    }
    Runtime.compose(render)
    val view = Node.current as GYWebView
    val initial = Trace.values.toList()
    check(initial.indexOf("host=configured") < initial.indexOf("onEvent"))
    check(initial.indexOf("onEvent") < initial.indexOf("request=one"))
    check(initial.indexOf("pageOwnerActive=true") < initial.indexOf("visible=true"))
    check(initial.indexOf("pictureInPicturePageActive=true") < initial.indexOf("request=one"))
    check(initial.count { it == "request=one" } == 1)
    view.getViewEvent().handlers.getValue("onEvent")(JSONObject())
    check(firstEvents == 1)
    event = { latestEvents++ }
    visible = false
    Trace.values.clear()
    Runtime.compose(render)
    check(Trace.values == listOf("host=configured", "pageOwnerActive=true", "pictureInPicturePageActive=true", "visible=false"))
    check(view.getViewEvent().registrations == 1)
    view.getViewEvent().handlers.getValue("onEvent")(JSONObject())
    check(firstEvents == 1 && latestEvents == 1)
    // A picker/background hide retains ownership and PiP; only explicit page leave revokes them.
    owner = false; pip = false
    currentRequest = WebViewRequest("two")
    Trace.values.clear()
    Runtime.compose(render)
    check(Trace.values == listOf("host=configured", "pageOwnerActive=false", "pictureInPicturePageActive=false", "visible=false", "request=two"))
    Trace.values.clear()
    Runtime.dispose()
    check(Trace.values == listOf("pageOwnerActive=false", "pictureInPicturePageActive=false", "visible=false", "stopLoading"))
    view.getViewEvent().handlers.getValue("onEvent")(JSONObject())
    check(latestEvents == 1)
    check(changes.size == 2 && changes.last() == null)
    println("PASS: production AppWebView legacy overloads, typed raw attributes, ownership/PiP ordering, picker hide, request/event deduplication and disposal; runtime/Renderer are fixtures")
}
''',
}
with TemporaryDirectory(prefix='webview-kuikly-lifecycle-') as directory:
    output = Path(directory)
    for name, source in fixtures.items():
        (output / name).write_text(source)
    sources = [str(path) for path in output.glob('*.kt')]
    production = ROOT / 'webview-kuikly/src/commonMain/kotlin/io/github/gycrosskit/composewebview/kuikly'
    sources += [str(production / name) for name in ('GYWebView.kt', 'AppWebView.kt')]
    subprocess.run(['java', '-cp', os.pathsep.join(compiler), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-nowarn', '-no-stdlib', '-no-reflect', '-classpath', compiler[1], '-d', str(output / 'classes'),
                    *sources], check=True)
    subprocess.run(['java', '-cp', str(output / 'classes') + os.pathsep + compiler[1],
                    'io.github.gycrosskit.composewebview.kuikly.ProbeKt'], check=True)

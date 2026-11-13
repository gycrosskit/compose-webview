package io.github.gycrosskit.composewebview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

const val NATIVE_GEOLOCATION_CHANNEL = "NativeGeolocation"

/** 网页偏好交给宿主决策；启用适配不保证原生定位策略等同于浏览器策略。 */
data class NativeGeolocationOptions(
    val enableHighAccuracy: Boolean,
    val maximumAgeMillis: Long,
    val timeoutMillis: Long?,
) {
    init {
        require(maximumAgeMillis in 0..4294967295L)
        require(timeoutMillis == null || timeoutMillis in 0..4294967295L)
    }
}

sealed interface NativeGeolocationRequest {
    val id: String
    data class GetPosition(override val id: String, val options: NativeGeolocationOptions) : NativeGeolocationRequest
    data class Cancel(override val id: String) : NativeGeolocationRequest
    data class Unsupported(val reason: String) : NativeGeolocationRequest { override val id: String = "0" }
}

enum class NativeGeolocationError(val code: Int) { PERMISSION_DENIED(1), POSITION_UNAVAILABLE(2), TIMEOUT(3) }

/** 复用已有文档绑定通道；调用方必须处理请求并回复，默认 WebViewRequest 不接管定位。 */
fun WebViewRequest.withNativeGeolocation(): WebViewRequest {
    val url = content.initialOrigin()
    require(settings.javaScriptEnabled && security.geolocationEnabled &&
        url?.toHttpOrigin()?.scheme == "https" && security.trustedOrigins.isTrusted(url)) {
        "Native geolocation requires a trusted HTTPS document with JavaScript and geolocation enabled"
    }
    require(NATIVE_GEOLOCATION_CHANNEL !in pageMessageChannels && scripts.none { it.id == "native-geolocation" })
    return copy(
        pageMessageChannels = pageMessageChannels + NATIVE_GEOLOCATION_CHANNEL,
        scripts = scripts + WebViewScript("native-geolocation", WEB_VIEW_NATIVE_GEOLOCATION_SCRIPT,
            WebViewScriptInjectionTime.DOCUMENT_START_REQUIRED),
    )
}

/** 仅解析固定的小消息，坐标和原生错误正文不能由网页请求提供。 */
fun parseNativeGeolocationRequest(raw: String): NativeGeolocationRequest? = runCatching {
    if (raw.length > 1024 || raw.encodeToByteArray().size > 1024) return null
    val value = Json.parseToJsonElement(raw) as? JsonObject ?: return null
    val id = (value["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.takeIf { it.matches(Regex("[1-9][0-9]{0,15}")) }
    when (value["action"]?.jsonPrimitive?.content) {
        "get" -> {
            if (id == null || value.keys != setOf("id", "action", "options")) return null
            val options = value["options"] as? JsonObject ?: return null
            if (options.keys != setOf("enableHighAccuracy", "maximumAge", "timeout")) return null
            val high = (options["enableHighAccuracy"] as? JsonPrimitive)?.takeUnless { it.isString }
                ?.booleanOrNull ?: return null
            val age = (options["maximumAge"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                ?.takeIf { it in 0..4294967295L } ?: return null
            val timeout = if (options["timeout"] == JsonNull) null else
                (options["timeout"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                    ?.takeIf { it in 0..4294967295L } ?: return null
            NativeGeolocationRequest.GetPosition(id, NativeGeolocationOptions(high, age, timeout))
        }
        "cancel" -> if (id != null && value.keys == setOf("id", "action")) NativeGeolocationRequest.Cancel(id) else null
        "unsupported" -> {
            val reason = value["reason"]?.jsonPrimitive?.content
            if (value.keys != setOf("id", "action", "reason") || value["id"] != JsonPrimitive("0") ||
                reason !in setOf("DOCUMENT_START_UNAVAILABLE", "METHODS_NOT_REPLACEABLE", "INSECURE_CONTEXT")) return null
            NativeGeolocationRequest.Unsupported(requireNotNull(reason))
        }
        else -> null
    }
}.getOrNull()

/** 采集时间与水平精度必须来自实际定位结果，不用交付时间或固定精度补齐。 */
fun nativeGeolocationPositionReply(id: String, latitude: Double, longitude: Double,
    accuracyMeters: Double, timestampMillis: Long): String {
    require(latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0)
    require(accuracyMeters.isFinite() && accuracyMeters >= 0 && timestampMillis > 0)
    return buildJsonObject {
        put("id", id)
        put("position", buildJsonObject {
            put("timestamp", timestampMillis)
            put("coords", buildJsonObject {
                put("latitude", latitude); put("longitude", longitude); put("accuracy", accuracyMeters)
                put("altitude", JsonNull); put("altitudeAccuracy", JsonNull); put("heading", JsonNull); put("speed", JsonNull)
            })
        })
    }.toString()
}

fun nativeGeolocationErrorReply(id: String, error: NativeGeolocationError, message: String): String = buildJsonObject {
    put("id", id)
    put("error", buildJsonObject { put("code", error.code); put("message", message.take(256)) })
}.toString()

/** 单次通道回复绑定原文档；watch 以可取消的串行单次请求采样，不伪装原生持续订阅。 */
val WEB_VIEW_NATIVE_GEOLOCATION_SCRIPT = """
    (function() {
      if (window !== window.top || window.__GY_NATIVE_GEOLOCATION_INSTALLED__) return;
      var channel = window.NativeGeolocation;
      if (!channel || typeof channel.postMessage !== 'function') return;
      function unsupported(reason) {
        channel.postMessage(JSON.stringify({id: '0', action: 'unsupported', reason: reason}));
      }
      if (document.readyState !== 'loading') { unsupported('DOCUMENT_START_UNAVAILABLE'); return; }
      if (!window.isSecureContext) { unsupported('INSECURE_CONTEXT'); return; }
      var geo = navigator.geolocation;
      if (!geo) { unsupported('METHODS_NOT_REPLACEABLE'); return; }
      var pending = Object.create(null), watches = Object.create(null), nextId = 0, nextWatch = 0, alive = true;
      function deliver(callback, value) {
        if (typeof callback !== 'function') return;
        setTimeout(function() { if (alive) callback(value); }, 0);
      }
      function error(code, message) {
        return {code: code, message: message, PERMISSION_DENIED: 1, POSITION_UNAVAILABLE: 2, TIMEOUT: 3};
      }
      function unsigned(value, fallback) {
        if (value === undefined) return fallback;
        value = Number(value);
        return isNaN(value) || value < 0 ? 0 : Math.min(4294967295, Math.floor(value));
      }
      function options(value) {
        value = value || {};
        return {enableHighAccuracy: !!value.enableHighAccuracy, maximumAge: unsigned(value.maximumAge, 0),
          timeout: value.timeout === undefined || value.timeout === Infinity ? null : unsigned(value.timeout, 0)};
      }
      function validate(success, failure) {
        if (typeof success !== 'function' || (failure != null && typeof failure !== 'function'))
          throw new TypeError('Geolocation callbacks must be functions');
      }
      function cancel(id) {
        if (!id || !pending[id]) return;
        delete pending[id];
        try { channel.postMessage(JSON.stringify({id: id, action: 'cancel'})); } catch (_) {}
      }
      function request(success, failure, settings) {
        var id = String(++nextId);
        if (!alive || Object.keys(pending).length >= 64) {
          deliver(failure, error(2, 'Native location is unavailable')); return null;
        }
        pending[id] = {success: success, failure: failure};
        try { channel.postMessage(JSON.stringify({id: id, action: 'get', options: settings})); }
        catch (_) { delete pending[id]; deliver(failure, error(2, 'Native location is unavailable')); }
        return id;
      }
      function getCurrentPosition(success, failure, settings) {
        validate(success, failure); request(success, failure, options(settings));
      }
      function clearWatch(id) {
        var watch = watches[id];
        if (!watch) return;
        delete watches[id]; clearTimeout(watch.timer); cancel(watch.request);
      }
      function watchPosition(success, failure, settings) {
        validate(success, failure);
        var id = ++nextWatch, watch = {request: null, timer: null, last: null}, configured = options(settings);
        watches[id] = watch;
        function again() {
          if (alive && watches[id] === watch) watch.timer = setTimeout(run, 1000);
        }
        function run() {
          if (!alive || watches[id] !== watch) return;
          watch.request = request(function(position) {
            if (watches[id] !== watch) return;
            watch.request = null;
            var value = JSON.stringify(position);
            if (value !== watch.last) {
              watch.last = value;
              try { success(position); } finally { again(); }
            } else again();
          }, function(problem) {
            if (watches[id] !== watch) return;
            watch.request = null;
            try { if (typeof failure === 'function') failure(problem); }
            finally { if (problem.code === 1) clearWatch(id); else again(); }
          }, configured);
        }
        run(); return id;
      }
      var methods = {getCurrentPosition: getCurrentPosition, watchPosition: watchPosition, clearWatch: clearWatch};
      var previous = Object.create(null), changed = [];
      try {
        Object.keys(methods).forEach(function(name) {
          previous[name] = Object.getOwnPropertyDescriptor(geo, name);
          Object.defineProperty(geo, name, {value: methods[name], configurable: true, writable: true});
          changed.push(name);
          if (geo[name] !== methods[name]) throw new Error('method replacement failed');
        });
      } catch (_) {
        changed.forEach(function(name) {
          try { if (previous[name]) Object.defineProperty(geo, name, previous[name]); else delete geo[name]; } catch (_) {}
        });
        unsupported('METHODS_NOT_REPLACEABLE'); return;
      }
      channel.onmessage = function(event) {
        var value;
        try { value = JSON.parse(event.data); } catch (_) { return; }
        if (!value || typeof value.id !== 'string') return;
        var item = pending[value.id];
        if (!item) return;
        delete pending[value.id];
        if (value.error && [1, 2, 3].indexOf(value.error.code) !== -1) {
          deliver(item.failure, error(value.error.code, String(value.error.message || 'Native location failed'))); return;
        }
        var position = value.position, coords = position && position.coords;
        if (!coords || !Number.isFinite(coords.latitude) || Math.abs(coords.latitude) > 90 ||
            !Number.isFinite(coords.longitude) || Math.abs(coords.longitude) > 180 ||
            !Number.isFinite(coords.accuracy) || coords.accuracy < 0 ||
            !Number.isFinite(position.timestamp) || position.timestamp <= 0) {
          deliver(item.failure, error(2, 'Invalid native location')); return;
        }
        deliver(item.success, position);
      };
      window.addEventListener('pagehide', function() {
        alive = false;
        Object.keys(watches).forEach(clearWatch);
        Object.keys(pending).forEach(cancel);
      });
      window.__GY_NATIVE_GEOLOCATION_INSTALLED__ = true;
    })();
""".trimIndent()

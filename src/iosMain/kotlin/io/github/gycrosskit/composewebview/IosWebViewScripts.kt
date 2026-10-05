package io.github.gycrosskit.composewebview

/** `JSAndroidBridge` 是既有 H5 协议名；iOS 也必须保留这个兼容入口。 */
internal const val APP_BRIDGE_HANDLER = "JSAndroidBridge"
internal const val WEB_EVENT_HANDLER = "ComposeWebViewEvent"
internal const val FILE_CHOOSER_ALLOWED_FLAG = "__COMPOSE_WEBVIEW_FILE_CHOOSER_ALLOWED__"
internal const val WEB_EVENT_FULLSCREEN_ENTER = "fullscreen:1"
internal const val WEB_EVENT_FULLSCREEN_EXIT = "fullscreen:0"

/** 旧 iOS 的交互兼容限制，不是安全边界；受控上传必须由 iOS18.4+ WKUIDelegate 决定。 */
internal val IOS_FILE_CHOOSER_GATE_SCRIPT = """
    (function() {
      window.$FILE_CHOOSER_ALLOWED_FLAG = false;
      function isFileInput(value) {
        return value instanceof HTMLInputElement && value.type === 'file';
      }
      document.addEventListener('click', function(event) {
        if (!isFileInput(event.target) || window.$FILE_CHOOSER_ALLOWED_FLAG === true) return;
        event.preventDefault();
        event.stopImmediatePropagation();
      }, true);
      var originalClick = HTMLInputElement.prototype.click;
      HTMLInputElement.prototype.click = function() {
        if (isFileInput(this) && window.$FILE_CHOOSER_ALLOWED_FLAG !== true) return;
        return originalClick.call(this);
      };
    })();
""".trimIndent()

/** 仅提供 accept/capture UI 提示；来源、能力、可见性和文档代次仍由原生校验。 */
internal const val IOS_FILE_INPUT_SCRIPT = """
JSON.stringify((function(){var input=document.activeElement;return input&&input.tagName==='INPUT'&&input.type==='file'?{accept:input.accept||'',capture:input.hasAttribute('capture')}:{};})())
"""

/** 仅把既有 Android Bridge 兼容协议转发给 WKScriptMessageHandler，不在脚本中解释业务消息。 */
internal const val IOS_BRIDGE_SCRIPT = """
    (function() {
      window.GYWebViewBridge = {
        postMessage: function(handlerName, data) {
          var value = (data === undefined || data === null) ? '{}' :
            (typeof data === 'object' ? JSON.stringify(data) : String(data));
          window.webkit.messageHandlers.JSAndroidBridge.postMessage(String(handlerName) + '\u001F' + value);
        }
      };
      window.JSAndroidBridge = {
        handleJSBridgeMessage: function(handlerName, data) {
          window.webkit.messageHandlers.JSAndroidBridge.postMessage(
            String(handlerName) + '\u001F' + String(data == null ? '{}' : data)
          );
        }
      };
      if (typeof WebViewJavascriptBridge === 'undefined') {
        window.WebViewJavascriptBridge = {
          callHandler: function(handlerName, data) {
            var value = (typeof data === 'object') ? JSON.stringify(data) : String(data == null ? '{}' : data);
            window.JSAndroidBridge.handleJSBridgeMessage(handlerName, value);
          },
          registerHandler: function(handlerName, callback) {}
        };
      }
    })();
"""

/** WebKit 没有直接暴露导航手势标志时，只在短时间窗口内把真实页面事件视为用户手势。 */
internal const val IOS_HAS_RECENT_USER_GESTURE_SCRIPT =
    "Boolean(window.__COMPOSE_WEBVIEW_LAST_USER_GESTURE__ && Date.now() - window.__COMPOSE_WEBVIEW_LAST_USER_GESTURE__ < 1500)"

/** 统一采集用户手势与 H5 自定义全屏事件，不携带页面正文或业务数据。 */
internal val IOS_WEB_EVENT_SCRIPT = """
    (function() {
      window.__COMPOSE_WEBVIEW_LAST_USER_GESTURE__ = 0;
      function markGesture() { window.__COMPOSE_WEBVIEW_LAST_USER_GESTURE__ = Date.now(); }
      document.addEventListener('pointerdown', markGesture, true);
      document.addEventListener('touchstart', markGesture, true);
      document.addEventListener('click', markGesture, true);
      function publishFullscreen(value) {
        window.webkit.messageHandlers.$WEB_EVENT_HANDLER.postMessage(value ? '$WEB_EVENT_FULLSCREEN_ENTER' : '$WEB_EVENT_FULLSCREEN_EXIT');
      }
      document.addEventListener('fullscreenchange', function() {
        publishFullscreen(Boolean(document.fullscreenElement || document.webkitFullscreenElement));
      }, true);
      document.addEventListener('webkitbeginfullscreen', function(event) {
        window.__GY_WEBVIEW_FULLSCREEN_VIDEO__ = event.target;
        publishFullscreen(true);
      }, true);
      document.addEventListener('webkitendfullscreen', function() {
        window.__GY_WEBVIEW_FULLSCREEN_VIDEO__ = null;
        publishFullscreen(false);
      }, true);
    })();
""".trimIndent()

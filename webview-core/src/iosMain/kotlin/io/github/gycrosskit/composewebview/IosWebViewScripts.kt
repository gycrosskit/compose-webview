package io.github.gycrosskit.composewebview

/** `JSAndroidBridge` 是既有 H5 协议名；iOS 也必须保留这个兼容入口。 */
internal const val APP_BRIDGE_HANDLER = "JSAndroidBridge"
internal const val WEB_EVENT_HANDLER = "ComposeWebViewEvent"
internal const val PAGE_MESSAGE_HANDLER = "ComposeWebViewPageMessage"
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

/** WebKit 的原生视频与 DOM 全屏均要等待退出后的实际状态，不把脚本发出当作成功。 */
internal const val IOS_EXIT_FULLSCREEN_SCRIPT = """
const video=window.__GY_WEBVIEW_FULLSCREEN_VIDEO__;
const full=()=>Boolean(document.fullscreenElement||document.webkitFullscreenElement);
let exit, stillFull;
if(full()){
  if(document.exitFullscreen) exit=()=>document.exitFullscreen();
  else if(document.webkitExitFullscreen) exit=()=>document.webkitExitFullscreen();
  else return false;
  stillFull=full;
}else if(video&&video.webkitDisplayingFullscreen&&video.webkitExitFullscreen){
  exit=()=>video.webkitExitFullscreen();
  stillFull=()=>Boolean(video.webkitDisplayingFullscreen);
}else return false;
return await new Promise(resolve=>{
  let settled=false, timer;
  const events=['fullscreenchange','webkitfullscreenchange'];
  const videoEvents=['webkitendfullscreen','webkitpresentationmodechanged'];
  function finish(consumed){
    if(settled) return;
    settled=true;
    clearTimeout(timer);
    events.forEach(name=>document.removeEventListener(name,check,true));
    if(video) videoEvents.forEach(name=>video.removeEventListener(name,check,true));
    resolve(consumed);
  }
  function check(){if(!stillFull()) finish(true);}
  events.forEach(name=>document.addEventListener(name,check,true));
  if(video) videoEvents.forEach(name=>video.addEventListener(name,check,true));
  timer=setTimeout(()=>finish(!stillFull()),1500);
  try{
    const result=exit();
    if(result&&typeof result.then==='function') result.then(check,()=>finish(false));
    check();
  }catch(error){finish(false);}
});
"""

/** Author viewport limits are public WebKit policy; this is a display setting, not a security boundary. */
internal val IOS_DISABLE_ZOOM_SCRIPT = """
    (function() {
      function apply() {
        if (!document.head) return;
        var metas = document.querySelectorAll('meta[name="viewport" i]');
        if (!metas.length) {
          var meta = document.createElement('meta');
          meta.name = 'viewport';
          document.head.appendChild(meta);
          metas = [meta];
        }
        for (var i = 0; i < metas.length; i++) {
          var parts = (metas[i].content || '').split(/[;,]/).filter(function(part) {
            return !/^\s*user-scalable\s*=/i.test(part) && part.trim();
          });
          parts.push('user-scalable=no');
          var value = parts.join(',');
          if (metas[i].content !== value) metas[i].content = value;
        }
      }
      new MutationObserver(apply).observe(document, {subtree:true, childList:true, attributes:true, attributeFilter:['content','name']});
      document.addEventListener('DOMContentLoaded', apply, {once:true});
      apply();
    })();
""".trimIndent()

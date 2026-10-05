import io.github.gycrosskit.composewebview.*;
import java.util.*;

/** 调用已编译的生产 generator，Node 仅模拟 DOM/来源并执行真实返回的脚本。 */
public final class ExportScript {
    public static void main(String[] args) {
        WebViewTrustPolicy trust = new WebViewTrustPolicy(List.of(), Set.of("trusted.test"));
        if (!trust.isTrusted("https://sub.trusted.test:65535/path") || trust.isTrusted("https://trusted.test.."))
            throw new AssertionError("Native suffix port/DNS contract changed");
        WebViewSettings settings = new WebViewSettings(true, true, false, false,
            WebViewMixedContentPolicy.NEVER_ALLOW, WebViewCachePolicy.DEFAULT,
            false, false, false, true, true, false, true, false, true, true, true, true, 100, 200, true, null);
        WebViewRequest request = new WebViewRequest(new WebViewContent.Url("https://trusted.test", Map.of()),
            settings, new WebViewSecurity(trust, false, false, false, false),
            List.of(new WebViewScript("port-guard", "window.hits = (window.hits || 0) + 1;", WebViewScriptInjectionTime.DOCUMENT_START, true)),
            List.of(), new WebViewNavigationPolicy());
        if (!WebViewEarlyScriptsKt.earlyScriptOriginRules(request).equals(Set.of("*")))
            throw new AssertionError("AndroidX rules cannot express wildcard ports");
        System.out.print(WebViewEarlyScriptsKt.earlyScriptSource(request));
    }
}

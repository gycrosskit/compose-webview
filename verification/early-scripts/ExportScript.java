import io.github.gycrosskit.composewebview.*;
import java.util.*;

/** 调用已编译的生产 generator，Node 仅模拟 DOM/来源并执行真实返回的脚本。 */
public final class ExportScript {
    public static void main(String[] args) {
        boolean page = args.length > 0 && args[0].equals("page");
        WebViewTrustPolicy trust = new WebViewTrustPolicy(List.of(), page ? Set.of() : Set.of("trusted.test"));
        if (!page && (!trust.isTrusted("https://sub.trusted.test:65535/path") || trust.isTrusted("https://trusted.test..")))
            throw new AssertionError("Native suffix port/DNS contract changed");
        WebViewSettings settings = new WebViewSettings(true, true, false, false,
            WebViewMixedContentPolicy.NEVER_ALLOW, WebViewCachePolicy.DEFAULT,
            false, false, false, true, true, false, true, false, true, true, true, true, 100, 200, true, null);
        WebViewRequest request = new WebViewRequest(new WebViewContent.Url(page ? "http://legacy.test/captcha" : "https://trusted.test", Map.of()),
            settings, new WebViewSecurity(trust, false, page, false, false),
            List.of(new WebViewScript("port-guard", "window.hits = (window.hits || 0) + 1;", WebViewScriptInjectionTime.DOCUMENT_START, true)),
            List.of(), new WebViewNavigationPolicy());
        Set<String> expected = page ? Set.of("http://legacy.test:80", "http://legacy.test.:80") : Set.of("*");
        if (!WebViewEarlyScriptsKt.earlyScriptOriginRules(request).equals(expected))
            throw new AssertionError("AndroidX source registration changed");
        System.out.print(WebViewEarlyScriptsKt.earlyScriptSource(request));
    }
}

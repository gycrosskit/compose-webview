#import "../Sources/GYWebView.m"
#import <objc/runtime.h>

static void Require(BOOL condition, NSString *message) {
    if (!condition) { fprintf(stderr, "FAIL: %s\n", message.UTF8String); exit(1); }
}

static void RequireBoolean(id value, BOOL expected, NSString *field) {
    Require(value && CFGetTypeID((__bridge CFTypeRef)value) == CFBooleanGetTypeID(),
            [NSString stringWithFormat:@"%@ must be a JSON Boolean, not numeric 0/1", field]);
    Require([value boolValue] == expected, field);
}

static NSDictionary *JSONRoundTrip(NSDictionary *value) {
    NSError *error;
    NSData *data = [NSJSONSerialization dataWithJSONObject:value options:0 error:&error];
    Require(data != nil && error == nil, @"JSON serialization failed");
    return [NSJSONSerialization JSONObjectWithData:data options:0 error:nil];
}

@interface GYWebView (WireCheck)
- (BOOL)allows:(NSString *)url mainFrame:(BOOL)mainFrame newWindow:(BOOL)newWindow gesture:(BOOL)gesture;
- (void)installResourceRulesAndLoad;
- (BOOL)validRequest:(NSDictionary *)request;
@end

@interface RuleProbe : GYWebView
@property(nonatomic) BOOL loaded;
@end
@implementation RuleProbe
- (void)loadContent { self.loaded = YES; }
@end

@interface UploadWebView : WKWebView
@end
@implementation UploadWebView
- (NSURL *)URL { return [NSURL URLWithString:@"https://safe.example/page"]; }
@end

@interface StopProbeWebView : UploadWebView
@property(nonatomic) NSUInteger stops;
@property(nonatomic, strong) NSMutableArray *evaluations;
@end
@implementation StopProbeWebView
- (void)stopLoading { self.stops++; }
- (void)evaluateJavaScript:(NSString *)script completionHandler:(void (^)(id, NSError *))callback {
    if (callback) { if (!self.evaluations) self.evaluations = [NSMutableArray array]; [self.evaluations addObject:[callback copy]]; }
}
@end
static void CheckStopLoadingCancellation(void) {
    for (NSNumber *replaceOwner in @[@NO, @YES]) {
        GYWebView *page = [[GYWebView alloc] initWithFrame:CGRectMake(0, 0, 100, 100)];
        StopProbeWebView *old = [[StopProbeWebView alloc] initWithFrame:page.bounds configuration:[WKWebViewConfiguration new]];
        StopProbeWebView *next = [[StopProbeWebView alloc] initWithFrame:page.bounds configuration:[WKWebViewConfiguration new]];
        page.webView = old;
        page.request = @{ @"content": @{ @"type": @"url", @"url": @"https://safe.example/page" },
            @"settings": @{ @"javaScriptEnabled": @YES },
            @"security": @{ @"trustedOrigins": @{ @"urls": @[@"https://safe.example"] }, @"fileChooserEnabled": @YES }, @"scripts": @[] };
        __block NSUInteger javascriptReplies = 0, files = 0, newFiles = 0;
        [page hrv_callWithMethod:@"evaluateJavascript" params:@"{\"script\":\"old()\"}" callback:^(id result) { javascriptReplies++; }];
        void (^oldJavascript)(id, NSError *) = old.evaluations.lastObject;
        NSURL *oldFile = [NSURL fileURLWithPath:[NSTemporaryDirectory() stringByAppendingPathComponent:NSUUID.UUID.UUIDString]];
        NSURL *newFile = [NSURL fileURLWithPath:[NSTemporaryDirectory() stringByAppendingPathComponent:NSUUID.UUID.UUIDString]];
        [@"old" writeToURL:oldFile atomically:YES encoding:NSUTF8StringEncoding error:nil];
        [@"new" writeToURL:newFile atomically:YES encoding:NSUTF8StringEncoding error:nil];
        page.temporaryFiles = [NSMutableArray arrayWithObject:oldFile];
        page.filePickerCompletion = ^(NSArray<NSURL *> *urls) {
            Require(urls == nil, @"stop must cancel pending files"); files++;
            if (replaceOwner.boolValue) page.webView = next;
            page.temporaryFiles = [NSMutableArray arrayWithObject:newFile];
            page.filePickerCompletion = ^(NSArray<NSURL *> *value) { newFiles++; };
        };
        [page hrv_callWithMethod:@"stopLoading" params:nil callback:nil];
        Require(files == 1, @"stopLoading must settle old file capability once");
        oldJavascript(@"old", nil);
        Require(javascriptReplies == 0, @"stopLoading must reject late JS result");
        Require(![NSFileManager.defaultManager fileExistsAtPath:oldFile.path] && [NSFileManager.defaultManager fileExistsAtPath:newFile.path], @"old cleanup deleted reentrant new file");
        Require(newFiles == 0, @"old stop revoked reentrant request");
        Require(next.stops == 0, @"old stop reached reentrant new owner");
        WKWebView *current = page.webView;
        [page hrv_callWithMethod:@"evaluateJavascript" params:@"{\"script\":\"fresh()\"}" callback:^(id result) { javascriptReplies++; }];
        void (^freshJavascript)(id, NSError *) = ((StopProbeWebView *)current).evaluations.lastObject;
        freshJavascript(@"fresh", nil);
        Require(javascriptReplies == 1, @"same owner must accept a fresh JS operation after stop");
        [page hrv_callWithMethod:@"stopLoading" params:nil callback:nil];
        Require(files == 1 && newFiles == 1, @"later stop must settle only the fresh file request");
        // 本 fixture 手工注入 probe，不经过 createWebView 的 KVO 注册。
        [old.evaluations removeAllObjects]; [next.evaluations removeAllObjects];
        page.webView = nil;
        [page hrv_removeFromSuperview];
        [NSFileManager.defaultManager removeItemAtURL:newFile error:nil];
    }
}

// 捕获生产代码传给 WebKit 的真实 JSON，随后继续调用系统编译器。
static IMP originalRuleCompiler;
static NSDictionary *lastTrigger;
static void CompileRules(id store, SEL selector, NSString *identifier, NSString *json,
                         void (^completion)(WKContentRuleList *, NSError *)) {
    NSArray *rules = [NSJSONSerialization JSONObjectWithData:[json dataUsingEncoding:NSUTF8StringEncoding] options:0 error:nil];
    lastTrigger = [rules firstObject][@"trigger"];
    ((void (*)(id, SEL, NSString *, NSString *, void (^)(WKContentRuleList *, NSError *)))originalRuleCompiler)(
        store, selector, identifier, json, completion);
}

static void WaitUntil(BOOL (^ready)(void), NSString *message) {
    NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:20];
    while (!ready() && deadline.timeIntervalSinceNow > 0)
        [NSRunLoop.mainRunLoop runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
    Require(ready(), message);
}
static id Evaluate(WKWebView *owner, NSString *source) {
    __block BOOL done = NO; __block id result; __block NSError *failure;
    [owner evaluateJavaScript:source completionHandler:^(id value, NSError *error) { result = value; failure = error; done = YES; }];
    WaitUntil(^BOOL { return done; }, @"Real WebKit evaluation timed out");
    Require(!failure, [NSString stringWithFormat:@"Real WebKit evaluation failed: %@", failure]);
    return result;
}
static NSString *RequestJSON(NSDictionary *request) {
    return [[NSString alloc] initWithData:[NSJSONSerialization dataWithJSONObject:request options:0 error:nil] encoding:NSUTF8StringEncoding];
}
static void CheckNativeCommandCancellation(NSDictionary *request, NSString *otherURL) {
    NSArray *modes = getenv("WEBVIEW_STOP_ONLY") ? @[@"stopLoading", @"reentrantStop"] : @[@"hide", @"request", @"release", @"reload", @"stopLoading", @"reentrantHide", @"reentrantStop"];
    for (NSString *mode in modes) {
        GYWebView *view = [[GYWebView alloc] initWithFrame:CGRectMake(0, 0, 200, 200)];
        [view hrv_setPropWithKey:@"request" propValue:RequestJSON(request)];
        WKWebView *old = view.webView;
        NSString *initialURL = request[@"content"][@"url"];
        WaitUntil(^BOOL { return !old.loading && [old.URL.absoluteString isEqual:initialURL]; }, @"Cancellation fixture initial page failed");
        [old loadRequest:[NSURLRequest requestWithURL:[NSURL URLWithString:otherURL]]];
        WaitUntil(^BOOL { return !old.loading && [old.URL.absoluteString isEqual:otherURL]; }, @"Cancellation fixture history page failed");
        Require(old.canGoBack, @"Cancellation fixture has no backward history");
        // 控制真实 WK JS Promise 的完成时机；此用例验证回执生命周期，不声称发生真实全屏 UI。
        Evaluate(old, @"window.__nativeFullscreen=true;window.__nativeExitEntered=false;window.__nativeExitPromise=new Promise(function(resolve,reject){window.__finishNativeExit=function(success){window.__nativeFullscreen=false;success?resolve():reject(new Error('late failure'));};});Object.defineProperty(document,'fullscreenElement',{configurable:true,get:function(){return window.__nativeFullscreen?document.body:null;}});document.exitFullscreen=function(){window.__nativeExitEntered=true;return window.__nativeExitPromise;};true");
        view.fullscreen = YES;
        NSMutableDictionary *replacement = [request mutableCopy];
        replacement[@"content"] = @{@"type": @"html", @"html": @"<html><body>replacement</body></html>", @"baseUrl": @"https://safe.example/replacement"};
        __block NSUInteger count = 0;
        [view hrv_callWithMethod:@"goBack" params:nil callback:^(id result) {
            count++; RequireBoolean(result[@"result"], NO, @"Cancelled native command result");
            if ([mode isEqual:@"reentrantHide"] || [mode isEqual:@"reentrantStop"]) [view hrv_setPropWithKey:@"request" propValue:RequestJSON(replacement)];
        }];
        Require([Evaluate(old, @"window.__nativeExitEntered === true") boolValue], @"Production fullscreen script did not await real WebKit Promise");
        if ([mode isEqual:@"hide"] || [mode isEqual:@"reentrantHide"]) [view hrv_setPropWithKey:@"visible" propValue:@NO];
        else if ([mode isEqual:@"request"]) [view hrv_setPropWithKey:@"request" propValue:RequestJSON(replacement)];
        else if ([mode isEqual:@"reload"]) [view hrv_callWithMethod:@"reload" params:nil callback:nil];
        else if ([mode isEqual:@"stopLoading"] || [mode isEqual:@"reentrantStop"]) [view hrv_callWithMethod:@"stopLoading" params:nil callback:nil];
        else [view hrv_removeFromSuperview];
        Require(count == 1, @"Owner invalidation must synchronously deliver one false terminal result");
        WKWebView *current = view.webView;
        NSString *token = view.documentToken;
        NSUInteger generation = view.callbackGeneration;
        if (current && current != old) WaitUntil(^BOOL { return !current.loading && current.URL != nil; }, @"Replacement owner did not finish loading");
        // pageStarted 对新 owner 合法推进 generation，在晚完成前记录最终新状态。
        token = view.documentToken; generation = view.callbackGeneration;
        NSString *currentURL = current.URL.absoluteString;
        Evaluate(old, [mode isEqual:@"hide"] ? @"window.__finishNativeExit(true);true" : @"window.__finishNativeExit(false);true");
        NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:0.5];
        while (deadline.timeIntervalSinceNow > 0) [NSRunLoop.mainRunLoop runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
        Require(count == 1 && [old.URL.absoluteString isEqual:otherURL], @"Late cancelled fullscreen callback repeated result or navigated old history");
        Require(view.webView == current && view.callbackGeneration == generation && (!token || [token isEqual:view.documentToken]) && (!currentURL || [currentURL isEqual:current.URL.absoluteString]), @"Late cancelled command changed current owner state");
        [view hrv_removeFromSuperview];
    }
}
static void CheckNavigationOwnerReplacement(NSDictionary *request) {
    for (NSString *mode in @[@"create", @"delegate"]) {
        GYWebView *view = [[GYWebView alloc] initWithFrame:CGRectMake(0, 0, 200, 200)];
        NSMutableDictionary *replacement = [request mutableCopy];
        replacement[@"content"] = @{@"type": @"html", @"html": @"<html><body>replacement</body></html>", @"baseUrl": @"https://safe.example/reentrant"};
        __block BOOL replaced = NO; __block NSUInteger messages = 0;
        __weak GYWebView *weakView = view;
        [view hrv_setPropWithKey:@"onEvent" propValue:^(id event) {
            if ([event[@"type"] isEqual:@"pageMessage"]) messages++;
            if (!replaced && [event[@"type"] isEqual:@"navigation"] && ([mode isEqual:@"create"] || weakView.webView)) {
                replaced = YES;
                [weakView hrv_setPropWithKey:@"request" propValue:RequestJSON(replacement)];
            }
        }];
        [view hrv_setPropWithKey:@"request" propValue:RequestJSON(request)];
        WaitUntil(^BOOL { return replaced && messages > 0 && !view.webView.loading; }, @"Reentrant native navigation event revoked or replaced the new owner's channel");
        Require([view.webView.URL.absoluteString isEqual:@"https://safe.example/reentrant"] && !view.pageMessageRevoked && messages == 1, @"Old navigation continued against the reentrant owner");
        [view hrv_removeFromSuperview];
    }
}

static void CheckHtmlContent(void) {
    for (NSDictionary *fixture in @[@{@"encoding": @"UTF-8", @"html": @"中文 <script>window.mimeExecuted=true</script>"},
            @{@"encoding": @"UTF-8", @"mime": @"text/html", @"html": @"中文 <script>window.mimeExecuted=true</script>"},
            @{@"encoding": @"ISO-8859-1", @"html": @"Café <b>literal</b>"},
            @{@"encoding": @"unknown-fixture-charset", @"html": @"text", @"error": @YES},
            @{@"encoding": @"US-ASCII", @"html": @"中文", @"error": @YES},
            @{@"encoding": @"UTF-8", @"html": @" \n", @"error": @YES, @"errorKind": @"EMPTY_CONTENT"},
            @{@"encoding": @"UTF-8", @"html": @"same history", @"base": @"https://safe.example/page", @"history": @"https://safe.example/page"},
            @{@"encoding": @"UTF-8", @"html": @"different history", @"base": @"https://safe.example/page", @"history": @"https://safe.example/other", @"error": @YES},
            @{@"encoding": @"UTF-8", @"html": @"foreign history", @"base": @"https://safe.example/page", @"history": @"https://foreign.example/", @"error": @YES}]) {
        GYWebView *view = [[GYWebView alloc] initWithFrame:CGRectMake(0, 0, 100, 100)];
        [UIApplication.sharedApplication.keyWindow.rootViewController.view addSubview:view];
        __block BOOL finished = NO, failed = NO;
        [view hrv_setPropWithKey:@"onEvent" propValue:^(id event) {
            if ([event[@"type"] isEqual:@"pageFinished"]) finished = YES;
            if ([event[@"type"] isEqual:@"loadFailed"] && [event[@"kind"] isEqual:fixture[@"errorKind"] ?: @"LOAD_EXCEPTION"]) failed = YES;
        }];
        [view hrv_setPropWithKey:@"request" propValue:RequestJSON(@{@"content": @{@"type": @"html", @"html": fixture[@"html"], @"baseUrl": fixture[@"base"] ?: NSNull.null, @"historyUrl": fixture[@"history"] ?: NSNull.null, @"mimeType": fixture[@"mime"] ?: @"text/plain", @"encoding": fixture[@"encoding"]}, @"settings": @{@"javaScriptEnabled": @YES}})];
        WaitUntil(^BOOL { return finished || failed; }, @"HTML MIME fixture never finished");
        Require(failed == [fixture[@"error"] boolValue], @"Invalid or lossy charset was falsely accepted");
        if (!failed) {
            BOOL html = [fixture[@"mime"] isEqual:@"text/html"];
            Require([Evaluate(view.webView, @"document.contentType") isEqual:html ? @"text/html" : @"text/plain"], @"Declared HTML MIME was ignored");
            Require([Evaluate(view.webView, @"document.body.innerText") containsString:html ? @"中文" : fixture[@"html"]], @"Declared charset changed content");
            Require([Evaluate(view.webView, @"window.mimeExecuted === true") boolValue] == html, @"MIME script execution policy changed");
        }
        [view hrv_removeFromSuperview];
    }
}

static void CheckFullscreenWithoutContentJavaScript(void) {
    NSString *initial = NSProcessInfo.processInfo.environment[@"WEBVIEW_WIRE_PAGE_URL"];
    NSString *other = [[[NSURL URLWithString:initial] URLByDeletingLastPathComponent].absoluteString stringByAppendingString:@"other"];
    NSString *movie = [[NSData dataWithContentsOfURL:[NSBundle.mainBundle URLForResource:@"capture" withExtension:@"mov"]] base64EncodedStringWithOptions:0];
    Require(initial.length && movie.length, @"Fullscreen fixture requires page URL and movie");
    GYWebView *view = [[GYWebView alloc] initWithFrame:UIScreen.mainScreen.bounds];
    [UIApplication.sharedApplication.keyWindow.rootViewController.view addSubview:view];
    __block BOOL fullscreen = NO;
    [view hrv_setPropWithKey:@"onEvent" propValue:^(id event) { if ([event[@"type"] isEqual:@"fullscreenChanged"]) fullscreen = [event[@"isFullscreen"] boolValue]; }];
    [view hrv_setPropWithKey:@"request" propValue:RequestJSON(@{@"content": @{@"type": @"url", @"url": initial}, @"settings": @{@"javaScriptEnabled": @NO}})];
    WKWebView *owner = view.webView;
    WaitUntil(^BOOL { return !owner.loading && [owner.URL.absoluteString isEqual:initial]; }, @"Fullscreen initial document failed");
    [owner loadRequest:[NSURLRequest requestWithURL:[NSURL URLWithString:other]]];
    WaitUntil(^BOOL { return !owner.loading && [owner.URL.absoluteString isEqual:other]; }, @"Fullscreen history document failed");
    Require(owner.canGoBack, @"Fullscreen fixture did not create backward history");
    Require(![Evaluate(owner, @"window.contentScriptExecuted === true") boolValue], @"Disabled page JavaScript executed");
    Evaluate(owner, [NSString stringWithFormat:@"var video=document.createElement('video');video.controls=true;video.src='data:video/quicktime;base64,%@';document.body.appendChild(video);true", movie]);
    WaitUntil(^BOOL { return [Evaluate(owner, @"video.readyState >= 1") boolValue]; }, @"Native video metadata unavailable");
    for (NSString *command in @[@"exitFullscreen", @"goBack"]) {
        Evaluate(owner, @"video.webkitEnterFullscreen();true");
        WaitUntil(^BOOL { return [Evaluate(owner, @"video.webkitDisplayingFullscreen") boolValue]; }, @"Real native video did not enter fullscreen");
        WaitUntil(^BOOL { return fullscreen; }, @"JS-disabled native fullscreen event was not observed");
        // WebKit 的进入事件早于 UIKit 呈现动画完成，此后才模拟宿主返回操作。
        [NSRunLoop.mainRunLoop runUntilDate:[NSDate dateWithTimeIntervalSinceNow:1.0]];
        __block NSInteger replies = 0; __block BOOL consumed = NO;
        [view hrv_callWithMethod:command params:nil callback:^(id value) { replies++; consumed = [value[@"result"] boolValue]; }];
        WaitUntil(^BOOL { return replies == 1 && !fullscreen; }, @"Native fullscreen exit did not settle");
        Require(consumed && ![Evaluate(owner, @"video.webkitDisplayingFullscreen") boolValue], @"Fullscreen command did not exit real video");
        Require([owner.URL.absoluteString isEqual:other] && owner.canGoBack, @"Fullscreen command consumed backward history");
        [NSRunLoop.mainRunLoop runUntilDate:[NSDate dateWithTimeIntervalSinceNow:1.0]];
    }
    [view hrv_removeFromSuperview];
    puts("PASS: Native JS-disabled real video fullscreen events, exitFullscreen and goBack preserve history; page script blocked");
}

static void Check(void) {
    CheckStopLoadingCancellation();
    if (getenv("WEBVIEW_STOP_ONLY")) {
        NSString *url = NSProcessInfo.processInfo.environment[@"WEBVIEW_WIRE_PAGE_URL"];
        Require(url.length > 0, @"Stop fixture requires loopback page URL");
        NSString *other = [[[NSURL URLWithString:url] URLByDeletingLastPathComponent].absoluteString stringByAppendingString:@"other"];
        CheckNativeCommandCancellation(@{@"content": @{@"type": @"url", @"url": url}, @"settings": @{@"javaScriptEnabled": @YES}}, other);
        printf("PASS: Native stop JS/file cancellation, reentry and fresh operations; real WK Promise terminal once\n"); fflush(stdout); exit(0);
    }
    CheckFullscreenWithoutContentJavaScript();
    CheckHtmlContent();
    for (NSArray *pair in @[@[@"2001:0db8:0000:0:0:0:0:1", @"2001:db8::1"], @[@"::ffff:192.0.2.1", @"::ffff:c000:201"], @[@"0:0:0:0:0:0:0:0", @"::"], @[@"1:0:0:2:0:0:3:4", @"1::2:0:0:3:4"]]) {
        NSString *raw = [NSString stringWithFormat:@"https://[%@]:8443", pair[0]];
        NSString *canonical = [NSString stringWithFormat:@"https://[%@]:8443/next", pair[1]];
        Require([GYOrigin(raw) isEqual:GYOrigin(canonical)], @"IPv6 equivalent origin failed");
    }
    for (NSString *host in @[@"1::2::3", @"1:2:3", @"::ffff:192.00.2.1", @"::ffff:256.0.0.1"])
        Require(!GYOrigin([NSString stringWithFormat:@"https://[%@]", host]), [NSString stringWithFormat:@"Invalid IPv6 trusted: %@", host]);
    NSDictionary *mall = @{@"type": @"hostSuffix", @"suffix": @"jd.com", @"scheme": @"https", @"includeRoot": @NO, @"rejectUserInfo": @YES};
    Require(GYRuleMatches(mall, @"https://shop.jd.com/item"), @"HTTPS subdomain combination failed");
    for (NSString *value in @[@"http://shop.jd.com/item", @"https://jd.com/item", @"https://shop.jd.com.evil/item", @"https://shop.jd.com..", @"https://shop..jd.com", @"https://user@shop.jd.com", @"https://@shop.jd.com", @"https://:@shop.jd.com"])
        Require(!GYRuleMatches(mall, value), @"Mall rule widened scheme/root/domain boundary");
    Require(!GYOrigin(@"https://trusted.example..") && !GYOrigin(@"https://sub..trusted.example") && !GYOrigin(@"https://[::1]."), @"Empty DNS label origin accepted");
    for (NSNumber *enabled in @[@NO, @YES]) for (NSNumber *javascript in @[@NO, @YES]) {
        GYWebView *zoom = [[GYWebView alloc] initWithFrame:CGRectMake(0, 0, 100, 100)];
        [UIApplication.sharedApplication.keyWindow.rootViewController.view addSubview:zoom];
        __block BOOL zoomFinished = NO;
        [zoom hrv_setPropWithKey:@"onEvent" propValue:^(id event) { if ([event[@"type"] isEqual:@"pageFinished"]) zoomFinished = YES; }];
        [zoom hrv_setPropWithKey:@"request" propValue:RequestJSON(@{@"content": @{@"type": @"html", @"html": @"<meta name='viewport' content='width=device-width,initial-scale=1'><p style='width:1000px'>zoom</p>"}, @"settings": @{@"supportZoom": enabled, @"javaScriptEnabled": javascript}})];
        WaitUntil(^BOOL { return zoomFinished; }, @"Zoom document never finished");
        [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.2]];
        NSString *viewport = Evaluate(zoom.webView, @"document.querySelector('meta[name=viewport]').content");
        Require([viewport containsString:@"width=device-width"] && [viewport containsString:@"initial-scale=1"] &&
          [viewport containsString:@"user-scalable=no"] == !enabled.boolValue && !zoom.webView.configuration.ignoresViewportScaleLimits,
          @"Production author viewport policy was ignored");
        if (!enabled.boolValue) {
            Evaluate(zoom.webView, @"document.querySelector('meta[name=viewport]').content='width=device-width;initial-scale=2;viewport-fit=cover;user-scalable=yes';true");
            NSString *updated = Evaluate(zoom.webView, @"document.querySelector('meta[name=viewport]').content");
            Require([updated containsString:@"initial-scale=2"] && [updated containsString:@"viewport-fit=cover"] &&
                [updated containsString:@"user-scalable=no"] && ![updated containsString:@"user-scalable=yes"], @"Dynamic author viewport parameters were lost");
        }
        [zoom hrv_removeFromSuperview];
    }
    NSString *fixture = NSProcessInfo.processInfo.environment[@"WEBVIEW_WIRE_PAGE_URL"];
    NSString *imageURL = [[[NSURL URLWithString:fixture] URLByDeletingLastPathComponent].absoluteString stringByAppendingString:@"mixed-image"];
    for (NSString *policy in @[@"NEVER_ALLOW", @"COMPATIBILITY", @"ALWAYS_ALLOW"]) {
        for (NSString *scheme in @[@"https", @"http"]) {
            GYWebView *mixed = [[GYWebView alloc] initWithFrame:CGRectMake(0, 0, 100, 100)];
            [UIApplication.sharedApplication.keyWindow.rootViewController.view addSubview:mixed];
            NSString *html = [NSString stringWithFormat:@"<img src='%@?%@-%@' onload='window.mixedResult=1' onerror='window.mixedResult=2'>", imageURL, policy, scheme];
            [mixed hrv_setPropWithKey:@"request" propValue:RequestJSON(@{@"content": @{@"type": @"html", @"html": html, @"baseUrl": [scheme isEqual:@"http"] ? fixture : @"https://safe.example/"}, @"settings": @{@"javaScriptEnabled": @YES, @"mixedContentPolicy": policy}, @"blockedResourceRules": @[@{@"type": @"exactHost", @"host": @"unrelated.example"}]})];
            __block NSNumber *result = nil;
            WaitUntil(^BOOL{
                [mixed.webView evaluateJavaScript:@"window.mixedResult || 0" completionHandler:^(id value, NSError *error) { if ([value integerValue]) result = value; }];
                return result != nil;
            }, @"Real WebKit mixed image request did not finish");
            BOOL blocked = [scheme isEqual:@"https"] && ![policy isEqual:@"ALWAYS_ALLOW"];
            Require(result.integerValue == (blocked ? 2 : 1), [NSString stringWithFormat:@"Mixed content %@ under %@ gave %@", policy, scheme, result]);
            [mixed hrv_removeFromSuperview];
        }
    }
    for (NSString *mode in @[@"default", @"network"]) for (NSNumber *dataImage in @[@NO, @YES]) {
        GYWebView *images = [[GYWebView alloc] initWithFrame:CGRectMake(0, 0, 100, 100)];
        [UIApplication.sharedApplication.keyWindow.rootViewController.view addSubview:images];
        NSString *source = dataImage.boolValue ? @"data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7" : [imageURL stringByAppendingFormat:@"?images-%@", mode];
        NSString *html = [NSString stringWithFormat:@"<img src='%@' onload='window.imageResult=1' onerror='window.imageResult=2'>", source];
        [images hrv_setPropWithKey:@"request" propValue:RequestJSON(@{@"content": @{@"type": @"html", @"html": html, @"baseUrl": fixture}, @"settings": @{@"javaScriptEnabled": @YES, @"mixedContentPolicy": @"ALWAYS_ALLOW", @"blockNetworkImage": [mode isEqual:@"network"] ? @YES : @NO}})];
        __block NSNumber *result = nil;
        WaitUntil(^BOOL { [images.webView evaluateJavaScript:@"window.imageResult || 0" completionHandler:^(id value, NSError *error) { if ([value integerValue]) result = value; }]; return result != nil; }, [NSString stringWithFormat:@"Actual image rule did not complete: %@ data=%@", mode, dataImage]);
        BOOL blocked = [mode isEqual:@"network"] && !dataImage.boolValue;
        Require(result.integerValue == (blocked ? 2 : 1), [NSString stringWithFormat:@"Image rule %@ data=%@ gave %@", mode, dataImage, result]);
        [images hrv_removeFromSuperview];
    }
    GYWebView *view = [[GYWebView alloc] initWithFrame:CGRectZero];
    view.request = @{@"security": @{@"trustedOrigins": @{@"urls": @[@"https://[::ffff:192.0.2.1]:8443", @"https://trusted.example."], @"trustedHostSuffixes": @[]}}};
    Require([[view trustExpression] containsString:@"[::ffff:c000:201]"], @"JS trust must use browser IPv6 representation");
    Require([[view trustExpression] containsString:@"location.hostname === \"trusted.example.\""], @"JS trust must preserve trailing-dot origin contract");
    for (id flag in @[@YES, @NO, @0, @1, @"true", NSNull.null]) {
        NSDictionary *request = @{@"content": @{@"type": @"url", @"url": @"https://safe.example"},
                                  @"security": @{@"pageBridgeEnabled": flag}};
        BOOL valid = CFGetTypeID((__bridge CFTypeRef)flag) == CFBooleanGetTypeID();
        Require([view validRequest:request] == valid, @"Native security flags require genuine JSON Booleans");
    }
    for (NSString *initial in @[@"http://legacy.example/captcha", @"http://legacy.example:8080/captcha", @"https://legacy.example:8443/captcha"]) {
        NSDictionary *security = @{@"pageBridgeEnabled": @YES, @"trustedOrigins": @{@"urls": @[], @"trustedHostSuffixes": @[]}};
        NSDictionary *policy = @{@"allowedSchemes": @[@"http", @"https"], @"allowedUrls": @[initial]};
        view.request = @{@"content": @{@"type": @"url", @"url": initial}, @"settings": @{@"javaScriptEnabled": @YES}, @"security": security, @"navigationPolicy": policy};
        Require([view validRequest:view.request], @"Initial page script scope rejected valid HTTP/HTTPS input");
        Require([view bridgeAllowed:initial], @"Initial page origin lost low privilege Bridge");
        Require(![view trusted:initial], @"Page Bridge must not grant high privilege trust");
        Require([[view trustExpression] containsString:GYQuote([GYOrigin(initial)[@"scheme"] stringByAppendingString:@":"])], @"Early script lost initial page scheme");
        Require([view allows:initial mainFrame:YES newWindow:NO gesture:NO], @"Exact URL rejected its configured document");
        for (NSString *target in @[[initial stringByReplacingOccurrencesOfString:@"/captcha" withString:@"/other"], [initial stringByAppendingString:@"?extra=1"], [initial stringByAppendingString:@"#next"]]) {
            Require(![view allows:target mainFrame:YES newWindow:NO gesture:NO], @"Exact URL normalized path/query/fragment");
            Require([view allows:target mainFrame:NO newWindow:NO gesture:NO], @"Exact main frame URLs blocked subframes");
        }
        for (NSString *target in @[@"http://legacy.example:9090/captcha", @"https://evil.example/captcha"])
            Require(![view bridgeAllowed:target], @"Page Bridge crossed scheme/host/port boundary");
        NSMutableDictionary *mixed = [view.request mutableCopy];
        mixed[@"security"] = @{@"appBridgeEnabled": @YES, @"pageBridgeEnabled": @YES, @"trustedOrigins": @{@"urls": @[@"https://trusted.example"], @"trustedHostSuffixes": @[]}};
        Require(![view validRequest:mixed], @"Mixed Bridge flags accepted");
        view.request = mixed;
        Require(![view bridgeAllowed:initial], @"Page Bridge bypassed app Bridge precedence");
    }
    for (id allowedUrls in @[NSNull.null, @"https://safe.example", @[@1], @[@"javascript:evil"], @[@"http://user@legacy.example/captcha"], @[@"http://legacy.example:0/captcha"], @[@" http://legacy.example/captcha"]]) {
        Require(![view validRequest:@{@"content": @{@"type": @"url", @"url": @"https://safe.example"}, @"navigationPolicy": @{@"allowedUrls": allowedUrls}}], @"Malformed exact URL wire accepted");
    }
    view.request = @{@"navigationPolicy": @{@"allowedSchemes": @[@"https"]}, @"security": @{}};
    __block NSDictionary *event;
    [view hrv_setPropWithKey:@"onEvent" propValue:^(id value) { event = JSONRoundTrip(value); }];
    for (NSNumber *allowed in @[@YES, @NO]) {
        BOOL expected = allowed.boolValue;
        BOOL result = [view allows:expected ? @"https://safe.example" : @"file:///private/example"
                        mainFrame:YES newWindow:NO gesture:NO];
        Require(result == expected, @"Navigation policy result changed");
        RequireBoolean(event[@"blocked"], !expected, @"blocked");
        RequireBoolean(event[@"isMainFrame"], YES, @"isMainFrame");
        RequireBoolean(event[@"hasUserGesture"], NO, @"hasUserGesture");
    }
    [view allows:@"https://safe.example" mainFrame:NO newWindow:YES gesture:YES];
    RequireBoolean(event[@"blocked"], YES, @"new window blocked");
    RequireBoolean(event[@"isMainFrame"], NO, @"subframe");
    RequireBoolean(event[@"hasUserGesture"], YES, @"gesture");

    for (NSString *command in @[@"reload", @"stopLoading", @"goBack", @"goForward"]) {
        [view hrv_callWithMethod:command params:nil callback:^(id value) {
            RequireBoolean(JSONRoundTrip(value)[@"result"], NO, command);
        }];
    }

    RuleProbe *probe = [[RuleProbe alloc] initWithFrame:CGRectZero];
    probe.webView = [[WKWebView alloc] initWithFrame:CGRectZero];
    NSDictionary *policyBase = @{@"content": @{@"type": @"url", @"url": @"https://safe.example"}, @"security": @{}, @"navigationPolicy": @{@"allowedSchemes": @[@"http", @"https"]}};
    probe.request = policyBase;
    WKWebView *policyOwner = probe.webView;
    NSMutableDictionary *policyUpdated = [policyBase mutableCopy];
    policyUpdated[@"navigationPolicy"] = @{@"allowedSchemes": @[@"https"], @"blockedRules": @[mall]};
    [probe hrv_setPropWithKey:@"request" propValue:[[NSString alloc] initWithData:[NSJSONSerialization dataWithJSONObject:policyUpdated options:0 error:nil] encoding:NSUTF8StringEncoding]];
    Require(probe.webView == policyOwner && [probe.request isEqual:policyUpdated], @"Policy update replaced current document");

    for (NSString *key in @[@"estimatedProgress", @"title", @"canGoBack", @"canGoForward"])
        [probe.webView addObserver:probe forKeyPath:key options:0 context:nil];
    for (NSString *command in @[@"reload", @"stopLoading"]) {
        [probe hrv_callWithMethod:command params:nil callback:^(id value) {
            RequireBoolean(JSONRoundTrip(value)[@"result"], YES, command);
        }];
    }
    SEL selector = @selector(compileContentRuleListForIdentifier:encodedContentRuleList:completionHandler:);
    Method method = class_getInstanceMethod(WKContentRuleListStore.class, selector);
    originalRuleCompiler = method_setImplementation(method, (IMP)CompileRules);
    NSArray *rules = @[
        @{@"type": @"contains", @"value": @"/ads/", @"ignoreCase": @NO},
        @{@"type": @"contains", @"value": @"/ads/", @"ignoreCase": @YES},
        @{@"type": @"exactHost", @"host": @"ads.example"},
        @{@"type": @"hostSuffix", @"suffix": @"ads.example"},
        mall
    ];
    __block BOOL failed = NO;
    [probe hrv_setPropWithKey:@"onEvent" propValue:^(id value) {
        if ([value[@"type"] isEqual:@"loadFailed"]) failed = YES;
    }];
    for (NSUInteger index = 0; index < rules.count; index++) {
        probe.loaded = NO;
        probe.request = @{@"blockedResourceRules": @[rules[index]]};
        [probe installResourceRulesAndLoad];
        RequireBoolean(lastTrigger[@"url-filter-is-case-sensitive"], index == 0, @"rule case sensitivity");
        if (index >= 2 && index <= 3) {
            NSRegularExpression *filter = [NSRegularExpression regularExpressionWithPattern:lastTrigger[@"url-filter"] options:0 error:nil];
            for (NSString *url in @[@"https://ads.example", @"https://ads.example:443/path", @"https://ads.example/path?q=1"]) {
                Require([filter numberOfMatchesInString:url options:0 range:NSMakeRange(0, url.length)] == 1, @"Host rule lost valid host/port/path");
            }
            for (NSString *url in @[@"https://ads.example.evil/path", @"https://notads.example/path"]) {
                Require([filter numberOfMatchesInString:url options:0 range:NSMakeRange(0, url.length)] == 0, @"Host rule crossed an authority boundary");
            }
            NSString *subdomain = @"https://sub.ads.example/path";
            Require([filter numberOfMatchesInString:subdomain options:0 range:NSMakeRange(0, subdomain.length)] == (index == 3 ? 1 : 0), @"Exact/suffix host semantics changed");
        }
        NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:15];
        while (!probe.loaded && !failed && deadline.timeIntervalSinceNow > 0)
            [NSRunLoop.mainRunLoop runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
        Require(probe.loaded && !failed, @"WebKit rejected production resource rules");
    }
    method_setImplementation(method, originalRuleCompiler);
    for (id channels in @[NSNull.null, @"NativeChannel", @[@1], @[@"window"], @[@"_private"], @[@"ComposeWebViewDanger"]]) {
        Require(![view validRequest:@{@"content": @{@"type": @"url", @"url": @"https://safe.example/page"}, @"pageMessageChannels": channels}], @"Malformed channel wire accepted");
    }
    Require(![view validRequest:@{@"content": @{@"type": @"html", @"html": @"empty origin"}, @"pageMessageChannels": @[@"NativeChannel"]}], @"Channel without HTTP(S) document origin accepted");
    GYWebView *page = [[GYWebView alloc] initWithFrame:CGRectMake(0, 0, 200, 200)];
    __block NSMutableArray<NSDictionary *> *messages = [NSMutableArray array];
    [page hrv_setPropWithKey:@"onEvent" propValue:^(id value) { if ([value[@"type"] isEqual:@"pageMessage"]) [messages addObject:value]; }];
    NSDictionary *pageRequest = @{@"content": @{@"type": @"html", @"html": @"<html><body>channel</body></html>", @"baseUrl": @"https://safe.example/page"},
        @"settings": @{@"javaScriptEnabled": @YES}, @"pageMessageChannels": @[@"NativeChannel"],
        @"navigationPolicy": @{@"allowedSchemes": @[@"http", @"https", @"about"]},
        @"scripts": @[@{@"id": @"early", @"source": @"window.NativeChannel.onmessage=function(event){window.replyData=event.data;};window.NativeChannel.postMessage('early');", @"injectionTime": @"DOCUMENT_START", @"onlyForTrustedMainFrame": @NO}]};
    [page hrv_setPropWithKey:@"request" propValue:[[NSString alloc] initWithData:[NSJSONSerialization dataWithJSONObject:pageRequest options:0 error:nil] encoding:NSUTF8StringEncoding]];
    NSDate *pageDeadline = [NSDate dateWithTimeIntervalSinceNow:20];
    while (!messages.count && pageDeadline.timeIntervalSinceNow > 0) [NSRunLoop.mainRunLoop runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
    if (!messages.count) {
        __block BOOL diagnosed = NO;
        [page.webView evaluateJavaScript:@"JSON.stringify({href:location.href,channel:typeof window.NativeChannel,reply:typeof window.__GY_WEBVIEW_PAGE_MESSAGE_REPLY__})" completionHandler:^(id value, NSError *error) { fprintf(stderr, "Channel diagnosis: %s / %s\n", [value description].UTF8String, error.description.UTF8String); diagnosed = YES; }];
        NSDate *diagnosisDeadline = [NSDate dateWithTimeIntervalSinceNow:5];
        while (!diagnosed && diagnosisDeadline.timeIntervalSinceNow > 0) [NSRunLoop.mainRunLoop runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
        fprintf(stderr, "Native channel diagnosis: token=%s URL=%s loading=%d scripts=%lu revoked=%d\n", page.documentToken.UTF8String, page.webView.URL.absoluteString.UTF8String, page.webView.loading, (unsigned long)page.webView.configuration.userContentController.userScripts.count, page.pageMessageRevoked);
    }
    Require(messages.count == 1 && [messages[0][@"data"] isEqual:@"early"], @"Document-start channel was not available before business script");
    [page hrv_callWithMethod:@"goBack" params:nil callback:^(id value) { RequireBoolean(value[@"result"], NO, @"No actual backward history"); }];
    [page hrv_callWithMethod:@"goForward" params:nil callback:^(id value) { RequireBoolean(value[@"result"], NO, @"No actual forward history"); }];
    Require(!page.pageMessageRevoked && page.pageMessageReplies.count == 1, @"Missing history revoked an active channel");
    NSString *firstReply = messages[0][@"replyId"];
    NSString *replyParams = [[NSString alloc] initWithData:[NSJSONSerialization dataWithJSONObject:@{@"replyId": firstReply, @"data": @"reply\"雪"} options:0 error:nil] encoding:NSUTF8StringEncoding];
    [page hrv_callWithMethod:@"replyPageMessage" params:replyParams callback:^(id value) { RequireBoolean(value[@"result"], YES, @"page reply accepted"); }];
    [page hrv_callWithMethod:@"replyPageMessage" params:replyParams callback:^(id value) { RequireBoolean(value[@"result"], NO, @"page reply single use"); }];
    __block BOOL replied = NO;
    [page.webView evaluateJavaScript:@"window.replyData" completionHandler:^(id value, NSError *error) { Require(!error && [value isEqual:@"reply\"雪"], @"Reply string did not reach onmessage(event.data)"); replied = YES; }];
    pageDeadline = [NSDate dateWithTimeIntervalSinceNow:20];
    while (!replied && pageDeadline.timeIntervalSinceNow > 0) [NSRunLoop.mainRunLoop runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
    Require(replied, @"Page reply did not complete");
    NSString *oldToken = page.documentToken;
    [page.webView evaluateJavaScript:@"window.NativeChannel.postMessage('before-hide')" completionHandler:nil];
    pageDeadline = [NSDate dateWithTimeIntervalSinceNow:20];
    while (messages.count < 2 && pageDeadline.timeIntervalSinceNow > 0) [NSRunLoop.mainRunLoop runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
    Require(messages.count == 2, @"Second channel message lost");
    NSString *hiddenReply = [[NSString alloc] initWithData:[NSJSONSerialization dataWithJSONObject:@{@"replyId": messages[1][@"replyId"], @"data": @"hidden"} options:0 error:nil] encoding:NSUTF8StringEncoding];
    [page hrv_setPropWithKey:@"visible" propValue:@NO];
    [page hrv_setPropWithKey:@"visible" propValue:@YES];
    [page hrv_callWithMethod:@"replyPageMessage" params:hiddenReply callback:^(id value) { RequireBoolean(value[@"result"], NO, @"Hidden document reply remained revoked after show"); }];
    __block BOOL rejected = NO;
    NSString *stale = [NSString stringWithFormat:@"window.NativeChannel.postMessage('late');window.webkit.messageHandlers.ComposeWebViewPageMessage.postMessage({token:%@,channel:'NativeChannel',data:'old-token'});true", GYQuote(oldToken)];
    [page.webView evaluateJavaScript:stale completionHandler:^(id value, NSError *error) { rejected = YES; }];
    pageDeadline = [NSDate dateWithTimeIntervalSinceNow:20];
    while (!rejected && pageDeadline.timeIntervalSinceNow > 0) [NSRunLoop.mainRunLoop runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
    Require(rejected && messages.count == 2, @"Hidden or old token channel reopened");
    [page.webView evaluateJavaScript:@"location.reload();true" completionHandler:nil];
    pageDeadline = [NSDate dateWithTimeIntervalSinceNow:1];
    while (pageDeadline.timeIntervalSinceNow > 0) [NSRunLoop.mainRunLoop runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
    Require(page.pageMessageRevoked && messages.count == 2, @"H5 reload restored a hidden channel without host authorization");
    WKWebView *oldOwner = page.webView;
    [page hrv_callWithMethod:@"reload" params:nil callback:nil];
    Require(page.webView != oldOwner, @"Explicit channel reload retained the old native owner");
    pageDeadline = [NSDate dateWithTimeIntervalSinceNow:20];
    while (messages.count < 3 && pageDeadline.timeIntervalSinceNow > 0) [NSRunLoop.mainRunLoop runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
    Require(messages.count == 3, @"New document did not restore channel");
    Require(![oldToken isEqual:page.documentToken], @"Reload reused prior document token");
    [page hrv_callWithMethod:@"replyPageMessage" params:hiddenReply callback:^(id value) { RequireBoolean(value[@"result"], NO, @"Old document reply entered new document"); }];
    NSUInteger acceptedMessages = messages.count;
    NSString *iframeMessage = [NSString stringWithFormat:@"parent.iframeAttempted=true;window.webkit.messageHandlers.ComposeWebViewPageMessage.postMessage({token:%@,channel:'NativeChannel',data:'iframe'});", GYQuote(page.documentToken)];
    NSString *iframeHTML = [NSString stringWithFormat:@"<script>%@</script>", iframeMessage];
    NSString *rejectSource = [NSString stringWithFormat:@"window.webkit.messageHandlers.ComposeWebViewPageMessage.postMessage({token:%@,channel:'NativeChannel',data:'stale-new-document'});var frame=document.createElement('iframe');frame.srcdoc=%@;document.body.appendChild(frame);true;", GYQuote(oldToken), GYQuote(iframeHTML)];
    __block BOOL sourceExecuted = NO;
    [page.webView evaluateJavaScript:rejectSource completionHandler:^(id value, NSError *error) { Require(!error, @"Source rejection probe failed to execute"); sourceExecuted = YES; }];
    pageDeadline = [NSDate dateWithTimeIntervalSinceNow:2];
    while (pageDeadline.timeIntervalSinceNow > 0) [NSRunLoop.mainRunLoop runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
    __block BOOL iframeAttempted = NO;
    [page.webView evaluateJavaScript:@"window.iframeAttempted === true" completionHandler:^(id value, NSError *error) { Require(!error && [value boolValue], @"Real iframe did not attempt its native handler call"); iframeAttempted = YES; }];
    pageDeadline = [NSDate dateWithTimeIntervalSinceNow:5];
    while (!iframeAttempted && pageDeadline.timeIntervalSinceNow > 0) [NSRunLoop.mainRunLoop runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
    Require(iframeAttempted && sourceExecuted && messages.count == acceptedMessages, @"Old same-origin document token or iframe entered active channel");
    for (NSString *foreignURL in @[@"https://safe.example/other", @"https://foreign.example/page"]) {
        [page.webView loadHTMLString:@"<html><body>foreign</body></html>" baseURL:[NSURL URLWithString:foreignURL]];
        __block BOOL loaded = NO;
        pageDeadline = [NSDate dateWithTimeIntervalSinceNow:20];
        while (!loaded && pageDeadline.timeIntervalSinceNow > 0) {
            [NSRunLoop.mainRunLoop runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
            loaded = !page.webView.loading && [page.webView.URL.absoluteString isEqual:foreignURL];
        }
        Require(loaded, @"Foreign document did not load");
        __block BOOL attacked = NO;
        NSString *attack = [NSString stringWithFormat:@"window.webkit.messageHandlers.ComposeWebViewPageMessage.postMessage({token:%@,channel:'NativeChannel',data:'foreign'});true", GYQuote(page.documentToken)];
        [page.webView evaluateJavaScript:attack completionHandler:^(id value, NSError *error) { Require(!error, @"Foreign frame attack did not execute"); attacked = YES; }];
        pageDeadline = [NSDate dateWithTimeIntervalSinceNow:2];
        while (pageDeadline.timeIntervalSinceNow > 0) [NSRunLoop.mainRunLoop runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
        Require(attacked && messages.count == acceptedMessages, @"Other same-origin path or foreign origin entered channel");
    }
    [page hrv_callWithMethod:@"reload" params:nil callback:nil];
    pageDeadline = [NSDate dateWithTimeIntervalSinceNow:20];
    while (messages.count == acceptedMessages && pageDeadline.timeIntervalSinceNow > 0) [NSRunLoop.mainRunLoop runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
    Require(messages.count == acceptedMessages + 1, @"Restored document did not create a pending reply");
    NSString *cancelledReply = [[NSString alloc] initWithData:[NSJSONSerialization dataWithJSONObject:@{@"replyId": messages.lastObject[@"replyId"], @"data": @"cancelled"} options:0 error:nil] encoding:NSUTF8StringEncoding];
    [page hrv_callWithMethod:@"stopLoading" params:nil callback:nil];
    [page hrv_callWithMethod:@"replyPageMessage" params:cancelledReply callback:^(id value) { RequireBoolean(value[@"result"], NO, @"Cancelled document reply"); }];
    NSString *historyURL = NSProcessInfo.processInfo.environment[@"WEBVIEW_WIRE_PAGE_URL"];
    Require(historyURL.length && [historyURL hasPrefix:@"http://127.0.0.1:"], @"Missing loopback-only history fixture");
    GYWebView *historyPage = [[GYWebView alloc] initWithFrame:CGRectMake(0, 0, 200, 200)];
    __block NSUInteger historyMessages = 0;
    [historyPage hrv_setPropWithKey:@"onEvent" propValue:^(id value) { if ([value[@"type"] isEqual:@"pageMessage"]) historyMessages++; }];
    NSMutableDictionary *historyRequest = [pageRequest mutableCopy];
    historyRequest[@"content"] = @{@"type": @"url", @"url": historyURL};
    [historyPage hrv_setPropWithKey:@"request" propValue:[[NSString alloc] initWithData:[NSJSONSerialization dataWithJSONObject:historyRequest options:0 error:nil] encoding:NSUTF8StringEncoding]];
    pageDeadline = [NSDate dateWithTimeIntervalSinceNow:20];
    while ((!historyMessages || historyPage.webView.loading) && pageDeadline.timeIntervalSinceNow > 0) [NSRunLoop.mainRunLoop runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
    Require(historyMessages == 1, @"Loopback initial document did not publish early channel message");
    NSString *otherHistoryURL = [historyURL stringByReplacingOccurrencesOfString:@"/page" withString:@"/other"];
    [historyPage.webView evaluateJavaScript:[NSString stringWithFormat:@"location.href=%@", GYQuote(otherHistoryURL)] completionHandler:nil];
    pageDeadline = [NSDate dateWithTimeIntervalSinceNow:20];
    while ((historyPage.webView.loading || ![historyPage.webView.URL.absoluteString isEqual:otherHistoryURL]) && pageDeadline.timeIntervalSinceNow > 0) [NSRunLoop.mainRunLoop runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
    Require(historyPage.webView.canGoBack && historyPage.pageMessageRevoked, @"Same-origin self-navigation did not revoke native channel");
    [historyPage.webView evaluateJavaScript:@"history.back();true" completionHandler:nil];
    pageDeadline = [NSDate dateWithTimeIntervalSinceNow:20];
    while ((historyPage.webView.loading || ![historyPage.webView.URL.absoluteString isEqual:historyURL]) && pageDeadline.timeIntervalSinceNow > 0) [NSRunLoop.mainRunLoop runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
    Require([historyPage.webView.URL.absoluteString isEqual:historyURL] && historyPage.pageMessageRevoked && historyMessages == 1, @"H5 history restored initial-page channel authorization");
    // 已有真实历史项，补一个待回复值以核验命令发起导航前就同步清表。
    historyPage.pageMessageRevoked = NO;
    historyPage.pageMessageReplies[@"navigation-reply"] = @{@"channel": @"NativeChannel", @"source": historyURL};
    [historyPage hrv_callWithMethod:@"goForward" params:nil callback:^(id value) { RequireBoolean(value[@"result"], YES, @"Actual forward navigation"); }];
    Require(historyPage.pageMessageRevoked && historyPage.pageMessageReplies.count == 0, @"Forward navigation left a synchronous reply window");
    historyPage.pageMessageRevoked = NO;
    historyPage.pageMessageReplies[@"failed-reply"] = @{@"channel": @"NativeChannel", @"source": historyURL};
    [historyPage fail:@"NETWORK" message:@"fixture failure" url:historyURL code:@(-1009)];
    NSString *failedReply = [[NSString alloc] initWithData:[NSJSONSerialization dataWithJSONObject:@{@"replyId": @"failed-reply", @"data": @"late"} options:0 error:nil] encoding:NSUTF8StringEncoding];
    [historyPage hrv_callWithMethod:@"replyPageMessage" params:failedReply callback:^(id value) { RequireBoolean(value[@"result"], NO, @"Failed document reply"); }];
    [historyPage hrv_removeFromSuperview];
    CheckNativeCommandCancellation(historyRequest, otherHistoryURL);
    CheckNavigationOwnerReplacement(pageRequest);
    [page hrv_removeFromSuperview];
    __block BOOL destroyedReply = NO;
    [page hrv_callWithMethod:@"replyPageMessage" params:hiddenReply callback:^(id value) { RequireBoolean(value[@"result"], NO, @"Destroyed owner reply"); destroyedReply = YES; }];
    Require(destroyedReply, @"Destroyed page reply did not reject");
    // 上传回执在原生 owner/文档代次门禁之后交付；基础 Foundation/UTType 使用真实实现。
    RuleProbe *upload = [[RuleProbe alloc] initWithFrame:CGRectZero];
    upload.webView = [[UploadWebView alloc] initWithFrame:CGRectZero];
    for (NSString *key in @[@"estimatedProgress", @"title", @"canGoBack", @"canGoForward"])
        [upload.webView addObserver:upload forKeyPath:key options:0 context:nil];
    upload.request = @{@"security": @{@"fileChooserEnabled": @YES, @"trustedOrigins": @{@"urls": @[@"https://safe.example"]}}};
    upload.fileTypes = @[UTTypePlainText]; upload.fileOrigin = @"https://safe.example";
    NSURL *directory = [NSURL fileURLWithPath:[NSTemporaryDirectory() stringByAppendingPathComponent:@"upload-directory.jpg"]];
    [NSFileManager.defaultManager createDirectoryAtURL:directory withIntermediateDirectories:YES attributes:nil error:nil];
    upload.fileTypes = @[UTTypeItem];
    upload.filePickerGeneration = upload.callbackGeneration;
    upload.filePickerCompletion = ^(NSArray<NSURL *> *files) { Require(files == nil, @"Directory was treated as an upload file"); };
    [upload finishFiles:@[directory]];
    [NSFileManager.defaultManager removeItemAtURL:directory error:nil];
    upload.fileTypes = @[UTTypePlainText];

    NSURL *file = [NSURL fileURLWithPath:[NSTemporaryDirectory() stringByAppendingPathComponent:@"upload-check.txt"]];
    [@"test" writeToURL:file atomically:YES encoding:NSUTF8StringEncoding error:nil];
    __block NSUInteger settled = 0;
    upload.filePickerCompletion = ^(NSArray<NSURL *> *files) { settled++; Require(files.count == 1, @"Valid file was lost"); };
    upload.filePickerGeneration = upload.callbackGeneration;
    [upload finishFiles:@[file]]; Require(settled == 1, @"Upload did not settle once");
    upload.filePickerCompletion = ^(NSArray<NSURL *> *files) { settled++; Require(files == nil, @"Stale document disclosed file"); };
    upload.filePickerGeneration = upload.callbackGeneration; upload.callbackGeneration++;
    [upload finishFiles:@[file]]; Require(settled == 2, @"Stale upload did not settle once");
    upload.filePickerCompletion = ^(NSArray<NSURL *> *files) { settled++; Require(files == nil, @"Cancel disclosed file"); };
    upload.temporaryFiles = [NSMutableArray arrayWithObject:file];
    [upload cancelFilePicker]; [upload cancelFilePicker]; Require(settled == 3, @"Cancel settled repeatedly");
    Require([NSFileManager.defaultManager fileExistsAtPath:file.path], @"Later chooser cancellation deleted accepted upload");
    [upload revokeFilePicker];
    Require(![NSFileManager.defaultManager fileExistsAtPath:file.path], @"Temporary upload file was retained");
    upload.fileTypes = @[UTTypeImage]; upload.filePickerGeneration = upload.callbackGeneration;
    UIImagePickerController *camera = [UIImagePickerController new]; upload.capturePicker = camera;
    UIGraphicsImageRenderer *renderer = [[UIGraphicsImageRenderer alloc] initWithSize:CGSizeMake(2, 2)];
    UIImage *image = [renderer imageWithActions:^(UIGraphicsImageRendererContext *context) { [UIColor.redColor setFill]; UIRectFill(CGRectMake(0, 0, 2, 2)); }];
    __block NSURL *captured;
    upload.filePickerCompletion = ^(NSArray<NSURL *> *files) { captured = files.firstObject; Require(captured != nil, @"Actual JPEG encoding did not return readable file"); };
    [upload imagePickerController:camera didFinishPickingMediaWithInfo:@{UIImagePickerControllerMediaType: UTTypeImage.identifier, UIImagePickerControllerOriginalImage: image}];
    NSData *jpeg = [NSData dataWithContentsOfURL:captured]; const unsigned char *header = jpeg.bytes;
    Require(jpeg.length > 3 && header[0] == 0xff && header[1] == 0xd8, @"Capture did not encode actual JPEG");
    [upload cancelFilePicker]; Require([NSData dataWithContentsOfURL:captured].length > 0, @"Later cancel interrupted H5 capture read");
    [upload prepareDocumentScripts]; Require(![NSFileManager.defaultManager fileExistsAtPath:captured.path], @"Navigation retained previous capture");
    NSURL *foreignVideo = [NSURL fileURLWithPath:[NSTemporaryDirectory() stringByAppendingPathComponent:@"camera-check.mp4"]];
    [@"foreign movie" writeToURL:foreignVideo atomically:YES encoding:NSUTF8StringEncoding error:nil];
    upload.fileTypes = @[UTTypeMovie]; upload.filePickerGeneration = upload.callbackGeneration;
    camera = [UIImagePickerController new]; upload.capturePicker = camera;
    upload.filePickerCompletion = ^(NSArray<NSURL *> *files) { Require(files == nil, @"Non-MOV capture was renamed as QuickTime"); };
    [upload imagePickerController:camera didFinishPickingMediaWithInfo:@{UIImagePickerControllerMediaType: UTTypeMovie.identifier, UIImagePickerControllerMediaURL: foreignVideo}];
    Require([NSFileManager.defaultManager fileExistsAtPath:foreignVideo.path], @"Rejected capture deleted provider-owned source");
    [NSFileManager.defaultManager removeItemAtURL:foreignVideo error:nil];
    for (NSString *mode in @[@"complete", @"cancel", @"hide"]) {
        NSURL *movie = [NSBundle.mainBundle URLForResource:@"capture" withExtension:@"mov"];
        Require(movie != nil, @"Missing real MOV fixture");
        upload.fileTypes = @[UTTypeMPEG4Movie]; upload.filePickerGeneration = upload.callbackGeneration;
        camera = [UIImagePickerController new]; upload.capturePicker = camera;
        __block NSUInteger movieResults = 0; __block NSURL *exported;
        upload.filePickerCompletion = ^(NSArray<NSURL *> *files) { movieResults++; exported = files.firstObject; };
        [upload imagePickerController:camera didFinishPickingMediaWithInfo:@{UIImagePickerControllerMediaType: UTTypeMovie.identifier, UIImagePickerControllerMediaURL: movie}];
        NSArray<NSURL *> *working = upload.exportingFiles;
        Require(working.count == 2, @"MP4-only capture did not start system export");
        if ([mode isEqual:@"cancel"]) [upload cancelFilePicker];
        if ([mode isEqual:@"hide"]) [upload hrv_setPropWithKey:@"visible" propValue:@NO];
        WaitUntil(^BOOL { return movieResults == 1; }, @"MP4 export did not settle once");
        if ([mode isEqual:@"complete"]) {
            Require([exported.pathExtension isEqual:@"mp4"], @"MP4 accept did not receive MP4 output");
            Require([[AVURLAsset URLAssetWithURL:exported options:nil] tracksWithMediaType:AVMediaTypeVideo].count > 0, @"Exported MP4 has no real video track");
            Require(![NSFileManager.defaultManager fileExistsAtPath:working.firstObject.path], @"MOV conversion input was retained");
            [upload revokeFilePicker];
        } else {
            Require(exported == nil, @"Revoked capture disclosed output");
            // Let the cancelled AVFoundation completion run before checking late cleanup.
            NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:0.5];
            while (deadline.timeIntervalSinceNow > 0) [NSRunLoop.mainRunLoop runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
        }
        for (NSURL *url in working) Require(![NSFileManager.defaultManager fileExistsAtPath:url.path], @"Export temporary file leaked");
        Require(movieResults == 1, @"Late movie export completed twice");
        Require([NSFileManager.defaultManager fileExistsAtPath:movie.path], @"Capture removed provider-owned fixture");
        [upload hrv_setPropWithKey:@"visible" propValue:@YES];
    }
    [upload hrv_removeFromSuperview];
    [probe hrv_removeFromSuperview];
    [view hrv_removeFromSuperview];
    puts("PASS: Native Boolean wire, navigation, command results, early page channel document gates and real WebKit rules");
    fflush(stdout);
    exit(0);
}

@interface WireCheckApp : UIResponder <UIApplicationDelegate>
@property(nonatomic, strong) UIWindow *window;
@end
@implementation WireCheckApp
- (void)runChecks { Check(); }
- (BOOL)application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)options {
    self.window = [[UIWindow alloc] initWithFrame:UIScreen.mainScreen.bounds];
    self.window.rootViewController = [UIViewController new];
    [self.window makeKeyAndVisible];
    [self performSelector:@selector(runChecks) withObject:nil afterDelay:0];
    return YES;
}
@end

int main(int argc, char *argv[]) {
    @autoreleasepool { return UIApplicationMain(argc, argv, nil, NSStringFromClass(WireCheckApp.class)); }
}

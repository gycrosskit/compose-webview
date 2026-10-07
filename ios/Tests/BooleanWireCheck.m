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

static void Check(void) {
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
    [upload hrv_removeFromSuperview];
    [probe hrv_removeFromSuperview];
    [view hrv_removeFromSuperview];
    puts("PASS: Native Boolean wire, navigation, command results and real WebKit rules");
    fflush(stdout);
    exit(0);
}

@interface WireCheckApp : UIResponder <UIApplicationDelegate>
@property(nonatomic, strong) UIWindow *window;
@end
@implementation WireCheckApp
- (BOOL)application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)options {
    self.window = [[UIWindow alloc] initWithFrame:UIScreen.mainScreen.bounds];
    self.window.rootViewController = [UIViewController new];
    [self.window makeKeyAndVisible];
    dispatch_async(dispatch_get_main_queue(), ^{ Check(); });
    return YES;
}
@end

int main(int argc, char *argv[]) {
    @autoreleasepool { return UIApplicationMain(argc, argv, nil, NSStringFromClass(WireCheckApp.class)); }
}

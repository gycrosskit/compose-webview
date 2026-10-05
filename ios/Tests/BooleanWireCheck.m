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
    GYWebView *view = [[GYWebView alloc] initWithFrame:CGRectZero];
    for (id flag in @[@YES, @NO, @0, @1, @"true", NSNull.null]) {
        NSDictionary *request = @{@"content": @{@"type": @"url", @"url": @"https://safe.example"},
                                  @"security": @{@"pageBridgeEnabled": flag}};
        BOOL valid = CFGetTypeID((__bridge CFTypeRef)flag) == CFBooleanGetTypeID();
        Require([view validRequest:request] == valid, @"Native security flags require genuine JSON Booleans");
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
        @{@"type": @"hostSuffix", @"suffix": @"ads.example"}
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
        if (index >= 2) {
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

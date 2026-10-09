#import "GYWebView.h"
#import <WebKit/WebKit.h>
#import <UniformTypeIdentifiers/UniformTypeIdentifiers.h>
#import <AVFoundation/AVFoundation.h>
#import <arpa/inet.h>
#import "GYWebViewScripts.inc"

static NSDictionary *GYObject(NSString *value) {
    if (![value isKindOfClass:NSString.class]) return nil;
    id json = [NSJSONSerialization JSONObjectWithData:[value dataUsingEncoding:NSUTF8StringEncoding] options:0 error:nil];
    return [json isKindOfClass:NSDictionary.class] ? json : nil;
}
static NSString *GYString(id value) { return [value isKindOfClass:NSString.class] ? value : nil; }
static BOOL GYBool(NSDictionary *object, NSString *key, BOOL fallback) {
    id value = object[key];
    return [value isKindOfClass:NSNumber.class] ? [value boolValue] : fallback;
}
static NSString *GYQuote(NSString *value) {
    NSString *array = [[NSString alloc] initWithData:[NSJSONSerialization dataWithJSONObject:@[value ?: @""] options:0 error:nil] encoding:NSUTF8StringEncoding];
    return [array substringWithRange:NSMakeRange(1, array.length - 2)];
}
static NSString *GYHost(NSString *host) {
    NSString *normalized = [[host lowercaseString] stringByTrimmingCharactersInSet:[NSCharacterSet characterSetWithCharactersInString:@"."]];
    if (![normalized hasPrefix:@"["]) return normalized;
    if (![normalized hasSuffix:@"]"]) return nil;
    NSString *address = [normalized substringWithRange:NSMakeRange(1, normalized.length - 2)];
    if ([address containsString:@"."]) {
        NSArray<NSString *> *parts = [[[address componentsSeparatedByString:@":"] lastObject] componentsSeparatedByString:@"."];
        if (parts.count != 4) return nil;
        for (NSString *part in parts) {
            if (![[NSPredicate predicateWithFormat:@"SELF MATCHES %@", @"0|[1-9][0-9]{0,2}"] evaluateWithObject:part] || part.integerValue > 255) return nil;
        }
    }
    struct in6_addr bytes;
    if (inet_pton(AF_INET6, address.UTF8String, &bytes) != 1) return nil;
    // inet_ntop 对 mapped IPv4 保留点分尾段，WebKit hostname 则统一为八组十六进制。
    NSMutableArray<NSString *> *groups = [NSMutableArray array];
    NSUInteger bestStart = NSNotFound, bestLength = 1, runStart = 0, runLength = 0;
    for (NSUInteger i = 0; i < 8; i++) {
        unsigned value = ((unsigned)bytes.s6_addr[i * 2] << 8) | bytes.s6_addr[i * 2 + 1];
        [groups addObject:[NSString stringWithFormat:@"%x", value]];
        if (!value) {
            if (!runLength) runStart = i;
            runLength++;
            if (runLength > bestLength) { bestStart = runStart; bestLength = runLength; }
        } else runLength = 0;
    }
    NSString *result = [groups componentsJoinedByString:@":"];
    if (bestStart != NSNotFound) result = [NSString stringWithFormat:@"%@::%@",
        [[groups subarrayWithRange:NSMakeRange(0, bestStart)] componentsJoinedByString:@":"],
        [[groups subarrayWithRange:NSMakeRange(bestStart + bestLength, 8 - bestStart - bestLength)] componentsJoinedByString:@":"]];
    return [NSString stringWithFormat:@"[%@]", result];
}
static NSDictionary *GYOrigin(NSString *value) {
    NSString *raw = [value ?: @"" stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
    if ([raw containsString:@"\\"] || [raw rangeOfCharacterFromSet:NSCharacterSet.whitespaceAndNewlineCharacterSet].location != NSNotFound || [raw rangeOfCharacterFromSet:NSCharacterSet.controlCharacterSet].location != NSNotFound) return nil;
    NSURLComponents *url = [NSURLComponents componentsWithString:raw];
    NSString *scheme = url.scheme.lowercaseString;
    if (!([scheme isEqual:@"https"] || [scheme isEqual:@"http"]) || !url.host.length || url.user != nil || url.password != nil || [url.host hasPrefix:@"."] ||
        (url.port && (url.port.integerValue < 1 || url.port.integerValue > 65535))) return nil;
    if ([url.host hasSuffix:@".."] || ([url.host hasPrefix:@"["] && [url.host hasSuffix:@"."])) return nil;
    NSString *host = GYHost(url.host);
    if (!host.length || (![host hasPrefix:@"["] && [[host componentsSeparatedByString:@"."] containsObject:@""])) return nil;
    return @{@"scheme": scheme, @"host": host, @"port": url.port ?: ([scheme isEqual:@"https"] ? @443 : @80)};
}
static NSString *GYPageMessageDocumentURL(NSString *value) {
    if (!GYOrigin(value)) return nil;
    NSURLComponents *url = [NSURLComponents componentsWithString:value];
    url.scheme = url.scheme.lowercaseString;
    url.host = url.host.lowercaseString;
    if (url.port.integerValue == ([url.scheme isEqual:@"https"] ? 443 : 80)) url.port = nil;
    if (!url.percentEncodedPath.length) url.percentEncodedPath = @"/";
    return url.string;
}
static NSString *GYSourceOrigin(WKSecurityOrigin *source) {
    NSString *host = source.host;
    if ([host containsString:@":"] && ![host hasPrefix:@"["]) host = [NSString stringWithFormat:@"[%@]", host];
    return [NSString stringWithFormat:@"%@://%@:%ld", source.protocol, host, (long)(source.port ?: ([source.protocol isEqual:@"https"] ? 443 : 80))];
}
static NSDictionary *GYFrameOrigin(WKFrameInfo *frame) { return GYOrigin(GYSourceOrigin(frame.securityOrigin)); }
static BOOL GYRuleMatches(NSDictionary *rule, NSString *value) {
    if ([rule[@"type"] isEqual:@"contains"]) {
        return [value rangeOfString:rule[@"value"] options:GYBool(rule, @"ignoreCase", NO) ? NSCaseInsensitiveSearch : 0].location != NSNotFound;
    }
    NSString *rawHost = [NSURLComponents componentsWithString:value].host;
    NSString *dnsHost = [rawHost hasSuffix:@"."] ? [rawHost substringToIndex:rawHost.length - 1] : rawHost;
    if ([rawHost hasPrefix:@"."] || [rawHost hasSuffix:@".."] || [[dnsHost componentsSeparatedByString:@"."] containsObject:@""]) return NO;
    NSString *host = GYHost(rawHost);
    if ([rule[@"type"] isEqual:@"exactHost"]) return [host isEqual:GYHost(rule[@"host"])];
    NSURLComponents *components = [NSURLComponents componentsWithString:value];
    if (GYBool(rule, @"rejectUserInfo", NO)) {
        NSString *raw = [value stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
        if (components.user != nil || components.password != nil || ![raw containsString:@"://"] || [raw containsString:@"\\"] || [raw rangeOfCharacterFromSet:NSCharacterSet.whitespaceAndNewlineCharacterSet].location != NSNotFound || [raw rangeOfCharacterFromSet:NSCharacterSet.controlCharacterSet].location != NSNotFound) return NO;
    }
    NSString *suffix = GYHost(rule[@"suffix"]);
    NSString *scheme = GYString(rule[@"scheme"]);
    if (scheme && ![[NSURLComponents componentsWithString:value].scheme.lowercaseString isEqual:scheme]) return NO;
    return (GYBool(rule, @"includeRoot", YES) && [host isEqual:suffix]) || [host hasSuffix:[@"." stringByAppendingString:suffix]];
}

@class GYWebView;
@interface GYWebViewMessageHandler : NSObject <WKScriptMessageHandler>
@property(nonatomic, weak) GYWebView *owner;
@end

@interface GYWebView () <WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler, UIDocumentPickerDelegate, UIImagePickerControllerDelegate, UINavigationControllerDelegate>
@property(nonatomic, strong) WKWebView *webView;
@property(nonatomic, copy) NSDictionary *request;
@property(nonatomic, copy) KuiklyRenderCallback onEvent;
@property(nonatomic, assign) BOOL pageVisible;
@property(nonatomic, assign) BOOL released;
@property(nonatomic, assign) NSUInteger callbackGeneration;
@property(nonatomic, strong) NSMutableDictionary<NSString *, id> *fullscreenCancellations;
@property(nonatomic, assign) BOOL navigationFailed;
@property(nonatomic, assign) BOOL initialHtmlNavigation;
@property(nonatomic, assign) BOOL fullscreen;
@property(nonatomic, copy) NSString *documentToken;
@property(nonatomic, strong) NSMutableDictionary<NSString *, NSDictionary *> *pageMessageReplies;
@property(nonatomic, assign) BOOL pageMessageRevoked;
@property(nonatomic, assign) BOOL pageMessageInitialNavigation;
@property(nonatomic, strong) UIDocumentPickerViewController *filePicker;
@property(nonatomic, copy) void (^filePickerCompletion)(NSArray<NSURL *> *);
@property(nonatomic, assign) NSUInteger filePickerGeneration;
@property(nonatomic, assign) NSUInteger filePickerRevision;
@property(nonatomic, assign) NSUInteger maxFiles;
@property(nonatomic, strong) UIImagePickerController *capturePicker;
@property(nonatomic, copy) NSArray<UTType *> *fileTypes;
@property(nonatomic, copy) NSString *fileOrigin;
@property(nonatomic, strong) NSMutableArray<NSURL *> *temporaryFiles;
@property(nonatomic, strong) AVAssetExportSession *videoExport;
@property(nonatomic, copy) NSArray<NSURL *> *exportingFiles;
@end

@implementation GYWebViewMessageHandler
- (void)userContentController:(WKUserContentController *)controller didReceiveScriptMessage:(WKScriptMessage *)message {
    [self.owner userContentController:controller didReceiveScriptMessage:message];
}
@end

@implementation GYWebView
@synthesize hr_rootView;

- (instancetype)initWithFrame:(CGRect)frame {
    if ((self = [super initWithFrame:frame])) self.pageVisible = YES;
    return self;
}
- (void)layoutSubviews { [super layoutSubviews]; self.webView.frame = self.bounds; }
- (void)hrv_setPropWithKey:(NSString *)propKey propValue:(id)propValue {
    if (self.released) return;
    NSString *key = propKey; id value = propValue;
    if ([key isEqual:@"onEvent"]) self.onEvent = value;
    else if ([key isEqual:@"visible"]) {
        BOOL visible = [value boolValue];
        if (self.pageVisible != visible && self.webView) {
            WKWebView *owner = self.webView;
            NSUInteger generation = self.callbackGeneration + 1;
            self.pageMessageRevoked = self.pageMessageRevoked || !visible;
            self.pageVisible = visible;
            [self prepareDocumentScripts];
            if (self.released || self.webView != owner || self.callbackGeneration != generation) return;
            [owner evaluateJavaScript:[self transportScript] completionHandler:nil];
        } else self.pageVisible = visible;
        self.hidden = !self.pageVisible;
        [self updateMediaVisibility];
    } else if ([key isEqual:@"request"]) {
        NSDictionary *request = GYObject(value);
        if ([request isEqual:self.request]) return;
        NSMutableDictionary *oldPage = [self.request mutableCopy], *newPage = [request mutableCopy];
        [oldPage removeObjectForKey:@"navigationPolicy"]; [newPage removeObjectForKey:@"navigationPolicy"];
        if (self.webView && [oldPage isEqual:newPage] && [self validRequest:request]) {
            self.request = request; return;
        }
        NSUInteger generation = self.callbackGeneration + 1;
        [self releaseWebView];
        if (self.released || self.webView || self.callbackGeneration != generation) return;
        self.request = nil;
        if (![self validRequest:request]) {
            [self fail:@"LOAD_EXCEPTION" message:@"Invalid WebView request" url:nil code:nil]; return;
        }
        self.request = request;
        [self createWebView];
    } else { KUIKLY_SET_CSS_COMMON_PROP; }
}
- (void)emit:(NSDictionary *)event { if (!self.released && self.onEvent) self.onEvent(event); }
- (void)fail:(NSString *)kind message:(NSString *)message url:(NSString *)url code:(NSNumber *)code {
    self.navigationFailed = YES;
    [self.pageMessageReplies removeAllObjects]; self.pageMessageRevoked = YES;
    [self emit:@{@"type": @"loadFailed", @"kind": kind, @"message": message ?: @"", @"url": url ?: NSNull.null, @"errorCode": code ?: NSNull.null, @"isMainFrame": @YES}];
}
- (BOOL)validRules:(id)rules {
    if (!rules || rules == NSNull.null) return YES;
    if (![rules isKindOfClass:NSArray.class]) return NO;
    for (id value in rules) {
        if (![value isKindOfClass:NSDictionary.class]) return NO;
        NSString *type = GYString(value[@"type"]);
        NSString *text = GYString(value[[type isEqual:@"contains"] ? @"value" : [type isEqual:@"exactHost"] ? @"host" : @"suffix"]);
        if (!text.length || ![@[@"contains", @"exactHost", @"hostSuffix"] containsObject:type]) return NO;
        if ([type isEqual:@"hostSuffix"]) {
            id root = value[@"includeRoot"], scheme = value[@"scheme"], userInfo = value[@"rejectUserInfo"];
            if (userInfo && (![userInfo isKindOfClass:NSNumber.class] || CFGetTypeID((__bridge CFTypeRef)userInfo) != CFBooleanGetTypeID())) return NO;
            if (root && (![root isKindOfClass:NSNumber.class] || CFGetTypeID((__bridge CFTypeRef)root) != CFBooleanGetTypeID())) return NO;
            if (scheme && scheme != NSNull.null && (!GYString(scheme) || ![[NSPredicate predicateWithFormat:@"SELF MATCHES %@", @"[a-z][a-z0-9+.-]*"] evaluateWithObject:scheme])) return NO;
        }
    }
    return YES;
}
- (BOOL)validRequest:(NSDictionary *)request {
    if (!request) return NO;
    NSDictionary *content = request[@"content"], *security = request[@"security"], *settings = request[@"settings"], *policy = request[@"navigationPolicy"];
    if (![content isKindOfClass:NSDictionary.class]) return NO;
    for (id value in @[security ?: @{}, settings ?: @{}, policy ?: @{}]) if (![value isKindOfClass:NSDictionary.class]) return NO;
    NSString *type = GYString(content[@"type"]);
    if (![type isEqual:@"url"] && ![type isEqual:@"html"]) return NO;
    if (!GYString(content[[type isEqual:@"url"] ? @"url" : @"html"])) return NO;
    if (![self validRules:request[@"blockedResourceRules"]] || ![self validRules:policy[@"blockedRules"]]) return NO;
    for (NSString *key in @[@"appBridgeEnabled", @"pageBridgeEnabled", @"fileChooserEnabled", @"mediaCaptureEnabled"]) {
        id value = security[key];
        if (value && (![value isKindOfClass:NSNumber.class] || CFGetTypeID((__bridge CFTypeRef)value) != CFBooleanGetTypeID())) return NO;
    }
    for (NSString *key in @[@"javaScriptEnabled", @"domStorageEnabled", @"allowFileAccess", @"allowContentAccess", @"acceptsThirdPartyCookies", @"supportMultipleWindows", @"javaScriptCanOpenWindowsAutomatically", @"mediaPlaybackRequiresUserGesture", @"loadsImagesAutomatically", @"blockNetworkImage", @"builtInZoomControls", @"displayZoomControls", @"supportZoom", @"useWideViewPort", @"loadWithOverviewMode", @"followSystemFontScale", @"algorithmicDarkeningAllowed"]) {
        id value = settings[key];
        if (value && (![value isKindOfClass:NSNumber.class] || CFGetTypeID((__bridge CFTypeRef)value) != CFBooleanGetTypeID())) return NO;
    }
    id newWindows = policy[@"allowNewWindows"];
    if (newWindows && (![newWindows isKindOfClass:NSNumber.class] || CFGetTypeID((__bridge CFTypeRef)newWindows) != CFBooleanGetTypeID())) return NO;
    NSDictionary *headers = content[@"additionalHeaders"];
    if (headers) {
        if (![headers isKindOfClass:NSDictionary.class]) return NO;
        for (id key in headers) if (!GYString(key) || !GYString(headers[key])) return NO;
    }
    for (NSString *key in @[@"baseUrl", @"mimeType", @"encoding", @"historyUrl"]) if (content[key] && content[key] != NSNull.null && !GYString(content[key])) return NO;
    if (GYBool(security, @"appBridgeEnabled", NO) && GYBool(security, @"pageBridgeEnabled", NO)) return NO;
    NSDictionary *origins = security[@"trustedOrigins"];
    if (origins && ![origins isKindOfClass:NSDictionary.class]) return NO;
    for (id value in @[origins[@"urls"] ?: @[], origins[@"trustedHostSuffixes"] ?: @[], policy[@"allowedSchemes"] ?: @[], policy[@"allowedOrigins"] ?: @[], policy[@"allowedUrls"] ?: @[]]) {
        if (![value isKindOfClass:NSArray.class]) return NO;
        for (id item in value) if (!GYString(item)) return NO;
    }
    for (NSString *url in policy[@"allowedOrigins"]) if (!GYOrigin(url)) return NO;
    for (NSString *url in policy[@"allowedUrls"]) if (![url isEqual:[url stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet]] || !GYOrigin(url)) return NO;
    for (NSString *url in origins[@"urls"]) if (![GYOrigin(url)[@"scheme"] isEqual:@"https"]) return NO;
    NSRegularExpression *hostPattern = [NSRegularExpression regularExpressionWithPattern:@"^[\\p{L}\\p{N}]([\\p{L}\\p{N}-]*[\\p{L}\\p{N}])?(\\.[\\p{L}\\p{N}]([\\p{L}\\p{N}-]*[\\p{L}\\p{N}])?)*$" options:0 error:nil];
    for (NSString *suffix in origins[@"trustedHostSuffixes"]) if (![hostPattern firstMatchInString:GYHost(suffix) options:0 range:NSMakeRange(0, GYHost(suffix).length)]) return NO;
    if (GYBool(security, @"appBridgeEnabled", NO) || GYBool(security, @"mediaCaptureEnabled", NO) || GYBool(security, @"fileChooserEnabled", NO)) {
        if (![origins[@"urls"] count] && ![origins[@"trustedHostSuffixes"] count]) return NO;
    }
    if (GYBool(settings, @"allowFileAccess", NO) || GYBool(settings, @"allowContentAccess", NO)) return NO;
    if (GYBool(security, @"fileChooserEnabled", NO)) { if (@available(iOS 18.4, *)) {} else { [self emit:@{@"type": @"capabilityUnsupported", @"capability": @"FILE_CHOOSER"}]; return NO; } }
    id channels = request[@"pageMessageChannels"] ?: @[];
    if (![channels isKindOfClass:NSArray.class] || [NSSet setWithArray:channels].count > 16) return NO;
    NSRegularExpression *channelPattern = [NSRegularExpression regularExpressionWithPattern:@"^[a-zA-Z][a-zA-Z0-9_]{0,79}$" options:0 error:nil];
    NSArray *reservedChannels = @[@"window", @"self", @"top", @"parent", @"frames", @"document", @"location", @"navigator", @"webkit", @"globalThis", @"console", @"history", @"performance", @"JSON", @"Object", @"Array", @"Function", @"Promise", @"eval", @"undefined", @"NaN", @"Infinity", @"onmessage", @"postMessage", @"name", @"constructor", @"prototype", @"JSAndroidBridge", @"WebViewJavascriptBridge"];
    for (id channel in channels) if (!GYString(channel) || ![channelPattern firstMatchInString:channel options:0 range:NSMakeRange(0, [channel length])] || [reservedChannels containsObject:channel] || [channel hasPrefix:@"ComposeWebView"] || [channel hasPrefix:@"GYWebView"]) return NO;
    NSString *pageUrl = [content[@"type"] isEqual:@"url"] ? GYString(content[@"url"]) : GYString(content[@"baseUrl"]);
    if ([channels count] && !GYOrigin(pageUrl)) return NO;
    id scripts = request[@"scripts"] ?: @[];
    if (![scripts isKindOfClass:NSArray.class]) return NO;
    NSRegularExpression *ids = [NSRegularExpression regularExpressionWithPattern:@"^[\\p{L}\\p{N}_.-]+$" options:0 error:nil];
    for (id script in scripts) {
        if (![script isKindOfClass:NSDictionary.class]) return NO;
        NSString *identifier = GYString(script[@"id"]), *source = GYString(script[@"source"]);
        id trustedOnly = script[@"onlyForTrustedMainFrame"];
        if (trustedOnly && (![trustedOnly isKindOfClass:NSNumber.class] || CFGetTypeID((__bridge CFTypeRef)trustedOnly) != CFBooleanGetTypeID())) return NO;
        if (!identifier.length || !source.length || ![ids firstMatchInString:identifier options:0 range:NSMakeRange(0, identifier.length)]) return NO;
        if (![@[@"DOCUMENT_START", @"DOM_READY", @"DOCUMENT_FINISHED"] containsObject:script[@"injectionTime"] ?: @"DOM_READY"]) return NO;
    }
    return YES;
}
- (BOOL)trusted:(NSString *)value {
    NSDictionary *origin = GYOrigin(value), *security = self.request[@"security"], *trust = security[@"trustedOrigins"];
    if (![origin[@"scheme"] isEqual:@"https"]) return NO;
    for (NSString *url in trust[@"urls"]) if ([origin isEqual:GYOrigin(url)]) return YES;
    for (NSString *suffixValue in trust[@"trustedHostSuffixes"]) {
        NSString *suffix = GYHost(suffixValue), *host = origin[@"host"];
        if (suffix.length && ([host isEqual:suffix] || [host hasSuffix:[@"." stringByAppendingString:suffix]])) return YES;
    }
    return NO;
}
- (BOOL)bridgeAllowed:(NSString *)value {
    NSDictionary *security = self.request[@"security"], *content = self.request[@"content"];
    if (GYBool(security, @"appBridgeEnabled", NO)) return [self trusted:value];
    NSString *initial = [content[@"type"] isEqual:@"url"] ? GYString(content[@"url"]) : GYString(content[@"baseUrl"]);
    NSDictionary *origin = GYOrigin(initial);
    return GYBool(security, @"pageBridgeEnabled", NO) && origin && [origin isEqual:GYOrigin(value)];
}
- (BOOL)pageMessageAllowed:(NSString *)url {
    NSDictionary *content = self.request[@"content"], *policy = self.request[@"navigationPolicy"];
    NSString *initial = [content[@"type"] isEqual:@"url"] ? GYString(content[@"url"]) : GYString(content[@"baseUrl"]);
    if (![self.request[@"pageMessageChannels"] count] || !GYBool(self.request[@"settings"], @"javaScriptEnabled", NO) || !GYOrigin(initial) || ![GYPageMessageDocumentURL(initial) isEqual:GYPageMessageDocumentURL(url)]) return NO;
    NSString *scheme = [NSURLComponents componentsWithString:url].scheme.lowercaseString;
    if (![(policy[@"allowedSchemes"] ?: @[@"http", @"https"]) containsObject:scheme]) return NO;
    if ([policy[@"allowedUrls"] count] && ![policy[@"allowedUrls"] containsObject:url]) return NO;
    if ([policy[@"allowedOrigins"] count]) {
        BOOL allowed = NO;
        for (NSString *origin in policy[@"allowedOrigins"]) if ([GYOrigin(origin) isEqual:GYOrigin(url)]) allowed = YES;
        if (!allowed) return NO;
    }
    for (NSDictionary *rule in policy[@"blockedRules"]) if (GYRuleMatches(rule, url)) return NO;
    NSDictionary *security = self.request[@"security"];
    if ((GYBool(security, @"appBridgeEnabled", NO) || GYBool(security, @"pageBridgeEnabled", NO)) && ![self bridgeAllowed:url]) return NO;
    return YES;
}
- (NSString *)pageMessageScript {
    NSDictionary *content = self.request[@"content"];
    NSString *initial = [content[@"type"] isEqual:@"url"] ? GYString(content[@"url"]) : GYString(content[@"baseUrl"]);
    if (self.pageMessageRevoked || (![self pageMessageAllowed:initial] && ![self pageMessageAllowed:GYPageMessageDocumentURL(initial)])) return nil;
    NSString *channels = [[NSString alloc] initWithData:[NSJSONSerialization dataWithJSONObject:self.request[@"pageMessageChannels"] options:0 error:nil] encoding:NSUTF8StringEncoding];
    return [[[WEB_VIEW_PAGE_MESSAGE_SCRIPT stringByReplacingOccurrencesOfString:@"__GY_PAGE_TOKEN_JSON__" withString:GYQuote(self.documentToken)] stringByReplacingOccurrencesOfString:@"__GY_PAGE_CHANNELS_JSON__" withString:channels] stringByReplacingOccurrencesOfString:@"__GY_PAGE_URL_JSON__" withString:GYQuote(initial)];
}
- (BOOL)allows:(NSString *)url mainFrame:(BOOL)mainFrame newWindow:(BOOL)newWindow gesture:(BOOL)gesture {
    NSDictionary *policy = self.request[@"navigationPolicy"], *security = self.request[@"security"];
    NSString *scheme = [NSURLComponents componentsWithString:url ?: @""].scheme.lowercaseString;
    NSArray *schemes = policy[@"allowedSchemes"] ?: @[@"http", @"https"];
    BOOL allow = [schemes containsObject:scheme ?: @""] && (!newWindow || GYBool(policy, @"allowNewWindows", NO));
    NSArray *allowedUrls = policy[@"allowedUrls"];
    if (mainFrame && allowedUrls.count && ![allowedUrls containsObject:url ?: @""]) allow = NO;
    NSArray *allowedOrigins = policy[@"allowedOrigins"];
    if (mainFrame && allowedOrigins.count) {
        NSDictionary *origin = GYOrigin(url);
        BOOL sameOrigin = NO;
        for (NSString *allowed in allowedOrigins) if (origin && [origin isEqual:GYOrigin(allowed)]) { sameOrigin = YES; break; }
        if (!sameOrigin) allow = NO;
    }
    if (mainFrame) for (NSDictionary *rule in policy[@"blockedRules"]) if (GYRuleMatches(rule, url)) allow = NO;
    if (mainFrame && (GYBool(security, @"appBridgeEnabled", NO) || GYBool(security, @"pageBridgeEnabled", NO)) && ![self bridgeAllowed:url]) allow = NO;
    // Objective-C 的 ! / && / 比较表达式是 int，直接 @() 会生成 JSON 0/1，而 wire 要求真正的 Boolean。
    [self emit:@{@"type": @"navigation", @"url": url ?: @"", @"isMainFrame": @(mainFrame), @"hasUserGesture": @(gesture), @"target": newWindow ? @"NEW_WINDOW" : @"CURRENT_WINDOW", @"blocked": allow ? @NO : @YES}];
    return allow && !self.released;
}
- (NSString *)trustExpression {
    NSDictionary *trust = self.request[@"security"][@"trustedOrigins"];
    NSMutableArray *parts = [NSMutableArray array];
    for (NSString *url in trust[@"urls"]) {
        NSDictionary *origin = GYOrigin(url);
        if (![origin[@"scheme"] isEqual:@"https"]) continue;
        NSString *host = origin[@"host"];
        [parts addObject:[NSString stringWithFormat:@"((location.hostname === %@ || location.hostname === %@) && (location.port || '443') === %@)", GYQuote(host), GYQuote([host stringByAppendingString:@"."]), GYQuote([origin[@"port"] stringValue])]];
    }
    for (NSString *suffix in trust[@"trustedHostSuffixes"]) [parts addObject:[NSString stringWithFormat:@"(function(h) { var s = %@, n = h.length; if (h[n - 1] === '.') n--; if (n < s.length || (n > s.length && h[n - s.length - 1] !== '.')) return false; for (var i = 0; i < s.length; i++) if (h[n - s.length + i] !== s[i]) return false; return true; })(location.hostname)", GYQuote(GYHost(suffix))]];
    NSString *trusted = parts.count ? [NSString stringWithFormat:@"(location.protocol === 'https:' && (%@))", [parts componentsJoinedByString:@" || "]] : @"false";
    NSDictionary *security = self.request[@"security"], *content = self.request[@"content"];
    NSString *initial = [content[@"type"] isEqual:@"url"] ? GYString(content[@"url"]) : GYString(content[@"baseUrl"]);
    NSDictionary *origin = GYOrigin(initial);
    if (GYBool(security, @"pageBridgeEnabled", NO) && !GYBool(security, @"appBridgeEnabled", NO) && origin) {
        NSString *host = origin[@"host"], *scheme = origin[@"scheme"];
        NSString *defaultPort = [scheme isEqual:@"https"] ? @"443" : @"80";
        return [NSString stringWithFormat:@"((%@) || (location.protocol === %@ && (location.hostname === %@ || location.hostname === %@) && (location.port || %@) === %@))",
            trusted, GYQuote([scheme stringByAppendingString:@":"]), GYQuote(host), GYQuote([host stringByAppendingString:@"."]), GYQuote(defaultPort), GYQuote([origin[@"port"] stringValue])];
    }
    return trusted;
}
- (void)addScript:(NSString *)source start:(BOOL)start {
    [self.webView.configuration.userContentController addUserScript:[[WKUserScript alloc] initWithSource:source injectionTime:start ? WKUserScriptInjectionTimeAtDocumentStart : WKUserScriptInjectionTimeAtDocumentEnd forMainFrameOnly:YES]];
}
- (void)createWebView {
    NSDictionary *request = self.request;
    NSUInteger generation = self.callbackGeneration;
    NSDictionary *content = request[@"content"], *settings = request[@"settings"], *security = request[@"security"];
    NSString *value = GYString(content[[content[@"type"] isEqual:@"url"] ? @"url" : @"html"]);
    NSString *origin = [content[@"type"] isEqual:@"url"] ? value : GYString(content[@"baseUrl"]);
    if (![value stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet].length) { [self fail:@"EMPTY_CONTENT" message:@"" url:origin code:nil]; return; }
    if (origin && ![self allows:origin mainFrame:YES newWindow:NO gesture:NO]) return;
    // navigation 事件与取消回执可同步替换 owner，旧调用不能继续创建或授权新页面。
    if (self.released || self.request != request || self.callbackGeneration != generation || self.webView) return;
    WKWebViewConfiguration *configuration = [WKWebViewConfiguration new];
    configuration.defaultWebpagePreferences.allowsContentJavaScript = GYBool(settings, @"javaScriptEnabled", NO);
    configuration.preferences.javaScriptCanOpenWindowsAutomatically = GYBool(settings, @"javaScriptCanOpenWindowsAutomatically", NO);
    configuration.ignoresViewportScaleLimits = NO;
    configuration.allowsInlineMediaPlayback = YES;
    configuration.mediaTypesRequiringUserActionForPlayback = GYBool(settings, @"mediaPlaybackRequiresUserGesture", YES) ? WKAudiovisualMediaTypeAll : WKAudiovisualMediaTypeNone;
    self.webView = [[WKWebView alloc] initWithFrame:self.bounds configuration:configuration];
    self.webView.navigationDelegate = self; self.webView.UIDelegate = self;
    self.webView.opaque = NO; self.webView.backgroundColor = UIColor.clearColor;
    self.webView.scrollView.backgroundColor = UIColor.clearColor;
    self.webView.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    [self addSubview:self.webView];
    for (NSString *key in @[@"estimatedProgress", @"title", @"canGoBack", @"canGoForward"]) [self.webView addObserver:self forKeyPath:key options:NSKeyValueObservingOptionNew context:nil];
    GYWebViewMessageHandler *handler = [GYWebViewMessageHandler new]; handler.owner = self;
    [configuration.userContentController addScriptMessageHandler:handler name:@"JSAndroidBridge"];
    [configuration.userContentController addScriptMessageHandler:handler name:@"ComposeWebViewEvent"];
    if ([self.request[@"pageMessageChannels"] count]) [configuration.userContentController addScriptMessageHandler:handler name:@"ComposeWebViewPageMessage"];
    self.pageMessageInitialNavigation = YES;
    self.pageMessageRevoked = !self.pageVisible;
    WKWebView *owner = self.webView;
    [self prepareDocumentScripts];
    if (self.released || self.webView != owner || self.callbackGeneration != generation + 1) return;
    NSString *suffix = GYString(settings[@"userAgentSuffix"]);
    self.webView.configuration.applicationNameForUserAgent = suffix;
    [self installResourceRulesAndLoad];
    [self updateMediaVisibility];
}
- (void)prepareDocumentScripts {
    WKWebView *owner = self.webView;
    WKWebViewConfiguration *configuration = owner.configuration;
    NSDictionary *settings = self.request[@"settings"], *security = self.request[@"security"];
    self.documentToken = NSUUID.UUID.UUIDString;
    self.pageMessageReplies = [NSMutableDictionary dictionary];
    NSUInteger generation = ++self.callbackGeneration;
    NSArray *cancellations = [self takeFullscreenCancellations];
    [self revokeFilePicker];
    for (void (^cancel)(void) in cancellations) cancel();
    if (self.released || self.webView != owner || self.callbackGeneration != generation) return;
    [configuration.userContentController removeAllUserScripts];
    [self addScript:[self transportScript] start:YES];
    // allowsContentJavaScript 只禁用网页脚本；原生视频仍需组件脚本监测和退出全屏。
    [self addScript:[IOS_WEB_EVENT_SCRIPT stringByReplacingOccurrencesOfString:@"window.webkit.messageHandlers.ComposeWebViewEvent" withString:@"window.__GY_WEBVIEW_EVENT_TRANSPORT__"] start:YES];
    if (!GYBool(settings, @"supportZoom", YES)) { configuration.ignoresViewportScaleLimits = NO; [self addScript:IOS_DISABLE_ZOOM_SCRIPT start:YES]; }
    [configuration.userContentController addUserScript:[[WKUserScript alloc] initWithSource:IOS_FILE_CHOOSER_GATE_SCRIPT injectionTime:WKUserScriptInjectionTimeAtDocumentStart forMainFrameOnly:NO]];
    if (GYBool(settings, @"javaScriptEnabled", NO)) {
        [self addScript:[WEB_VIEW_PERFORMANCE_SCRIPT stringByReplacingOccurrencesOfString:@"window.webkit.messageHandlers.ComposeWebViewEvent" withString:@"window.__GY_WEBVIEW_EVENT_TRANSPORT__"] start:YES];
        if (GYBool(security, @"appBridgeEnabled", NO) || GYBool(security, @"pageBridgeEnabled", NO)) [self addScript:[IOS_BRIDGE_SCRIPT stringByReplacingOccurrencesOfString:@"window.webkit.messageHandlers.JSAndroidBridge" withString:@"window.__GY_WEBVIEW_BRIDGE_TRANSPORT__"] start:YES];
        NSString *pageMessages = [self pageMessageScript];
        if (pageMessages) [self addScript:pageMessages start:YES];
        for (NSDictionary *script in self.request[@"scripts"]) {
            NSString *source = script[@"source"];
            if ([script[@"injectionTime"] isEqual:@"DOCUMENT_FINISHED"]) continue;
            NSString *body = [NSString stringWithFormat:@"if (%@ && !(window.__GY_WEBVIEW_SCRIPT_IDS__ || {})[%@]) { window.__GY_WEBVIEW_SCRIPT_IDS__ = window.__GY_WEBVIEW_SCRIPT_IDS__ || {}; window.__GY_WEBVIEW_SCRIPT_IDS__[%@] = true; (function(){%@})(); }", GYBool(script, @"onlyForTrustedMainFrame", YES) ? [self trustExpression] : @"true", GYQuote(script[@"id"]), GYQuote(script[@"id"]), source];
            if ([script[@"injectionTime"] isEqual:@"DOM_READY"]) body = [NSString stringWithFormat:@"if(document.readyState==='loading') document.addEventListener('DOMContentLoaded',function(){%@},{once:true}); else {%@}", body, body];
            [self addScript:body start:YES];
        }
    }
}
- (NSString *)transportScript {
    NSString *token = GYQuote(self.documentToken);
    return [NSString stringWithFormat:@"if((window.__GY_WEBVIEW_TRANSPORT_REVISION__||0)<=%lu){window.__GY_WEBVIEW_TRANSPORT_REVISION__=%lu;window.__GY_WEBVIEW_BRIDGE_TRANSPORT__={postMessage:function(value){if(%@)window.webkit.messageHandlers.JSAndroidBridge.postMessage({token:%@,value:value});}};window.__GY_WEBVIEW_EVENT_TRANSPORT__={postMessage:function(value){window.webkit.messageHandlers.ComposeWebViewEvent.postMessage({token:%@,value:value});}};}", (unsigned long)self.callbackGeneration, (unsigned long)self.callbackGeneration, self.pageVisible ? @"true" : @"false", token, token];
}
- (void)installResourceRulesAndLoad {
    NSArray *rules = self.request[@"blockedResourceRules"];
    NSMutableArray *encoded = [NSMutableArray array];
    for (NSDictionary *rule in rules) {
        NSString *type = rule[@"type"], *filter;
        if ([type isEqual:@"contains"]) filter = [NSString stringWithFormat:@".*%@.*", [NSRegularExpression escapedPatternForString:rule[@"value"]]];
        // WKContentRuleList 不支持 |；用可选路径和末尾锚点保持 host/port 的边界。
        else filter = [NSString stringWithFormat:@"^%@://%@%@(:[0-9]+)?(/.*)?$", GYString(rule[@"scheme"]) ?: @"[a-zA-Z][a-zA-Z0-9+.-]*", [type isEqual:@"hostSuffix"] ? (GYBool(rule, @"includeRoot", YES) ? (GYBool(rule, @"rejectUserInfo", NO) ? @"([^./:@]+\\.)*" : @"([^./]+\\.)*") : (GYBool(rule, @"rejectUserInfo", NO) ? @"([^./:@]+\\.)+" : @"([^./]+\\.)+")) : @"", [NSRegularExpression escapedPatternForString:GYHost(rule[[type isEqual:@"exactHost"] ? @"host" : @"suffix"])]];
        [encoded addObject:@{@"trigger": @{@"url-filter": filter, @"url-filter-is-case-sensitive": ([type isEqual:@"contains"] && !GYBool(rule, @"ignoreCase", NO)) ? @YES : @NO}, @"action": @{@"type": @"block"}}];
    }
    // COMPATIBILITY uses the conservative WebKit policy; only HTTPS top documents are restricted.
    if (![GYString(self.request[@"settings"][@"mixedContentPolicy"]) isEqual:@"ALWAYS_ALLOW"])
        [encoded addObject:@{@"trigger": @{@"url-filter": @"^http://", @"if-top-url": @[@"^https://"], @"url-filter-is-case-sensitive": @NO}, @"action": @{@"type": @"block"}}];
    if (GYBool(self.request[@"settings"], @"blockNetworkImage", NO))
        [encoded addObject:@{@"trigger": @{@"url-filter": @"^https?://", @"resource-type": @[@"image"], @"url-filter-is-case-sensitive": @NO}, @"action": @{@"type": @"block"}}];
    if (!encoded.count) { [self loadContent]; return; }
    NSString *json = [[NSString alloc] initWithData:[NSJSONSerialization dataWithJSONObject:encoded options:0 error:nil] encoding:NSUTF8StringEncoding];
    WKWebView *owner = self.webView;
    __weak typeof(self) weakSelf = self;
    NSString *identifier = NSUUID.UUID.UUIDString;
    [[WKContentRuleListStore defaultStore] compileContentRuleListForIdentifier:identifier encodedContentRuleList:json completionHandler:^(WKContentRuleList *list, NSError *error) {
        [[WKContentRuleListStore defaultStore] removeContentRuleListForIdentifier:identifier completionHandler:^(NSError *unused) {}];
        GYWebView *strongSelf = weakSelf;
        if (!strongSelf || strongSelf.webView != owner || strongSelf.released) return;
        if (!list) { [strongSelf fail:@"LOAD_EXCEPTION" message:error.localizedDescription url:nil code:@(error.code)]; return; }
        [owner.configuration.userContentController addContentRuleList:list];
        [strongSelf loadContent];
    }];
}
- (void)loadContent {
    NSDictionary *content = self.request[@"content"], *settings = self.request[@"settings"];
    if ([content[@"type"] isEqual:@"html"]) {
        self.initialHtmlNavigation = YES;
        NSString *historyURL = GYString(content[@"historyUrl"]);
        if (historyURL && ![historyURL isEqual:GYString(content[@"baseUrl"])]) {
            [self fail:@"LOAD_EXCEPTION" message:@"HTML historyUrl must equal baseUrl on iOS" url:historyURL code:nil]; return;
        }
        NSURL *baseURL = [NSURL URLWithString:GYString(content[@"baseUrl"]) ?: @"about:blank"];
        NSString *encoding = GYString(content[@"encoding"]) ?: @"UTF-8";
        CFStringEncoding charset = CFStringConvertIANACharSetNameToEncoding((__bridge CFStringRef)encoding);
        NSData *data = charset == kCFStringEncodingInvalidId ? nil :
            [content[@"html"] dataUsingEncoding:CFStringConvertEncodingToNSStringEncoding(charset) allowLossyConversion:NO];
        if (!data) { [self fail:@"LOAD_EXCEPTION" message:@"Unsupported HTML character encoding" url:GYString(content[@"baseUrl"]) code:nil]; return; }
        [self.webView loadData:data MIMEType:GYString(content[@"mimeType"]) ?: @"text/html"
            characterEncodingName:encoding baseURL:baseURL];
    }
    else {
        NSURL *url = [NSURL URLWithString:content[@"url"]];
        if (!url) { [self fail:@"LOAD_EXCEPTION" message:@"Invalid URL" url:content[@"url"] code:nil]; return; }
        NSURLRequestCachePolicy cache = NSURLRequestUseProtocolCachePolicy;
        if ([settings[@"cachePolicy"] isEqual:@"NO_CACHE"]) cache = NSURLRequestReloadIgnoringLocalCacheData;
        else if ([settings[@"cachePolicy"] isEqual:@"CACHE_ELSE_NETWORK"]) cache = NSURLRequestReturnCacheDataElseLoad;
        else if ([settings[@"cachePolicy"] isEqual:@"CACHE_ONLY"]) cache = NSURLRequestReturnCacheDataDontLoad;
        NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:url cachePolicy:cache timeoutInterval:60];
        NSDictionary *headers = content[@"additionalHeaders"];
        for (NSString *key in headers) [request setValue:headers[key] forHTTPHeaderField:key];
        [self.webView loadRequest:request];
    }
}
- (void)observeValueForKeyPath:(NSString *)key ofObject:(id)object change:(NSDictionary *)change context:(void *)context {
    if (object != self.webView || self.released) return;
    if ([key isEqual:@"estimatedProgress"]) [self emit:@{@"type": @"progressChanged", @"progress": @(MAX(0, MIN(100, (int)(self.webView.estimatedProgress * 100))))}];
    else if ([key isEqual:@"title"]) [self emit:@{@"type": @"titleChanged", @"title": self.webView.title ?: NSNull.null}];
    else [self history];
}
- (void)history { [self emit:@{@"type": @"historyChanged", @"url": self.webView.URL.absoluteString ?: NSNull.null, @"canGoBack": @(self.webView.canGoBack), @"canGoForward": @(self.webView.canGoForward)}]; }
- (void)webView:(WKWebView *)view decidePolicyForNavigationAction:(WKNavigationAction *)action decisionHandler:(void (^)(WKNavigationActionPolicy))decision {
    if (view != self.webView || self.released) { decision(WKNavigationActionPolicyCancel); return; }
    NSUInteger generation = self.callbackGeneration;
    BOOL popup = action.targetFrame == nil, mainFrame = popup || action.targetFrame.isMainFrame;
    NSString *url = action.request.URL.absoluteString;
    BOOL internalHtmlLoad = self.initialHtmlNavigation && mainFrame && !popup && action.navigationType == WKNavigationTypeOther &&
        ([url.lowercaseString hasPrefix:@"data:text/html"] || [url isEqual:@"about:blank"]);
    if (mainFrame) self.initialHtmlNavigation = NO;
    BOOL allow = view == self.webView && !self.released && (internalHtmlLoad || [self allows:url mainFrame:mainFrame newWindow:popup gesture:action.navigationType == WKNavigationTypeLinkActivated]);
    if (internalHtmlLoad && allow) [self emit:@{@"type": @"navigation", @"url": url, @"isMainFrame": @YES, @"hasUserGesture": @NO, @"target": @"CURRENT_WINDOW", @"blocked": @NO}];
    allow = allow && view == self.webView && !self.released && self.callbackGeneration == generation;
    if (allow && !popup && mainFrame) {
        if (!self.pageMessageInitialNavigation) self.pageMessageRevoked = YES;
        self.pageMessageInitialNavigation = NO;
        [self prepareDocumentScripts];
        allow = view == self.webView && !self.released && self.callbackGeneration == generation + 1;
    }
    decision(allow && !popup ? WKNavigationActionPolicyAllow : WKNavigationActionPolicyCancel);
    if (allow && popup && view == self.webView && !self.released) [view loadRequest:action.request];
}
- (WKWebView *)webView:(WKWebView *)view createWebViewWithConfiguration:(WKWebViewConfiguration *)configuration forNavigationAction:(WKNavigationAction *)action windowFeatures:(WKWindowFeatures *)features { return nil; }
- (void)webView:(WKWebView *)view didStartProvisionalNavigation:(WKNavigation *)navigation {
    if (view != self.webView) return;
    self.initialHtmlNavigation = NO;
    NSUInteger generation = ++self.callbackGeneration;
    NSArray *cancellations = [self takeFullscreenCancellations];
    self.navigationFailed = NO; [self revokeFilePicker];
    for (void (^cancel)(void) in cancellations) cancel();
    if (view != self.webView || self.released || self.callbackGeneration != generation) return;
    [self emit:@{@"type": @"pageStarted", @"url": view.URL.absoluteString ?: NSNull.null}];
}
- (void)webView:(WKWebView *)view didCommitNavigation:(WKNavigation *)navigation { if (view == self.webView) [self emit:@{@"type": @"firstContentVisible", @"url": view.URL.absoluteString ?: NSNull.null}]; }
- (void)webView:(WKWebView *)view didFinishNavigation:(WKNavigation *)navigation {
    if (view != self.webView || self.navigationFailed) return;
    [self emit:@{@"type": @"pageFinished", @"url": view.URL.absoluteString ?: NSNull.null}];
    if (view != self.webView || self.released) return;
    [self history];
    if (view != self.webView || self.released) return;
    BOOL fileAllowed = GYBool(self.request[@"security"], @"fileChooserEnabled", NO) && [self trusted:view.URL.absoluteString];
    [view evaluateJavaScript:[NSString stringWithFormat:@"window.__COMPOSE_WEBVIEW_FILE_CHOOSER_ALLOWED__=%@;", fileAllowed ? @"true" : @"false"] completionHandler:nil];
    if (GYBool(self.request[@"settings"], @"javaScriptEnabled", NO)) for (NSDictionary *script in self.request[@"scripts"]) if ([script[@"injectionTime"] isEqual:@"DOCUMENT_FINISHED"] && (!GYBool(script, @"onlyForTrustedMainFrame", YES) || [self trusted:view.URL.absoluteString] || (GYBool(self.request[@"security"], @"pageBridgeEnabled", NO) && [self bridgeAllowed:view.URL.absoluteString]))) [view evaluateJavaScript:script[@"source"] completionHandler:nil];
}
- (void)webView:(WKWebView *)view didFailProvisionalNavigation:(WKNavigation *)navigation withError:(NSError *)error { if (view == self.webView && error.code != NSURLErrorCancelled) [self fail:@"NETWORK" message:error.localizedDescription url:view.URL.absoluteString code:@(error.code)]; }
- (void)webView:(WKWebView *)view didFailNavigation:(WKNavigation *)navigation withError:(NSError *)error { [self webView:view didFailProvisionalNavigation:navigation withError:error]; }
- (void)webViewWebContentProcessDidTerminate:(WKWebView *)view { if (view == self.webView) [self fail:@"RENDER_PROCESS" message:@"WebKit process terminated" url:view.URL.absoluteString code:nil]; }
- (void)webView:(WKWebView *)view decidePolicyForNavigationResponse:(WKNavigationResponse *)response decisionHandler:(void (^)(WKNavigationResponsePolicy))decision {
    NSHTTPURLResponse *http = [response.response isKindOfClass:NSHTTPURLResponse.class] ? (NSHTTPURLResponse *)response.response : nil;
    BOOL failed = view == self.webView && response.isForMainFrame && http.statusCode >= 400;
    if (failed) { self.navigationFailed = YES; [self.pageMessageReplies removeAllObjects]; self.pageMessageRevoked = YES; [self emit:@{@"type": @"loadFailed", @"kind": @"HTTP", @"url": http.URL.absoluteString ?: NSNull.null, @"httpStatus": @(http.statusCode), @"message": @"", @"isMainFrame": @YES}]; }
    decision(failed ? WKNavigationResponsePolicyCancel : WKNavigationResponsePolicyAllow);
}
- (void)webView:(WKWebView *)view requestMediaCapturePermissionForOrigin:(WKSecurityOrigin *)origin initiatedByFrame:(WKFrameInfo *)frame type:(WKMediaCaptureType)type decisionHandler:(void (^)(WKPermissionDecision))decision API_AVAILABLE(ios(15.0)) {
    NSString *url = GYSourceOrigin(origin);
    BOOL allow = view == self.webView && self.pageVisible && frame.isMainFrame && GYBool(self.request[@"security"], @"mediaCaptureEnabled", NO) && [self trusted:url] && [self trusted:view.URL.absoluteString];
    decision(allow ? WKPermissionDecisionPrompt : WKPermissionDecisionDeny);
}
- (void)userContentController:(WKUserContentController *)controller didReceiveScriptMessage:(WKScriptMessage *)message {
    if (message.webView != self.webView || !message.frameInfo.isMainFrame || controller != self.webView.configuration.userContentController || self.released) return;
    NSDictionary *envelope = [message.body isKindOfClass:NSDictionary.class] ? message.body : nil;
    if (![envelope[@"token"] isEqual:self.documentToken]) return;
    if ([message.name isEqual:@"ComposeWebViewPageMessage"]) {
        NSString *channel = GYString(envelope[@"channel"]), *data = GYString(envelope[@"data"]), *source = message.frameInfo.request.URL.absoluteString;
        if (!self.pageVisible || self.pageMessageRevoked || !channel || !data || data.length > 65536 || ![data canBeConvertedToEncoding:NSUTF8StringEncoding] || [data lengthOfBytesUsingEncoding:NSUTF8StringEncoding] > 65536 || self.pageMessageReplies.count >= 128 || ![self.request[@"pageMessageChannels"] containsObject:channel] || ![self pageMessageAllowed:source] || ![GYFrameOrigin(message.frameInfo) isEqual:GYOrigin(source)]) return;
        NSString *replyId = NSUUID.UUID.UUIDString;
        self.pageMessageReplies[replyId] = @{@"channel": channel, @"source": source};
        [self emit:@{@"type": @"pageMessage", @"channel": channel, @"data": data, @"replyId": replyId}];
        return;
    }
    NSString *raw = GYString(envelope[@"value"]);
    if ([message.name isEqual:@"JSAndroidBridge"]) {
        if (!self.pageVisible) return;
        WKSecurityOrigin *origin = message.frameInfo.securityOrigin;
        NSString *source = GYSourceOrigin(origin);
        if (![self bridgeAllowed:source] || ![self bridgeAllowed:self.webView.URL.absoluteString]) return;
        NSRange separator = [raw rangeOfString:@"\x1F"];
        if (!raw || [raw lengthOfBytesUsingEncoding:NSUTF8StringEncoding] > 65536 || separator.location == NSNotFound || separator.location == 0 || separator.location > 80) return;
        [self emit:@{@"type": @"bridgeMessage", @"handlerName": [raw substringToIndex:separator.location], @"data": [raw substringFromIndex:separator.location + 1]}];
    } else if ([raw hasPrefix:@"fullscreen:"]) { self.fullscreen = [raw isEqual:@"fullscreen:1"]; [self emit:@{@"type": @"fullscreenChanged", @"isFullscreen": @(self.fullscreen)}]; }
    else if ([raw hasPrefix:@"perf:"]) {
        if (!self.pageVisible) return;
        NSArray *parts = [raw componentsSeparatedByString:@":"];
        NSDictionary *names = @{@"dns": @"DNS_LOOKUP", @"tcp": @"TCP_CONNECT", @"tls": @"TLS_HANDSHAKE", @"request": @"REQUEST", @"response": @"RESPONSE", @"ttfb": @"TIME_TO_FIRST_BYTE", @"dom": @"DOM_CONTENT_LOADED", @"visible": @"FIRST_CONTENT_VISIBLE", @"fcp": @"FIRST_CONTENTFUL_PAINT", @"lcp": @"LARGEST_CONTENTFUL_PAINT"};
        if (parts.count == 3 && names[parts[1]]) {
            NSScanner *scanner = [NSScanner scannerWithString:parts[2]]; double duration;
            if ([scanner scanDouble:&duration] && scanner.isAtEnd && isfinite(duration) && duration >= 0) [self emit:@{@"type": @"performanceMetric", @"name": names[parts[1]], @"navigationDurationMillis": @((long long)duration)}];
        }
    }
}
- (void)hrv_callWithMethod:(NSString *)method params:(NSString *)params callback:(KuiklyRenderCallback)callback {
    WKWebView *owner = self.webView;
    if (self.released) { if ([@[@"replyPageMessage", @"goBack", @"exitFullscreen"] containsObject:method] && callback) callback(@{@"result": @NO}); return; }
    id result = @NO;
    if ([method isEqual:@"reload"]) {
        // 重建具名频道 owner，旧文档不能抢占宿主 reload 的下一次导航授权。
        if (owner && [self.request[@"pageMessageChannels"] count]) {
            NSUInteger generation = self.callbackGeneration + 1;
            [self releaseWebView];
            if (!self.released && !self.webView && self.callbackGeneration == generation) [self createWebView];
            result = self.webView ? @YES : @NO;
        }
        else { [owner reload]; result = owner ? @YES : @NO; }
    }
    else if ([method isEqual:@"goBack"]) {
        if (self.fullscreen) { [self exitFullscreen:callback fallbackToHistory:YES]; return; }
        result = @(owner.canGoBack); if (owner.canGoBack) { [self.pageMessageReplies removeAllObjects]; self.pageMessageRevoked = YES; [owner goBack]; }
    }
    else if ([method isEqual:@"goForward"]) { result = @(owner.canGoForward); if (owner.canGoForward) { [self.pageMessageReplies removeAllObjects]; self.pageMessageRevoked = YES; [owner goForward]; } }
    else if ([method isEqual:@"stopLoading"]) {
        [self.pageMessageReplies removeAllObjects]; self.pageMessageRevoked = YES;
        // 取消回执可重入创建/加载新文档；只在交付回执之前停止旧加载。
        [owner stopLoading];
        NSUInteger generation = self.callbackGeneration + 1;
        [self prepareDocumentScripts];
        if (!self.released && self.webView == owner && self.callbackGeneration == generation)
            [owner evaluateJavaScript:[self transportScript] completionHandler:nil];
        result = owner ? @YES : @NO;
    }
    else if ([method isEqual:@"exitFullscreen"]) { [self exitFullscreen:callback fallbackToHistory:NO]; return; }
    else if ([method isEqual:@"replyPageMessage"]) {
        NSDictionary *value = GYObject(params);
        NSString *replyId = GYString(value[@"replyId"]), *data = GYString(value[@"data"]);
        NSDictionary *reply = replyId ? self.pageMessageReplies[replyId] : nil;
        if (owner && self.pageVisible && !self.pageMessageRevoked && data && data.length <= 65536 && [data canBeConvertedToEncoding:NSUTF8StringEncoding] && [data lengthOfBytesUsingEncoding:NSUTF8StringEncoding] <= 65536 && reply && [self.request[@"pageMessageChannels"] containsObject:reply[@"channel"]] && [self pageMessageAllowed:reply[@"source"]]) {
            [self.pageMessageReplies removeObjectForKey:replyId];
            NSString *script = [NSString stringWithFormat:@"window.__GY_WEBVIEW_PAGE_MESSAGE_REPLY__ && window.__GY_WEBVIEW_PAGE_MESSAGE_REPLY__(%@,%@,%@)", GYQuote(self.documentToken), GYQuote(reply[@"channel"]), GYQuote(data)];
            [owner evaluateJavaScript:script completionHandler:nil];
            result = @YES;
        }
    }
    else if ([method isEqual:@"evaluateJavascript"]) {
        NSString *script = GYString(GYObject(params)[@"script"]);
        if (!owner || !script || !self.pageVisible || !GYBool(self.request[@"settings"], @"javaScriptEnabled", NO) || !([self trusted:owner.URL.absoluteString] || (GYBool(self.request[@"security"], @"pageBridgeEnabled", NO) && [self bridgeAllowed:owner.URL.absoluteString]))) { if (callback) callback(@{@"result": NSNull.null}); return; }
        NSUInteger generation = self.callbackGeneration;
        __weak typeof(self) weakSelf = self;
        [owner evaluateJavaScript:script completionHandler:^(id value, NSError *error) {
            if (!weakSelf || weakSelf.released || !weakSelf.pageVisible || weakSelf.webView != owner || weakSelf.callbackGeneration != generation) return;
            BOOL valid = !error;
            id result = NSNull.null;
            if (valid && value) { NSData *data = [NSJSONSerialization dataWithJSONObject:value options:NSJSONWritingFragmentsAllowed error:nil]; if (data) result = [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding]; }
            if (callback) callback(@{@"result": result});
        }];
        return;
    }
    if (callback) callback(@{@"result": result});
}
- (void)webView:(WKWebView *)view runOpenPanelWithParameters:(WKOpenPanelParameters *)parameters initiatedByFrame:(WKFrameInfo *)frame completionHandler:(void (^)(NSArray<NSURL *> *))completion API_AVAILABLE(ios(18.4)) {
    if (self.released || view != self.webView || !self.pageVisible || !frame.isMainFrame || parameters.allowsDirectories || !GYBool(self.request[@"security"], @"fileChooserEnabled", NO) || ![self trusted:frame.request.URL.absoluteString] || ![GYFrameOrigin(frame) isEqual:GYOrigin(view.URL.absoluteString)] || ![GYOrigin(frame.request.URL.absoluteString) isEqual:GYOrigin(view.URL.absoluteString)] || self.filePickerCompletion) { completion(nil); return; }
    self.maxFiles = parameters.allowsMultipleSelection ? 10 : 1;
    self.filePickerCompletion = completion; self.filePickerGeneration = self.callbackGeneration; self.fileOrigin = view.URL.absoluteString;
    NSUInteger revision = ++self.filePickerRevision;
    [view evaluateJavaScript:IOS_FILE_INPUT_SCRIPT completionHandler:^(id value, NSError *error) {
        if (revision != self.filePickerRevision) return;
        if (![self filePickerAllowed:view]) { [self cancelFilePicker]; return; }
        if (!GYString(value) || [value length] > 16384) { [self cancelFilePicker]; return; }
        NSDictionary *input = GYObject(value);
        if (error || !input) { [self cancelFilePicker]; return; }
        NSString *accept = GYString(input[@"accept"]);
        id capture = input[@"capture"];
        if (!accept || accept.length > 2048 || !capture || CFGetTypeID((__bridge CFTypeRef)capture) != CFBooleanGetTypeID()) { [self cancelFilePicker]; return; }
        NSArray *accepts = [accept componentsSeparatedByString:@","];
        if (accepts.count > 32) { [self cancelFilePicker]; return; }
        NSMutableArray<UTType *> *types = [NSMutableArray array];
        for (NSString *part in accepts) {
            NSString *mime = [part stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceCharacterSet].lowercaseString;
            if (!mime.length) continue;
            UTType *type = [mime isEqual:@"*/*"] ? UTTypeItem : [mime isEqual:@"image/*"] ? UTTypeImage : [mime isEqual:@"video/*"] ? UTTypeMovie : [mime hasPrefix:@"."] ? [UTType typeWithFilenameExtension:[mime substringFromIndex:1]] : [UTType typeWithMIMEType:mime];
            if (!type) { [self cancelFilePicker]; return; }
            [types addObject:type];
        }
        self.fileTypes = types.count ? types : @[UTTypeItem];
        if (GYBool(input, @"capture", NO)) { [self captureFile:view]; return; }
        [self presentDocumentPicker:parameters.allowsMultipleSelection];
    }];
}
- (BOOL)filePickerAllowed:(WKWebView *)owner {
    return owner && owner == self.webView && !self.released && self.pageVisible && self.filePickerCompletion && self.filePickerGeneration == self.callbackGeneration && GYBool(self.request[@"security"], @"fileChooserEnabled", NO) && [self trusted:owner.URL.absoluteString] && [GYOrigin(self.fileOrigin) isEqual:GYOrigin(owner.URL.absoluteString)];
}
- (UIViewController *)filePresenter {
    UIViewController *presenter = self.window.rootViewController;
    while (presenter.presentedViewController) presenter = presenter.presentedViewController;
    return presenter;
}
- (void)presentDocumentPicker:(BOOL)multiple {
    UIViewController *presenter = [self filePresenter];
    if (!presenter) { [self cancelFilePicker]; return; }
    UIDocumentPickerViewController *picker = [[UIDocumentPickerViewController alloc] initForOpeningContentTypes:self.fileTypes asCopy:YES];
    picker.allowsMultipleSelection = multiple;
    picker.delegate = self;
    self.filePicker = picker;
    [presenter presentViewController:picker animated:YES completion:nil];
}
- (void)captureFile:(WKWebView *)owner {
    BOOL image = [self.fileTypes containsObject:UTTypeItem] || [self.fileTypes indexOfObjectPassingTest:^BOOL(UTType *type, NSUInteger index, BOOL *stop) { return [UTTypeJPEG conformsToType:type]; }] != NSNotFound;
    BOOL video = !image && [self.fileTypes indexOfObjectPassingTest:^BOOL(UTType *type, NSUInteger index, BOOL *stop) { return [UTTypeQuickTimeMovie conformsToType:type] || [UTTypeMPEG4Movie conformsToType:type]; }] != NSNotFound;
    if ((!image && !video) || !GYBool(self.request[@"security"], @"mediaCaptureEnabled", NO) || ![UIImagePickerController isSourceTypeAvailable:UIImagePickerControllerSourceTypeCamera] || ![NSBundle.mainBundle objectForInfoDictionaryKey:@"NSCameraUsageDescription"] || (video && ![NSBundle.mainBundle objectForInfoDictionaryKey:@"NSMicrophoneUsageDescription"])) { [self cancelFilePicker]; return; }
    NSUInteger generation = self.filePickerGeneration;
    NSUInteger revision = self.filePickerRevision;
    void (^present)(void) = ^{
        if (revision != self.filePickerRevision) return;
        if (![self filePickerAllowed:owner] || generation != self.filePickerGeneration) { [self cancelFilePicker]; return; }
        UIViewController *presenter = [self filePresenter];
        if (!presenter) { [self cancelFilePicker]; return; }
        UIImagePickerController *picker = [UIImagePickerController new]; picker.sourceType = UIImagePickerControllerSourceTypeCamera;
        picker.mediaTypes = @[image ? UTTypeImage.identifier : UTTypeMovie.identifier]; picker.videoMaximumDuration = 60; picker.delegate = self;
        self.capturePicker = picker; [presenter presentViewController:picker animated:YES completion:nil];
    };
    void (^authorizeAudio)(void) = ^{
        if (!video) { present(); return; }
        [AVCaptureDevice requestAccessForMediaType:AVMediaTypeAudio completionHandler:^(BOOL granted) { dispatch_async(dispatch_get_main_queue(), ^{ if (revision != self.filePickerRevision) return; if (granted) present(); else [self cancelFilePicker]; }); }];
    };
    [AVCaptureDevice requestAccessForMediaType:AVMediaTypeVideo completionHandler:^(BOOL granted) { dispatch_async(dispatch_get_main_queue(), ^{ if (revision != self.filePickerRevision) return; if ([self filePickerAllowed:owner] && generation == self.filePickerGeneration && granted) authorizeAudio(); else [self cancelFilePicker]; }); }];
}
- (void)imagePickerController:(UIImagePickerController *)picker didFinishPickingMediaWithInfo:(NSDictionary<UIImagePickerControllerInfoKey,id> *)info {
    if (picker != self.capturePicker) return;
    if (![self filePickerAllowed:self.webView]) { [self cancelFilePicker]; return; }
    NSURL *destination = [NSURL fileURLWithPath:[NSTemporaryDirectory() stringByAppendingPathComponent:[NSUUID.UUID.UUIDString stringByAppendingString:[info[UIImagePickerControllerMediaType] isEqual:UTTypeMovie.identifier] ? @".mov" : @".jpg"]]];
    BOOL saved = NO;
    if ([info[UIImagePickerControllerMediaType] isEqual:UTTypeMovie.identifier]) {
        NSURL *source = [info[UIImagePickerControllerMediaURL] isKindOfClass:NSURL.class] ? info[UIImagePickerControllerMediaURL] : nil;
        saved = [source.pathExtension.lowercaseString isEqual:@"mov"] && [NSFileManager.defaultManager copyItemAtURL:source toURL:destination error:nil];
    }
    else { NSData *data = UIImageJPEGRepresentation(info[UIImagePickerControllerOriginalImage], 0.9); saved = data.length && [data writeToURL:destination atomically:YES]; }
    if (!self.temporaryFiles) self.temporaryFiles = [NSMutableArray array];
    if (!saved) [NSFileManager.defaultManager removeItemAtURL:destination error:nil];
    BOOL acceptsMOV = [self.fileTypes indexOfObjectPassingTest:^BOOL(UTType *type, NSUInteger index, BOOL *stop) { return [UTTypeQuickTimeMovie conformsToType:type]; }] != NSNotFound;
    BOOL acceptsMP4 = [self.fileTypes indexOfObjectPassingTest:^BOOL(UTType *type, NSUInteger index, BOOL *stop) { return [UTTypeMPEG4Movie conformsToType:type]; }] != NSNotFound;
    if (saved && [destination.pathExtension isEqual:@"mov"] && !acceptsMOV && acceptsMP4) [self exportCapturedMovie:destination];
    else [self finishFiles:saved ? @[destination] : nil];
}
// Input and final output are limited to 50 MiB; AVFoundation intermediate disk usage is not bounded.
- (void)exportCapturedMovie:(NSURL *)source {
    NSNumber *size = [NSFileManager.defaultManager attributesOfItemAtPath:source.path error:nil][NSFileSize];
    if (!self.filePickerCompletion || ![self filePickerAllowed:self.webView] || size.longLongValue <= 0 || size.longLongValue > 50 * 1024 * 1024) {
        [NSFileManager.defaultManager removeItemAtURL:source error:nil]; [self cancelFilePicker]; return;
    }
    NSURL *target = [NSURL fileURLWithPath:[NSTemporaryDirectory() stringByAppendingPathComponent:[NSUUID.UUID.UUIDString stringByAppendingString:@".mp4"]]];
    AVAssetExportSession *session = [[AVAssetExportSession alloc] initWithAsset:[AVURLAsset URLAssetWithURL:source options:nil] presetName:AVAssetExportPresetHighestQuality];
    if (!session || ![session.supportedFileTypes containsObject:AVFileTypeMPEG4]) {
        [NSFileManager.defaultManager removeItemAtURL:source error:nil]; [self cancelFilePicker]; return;
    }
    NSUInteger revision = self.filePickerRevision;
    self.videoExport = session; self.exportingFiles = @[source, target];
    session.outputURL = target; session.outputFileType = AVFileTypeMPEG4;
    [session exportAsynchronouslyWithCompletionHandler:^{ dispatch_async(dispatch_get_main_queue(), ^{
        [NSFileManager.defaultManager removeItemAtURL:source error:nil];
        if (self.videoExport == session) { self.videoExport = nil; self.exportingFiles = nil; }
        if (revision != self.filePickerRevision) [NSFileManager.defaultManager removeItemAtURL:target error:nil];
        else if (session.status == AVAssetExportSessionStatusCompleted && [self filePickerAllowed:self.webView]) [self finishFiles:@[target]];
        else { [NSFileManager.defaultManager removeItemAtURL:target error:nil]; [self cancelFilePicker]; }
    }); }];
}
- (void)imagePickerControllerDidCancel:(UIImagePickerController *)picker { if (picker == self.capturePicker) [self cancelFilePicker]; }
- (void)finishFiles:(NSArray<NSURL *> *)urls {
    BOOL allowed = [self filePickerAllowed:self.webView] && urls.count > 0 && urls.count <= (self.maxFiles ?: 1);
    if (allowed) for (NSURL *url in urls) {
        NSNumber *size, *regular; UTType *type;
        if (!url.isFileURL || ![url getResourceValue:&regular forKey:NSURLIsRegularFileKey error:nil] || !regular.boolValue || ![url getResourceValue:&size forKey:NSURLFileSizeKey error:nil] || size.longLongValue <= 0 || size.longLongValue > 50 * 1024 * 1024 || ![url getResourceValue:&type forKey:NSURLContentTypeKey error:nil] || [self.fileTypes indexOfObjectPassingTest:^BOOL(UTType *accept, NSUInteger index, BOOL *stop) { return [type conformsToType:accept]; }] == NSNotFound) allowed = NO;
    }
    if (self.capturePicker) {
        if (allowed) { if (!self.temporaryFiles) self.temporaryFiles = [NSMutableArray array]; [self.temporaryFiles addObjectsFromArray:urls]; }
        else for (NSURL *url in urls) [NSFileManager.defaultManager removeItemAtURL:url error:nil];
    }
    void (^completion)(NSArray<NSURL *> *) = self.filePickerCompletion; self.filePickerCompletion = nil;
    self.filePickerRevision++;
    self.filePicker.delegate = nil; [self.filePicker dismissViewControllerAnimated:NO completion:nil]; self.filePicker = nil;
    self.capturePicker.delegate = nil; [self.capturePicker dismissViewControllerAnimated:NO completion:nil]; self.capturePicker = nil;
    if (completion) completion(allowed ? urls : nil);
}
- (void)documentPicker:(UIDocumentPickerViewController *)picker didPickDocumentsAtURLs:(NSArray<NSURL *> *)urls {
    if (picker != self.filePicker) return;
    [self finishFiles:urls];
}
- (void)documentPickerWasCancelled:(UIDocumentPickerViewController *)picker { if (picker == self.filePicker) [self cancelFilePicker]; }
- (void)cancelFilePicker {
    self.filePickerRevision++;
    [self.videoExport cancelExport]; self.videoExport = nil;
    for (NSURL *url in self.exportingFiles) [NSFileManager.defaultManager removeItemAtURL:url error:nil];
    self.exportingFiles = nil;
    UIDocumentPickerViewController *picker = self.filePicker; self.filePicker = nil;
    void (^completion)(NSArray<NSURL *> *) = self.filePickerCompletion; self.filePickerCompletion = nil;
    picker.delegate = nil; [picker dismissViewControllerAnimated:NO completion:nil];
    self.capturePicker.delegate = nil; [self.capturePicker dismissViewControllerAnimated:NO completion:nil]; self.capturePicker = nil;
    if (completion) completion(nil);
}
- (void)revokeFilePicker {
    NSArray<NSURL *> *files = self.temporaryFiles.copy;
    [self.temporaryFiles removeAllObjects];
    for (NSURL *url in files) [NSFileManager.defaultManager removeItemAtURL:url error:nil];
    [self cancelFilePicker];
}
- (NSArray *)takeFullscreenCancellations {
    NSArray *pending = self.fullscreenCancellations.allValues ?: @[];
    [self.fullscreenCancellations removeAllObjects];
    return pending;
}
- (void)exitFullscreen:(KuiklyRenderCallback)callback fallbackToHistory:(BOOL)back {
    WKWebView *owner = self.webView;
    if (!owner || !self.fullscreen) { if (callback) callback(@{@"result": @NO}); return; }
    NSUInteger generation = self.callbackGeneration;
    __weak typeof(self) weakSelf = self;
    NSString *identifier = NSUUID.UUID.UUIDString;
    __block BOOL settled = NO;
    void (^settle)(BOOL) = ^(BOOL result) {
        if (settled) return;
        settled = YES;
        [weakSelf.fullscreenCancellations removeObjectForKey:identifier];
        if (callback) callback(@{@"result": @(result)});
    };
    if (!self.fullscreenCancellations) self.fullscreenCancellations = [NSMutableDictionary dictionary];
    self.fullscreenCancellations[identifier] = [^{ settle(NO); } copy];
    [owner callAsyncJavaScript:IOS_EXIT_FULLSCREEN_SCRIPT arguments:@{} inFrame:nil inContentWorld:WKContentWorld.pageWorld completionHandler:^(id value, NSError *error) {
        GYWebView *strongSelf = weakSelf;
        if (settled) return;
        if (!strongSelf || strongSelf.released || strongSelf.webView != owner || strongSelf.callbackGeneration != generation) { settle(NO); return; }
        BOOL consumed = !error && [value boolValue];
        if (consumed) strongSelf.fullscreen = NO;
        else if (back && owner.canGoBack) { [strongSelf.pageMessageReplies removeAllObjects]; strongSelf.pageMessageRevoked = YES; [owner goBack]; consumed = YES; }
        settle(consumed);
    }];
}
- (void)updateMediaVisibility {
    if (!self.webView) return;
    if (!self.pageVisible) [self exitFullscreen:nil fallbackToHistory:NO];
    if (@available(iOS 15.0, *)) [self.webView setAllMediaPlaybackSuspended:!self.pageVisible completionHandler:nil];
    else if (!self.pageVisible) [self.webView evaluateJavaScript:@"document.querySelectorAll('video,audio').forEach(function(v){v.pause();});" completionHandler:nil];
}
- (void)releaseWebView {
    self.initialHtmlNavigation = NO;
    self.callbackGeneration++;
    NSArray *cancellations = [self takeFullscreenCancellations];
    [self.pageMessageReplies removeAllObjects];
    self.pageMessageRevoked = YES;
    self.pageMessageInitialNavigation = NO;
    WKWebView *owner = self.webView; self.webView = nil; self.documentToken = nil; self.fullscreen = NO;
    if (owner) {
    for (NSString *key in @[@"estimatedProgress", @"title", @"canGoBack", @"canGoForward"]) [owner removeObserver:self forKeyPath:key];
    [owner stopLoading]; owner.navigationDelegate = nil; owner.UIDelegate = nil;
    [owner.configuration.userContentController removeAllUserScripts];
    [owner.configuration.userContentController removeScriptMessageHandlerForName:@"JSAndroidBridge"];
    [owner.configuration.userContentController removeScriptMessageHandlerForName:@"ComposeWebViewEvent"];
    [owner.configuration.userContentController removeScriptMessageHandlerForName:@"ComposeWebViewPageMessage"];
    [owner removeFromSuperview];
    }
    [self revokeFilePicker];
    // 先完成旧 owner 的清理，再交付取消，允许回调同步绑定新 owner。
    for (void (^cancel)(void) in cancellations) cancel();
}
- (void)hrv_removeFromSuperview { self.released = YES; [self releaseWebView]; self.onEvent = nil; self.request = nil; [self removeFromSuperview]; }
- (void)dealloc { [self releaseWebView]; }
@end

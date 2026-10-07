Pod::Spec.new do |s|
  s.name = 'GYWebView'
  s.version = '0.2.0-rc.11'
  s.summary = '由 gycrosskit 维护的 Kuikly 系统 WKWebView 适配'
  s.homepage = 'https://github.com/gycrosskit/compose-webview'
  s.license = { :type => 'Apache-2.0', :file => 'LICENSE' }
  s.author = 'GY CrossKit'
  s.source = { :git => 'https://github.com/gycrosskit/compose-webview.git', :tag => s.version.to_s }
  s.platform = :ios, '15.0'
  s.source_files = 'ios/Sources/**/*.{h,m,inc}'
  s.public_header_files = 'ios/Sources/GYWebView.h'
  s.frameworks = 'UIKit', 'WebKit', 'UniformTypeIdentifiers', 'AVFoundation'
  s.dependency 'OpenKuiklyIOSRender', '2.28.0'
  s.user_target_xcconfig = { 'OTHER_LDFLAGS' => '$(inherited) -ObjC' }
  s.requires_arc = true
end

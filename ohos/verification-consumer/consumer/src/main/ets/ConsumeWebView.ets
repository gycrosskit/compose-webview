import { GYWebView, WebViewRequest, WebEvent, KuiklyRenderBaseView } from '@gycrosskit/webview';

export const request: WebViewRequest = {
  content: { type: 'url', url: 'https://example.test' },
  settings: { javaScriptEnabled: false },
  security: { trustedOrigins: { urls: [], trustedHostSuffixes: [] } },
  scripts: [], blockedResourceRules: [],
  navigationPolicy: { allowedSchemes: ['https'], blockedRules: [], allowNewWindows: false }
};

/** 只编译产物的公开类型、属性与注册工厂，不启动网页或系统权限。 */
export function createWebView(): KuiklyRenderBaseView {
  const view = new GYWebView();
  view.setProp('request', JSON.stringify(request));
  view.setProp('visible', true);
  view.setProp('onEvent', (event) => {});
  view.call('exitFullscreen', null, (result) => {});
  return view;
}
export function consumeEvent(event: WebEvent): string { return event.type; }
export const viewName: string = GYWebView.VIEW_NAME;

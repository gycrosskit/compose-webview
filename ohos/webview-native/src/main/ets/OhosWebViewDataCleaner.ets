import { webview } from '@kit.ArkWeb';

/**
 * 清理应用共享的默认 ArkWeb 数据，不创建或持有 WebView。
 * 宿主在 UI 线程、Web 组件加载后调用，并负责清理时机与自身账号/缓存。
 * Cookie 与 WebStorage 使用默认非隐私存储；不承诺隐私模式清理。
 * 取消等待或销毁宿主不能撤销已发起的系统删除。
 */
export class OhosWebViewDataCleaner {
  /** 删除内存与磁盘资源缓存，保留 Cookie 和网站存储；系统异常原样抛出。 */
  static clearResourceCache(): void {
    webview.WebviewController.removeAllCache(true);
  }

  /**
   * 依次清理资源缓存、Cookie 与 WebStorage；任一步失败即 reject，不回报成功。
   * 等待 Cookie 删除完成；缓存与 WebStorage 无完成回调，仅确认系统 API 已返回，
   * 不证明业务退出、持久化完成或所有内核数据类型均已删除。
   */
  static async clearWebsiteData(): Promise<void> {
    OhosWebViewDataCleaner.clearResourceCache();
    await webview.WebCookieManager.clearAllCookies();
    webview.WebStorage.deleteAllData();
  }
}

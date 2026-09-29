package io.github.gycrosskit.composewebview

import androidx.core.content.FileProvider

/** 使用独立 Provider，避免与宿主已有的 FileProvider 发生 Manifest 合并冲突。 */
class ComposeWebViewFileProvider : FileProvider()

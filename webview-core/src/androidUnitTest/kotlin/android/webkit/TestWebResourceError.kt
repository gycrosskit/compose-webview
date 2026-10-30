package android.webkit

// 与 SDK 同包访问构造函数，仅用于原生错误映射回归。
class TestWebResourceError(
    private val code: Int,
    private val description: CharSequence,
) : WebResourceError() {
    override fun getErrorCode(): Int = code
    override fun getDescription(): CharSequence = description
}

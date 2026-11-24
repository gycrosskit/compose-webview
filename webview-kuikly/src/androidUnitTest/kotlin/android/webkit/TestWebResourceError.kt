package android.webkit

class TestWebResourceError(private val code: Int) : WebResourceError() {
    override fun getErrorCode() = code
    override fun getDescription() = "fixture resource failure"
}

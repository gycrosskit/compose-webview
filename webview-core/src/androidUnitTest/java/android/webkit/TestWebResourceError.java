package android.webkit;

// SDK 构造函数仅包内可见；测试替身与其同包，不放宽生产 API。
public final class TestWebResourceError extends WebResourceError {
    private final int code;
    private final CharSequence description;

    public TestWebResourceError(int code, CharSequence description) {
        this.code = code;
        this.description = description;
    }

    @Override public int getErrorCode() { return code; }
    @Override public CharSequence getDescription() { return description; }
}

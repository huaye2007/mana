package cn.managame.runtime.execution;

/** Example transport failure codes for tests; runtime does not define a wire error namespace. */
final class TestErrorCodes {
    private TestErrorCodes() {}
    static final int TIMEOUT = 6;
    static final int UNAVAILABLE = 5;
}

package cn.managame.runtime.error;

import cn.managame.core.FrameworkErrorCodes;

/**
 * Admission rejection carrying a framework error code. It is control flow, not a defect: no stack trace is
 * captured, so rejecting under overload costs no more than the allocation.
 */
public class RuntimeDispatchException extends RuntimeException {
    private final int errorCode;

    public RuntimeDispatchException(int errorCode, String message) {
        super(message, null, true, false);
        this.errorCode = errorCode;
    }

    public int errorCode() { return errorCode; }

    @Override public String toString() {
        return getClass().getName() + ": " + getMessage() + " [" + FrameworkErrorCodes.describe(errorCode) + "]";
    }
}

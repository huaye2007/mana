package cn.managame.data;

import cn.managame.FrameworkErrorCodes;

/**
 * The database failed: {@link FrameworkErrorCodes#DATA_LOAD_FAILED} from a load, {@link FrameworkErrorCodes#DATA_SAVE_FAILED}
 * from a flush or close that left changes unsaved.
 */
public class DataException extends RuntimeException {
    private final int errorCode;

    public DataException(int errorCode, String message, Throwable cause) {
        super(FrameworkErrorCodes.describe(errorCode) + ": " + message, cause);
        this.errorCode = errorCode;
    }

    public int errorCode() { return errorCode; }
}

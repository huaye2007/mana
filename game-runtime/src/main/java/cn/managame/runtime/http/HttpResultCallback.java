package cn.managame.runtime.http;

/** Protocol-independent business completion. The winning object is encoded synchronously before return. */
public interface HttpResultCallback {
    default boolean onResponse(Object result) { return onResponse(200, result); }
    /** Final HTTP status plus a business object; null encodes as a JSON null with the default codec. */
    boolean onResponse(int statusCode, Object result);
    /** A null cause is rejected without claiming completion. */
    boolean onFail(Throwable cause);
}

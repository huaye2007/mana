package cn.managame.runtime.http;

import cn.managame.runtime.context.Context;
import io.netty.handler.codec.http.FullHttpRequest;

/** HTTP Route and completion context. Request access is borrowed only through the annotated method's return. */
public interface HttpContext extends Context {
    FullHttpRequest request();
    HttpResultCallback responseCallback();
}

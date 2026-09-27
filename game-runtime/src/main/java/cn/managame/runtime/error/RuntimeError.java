package cn.managame.runtime.error;

import cn.managame.runtime.context.Context;

public record RuntimeError(int errorCode, Context context, int routeDomain, long routeKey, Throwable cause) {}

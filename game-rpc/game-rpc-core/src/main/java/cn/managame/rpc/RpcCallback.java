package cn.managame.rpc;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * 回调式调用的响应回调。框架根据泛型参数 V 自动反序列化响应 body。
 * 反序列化和回调在 Netty IO 线程或超时 timer 线程上执行，实现内禁止阻塞，
 * 需要切换到业务线程由实现方自行投递。
 * 回调风格只拿业务结果；需要读响应 metadata（如链路追踪回传）用 future 风格
 * （{@link RpcResponse#metadata()}）。
 */
public abstract class RpcCallback<V> {

    /** 泛型反射解析按子类缓存，避免每次实例化（每次 RPC 调用）都走 getGenericSuperclass */
    private static final ClassValue<Class<?>> RESPONSE_TYPE_CACHE = new ClassValue<>() {
        @Override
        protected Class<?> computeValue(Class<?> type) {
            return resolveResponseType(type);
        }
    };

    private final Class<V> responseType;

    @SuppressWarnings("unchecked")
    protected RpcCallback() {
        this.responseType = (Class<V>) RESPONSE_TYPE_CACHE.get(getClass());
    }

    private static Class<?> resolveResponseType(Class<?> callbackClass) {
        Type superClass = callbackClass.getGenericSuperclass();
        if (!(superClass instanceof ParameterizedType parameterizedType)) {
            throw new IllegalStateException("RpcCallback subclass must be parameterized, e.g. new RpcCallback<LoginResp>(){...}");
        }
        Type actual = parameterizedType.getActualTypeArguments()[0];
        if (actual instanceof Class<?> clazz) {
            return clazz;
        }
        if (actual instanceof ParameterizedType nested && nested.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        throw new IllegalStateException("cannot resolve response type from " + actual);
    }

    public Class<V> getResponseType() {
        return responseType;
    }

    public abstract void onSuccess(V result);

    public abstract void onException(Throwable error);
}

package cn.managame.runtime.route;

import java.util.Objects;
import cn.managame.runtime.internal.MessageRouteKey;
public record RouteKeyBinding<T>(Class<T> messageType, RouteKeyExtractor<T> extractor) {
    public RouteKeyBinding { Objects.requireNonNull(messageType); Objects.requireNonNull(extractor); }
    public static <T> RouteKeyBinding<T> of(Class<T> type, RouteKeyExtractor<T> extractor) { return new RouteKeyBinding<>(type, extractor); }
    public static <T> RouteKeyBinding<T> ofField(Class<T> type, String field) {
        return of(type, MessageRouteKey.field(type, field));
    }
    public static <T> RouteKeyBinding<T> ofMethod(Class<T> type, String method) {
        return of(type, MessageRouteKey.method(type, method));
    }
    long extract(Object message) { return extractor.extract(messageType.cast(message)); }
}

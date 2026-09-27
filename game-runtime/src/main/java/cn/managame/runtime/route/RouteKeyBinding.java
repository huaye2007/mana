package cn.managame.runtime.route;

import java.util.Objects;
public record RouteKeyBinding<T>(Class<T> messageType, RouteKeyExtractor<T> extractor) {
    public RouteKeyBinding { Objects.requireNonNull(messageType); Objects.requireNonNull(extractor); }
    public static <T> RouteKeyBinding<T> of(Class<T> type, RouteKeyExtractor<T> extractor) { return new RouteKeyBinding<>(type, extractor); }
    long extract(Object message) { return extractor.extract(messageType.cast(message)); }
}

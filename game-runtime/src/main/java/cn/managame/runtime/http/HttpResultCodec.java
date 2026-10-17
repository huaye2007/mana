package cn.managame.runtime.http;


/** Encodes ordinary business objects to an owned byte array, without an HTTP-version or Netty type. */
@FunctionalInterface
public interface HttpResultCodec {
    byte[] encode(Object result) throws Exception;
    default String contentType() { return "application/json; charset=UTF-8"; }
    /**
     * Immutable Jackson writer with the same property rules as the default request codec (fields of any
     * visibility plus getters); no automatic module discovery or business body binding.
     */
    static HttpResultCodec json() {
        var writer = JsonSupport.MAPPER.writer();
        return writer::writeValueAsBytes;
    }
}

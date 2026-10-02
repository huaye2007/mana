package cn.managame.runtime.http;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Encodes ordinary business objects to an owned byte array, without an HTTP-version or Netty type. */
@FunctionalInterface
public interface HttpResultCodec {
    byte[] encode(Object result) throws Exception;
    default String contentType() { return "application/json; charset=UTF-8"; }
    /** An independent, immutable Jackson writer; no automatic module discovery or business body binding. */
    static HttpResultCodec json() {
        var writer = new ObjectMapper().writer();
        return writer::writeValueAsBytes;
    }
}

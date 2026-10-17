package cn.managame.runtime.http;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The one Jackson configuration shared by the default HTTP request and result codecs, so a DTO reads and
 * writes the same properties: fields of any visibility (plus ordinary getters/setters), unknown request
 * properties ignored, trailing tokens rejected. The mapper is configured once and only used through
 * immutable readers and writers afterwards.
 */
final class JsonSupport {
    static final ObjectMapper MAPPER = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private JsonSupport() {}
}

package com.github.huaye2007.mana.jpa.core.exception;

/**
 * 元数据解析异常。
 */
public class MetadataException extends GameJpaException {

    public MetadataException(String message) {
        super(message);
    }

    public MetadataException(String message, Throwable cause) {
        super(message, cause);
    }
}

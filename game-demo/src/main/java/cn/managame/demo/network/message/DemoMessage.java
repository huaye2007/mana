package cn.managame.demo.network.message;

/** Application payload; GamePacket continues to carry its serialized bytes. */
public record DemoMessage(long userId, String message) {}

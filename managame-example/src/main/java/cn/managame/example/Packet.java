package cn.managame.example;

import com.google.protobuf.Message;

/**
 * One frame of the game's protocol, either way: {@code | length | message id | seq | code | protobuf body |}. A request
 * has code 0; an answer echoes its request's seq, with the response message and code 0, or no message and the error
 * code.
 */
record Packet(int seq, int code, Message message) {
    static Packet request(int seq, Message message) { return new Packet(seq, 0, message); }

    static Packet error(int seq, int code) { return new Packet(seq, code, null); }
}

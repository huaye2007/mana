package cn.managame.example;

import cn.managame.example.proto.BagRequest;
import cn.managame.example.proto.BuyRequest;
import cn.managame.example.proto.LoginRequest;
import cn.managame.example.proto.RankRequest;
import com.google.protobuf.Message;
import com.google.protobuf.TextFormat;

/**
 * Plays a few requests against a running example game server and prints each answer:
 * {@code java -cp example.jar cn.managame.example.ExampleClient [playerId] [name]}.
 */
public final class ExampleClient {
    private ExampleClient() {}

    public static void main(String[] args) throws Exception {
        long playerId = args.length > 0 ? Long.parseLong(args[0]) : 1001;
        String name = args.length > 1 ? args[1] : "ann";
        try (GameClient client = new GameClient("127.0.0.1", 9001)) {
            for (Message request : new Message[] {
                    LoginRequest.newBuilder().setPlayerId(playerId).setName(name).build(),
                    BuyRequest.newBuilder().setConfigId(5001).build(),
                    BuyRequest.newBuilder().setConfigId(5003).build(),
                    BagRequest.getDefaultInstance(),
                    RankRequest.getDefaultInstance()}) {
                Packet answer = client.request(request);
                System.out.println("> " + request.getDescriptorForType().getName() + " " + TextFormat.shortDebugString(request));
                System.out.println("< " + (answer.code() != 0 ? "error " + answer.code()
                        : answer.message().getDescriptorForType().getName() + " " + TextFormat.shortDebugString(answer.message())));
            }
        }
    }
}

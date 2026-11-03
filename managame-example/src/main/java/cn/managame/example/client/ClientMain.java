package cn.managame.example.client;

import cn.managame.example.GamePacket;
import cn.managame.example.Protocols;
import cn.managame.example.proto.Login;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 示例客户端：连上服务器，发 LoginReq 登录，之后在同一个连接上继续发其它消息。参数 host port，默认 127.0.0.1 7001。
 */
public class ClientMain {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 7001;
        try (GameClient client = GameClient.connect(new InetSocketAddress(host, port), Protocols.game(),
                push -> System.out.println("推送 " + push.getCommand() + "：" + push.getBody()), Duration.ofSeconds(5))) {
            Login.LoginReq login = Login.LoginReq.newBuilder().setServerId(1).setToken("test-token").setUserId(10001).build();
            GamePacket answer = client.request(login).get(5, TimeUnit.SECONDS);
            if (answer.getCode() != 0) {
                System.out.println("登录失败，错误码 " + answer.getCode());
                return;
            }
            Login.LoginRes res = (Login.LoginRes) answer.getBody();
            System.out.println("登录成功，roleId " + res.getRoleId());

            // 之后的消息一样发：client.request(message) 等应答，client.send(message) 不等。
            // 例如 GamePacket bag = client.request(Bag.BagReq.getDefaultInstance()).get(5, TimeUnit.SECONDS);
        }
    }
}

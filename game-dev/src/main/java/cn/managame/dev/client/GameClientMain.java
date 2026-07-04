package cn.managame.dev.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 交互式测试客户端入口：连上服务端后，从控制台读命令发消息、实时打印回包。
 *
 * <p>启动（服务端先跑起来，默认 8080）：
 * <pre>{@code
 * mvn -q -pl game-dev exec:java -Dexec.mainClass=cn.managame.dev.client.GameClientMain
 * # 或指定地址：... -Dexec.args="127.0.0.1 8080"
 * }</pre>
 *
 * <p>控制台命令：
 * <pre>
 *   login &lt;userId&gt; [token]          发登陆包 (command=1000, body=LoginReq)
 *   send  &lt;command&gt; &lt;userId&gt; [token] 发任意 command，body 用 LoginReq 形状填充（测路由/编解码）
 *   ping                            发心跳 (command=1001)，服务端回 HeartbeatRes
 *   raw   &lt;command&gt; [seq]            发空 body 的帧
 *   help                            打印帮助
 *   quit | exit                     断开并退出
 * </pre>
 */
public final class GameClientMain {

    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 8080;

    private GameClientMain() {
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : DEFAULT_HOST;
        int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;

        // 默认 Fory 要求类注册，发包前先把消息 DTO 登记进去
        cn.managame.dev.bootstrap.ForyMessageRegistrar.registerMessageTypes();

        GameClient client = new GameClient(host, port);
        // 回包回调跑在 IO 线程，这里只打印
        client.onResponse(resp -> System.out.println("<< " + resp));
        client.connect();

        printHelp();
        runConsole(client);

        client.close();
    }

    private static void runConsole(GameClient client) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while (client.isActive() && (line = reader.readLine()) != null) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 0 || parts[0].isEmpty()) {
                continue;
            }
            try {
                if (!dispatch(client, parts)) {
                    break; // quit/exit
                }
            } catch (RuntimeException e) {
                System.out.println("!! 命令执行失败: " + e.getMessage());
            }
        }
    }

    /** @return false 表示请求退出循环。 */
    private static boolean dispatch(GameClient client, String[] parts) {
        String cmd = parts[0].toLowerCase();
        switch (cmd) {
            case "login" -> {
                long userId = Long.parseLong(parts[1]);
                String token = parts.length > 2 ? parts[2] : "";
                int serverId = parts.length > 3 ? Integer.parseInt(parts[3]) : 0;
                int seq = client.login(userId, token,serverId);
                System.out.println(">> login userId=" + userId + " seq=" + seq);
            }
            case "send" -> {
                int command = Integer.parseInt(parts[1]);
                long userId = Long.parseLong(parts[2]);
                String token = parts.length > 3 ? parts[3] : "";
                cn.managame.dev.message.LoginReq body = new cn.managame.dev.message.LoginReq();
                body.setUserId(userId);
                body.setToken(token);
                int seq = client.send(command, body);
                System.out.println(">> send command=" + command + " seq=" + seq);
            }
            case "ping" -> {
                int seq = client.heartbeat();
                System.out.println(">> ping seq=" + seq);
            }
            case "raw" -> {
                int command = Integer.parseInt(parts[1]);
                int seq = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
                client.sendRaw(command, seq, 0, (byte) 0, new byte[0]);
                System.out.println(">> raw command=" + command + " seq=" + seq);
            }
            case "help" -> printHelp();
            case "quit", "exit" -> {
                return false;
            }
            default -> System.out.println("未知命令: " + cmd + "（输入 help 查看用法）");
        }
        return true;
    }

    private static void printHelp() {
        System.out.println("""
                命令:
                  login <userId> [token] [serverId]  发登陆包 (command=1000)
                  send  <command> <userId> [token]  发任意 command，body=LoginReq
                  ping                              发心跳 (command=1001)，回 HeartbeatRes
                  raw   <command> [seq]             发空 body 的帧
                  help                              帮助
                  quit | exit                       退出""");
    }
}

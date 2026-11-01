package cn.managame.example;

/**
 * The game's own client protocol, which the framework leaves to the application: one line of text per message,
 * {@code <seq> <command> [arguments]}, answered by {@code <seq> ok <result>} or {@code <seq> err <code>}.
 */
final class Messages {
    record Login(long playerId, String name) {}

    record Buy(int configId) {}

    record ShowBag() {}

    record AskRank() {}

    /** Sent by the gateway, not the client, when a logged-in connection closes. */
    record Logout() {}

    private Messages() {}

    /** The message of a line's command and arguments; IllegalArgumentException for one it does not know. */
    static Object decode(String command, String arguments) {
        String[] words = arguments.isBlank() ? new String[0] : arguments.strip().split("\\s+");
        return switch (command) {
            case "login" -> new Login(Long.parseLong(words[0]), words[1]);
            case "buy" -> new Buy(Integer.parseInt(words[0]));
            case "bag" -> new ShowBag();
            case "rank" -> new AskRank();
            default -> throw new IllegalArgumentException("unknown command " + command);
        };
    }
}

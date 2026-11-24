package cn.managame.ai.mcp;

import static cn.managame.FrameworkErrorCodes.AI_TOOL_EXECUTION_ERROR;
import static cn.managame.FrameworkErrorCodes.AI_TOOL_NOT_FOUND;

import cn.managame.FrameworkErrorCodes;
import cn.managame.ai.internal.Json;
import cn.managame.network.http.HttpExchange;
import cn.managame.network.http.HttpHandler;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Stateless MCP (Model Context Protocol) Streamable HTTP {@link HttpHandler} serving {@link McpTools} over
 * {@code managame-network}:
 *
 * <pre>{@code
 * NetworkServer server = NetworkServer.start(8082,
 *         HttpInitializer.http().contextPath("/mcp").handler(new McpHttpHandler(tools)).build());
 * }</pre>
 *
 * <p>Implements JSON-RPC 2.0 over HTTP POST ({@code initialize}, {@code notifications/initialized}, {@code ping},
 * {@code tools/list}, {@code tools/call}) in stateless Streamable HTTP mode: GET and DELETE return {@code 405 Method
 * Not Allowed} ({@code Allow: POST}) per the MCP Streamable HTTP specification, JSON-RPC notifications return
 * {@code 202 Accepted}, unknown tools return JSON-RPC {@code -32602}, and tool validation/rejection/execution failures
 * return a tool result with {@code isError: true} so the model can observe and recover. Never blocks the Netty
 * EventLoop.
 */
public final class McpHttpHandler implements HttpHandler {
    private static final System.Logger LOG = System.getLogger("cn.managame.ai.mcp");
    private static final String LATEST_PROTOCOL_VERSION = "2025-11-25";
    private static final Set<String> SUPPORTED_PROTOCOL_VERSIONS = Set.of(
            "2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");
    private static final int JSONRPC_PARSE_ERROR = -32700;
    private static final int JSONRPC_INVALID_REQUEST = -32600;
    private static final int JSONRPC_METHOD_NOT_FOUND = -32601;
    private static final int JSONRPC_INVALID_PARAMS = -32602;

    private static final int DEFAULT_MAX_RESULT_CHARS = 256 * 1024;

    private final McpTools tools;
    private final String serverName;
    private final String serverVersion;
    private final String instructions;
    private final int maxResultChars;

    /** Creates an MCP HTTP handler for {@code tools} with default server info ({@code managame-ai / 1.0.0}). */
    public McpHttpHandler(McpTools tools) {
        this(builder(tools));
    }

    private McpHttpHandler(Builder b) {
        this.tools = b.tools;
        this.serverName = b.serverName;
        this.serverVersion = b.serverVersion;
        this.instructions = b.instructions;
        this.maxResultChars = b.maxResultChars;
        LOG.log(Level.INFO, () -> "McpHttpHandler ready (" + serverName + " " + serverVersion
                + ", " + tools.definitions().size() + " tools)");
    }

    public static Builder builder(McpTools tools) { return new Builder(tools); }

    @Override public void handle(HttpExchange exchange) {
        if (!"/".equals(exchange.path())) {
            exchange.respond(HttpResponseStatus.NOT_FOUND);
            return;
        }
        FullHttpRequest request = exchange.request();
        if (!HttpMethod.POST.equals(request.method())) {
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.METHOD_NOT_ALLOWED);
            response.headers().set(HttpHeaderNames.ALLOW, "POST");
            exchange.respond(response);
            return;
        }
        String body = request.content().toString(StandardCharsets.UTF_8);
        Map<String, String> headers = copyHeaders(request);
        Object parsed;
        try {
            parsed = Json.parse(body);
        } catch (IllegalArgumentException badJson) {
            respondJson(exchange, HttpResponseStatus.BAD_REQUEST,
                    jsonRpcError(null, JSONRPC_PARSE_ERROR, "Parse error: " + badJson.getMessage()));
            return;
        }
        if (!(parsed instanceof Map<?, ?> rawEnvelope)) {
            respondJson(exchange, HttpResponseStatus.BAD_REQUEST,
                    jsonRpcError(null, JSONRPC_INVALID_REQUEST, "JSON-RPC request must be a JSON object"));
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> envelope = (Map<String, Object>) rawEnvelope;
        if (!"2.0".equals(envelope.get("jsonrpc"))) {
            respondJson(exchange, HttpResponseStatus.BAD_REQUEST,
                    jsonRpcError(null, JSONRPC_INVALID_REQUEST, "Field 'jsonrpc' must be '2.0'"));
            return;
        }
        Object methodObj = envelope.get("method");
        if (!(methodObj instanceof String method) || method.isBlank()) {
            respondJson(exchange, HttpResponseStatus.BAD_REQUEST,
                    jsonRpcError(null, JSONRPC_INVALID_REQUEST, "Field 'method' must be a non-empty string"));
            return;
        }
        boolean hasId = envelope.containsKey("id");
        Object id = envelope.get("id");
        if (hasId && id != null && !(id instanceof String) && !(id instanceof Number)) {
            respondJson(exchange, HttpResponseStatus.BAD_REQUEST,
                    jsonRpcError(null, JSONRPC_INVALID_REQUEST, "Field 'id' must be a string, number or null"));
            return;
        }
        if (!hasId) {
            if (LOG.isLoggable(Level.DEBUG)) LOG.log(Level.DEBUG, () -> "MCP notification " + method);
            exchange.respond(HttpResponseStatus.ACCEPTED);
            return;
        }
        Object paramsObj = envelope.get("params");
        if (LOG.isLoggable(Level.DEBUG)) LOG.log(Level.DEBUG, () -> "MCP request " + method + " (id=" + id + ")");
        switch (method) {
            case "initialize" -> handleInitialize(exchange, id, paramsObj);
            case "ping" -> respondJson(exchange, HttpResponseStatus.OK, jsonRpcResult(id, Map.of()));
            case "tools/list" -> handleToolsList(exchange, id, headers);
            case "tools/call" -> handleToolsCall(exchange, id, paramsObj, headers);
            default -> respondJson(exchange, HttpResponseStatus.OK,
                    jsonRpcError(id, JSONRPC_METHOD_NOT_FOUND, "Method not found: " + method));
        }
    }

    private void handleInitialize(HttpExchange exchange, Object id, Object paramsObj) {
        String version = LATEST_PROTOCOL_VERSION;
        if (paramsObj instanceof Map<?, ?> params) {
            Object requested = params.get("protocolVersion");
            if (requested instanceof String reqVersion && SUPPORTED_PROTOCOL_VERSIONS.contains(reqVersion)) {
                version = reqVersion;
            }
        }
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", Map.of());
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", serverName);
        serverInfo.put("version", serverVersion);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", version);
        result.put("capabilities", capabilities);
        result.put("serverInfo", serverInfo);
        if (!instructions.isEmpty()) result.put("instructions", instructions);
        respondJson(exchange, HttpResponseStatus.OK, jsonRpcResult(id, result));
    }

    private void handleToolsList(HttpExchange exchange, Object id, Map<String, String> headers) {
        List<McpToolDefinition> visible = tools.listNormalized(headers);
        List<Map<String, Object>> serialized = new ArrayList<>(visible.size());
        for (McpToolDefinition def : visible) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", def.name());
            entry.put("description", def.description());
            entry.put("inputSchema", new Json.Raw(def.inputSchema()));
            if (def.readOnly() || def.idempotent()) {
                Map<String, Object> annotations = new LinkedHashMap<>();
                if (def.readOnly()) annotations.put("readOnlyHint", Boolean.TRUE);
                if (def.idempotent()) annotations.put("idempotentHint", Boolean.TRUE);
                entry.put("annotations", annotations);
            }
            serialized.add(entry);
        }
        respondJson(exchange, HttpResponseStatus.OK, jsonRpcResult(id, Map.of("tools", serialized)));
    }

    private void handleToolsCall(HttpExchange exchange, Object id, Object paramsObj, Map<String, String> headers) {
        if (!(paramsObj instanceof Map<?, ?> params)) {
            respondJson(exchange, HttpResponseStatus.OK,
                    jsonRpcError(id, JSONRPC_INVALID_PARAMS, "Params for tools/call must be a JSON object"));
            return;
        }
        Object nameObj = params.get("name");
        if (!(nameObj instanceof String toolName) || toolName.isBlank()) {
            respondJson(exchange, HttpResponseStatus.OK,
                    jsonRpcError(id, JSONRPC_INVALID_PARAMS, "Tool 'name' is required"));
            return;
        }
        Object argsObj = params.get("arguments");
        Map<String, Object> arguments;
        if (argsObj == null) {
            arguments = Map.of();
        } else if (argsObj instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) map;
            arguments = cast;
        } else {
            respondJson(exchange, HttpResponseStatus.OK,
                    jsonRpcError(id, JSONRPC_INVALID_PARAMS, "Tool 'arguments' must be a JSON object"));
            return;
        }
        tools.invokeParsed(toolName, arguments, null, headers, exchange.remoteAddress(), result -> {
            if (result.code() == AI_TOOL_NOT_FOUND) {
                respondJson(exchange, HttpResponseStatus.OK,
                        jsonRpcError(id, JSONRPC_INVALID_PARAMS, result.message()));
                return;
            }
            if (result.isSuccess()) {
                String text;
                boolean isError = false;
                Object data = result.data();
                if (data == null) {
                    text = "ok";
                } else if (data instanceof String s) {
                    text = s;
                } else {
                    try {
                        text = tools.codec().encode(data);
                    } catch (Throwable cause) {
                        LOG.log(Level.ERROR, "Failed to encode result of MCP tool '" + toolName + "'", cause);
                        text = FrameworkErrorCodes.describe(AI_TOOL_EXECUTION_ERROR) + ": " + cause.getMessage();
                        isError = true;
                    }
                }
                if (!isError && text.length() > maxResultChars) {
                    int actualLen = text.length();
                    LOG.log(Level.WARNING, () -> "MCP tool '" + toolName + "' result exceeded maxResultChars ("
                            + actualLen + " > " + maxResultChars + ")");
                    text = "Tool '" + toolName + "' result exceeded limit (" + maxResultChars + " chars); narrow the query";
                    isError = true;
                }
                respondJson(exchange, HttpResponseStatus.OK, jsonRpcResult(id, toolCallResult(text, isError)));
            } else {
                String text = result.message().isEmpty()
                        ? FrameworkErrorCodes.describe(result.code()) : result.message();
                respondJson(exchange, HttpResponseStatus.OK, jsonRpcResult(id, toolCallResult(text, true)));
            }
        });
    }

    private static Map<String, Object> toolCallResult(String text, boolean isError) {
        Map<String, Object> contentItem = new LinkedHashMap<>();
        contentItem.put("type", "text");
        contentItem.put("text", text);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", List.of(contentItem));
        payload.put("isError", isError);
        return payload;
    }

    private static String jsonRpcResult(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return Json.write(response);
    }

    private static String jsonRpcError(Object id, int code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", error);
        return Json.write(response);
    }

    private static void respondJson(HttpExchange exchange, HttpResponseStatus status, String json) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                Unpooled.copiedBuffer(json, StandardCharsets.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        exchange.respond(response);
    }

    private static Map<String, String> copyHeaders(FullHttpRequest request) {
        TreeMap<String, String> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, String> entry : request.headers()) {
            map.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return map.isEmpty() ? Map.of() : Collections.unmodifiableMap(map);
    }

    public static final class Builder {
        private final McpTools tools;
        private String serverName = "managame-ai";
        private String serverVersion = "1.0.0";
        private String instructions = "";
        private int maxResultChars = DEFAULT_MAX_RESULT_CHARS;

        private Builder(McpTools tools) {
            this.tools = Objects.requireNonNull(tools, "tools");
        }

        /** Server name and version returned in MCP {@code initialize}. */
        public Builder serverInfo(String name, String version) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(version, "version");
            if (name.isBlank()) throw new IllegalArgumentException("serverName must not be blank");
            if (version.isBlank()) throw new IllegalArgumentException("serverVersion must not be blank");
            this.serverName = name;
            this.serverVersion = version;
            return this;
        }

        /** Optional instructions returned in MCP {@code initialize} to guide connected AI agents. */
        public Builder instructions(String instructions) {
            this.instructions = Objects.requireNonNull(instructions, "instructions");
            return this;
        }

        /**
         * Maximum encoded result size in characters for {@code tools/call}; oversized responses return {@code isError: true}
         * so the model is told to narrow the query instead of blowing up its context window. 256 KiB chars by default.
         */
        public Builder maxResultChars(int maxResultChars) {
            if (maxResultChars <= 0) throw new IllegalArgumentException("maxResultChars must be positive");
            this.maxResultChars = maxResultChars;
            return this;
        }

        public McpHttpHandler build() { return new McpHttpHandler(this); }
    }
}

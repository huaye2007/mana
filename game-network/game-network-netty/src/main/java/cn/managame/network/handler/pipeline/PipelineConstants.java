package cn.managame.network.handler.pipeline;

public class PipelineConstants {
    // Priority constants
    public static final int PRIORITY_SSL                 = 100;
    public static final int PRIORITY_IP_FILTER           = 200;
    public static final int PRIORITY_TRAFFIC             = 300;
    public static final int PRIORITY_METRICS             = 350;
    public static final int PRIORITY_FRAMING             = 400;
    public static final int PRIORITY_IDLE_STATE          = 500;
    public static final int PRIORITY_CODEC               = 600;
    public static final int PRIORITY_AUTH                = 700;
    public static final int PRIORITY_BUSINESS_DISPATCHER = 1000;

    // Handler name constants
    public static final String NAME_METRICS      = "metricsHandler";
    public static final String NAME_SSL          = "sslHandler";
    public static final String NAME_IP_FILTER    = "ipFilterHandler";
    public static final String NAME_FRAMER       = "frameDecoder";
    public static final String NAME_IDLE_STATE   = "idleStateHandler";
    public static final String NAME_DECODER      = "packetDecoder";
    public static final String NAME_ENCODER      = "packetEncoder";
    public static final String NAME_BYTE_ARRAY_DECODER = "byteBufToByteArrayDecoder";
    public static final String NAME_BYTE_ARRAY_ENCODER = "byteArrayToByteBufEncoder";
    public static final String NAME_HTTP_CODEC = "httpServerCodec";
    public static final String NAME_HTTP_CLIENT_CODEC = "httpClientCodec";
    public static final String NAME_HTTP_AGGREGATOR = "httpObjectAggregator";
    public static final String NAME_HTTP_DISPATCHER = "httpDispatcher";
    public static final String NAME_HTTP_CLEAR_TEXT_UPGRADE = "clearTextHttp2ServerUpgradeHandler";
    public static final String NAME_HTTP2_FRAME_CODEC = "http2FrameCodec";
    public static final String NAME_HTTP2_MULTIPLEX = "http2MultiplexHandler";
    public static final String NAME_WEBSOCKET_PROTOCOL = "webSocketServerProtocolHandler";
    public static final String NAME_WEBSOCKET_CLIENT_PROTOCOL = "webSocketClientProtocolHandler";
    public static final String NAME_WEBSOCKET_FRAME_DECODER = "webSocketFrameToPacketDecoder";
    public static final String NAME_WEBSOCKET_FRAME_ENCODER = "packetToWebSocketFrameEncoder";
    public static final String NAME_DISPATCHER   = "businessDispatcher";
}

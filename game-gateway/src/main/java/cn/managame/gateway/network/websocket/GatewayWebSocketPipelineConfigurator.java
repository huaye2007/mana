package cn.managame.gateway.network.websocket;

import cn.managame.gateway.codec.BodyCodec;
import cn.managame.gateway.codec.GatewayPacketDecoder;
import cn.managame.gateway.codec.GatewayPacketEncoder;
import cn.managame.network.connection.ConnectionManager;
import cn.managame.network.connection.IConnectionIdGenerator;
import cn.managame.network.handler.IConnectionHandler;
import cn.managame.network.handler.pipeline.DefaultWebSocketPipelineConfigurator;
import cn.managame.network.handler.pipeline.PipelineConstants;
import cn.managame.network.server.NetworkWsServerConfig;
import io.netty.channel.ChannelPipeline;

/**
 * 在框架 WebSocket 管线基础上插入网关帧编解码。
 *
 * <p>父类装配到：{@code ... WS帧解码器(WSFrame→ByteBuf) → WS帧编码器(ByteBuf→WSFrame) → 派发器}。
 * 这里把 {@link GatewayPacketDecoder}/{@link GatewayPacketEncoder} 插到派发器之前，
 * 使二进制 WS 帧里承载的就是与 TCP 完全一致的 17 字节头 GamePacket 帧格式：</p>
 * <ul>
 *   <li>入站：WSFrame→ByteBuf→{@link GatewayPacketDecoder}→GatewayPacket→派发器；</li>
 *   <li>出站：派发器写 GatewayPacket→{@link GatewayPacketEncoder}→ByteBuf→WS帧编码器→BinaryWebSocketFrame。</li>
 * </ul>
 * 编码器与解码器方向相反，二者相对顺序不影响正确性。
 */
public class GatewayWebSocketPipelineConfigurator extends DefaultWebSocketPipelineConfigurator {

    private final BodyCodec bodyCodec;

    public GatewayWebSocketPipelineConfigurator(NetworkWsServerConfig config,
                                                IConnectionHandler connectionHandler,
                                                ConnectionManager connectionManager,
                                                IConnectionIdGenerator connectionIdGenerator,
                                                BodyCodec bodyCodec) {
        super(config, connectionHandler, connectionManager, connectionIdGenerator);
        this.bodyCodec = bodyCodec;
    }

    @Override
    public void configure(ChannelPipeline pipeline) {
        super.configure(pipeline);
        pipeline.addBefore(PipelineConstants.NAME_DISPATCHER, "gatewayPacketEncoder",
                new GatewayPacketEncoder(bodyCodec));
        pipeline.addBefore(PipelineConstants.NAME_DISPATCHER, "gatewayPacketDecoder",
                new GatewayPacketDecoder(bodyCodec));
    }
}

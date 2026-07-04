package cn.managame.network.server;

import cn.managame.network.connection.ConnectionManager;
import cn.managame.network.handler.pipeline.DefaultHttpPipelineConfigurator;
import cn.managame.network.http.IHttpHandler;
import cn.managame.network.session.SessionManager;

public class NettyHttpServer extends AbstractNettyServer {

    public NettyHttpServer(NetworkHttpServerConfig config, IHttpHandler httpHandler) {
        super(config, new DefaultHttpPipelineConfigurator(config, httpHandler),
                new SessionManager(), new ConnectionManager());
    }
}

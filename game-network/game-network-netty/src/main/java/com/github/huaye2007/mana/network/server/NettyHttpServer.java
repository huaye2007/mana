package com.github.huaye2007.mana.network.server;

import com.github.huaye2007.mana.network.connection.ConnectionManager;
import com.github.huaye2007.mana.network.handler.pipeline.DefaultHttpPipelineConfigurator;
import com.github.huaye2007.mana.network.http.IHttpHandler;
import com.github.huaye2007.mana.network.session.SessionManager;

public class NettyHttpServer extends AbstractNettyServer {

    public NettyHttpServer(NetworkHttpServerConfig config, IHttpHandler httpHandler) {
        super(config, new DefaultHttpPipelineConfigurator(config, httpHandler),
                new SessionManager(), new ConnectionManager());
    }
}

package com.github.huaye2007.mana.network.handler;

import com.github.huaye2007.mana.network.connection.IConnection;
import com.github.huaye2007.mana.network.session.ISession;
import com.github.huaye2007.mana.network.session.SessionManager;

public class ServerConnectionHandler implements IConnectionHandler{

    private final INetworkHandler networkHandler;

    private final SessionManager sessionManager;

    public ServerConnectionHandler(SessionManager sessionManager,INetworkHandler networkHandler){
        this.networkHandler = networkHandler;
        this.sessionManager = sessionManager;
    }

    @Override
    public void onConnect(IConnection connection) {
        ISession session = networkHandler.createSession(connection);
        sessionManager.addSession(session);
        networkHandler.onConnect(session);
    }

    @Override
    public void onMessage(IConnection connection, Object packet) {
        ISession session = sessionManager.getSession(connection);
        if(session == null){
            return;
        }
        networkHandler.onMessage(session,packet);
    }

    @Override
    public void onDisconnect(IConnection connection) {
        ISession session = sessionManager.removeSession(connection);
        if(session == null){
            return;
        }
        networkHandler.onDisconnect(session);
    }

    @Override
    public void onException(IConnection connection, Throwable cause) {
        ISession session = sessionManager.getSession(connection);
        if(session != null){
            networkHandler.onException(session,cause);
            session.close();
            return;
        }
        if(connection != null){
            connection.close();
        }
    }

    @Override
    public void onIdle(IConnection connection) {
        ISession session = sessionManager.getSession(connection);
        if(session == null){
            return;
        }
        if(networkHandler.onIdle(session)){
            session.close();
        }
    }
}

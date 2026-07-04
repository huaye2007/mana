package cn.managame.network.session;

import cn.managame.network.connection.IConnection;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {
    private final Map<IConnection,ISession> connectionISessionMap = new ConcurrentHashMap<>();

    public void addSession(ISession session){
        IConnection connection = session.getConnection();
        connectionISessionMap.put(connection,session);
    }

    public ISession removeSession(IConnection connection){
        if(connection == null){
            return null;
        }
        return connectionISessionMap.remove(connection);
    }

    public void removeSession(ISession session){
        if(session == null){
            return;
        }
        boolean removed = connectionISessionMap.remove(session.getConnection(), session);
        if(!removed){
            connectionISessionMap.entrySet().removeIf(entry -> entry.getValue() == session);
        }
    }

    public ISession getSession(IConnection connection){
        if(connection == null){
            return null;
        }
        return connectionISessionMap.get(connection);
    }

    public int size(){
        return connectionISessionMap.size();
    }

    public void closeAll(){
        for(ISession session : new ArrayList<>(connectionISessionMap.values())){
            session.close();
        }
        connectionISessionMap.clear();
    }
}

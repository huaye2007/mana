package com.github.huaye2007.mana.network.connection;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

public class ConnectionManager {
    private final ConcurrentHashMap<Long, IConnection> id2ConnMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Object, IConnection> channel2ConnMap = new ConcurrentHashMap<>();

    public void addConnection(IConnection connection,Object channel){
        IConnection oldConnection = id2ConnMap.put(connection.getConnectionId(), connection);
        if(oldConnection != null && oldConnection != connection){
            channel2ConnMap.entrySet().removeIf(entry -> entry.getValue() == oldConnection);
        }

        IConnection oldChannelConnection = channel2ConnMap.put(channel,connection);
        if(oldChannelConnection != null && oldChannelConnection != connection){
            id2ConnMap.remove(oldChannelConnection.getConnectionId(), oldChannelConnection);
        }
    }

    public IConnection removeConnectionByChannel(Object channel){
        if(channel == null){
            return null;
        }
        IConnection connection = channel2ConnMap.remove(channel);
        if(connection != null){
            id2ConnMap.remove(connection.getConnectionId(), connection);
        }
        return connection;
    }

    public IConnection removeConnection(long connectionId){
        IConnection connection = id2ConnMap.remove(connectionId);
        if(connection != null){
            channel2ConnMap.entrySet().removeIf(entry -> entry.getValue() == connection);
        }
        return connection;
    }

    public IConnection getConnection(Object channel){
        if(channel == null){
            return null;
        }
        return channel2ConnMap.get(channel);
    }

    public IConnection getConnection(long connectionId){
        return id2ConnMap.get(connectionId);
    }

    public boolean containsConnection(long connectionId){
        return id2ConnMap.containsKey(connectionId);
    }

    public boolean containsChannel(Object channel){
        return channel != null && channel2ConnMap.containsKey(channel);
    }

    public int size(){
        return id2ConnMap.size();
    }

    public void closeAll(){
        for(IConnection connection : new ArrayList<>(id2ConnMap.values())){
            connection.close();
        }
        id2ConnMap.clear();
        channel2ConnMap.clear();
    }
}

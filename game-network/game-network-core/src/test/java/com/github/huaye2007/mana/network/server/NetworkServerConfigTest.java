package com.github.huaye2007.mana.network.server;

import com.github.huaye2007.mana.network.http.HttpProtocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkServerConfigTest {

    @Test
    void validatesCommonConfigValues() {
        NetworkTcpServerConfig config = new NetworkTcpServerConfig();

        assertThrows(IllegalArgumentException.class, () -> config.setPort(-1));
        assertThrows(IllegalArgumentException.class, () -> config.setPort(65536));
        assertThrows(IllegalArgumentException.class, () -> config.setBossThreads(0));
        assertThrows(IllegalArgumentException.class, () -> config.setWorkerThreads(-1));
        assertThrows(IllegalArgumentException.class, () -> config.setIdleSeconds(-1));
        assertThrows(IllegalArgumentException.class, () -> config.setSoBacklog(0));
    }

    @Test
    void keepsCommonDefaultsSimple() {
        NetworkTcpServerConfig config = new NetworkTcpServerConfig(9000);

        assertEquals("0.0.0.0", config.getHost());
        assertEquals(9000, config.getPort());
        assertEquals(1, config.getBossThreads());
        assertEquals(0, config.getWorkerThreads());
        assertEquals(0, config.getIdleSeconds());
        assertEquals(1024, config.getSoBacklog());
    }

    @Test
    void keepsHttpDefaultsSimple() {
        NetworkHttpServerConfig config = new NetworkHttpServerConfig(9000);

        assertEquals(65536, config.getHttpMaxContentLength());
        assertEquals(HttpProtocol.HTTP1, config.getHttpProtocol());
    }

    @Test
    void keepsWebsocketDefaultsSimple() {
        NetworkWsServerConfig config = new NetworkWsServerConfig(9000);

        assertEquals("/", config.getWebsocketPath());
        assertEquals(65536, config.getHttpMaxContentLength());
    }

    @Test
    void normalizesWebsocketPath() {
        NetworkWsServerConfig config = new NetworkWsServerConfig();

        config.setWebsocketPath("ws");

        assertEquals("/ws", config.getWebsocketPath());
    }
}

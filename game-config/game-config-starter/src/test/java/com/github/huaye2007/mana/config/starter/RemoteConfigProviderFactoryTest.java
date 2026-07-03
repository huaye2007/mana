package com.github.huaye2007.mana.config.starter;

import com.github.huaye2007.mana.config.spi.RemoteConfigProvider;
import com.github.huaye2007.mana.config.spi.RemoteConfigProviderFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class RemoteConfigProviderFactoryTest {
    @Test
    void shouldLoadNacosProviderBySpi() {
        RemoteConfigProvider provider = RemoteConfigProviderFactory.create("nacos");
        assertNotNull(provider);
        assertEquals("nacos", provider.type());
    }

    @Test
    void shouldLoadLocalProviderBySpi() {
        RemoteConfigProvider provider = RemoteConfigProviderFactory.create("local");
        assertNotNull(provider);
        assertEquals("local", provider.type());
    }

    @Test
    void shouldLoadApolloProviderBySpi() {
        RemoteConfigProvider provider = RemoteConfigProviderFactory.create("apollo");
        assertNotNull(provider);
        assertEquals("apollo", provider.type());
    }

    @Test
    void shouldLoadConsulProviderBySpi() {
        RemoteConfigProvider provider = RemoteConfigProviderFactory.create("consul");
        assertNotNull(provider);
        assertEquals("consul", provider.type());
    }

    @Test
    void shouldLoadEtcdProviderBySpi() {
        RemoteConfigProvider provider = RemoteConfigProviderFactory.create("etcd");
        assertNotNull(provider);
        assertEquals("etcd", provider.type());
    }

    @Test
    void shouldCreateIndependentProviderInstances() {
        RemoteConfigProvider first = RemoteConfigProviderFactory.create("nacos");
        RemoteConfigProvider second = RemoteConfigProviderFactory.create("nacos");

        assertNotSame(first, second);
    }
}

package cn.managame.network.netty;

import cn.managame.network.error.NetworkException;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.nio.file.Path;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;

/** Server SslContext helpers for wss and TLS over TCP. Failures throw NetworkException with the cause. */
public final class NetworkSsl {
    private NetworkSsl() {}

    /** PEM certificate chain and an unencrypted PKCS#8 PEM private key. */
    public static SslContext server(Path certificateChain, Path privateKey) {
        return server(certificateChain, privateKey, null);
    }

    /** PEM certificate chain and a PKCS#8 PEM private key, encrypted when {@code keyPassword} is not null. */
    public static SslContext server(Path certificateChain, Path privateKey, String keyPassword) {
        try {
            return SslContextBuilder.forServer(certificateChain.toFile(), privateKey.toFile(), keyPassword).build();
        } catch (Exception e) {
            throw new NetworkException("Cannot load TLS certificate " + certificateChain + " and key " + privateKey, e);
        }
    }

    /** PKCS#12 (.p12/.pfx) or JKS key store whose key entry uses the store password. */
    public static SslContext server(Path keyStore, char[] password) {
        try {
            KeyStore store = KeyStore.getInstance(keyStore.toFile(), password);
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, password);
            return SslContextBuilder.forServer(keys).build();
        } catch (Exception e) {
            throw new NetworkException("Cannot load TLS key store " + keyStore, e);
        }
    }
}

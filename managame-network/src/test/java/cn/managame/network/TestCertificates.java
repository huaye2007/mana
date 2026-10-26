package cn.managame.network;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Self-signed test certificates, made with the JDK's keytool. */
public final class TestCertificates {
    private TestCertificates() {}

    /** A PKCS#12 key store for localhost and 127.0.0.1. */
    public static Path selfSignedKeyStore(Path directory, char[] password) throws Exception {
        Path store = directory.resolve("server.p12");
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        if (!Files.exists(keytool)) keytool = Path.of(keytool + ".exe");
        Process process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "server", "-keyalg", "EC",
                "-groupname", "secp256r1", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-validity", "2", "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", new String(password), "-keypass", new String(password))
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0, "keytool failed: " + output);
        return store;
    }

    /** A client context that trusts exactly the certificate in {@code keyStore}. */
    public static SSLContext trusting(Path keyStore, char[] password) throws Exception {
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(KeyStore.getInstance(keyStore.toFile(), password));
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }
}

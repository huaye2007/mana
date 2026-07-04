package cn.managame.config.apollo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ApolloRemoteConfigProviderIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void shouldLoadConfiguredNamespaceFromRealApollo() throws Exception {
        assumeTrue(enabled(), "Set GAME_CONFIG_INTEGRATION_APOLLO=true to run Apollo integration tests");

        Map<String, String> config = new ApolloRemoteConfigProvider().load(apolloProperties());

        assertNotNull(config);
        String expectedKey = setting("game.config.apollo.expectedKey", "GAME_CONFIG_APOLLO_EXPECTED_KEY", null);
        if (expectedKey != null && !expectedKey.isBlank()) {
            assertEquals(
                    setting("game.config.apollo.expectedValue", "GAME_CONFIG_APOLLO_EXPECTED_VALUE", ""),
                    config.get(expectedKey));
        }
    }

    @Test
    void shouldReceivePushFromRealApolloWhenOpenApiConfigured() throws Exception {
        assumeTrue(enabled(), "Set GAME_CONFIG_INTEGRATION_APOLLO=true to run Apollo integration tests");
        assumeTrue(pushEnabled(), "Set GAME_CONFIG_INTEGRATION_APOLLO_PUSH=true to run Apollo push integration tests");

        String portalUrl = requiredSetting("game.config.apollo.portalUrl", "GAME_CONFIG_APOLLO_PORTAL_URL");
        String token = requiredSetting("game.config.apollo.openapiToken", "GAME_CONFIG_APOLLO_OPENAPI_TOKEN");
        String env = setting("game.config.apollo.env", "GAME_CONFIG_APOLLO_ENV", "DEV");
        String operator = setting("game.config.apollo.operator", "GAME_CONFIG_APOLLO_OPERATOR", "game-config-test");
        String key = requiredSetting("game.config.apollo.pushKey", "GAME_CONFIG_APOLLO_PUSH_KEY");
        String value = "game-config-integration-" + System.nanoTime();
        long waitMillis = Long.parseLong(setting(
                "game.config.apollo.pushWaitMillis",
                "GAME_CONFIG_APOLLO_PUSH_WAIT_MILLIS",
                "15000"));

        Properties properties = apolloProperties();
        assumeTrue(!properties.getProperty("namespace").contains(","),
                "Apollo push integration test requires a single namespace");
        ApolloRemoteConfigProvider provider = new ApolloRemoteConfigProvider();
        CountDownLatch pushed = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<Map<String, String>> latest = new AtomicReference<>();
        try {
            provider.subscribe(properties, config -> {
                if (callbacks.incrementAndGet() > 1 && value.equals(config.get(key))) {
                    latest.set(config);
                    pushed.countDown();
                }
            });

            upsertApolloItem(portalUrl, token, env, properties, key, value, operator);
            releaseApolloNamespace(portalUrl, token, env, properties, operator);

            assertTrue(pushed.await(waitMillis, TimeUnit.MILLISECONDS));
            assertEquals(value, latest.get().get(key));
        } finally {
            provider.close();
        }
    }

    private Properties apolloProperties() {
        Properties properties = new Properties();
        properties.setProperty("configServiceUrl", setting(
                "game.config.apollo.endpoint",
                "GAME_CONFIG_APOLLO_ENDPOINT",
                "http://127.0.0.1:8080"));
        properties.setProperty("appId", setting("game.config.apollo.appId", "GAME_CONFIG_APOLLO_APP_ID", "game"));
        properties.setProperty("cluster", setting("game.config.apollo.cluster", "GAME_CONFIG_APOLLO_CLUSTER", "default"));
        properties.setProperty("namespace", setting(
                "game.config.apollo.namespace",
                "GAME_CONFIG_APOLLO_NAMESPACE",
                "application"));
        maybeSet(properties, "ip", setting("game.config.apollo.ip", "GAME_CONFIG_APOLLO_IP", null));
        maybeSet(properties, "accessKeySecret", setting(
                "game.config.apollo.accessKeySecret",
                "GAME_CONFIG_APOLLO_ACCESS_KEY_SECRET",
                null));
        return properties;
    }

    private void upsertApolloItem(String portalUrl,
                                  String token,
                                  String env,
                                  Properties properties,
                                  String key,
                                  String value,
                                  String operator) throws Exception {
        URI uri = openApiUri(portalUrl, env, properties, "/items/" + pathSegment(key));
        String body = JSON.writeValueAsString(Map.of(
                "key", key,
                "value", value,
                "comment", "game-config integration push test",
                "dataChangeLastModifiedBy", operator));
        HttpRequest request = openApiRequest(uri, token)
                .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        assertSuccess(httpClient().send(request, HttpResponse.BodyHandlers.ofString()));
    }

    private void releaseApolloNamespace(String portalUrl,
                                        String token,
                                        String env,
                                        Properties properties,
                                        String operator) throws Exception {
        URI uri = openApiUri(portalUrl, env, properties, "/releases");
        String body = JSON.writeValueAsString(Map.of(
                "releaseTitle", "game-config-integration-" + System.nanoTime(),
                "releaseComment", "game-config integration push test",
                "releasedBy", operator));
        HttpRequest request = openApiRequest(uri, token)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        assertSuccess(httpClient().send(request, HttpResponse.BodyHandlers.ofString()));
    }

    private HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    private HttpRequest.Builder openApiRequest(URI uri, String token) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", token)
                .header("Content-Type", "application/json");
    }

    private URI openApiUri(String portalUrl, String env, Properties properties, String suffix) {
        String base = portalUrl.endsWith("/") ? portalUrl.substring(0, portalUrl.length() - 1) : portalUrl;
        String path = "/openapi/v1/envs/" + pathSegment(env)
                + "/apps/" + pathSegment(properties.getProperty("appId"))
                + "/clusters/" + pathSegment(properties.getProperty("cluster"))
                + "/namespaces/" + pathSegment(properties.getProperty("namespace"))
                + suffix;
        return URI.create(base + path);
    }

    private String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private void assertSuccess(HttpResponse<String> response) {
        assertTrue(
                response.statusCode() >= 200 && response.statusCode() < 300,
                "Apollo OpenAPI request failed with status " + response.statusCode() + ": " + response.body());
    }

    private boolean enabled() {
        return Boolean.parseBoolean(setting(
                "game.config.integration.apollo",
                "GAME_CONFIG_INTEGRATION_APOLLO",
                "false"));
    }

    private boolean pushEnabled() {
        return Boolean.parseBoolean(setting(
                "game.config.integration.apollo.push",
                "GAME_CONFIG_INTEGRATION_APOLLO_PUSH",
                "false"));
    }

    private void maybeSet(Properties properties, String key, String value) {
        if (value != null && !value.isBlank()) {
            properties.setProperty(key, value);
        }
    }

    private String requiredSetting(String propertyName, String envName) {
        String value = setting(propertyName, envName, null);
        assumeTrue(value != null && !value.isBlank(), "Set " + envName + " to run Apollo push integration tests");
        return value;
    }

    private String setting(String propertyName, String envName, String defaultValue) {
        String propertyValue = System.getProperty(propertyName);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue.trim();
        }
        String envValue = System.getenv(envName);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }
        return defaultValue;
    }
}

package cn.managame.config.starter;

import cn.managame.config.api.ConfigValidator;
import cn.managame.config.factory.GameConfigOptions;
import cn.managame.config.manager.GameConfigManager;
import cn.managame.config.source.ClasspathConfigSource;
import cn.managame.config.source.CommandLineConfigSource;
import cn.managame.config.source.DefaultConfigSource;
import cn.managame.config.source.EnvironmentConfigSource;
import cn.managame.config.source.JvmConfigSource;
import cn.managame.config.source.LocalDirectoryConfigSource;
import cn.managame.config.source.LocalFileConfigSource;
import cn.managame.config.source.RemoteConfigSource;
import cn.managame.config.spi.RemoteConfigProviderFactory;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Small bootstrap entry for applications that want to use game-config directly
 * without assembling every ConfigSource by hand.
 */
public final class GameConfigStarter {
    public static final String DEFAULT_LOCAL_FILE = "config/application.properties";

    private GameConfigStarter() {
    }

    public static GameConfigManager start(String[] args) {
        return builder().args(args).localFile(DEFAULT_LOCAL_FILE).start();
    }

    public static GameConfigManager start(String[] args, String localFile) {
        return builder().args(args).localFile(localFile).start();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private List<String> args = List.of();
        private String localFile;
        private String localDirectory;
        private boolean localDirectoryRecursive;
        private String classpathResource;
        private final Map<String, String> defaults = new LinkedHashMap<>();
        private String remoteType;
        private Properties remoteProperties;
        private boolean includeSystemProperties;
        private boolean includeEnvironmentVariables;
        private boolean hotReloadEnabled = true;
        private long refreshIntervalMillis = 3000L;
        private boolean failFast;
        private Consumer<Exception> errorHandler;
        private Executor listenerExecutor;
        private final List<ConfigValidator> validators = new java.util.ArrayList<>();

        private Builder() {
        }

        public Builder args(String[] args) {
            this.args = args == null ? List.of() : Arrays.asList(args);
            return this;
        }

        public Builder args(List<String> args) {
            this.args = args == null ? List.of() : List.copyOf(args);
            return this;
        }

        public Builder localFile(String localFile) {
            this.localFile = localFile;
            return this;
        }

        public Builder localDirectory(String localDirectory) {
            return localDirectory(localDirectory, false);
        }

        public Builder localDirectory(String localDirectory, boolean recursive) {
            this.localDirectory = localDirectory;
            this.localDirectoryRecursive = recursive;
            return this;
        }

        public Builder classpathResource(String classpathResource) {
            this.classpathResource = classpathResource;
            return this;
        }

        public Builder defaults(Map<String, String> defaults) {
            this.defaults.clear();
            if (defaults != null) {
                this.defaults.putAll(defaults);
            }
            return this;
        }

        public Builder defaultValue(String key, String value) {
            if (key != null && !key.isBlank() && value != null) {
                this.defaults.put(key, value);
            }
            return this;
        }

        public Builder remote(String remoteType, Properties remoteProperties) {
            this.remoteType = remoteType;
            this.remoteProperties = copyProperties(remoteProperties);
            return this;
        }

        public Builder remote(String remoteType, Map<String, String> remoteProperties) {
            Properties props = new Properties();
            if (remoteProperties != null) {
                props.putAll(remoteProperties);
            }
            return remote(remoteType, props);
        }

        public Builder systemProperties(boolean includeSystemProperties) {
            this.includeSystemProperties = includeSystemProperties;
            return this;
        }

        public Builder environmentVariables(boolean includeEnvironmentVariables) {
            this.includeEnvironmentVariables = includeEnvironmentVariables;
            return this;
        }

        public Builder hotReload(boolean hotReloadEnabled) {
            this.hotReloadEnabled = hotReloadEnabled;
            return this;
        }

        public Builder refreshIntervalMillis(long refreshIntervalMillis) {
            this.refreshIntervalMillis = refreshIntervalMillis;
            return this;
        }

        public Builder listenerExecutor(Executor listenerExecutor) {
            this.listenerExecutor = listenerExecutor;
            return this;
        }

        public Builder failFast(boolean failFast) {
            this.failFast = failFast;
            return this;
        }

        public Builder errorHandler(Consumer<Exception> errorHandler) {
            this.errorHandler = errorHandler;
            return this;
        }

        public Builder validator(ConfigValidator validator) {
            if (validator != null) {
                this.validators.add(validator);
            }
            return this;
        }

        public GameConfigOptions buildOptions() {
            GameConfigOptions options = new GameConfigOptions();
            options.setHotReloadEnabled(hotReloadEnabled);
            options.setRefreshIntervalMillis(refreshIntervalMillis);
            options.setFailFast(failFast);
            options.setErrorHandler(errorHandler);
            options.setListenerExecutor(listenerExecutor);

            options.addSource(new CommandLineConfigSource(args));
            if (includeSystemProperties) {
                options.addSource(new JvmConfigSource(true));
            }
            if (includeEnvironmentVariables) {
                options.addSource(new EnvironmentConfigSource());
            }
            if (remoteType != null && !remoteType.isBlank()) {
                options.addSource(new RemoteConfigSource(
                        RemoteConfigProviderFactory.create(remoteType),
                        copyProperties(remoteProperties)));
            }
            if (localFile != null && !localFile.isBlank()) {
                options.addSource(new LocalFileConfigSource(localFile));
            }
            if (localDirectory != null && !localDirectory.isBlank()) {
                options.addSource(new LocalDirectoryConfigSource(localDirectory, localDirectoryRecursive));
            }
            if (classpathResource != null && !classpathResource.isBlank()) {
                options.addSource(new ClasspathConfigSource(classpathResource));
            }
            if (!defaults.isEmpty()) {
                options.addSource(new DefaultConfigSource(defaults));
            }
            for (ConfigValidator validator : validators) {
                options.addValidator(validator);
            }
            return options;
        }

        public GameConfigManager start() {
            GameConfigManager manager = new GameConfigManager(buildOptions());
            manager.start();
            return manager;
        }

        private static Properties copyProperties(Properties source) {
            Properties copy = new Properties();
            if (source != null) {
                copy.putAll(source);
            }
            return copy;
        }
    }
}

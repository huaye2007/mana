package cn.managame.config.factory;

import cn.managame.config.api.ConfigValidator;
import cn.managame.config.source.ConfigSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 全局配置选项。只保留与配置管理器行为相关的设置（热加载等），
 * 各配置源的参数由各自的 {@link ConfigSource} 实现持有。
 * <p>
 * sources 列表的顺序即为优先级顺序（索引越小优先级越高）。
 */
public class GameConfigOptions {
    private List<ConfigSource> sources = new ArrayList<>();
    private boolean hotReloadEnabled = true;
    private long refreshIntervalMillis = 3000L;
    private boolean failFast;
    private Consumer<Exception> errorHandler;
    private Executor listenerExecutor;
    private final List<ConfigValidator> validators = new ArrayList<>();

    public List<ConfigSource> getSources() {
        return new ArrayList<>(sources);
    }

    public void setSources(List<ConfigSource> sources) {
        this.sources = new ArrayList<>();
        if (sources == null) {
            return;
        }
        for (ConfigSource source : sources) {
            if (source != null) {
                this.sources.add(source);
            }
        }
    }

    public void addSource(ConfigSource source) {
        if (source != null) {
            this.sources.add(source);
        }
    }

    public boolean isHotReloadEnabled() {
        return hotReloadEnabled;
    }

    public void setHotReloadEnabled(boolean hotReloadEnabled) {
        this.hotReloadEnabled = hotReloadEnabled;
    }

    public long getRefreshIntervalMillis() {
        return refreshIntervalMillis;
    }

    public void setRefreshIntervalMillis(long refreshIntervalMillis) {
        this.refreshIntervalMillis = refreshIntervalMillis;
    }

    public Executor getListenerExecutor() {
        return listenerExecutor;
    }

    /**
     * 设置监听器执行器。设置后 {@link cn.managame.config.api.ConfigChangeListener#onChange} 会
     * 投递到该 Executor 异步执行，避免慢监听器阻塞 reload/push 链路。
     * 未设置时（null）保持同步通知。
     */
    public void setListenerExecutor(Executor listenerExecutor) {
        this.listenerExecutor = listenerExecutor;
    }

    public boolean isFailFast() {
        return failFast;
    }

    public void setFailFast(boolean failFast) {
        this.failFast = failFast;
    }

    public Consumer<Exception> getErrorHandler() {
        return errorHandler;
    }

    /**
     * 设置配置加载异常处理器。热加载轮询中发生的异常会回调此 handler，
     * 而非静默吞掉。可用于接入日志框架或告警系统。
     */
    public void setErrorHandler(Consumer<Exception> errorHandler) {
        this.errorHandler = errorHandler;
    }

    public List<ConfigValidator> getValidators() {
        return new ArrayList<>(validators);
    }

    public void addValidator(ConfigValidator validator) {
        if (validator != null) {
            this.validators.add(validator);
        }
    }

    public void addValidators(ConfigValidator... validators) {
        if (validators == null) {
            return;
        }
        addValidators(Arrays.asList(validators));
    }

    public void addValidators(Iterable<ConfigValidator> validators) {
        if (validators == null) {
            return;
        }
        for (ConfigValidator validator : validators) {
            addValidator(validator);
        }
    }
}

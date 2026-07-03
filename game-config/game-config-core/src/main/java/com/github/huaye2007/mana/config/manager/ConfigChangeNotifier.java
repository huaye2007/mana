package com.github.huaye2007.mana.config.manager;

import com.github.huaye2007.mana.config.api.ConfigChangeEvent;
import com.github.huaye2007.mana.config.api.ConfigChangeListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.LongAdder;

final class ConfigChangeNotifier {
    private static final Logger log = LoggerFactory.getLogger(ConfigChangeNotifier.class);

    private final CopyOnWriteArrayList<ConfigChangeListener> listeners = new CopyOnWriteArrayList<>();
    private final Executor listenerExecutor;
    private final LongAdder listenerErrorCount = new LongAdder();

    ConfigChangeNotifier(Executor listenerExecutor) {
        this.listenerExecutor = listenerExecutor;
    }

    void add(ConfigChangeListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    void remove(ConfigChangeListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    long errorCount() {
        return listenerErrorCount.sum();
    }

    void notifyListeners(ConfigChangeEvent event) {
        for (ConfigChangeListener listener : listeners) {
            if (listenerExecutor == null) {
                invokeListener(listener, event);
            } else {
                try {
                    listenerExecutor.execute(() -> invokeListener(listener, event));
                } catch (RejectedExecutionException e) {
                    listenerErrorCount.increment();
                    log.warn("Listener executor rejected notification", e);
                }
            }
        }
    }

    private void invokeListener(ConfigChangeListener listener, ConfigChangeEvent event) {
        try {
            listener.onChange(event);
        } catch (Exception e) {
            listenerErrorCount.increment();
            log.warn("ConfigChangeListener threw exception", e);
        }
    }
}

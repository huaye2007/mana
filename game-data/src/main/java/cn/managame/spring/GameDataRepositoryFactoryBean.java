package cn.managame.spring;

import cn.managame.core.GameData;
import org.springframework.beans.factory.FactoryBean;

/** Infrastructure factory; the referenced GameData bean owns repository resources and shutdown. */
public final class GameDataRepositoryFactoryBean<T> implements FactoryBean<T> {
    private final Class<T> repositoryType;
    private final T repository;

    public GameDataRepositoryFactoryBean(Class<T> repositoryType, GameData gameData, String dataAccess) {
        this.repositoryType = repositoryType;
        this.repository = dataAccess.isEmpty() ? gameData.repository(repositoryType)
                : gameData.repository(dataAccess, repositoryType);
    }

    @Override public T getObject() { return repository; }
    @Override public Class<?> getObjectType() { return repositoryType; }
    @Override public boolean isSingleton() { return true; }
}

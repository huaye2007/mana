package cn.managame.core.repository;

import java.util.Optional;

public interface SingleRepository<T, ID> {
    Optional<T> get(ID id);
    void insert(T entity);
    void update(T entity);
    void delete(T entity);
    void deleteById(ID id);
    boolean resident();
}

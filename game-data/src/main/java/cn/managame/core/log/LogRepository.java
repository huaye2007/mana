package cn.managame.core.log;

/** Business log interfaces extend this contract; event-time partitioning is declared on the entity. */
public interface LogRepository<T> {
    void append(T log);
    /** Explicit drain when needed; ordinary appends are automatically batched. */
    void flush();
}

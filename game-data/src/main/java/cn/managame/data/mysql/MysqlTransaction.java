package cn.managame.data.mysql;
import java.util.List;
public interface MysqlTransaction {
    <T> T queryOne(String sql, Object[] args, RowMapper<T> mapper);
    <T> List<T> query(String sql, Object[] args, RowMapper<T> mapper);
    int update(String sql, Object[] args);
    int[] batchUpdate(String sql, List<Object[]> args);
}

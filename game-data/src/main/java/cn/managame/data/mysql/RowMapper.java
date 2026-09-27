package cn.managame.data.mysql;
import java.sql.*;
@FunctionalInterface
public interface RowMapper<T> { T map(ResultSet row) throws SQLException; }

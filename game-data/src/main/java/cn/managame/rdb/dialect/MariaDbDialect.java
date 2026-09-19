package cn.managame.rdb.dialect;

/** MariaDB currently shares the MySQL SQL/type rules used by game-data. */
public final class MariaDbDialect extends MySqlDialect {
    @Override public String name() { return "mariadb"; }
}

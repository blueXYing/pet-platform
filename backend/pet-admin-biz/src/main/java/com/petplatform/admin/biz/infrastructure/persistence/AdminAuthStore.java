package com.petplatform.admin.biz.infrastructure.persistence;

import com.petplatform.admin.biz.application.AdminAuthFailure;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

/** Only this Owner's tables. SQL errors never escape with bound credential values. */
public final class AdminAuthStore {
  @FunctionalInterface
  public interface Work<T> {
    T apply(Tx tx) throws SQLException;
  }

  public static final class CommitUnknown extends RuntimeException {
    public CommitUnknown() {
      super("AUTH_COMMIT_ACK_UNKNOWN", null, false, false);
    }
  }

  public static final class Row {
    private final Map<String, Object> values;

    Row(Map<String, Object> values) {
      this.values = values;
    }

    public String text(String key) {
      Object v = values.get(key);
      return v == null ? null : String.valueOf(v);
    }

    public long number(String key) {
      Object v = values.get(key);
      return v == null ? 0 : ((Number) v).longValue();
    }

    public byte[] bytes(String key) {
      return (byte[]) values.get(key);
    }

    public Instant time(String key) {
      Object v = values.get(key);
      return v == null ? null : ((Timestamp) v).toInstant();
    }

    public boolean bool(String key) {
      Object v = values.get(key);
      return v instanceof Boolean b ? b : v instanceof Number n && n.intValue() != 0;
    }

    @Override
    public String toString() {
      return "AdminAuthRow[REDACTED]";
    }
  }

  public static final class Tx {
    private final Connection c;

    Tx(Connection c) {
      this.c = c;
    }

    private PreparedStatement statement(String sql, Object... args) throws SQLException {
      PreparedStatement s = c.prepareStatement(sql);
      s.setQueryTimeout(5);
      for (int i = 0; i < args.length; i++) {
        Object a = args[i];
        if (a instanceof Instant t)
          s.setTimestamp(
              i + 1, Timestamp.from(t), Calendar.getInstance(TimeZone.getTimeZone("UTC")));
        else s.setObject(i + 1, a);
      }
      return s;
    }

    public int update(String sql, Object... args) throws SQLException {
      try (var s = statement(sql, args)) {
        return s.executeUpdate();
      }
    }

    public List<Row> rows(String sql, Object... args) throws SQLException {
      try (var s = statement(sql, args);
          var r = s.executeQuery()) {
        List<Row> out = new ArrayList<>();
        var m = r.getMetaData();
        while (r.next()) {
          Map<String, Object> v = new LinkedHashMap<>();
          for (int i = 1; i <= m.getColumnCount(); i++) {
            String key = m.getColumnLabel(i).toLowerCase(Locale.ROOT);
            if (m.getColumnType(i) == Types.TIMESTAMP)
              v.put(key, r.getTimestamp(i, Calendar.getInstance(TimeZone.getTimeZone("UTC"))));
            else v.put(key, r.getObject(i));
          }
          out.add(new Row(v));
        }
        return out;
      }
    }

    public Row one(String sql, Object... args) throws SQLException {
      var rows = rows(sql, args);
      if (rows.size() != 1) throw AdminAuthFailure.unavailable();
      return rows.getFirst();
    }

    public Row optional(String sql, Object... args) throws SQLException {
      var rows = rows(sql, args);
      if (rows.size() > 1) throw AdminAuthFailure.unavailable();
      return rows.isEmpty() ? null : rows.getFirst();
    }

    public Instant now() throws SQLException {
      return one("SELECT UTC_TIMESTAMP(3) AS clock").time("clock");
    }
  }

  private final DataSource source;

  public AdminAuthStore(DataSource source) {
    this.source = Objects.requireNonNull(source);
  }

  public <T> T read(Work<T> work) {
    return run(work, true);
  }

  public <T> T write(Work<T> work) {
    return run(work, false);
  }

  private <T> T run(Work<T> work, boolean read) {
    try (Connection c = source.getConnection()) {
      c.setTransactionIsolation(
          read ? Connection.TRANSACTION_REPEATABLE_READ : Connection.TRANSACTION_READ_COMMITTED);
      c.setAutoCommit(false);
      try (var s = c.createStatement()) {
        s.execute("SET SESSION time_zone='+00:00'");
        s.execute("SET SESSION innodb_lock_wait_timeout=2");
      }
      T result;
      try {
        result = work.apply(new Tx(c));
      } catch (SQLException | RuntimeException e) {
        try {
          c.rollback();
        } catch (SQLException ignored) {
        }
        if (e instanceof AdminAuthFailure a) throw a;
        throw AdminAuthFailure.unavailable();
      }
      try {
        c.commit();
      } catch (SQLException e) {
        throw new CommitUnknown();
      }
      return result;
    } catch (CommitUnknown | AdminAuthFailure e) {
      throw e;
    } catch (SQLException e) {
      throw AdminAuthFailure.unavailable();
    }
  }
}

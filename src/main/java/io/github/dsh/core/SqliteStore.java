package io.github.dsh.core;

import static io.github.dsh.core.Model.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;

/** 一个 SQLite 连接、一个进程。事务同时保存领域状态和副作用，避免「状态提交了但忘记启动执行器」。 */
public final class SqliteStore implements AutoCloseable {
  private final Connection connection;
  private final ObjectMapper json = new ObjectMapper();
  private int transactionDepth;

  public SqliteStore(Path file) {
    try {
      if (file.toAbsolutePath().getParent() != null)
        Files.createDirectories(file.toAbsolutePath().getParent());
      connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
      try (Statement s = connection.createStatement()) {
        s.execute("PRAGMA journal_mode=WAL");
        s.execute("PRAGMA busy_timeout=5000");
        int version;
        try (ResultSet r = s.executeQuery("PRAGMA user_version")) {
          r.next();
          version = r.getInt(1);
        }
        if (version > 2)
          throw new IllegalStateException("Database schema is newer than this program");
        s.execute(
            "CREATE TABLE IF NOT EXISTS entity (kind TEXT NOT NULL,id TEXT NOT NULL,json TEXT NOT"
                + " NULL,PRIMARY KEY(kind,id))");
        s.execute(
            "CREATE TABLE IF NOT EXISTS effect (id TEXT PRIMARY KEY,kind TEXT NOT NULL,work_key"
                + " TEXT NOT NULL,payload TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,due_at"
                + " INTEGER NOT NULL,last_error TEXT,lease_until INTEGER NOT NULL DEFAULT"
                + " 0,UNIQUE(kind,work_key))");
        s.execute("CREATE INDEX IF NOT EXISTS effect_due ON effect(due_at,lease_until)");
        boolean generation = false;
        try (ResultSet columns = s.executeQuery("PRAGMA table_info(effect)")) {
          while (columns.next())
            if ("generation".equals(columns.getString("name"))) generation = true;
        }
        if (!generation)
          s.execute("ALTER TABLE effect ADD COLUMN generation INTEGER NOT NULL DEFAULT 0");
        s.execute(
            "CREATE TABLE IF NOT EXISTS domain_event(seq INTEGER PRIMARY KEY AUTOINCREMENT,type"
                + " TEXT NOT NULL,payload TEXT NOT NULL,at INTEGER NOT NULL)");
        if (version == 1) migrateVersionOne();
        s.execute("PRAGMA user_version=2");
      }
    } catch (Exception e) {
      throw failure(e);
    }
  }

  /** 早期 Java 版本只有 acknowledged；迁移为明确的 delivered/read 和业务结束事实。 */
  private void migrateVersionOne() {
    atomic(
        () -> {
          for (Ticket ticket : list("ticket", Ticket.class)) {
            Assignment first = ticket.assignments.isEmpty() ? null : ticket.assignments.getFirst();
            if (ticket.createdAt == 0)
              ticket.createdAt = first == null ? System.currentTimeMillis() : first.createdAt;
            if (ticket.updatedAt == 0) ticket.updatedAt = ticket.createdAt;
            if (ticket.shortName == null)
              ticket.shortName = ticket.id.substring(Math.max(0, ticket.id.length() - 8));
            if (ticket.branch == null) ticket.branch = "superagent/" + ticket.shortName;
            if (ticket.originTriggerIds.isEmpty()) {
              for (Decision decision : list("decision", Decision.class))
                if (ticket.id.equals(decision.ticketId()))
                  ticket.originTriggerIds.addAll(decision.triggerIds());
              if (ticket.originTriggerIds.isEmpty())
                ticket.originTriggerIds.add(ticket.originTriggerId);
            }
            for (Input input : ticket.inputs) {
              Trigger trigger =
                  input.triggerId == null ? null : get("trigger", input.triggerId, Trigger.class);
              if (input.at == 0) input.at = ticket.createdAt;
              input.kind = trigger == null ? InputKind.NOTE : InputKind.TRIGGER;
              input.human = trigger != null && trigger.human();
              if (input.acknowledged) {
                input.readAt = input.at;
                input.deliveredAt = input.at;
              } else if (ticket.status == TicketStatus.OPEN
                  && !ticket.undelivered.contains(input.id)) ticket.undelivered.add(input.id);
              if (trigger != null) {
                Disposition d = get("disposition", trigger.id(), Disposition.class);
                if (d == null) d = new Disposition(trigger.id());
                d.fanout.add(ticket.id);
                if (input.contextOnly) d.context.add(ticket.id);
                save("disposition", trigger.id(), d);
              }
            }
            for (Assignment assignment : ticket.assignments)
              if (assignment.report != null && assignment.reportAt == 0) {
                assignment.reportAt = ticket.updatedAt;
                assignment.reportInputCount = ticket.inputs.size();
                if (ticket.status == TicketStatus.OPEN
                    && Set.of(ReportKind.BLOCKED, ReportKind.FAILED)
                        .contains(assignment.report.kind())
                    && assignment == ticket.latestAssignment())
                  assignment.status = AssignmentStatus.RUNNING;
              }
            if (ticket.status == TicketStatus.ENDED && ticket.ended == null) {
              Assignment latest = ticket.latestAssignment();
              String outcome =
                  ticket.stopped
                      ? "stopped"
                      : latest != null
                              && latest.report != null
                              && latest.report.kind() == ReportKind.DROPPED
                          ? "dropped"
                          : "completed";
              ticket.ended =
                  new TicketEnd(
                      ticket.stopped ? "human" : "executor",
                      outcome,
                      ticket.updatedAt,
                      "Migrated Java schema 1");
            }
            save("ticket", ticket.id, ticket);
          }
          return null;
        });
  }

  public synchronized <T> T atomic(Supplier<T> operation) {
    if (transactionDepth > 0) {
      Savepoint savepoint;
      try {
        savepoint = connection.setSavepoint();
      } catch (SQLException e) {
        throw failure(e);
      }
      transactionDepth++;
      try {
        T value = operation.get();
        connection.releaseSavepoint(savepoint);
        return value;
      } catch (Throwable failure) {
        try {
          connection.rollback(savepoint);
          connection.releaseSavepoint(savepoint);
        } catch (SQLException rollback) {
          failure.addSuppressed(rollback);
        }
        if (failure instanceof Error error) throw error;
        throw failure((Exception) failure);
      } finally {
        transactionDepth--;
      }
    }
    try {
      connection.setAutoCommit(false);
      transactionDepth++;
      T value = operation.get();
      connection.commit();
      return value;
    } catch (Throwable e) {
      try {
        connection.rollback();
      } catch (SQLException rollback) {
        e.addSuppressed(rollback);
      }
      if (e instanceof Error error) throw error;
      throw failure((Exception) e);
    } finally {
      transactionDepth = 0;
      try {
        connection.setAutoCommit(true);
      } catch (SQLException e) {
        throw failure(e);
      }
    }
  }

  public synchronized <T> T get(String kind, String id, Class<T> type) {
    try (PreparedStatement s =
        connection.prepareStatement("SELECT json FROM entity WHERE kind=? AND id=?")) {
      s.setString(1, kind);
      s.setString(2, id);
      try (ResultSet r = s.executeQuery()) {
        return r.next() ? json.readValue(r.getString(1), type) : null;
      }
    } catch (Exception e) {
      throw failure(e);
    }
  }

  public synchronized <T> List<T> list(String kind, Class<T> type) {
    try (PreparedStatement s =
        connection.prepareStatement("SELECT json FROM entity WHERE kind=? ORDER BY rowid")) {
      s.setString(1, kind);
      List<T> result = new ArrayList<>();
      try (ResultSet r = s.executeQuery()) {
        while (r.next()) result.add(json.readValue(r.getString(1), type));
      }
      return result;
    } catch (Exception e) {
      throw failure(e);
    }
  }

  synchronized void save(String kind, String id, Object value) {
    try (PreparedStatement s =
        connection.prepareStatement(
            "INSERT INTO entity(kind,id,json) VALUES(?,?,?) ON CONFLICT(kind,id) DO UPDATE SET"
                + " json=excluded.json")) {
      s.setString(1, kind);
      s.setString(2, id);
      s.setString(3, json.writeValueAsString(value));
      s.executeUpdate();
    } catch (Exception e) {
      throw failure(e);
    }
  }

  public synchronized String cursor(String source) {
    return get("cursor", source, String.class);
  }

  synchronized void cursor(String source, String cursor) {
    save("cursor", source, cursor);
  }

  public synchronized void enqueue(
      EffectKind kind, String key, Map<String, String> payload, long now) {
    try (PreparedStatement s =
        connection.prepareStatement(
            "INSERT OR IGNORE INTO effect(id,kind,work_key,payload,due_at) VALUES(?,?,?,?,?)")) {
      s.setString(1, UUID.randomUUID().toString());
      s.setString(2, kind.name());
      s.setString(3, key);
      s.setString(4, json.writeValueAsString(payload));
      s.setLong(5, now);
      s.executeUpdate();
    } catch (Exception e) {
      throw failure(e);
    }
  }

  /** 同一调度键的新状态取代旧 payload，保留失败历史并 fencing 旧在途完成。 */
  public synchronized void coalesceEffect(
      EffectKind kind, String key, Map<String, String> payload, long now) {
    try (PreparedStatement s =
        connection.prepareStatement(
            "INSERT INTO effect(id,kind,work_key,payload,due_at) VALUES(?,?,?,?,?) ON"
                + " CONFLICT(kind,work_key) DO UPDATE SET"
                + " payload=excluded.payload,due_at=min(effect.due_at,excluded.due_at),generation=effect.generation+1,lease_until=0")) {
      s.setString(1, UUID.randomUUID().toString());
      s.setString(2, kind.name());
      s.setString(3, key);
      s.setString(4, json.writeValueAsString(payload));
      s.setLong(5, now);
      s.executeUpdate();
    } catch (Exception e) {
      throw failure(e);
    }
  }

  /** 租约到期后重新认领；崩溃不会永久丢任务。外部适配器仍须用 key 保证幂等。 */
  public synchronized List<Effect> claimEffects(long now, int limit) {
    return claimEffects(now, limit, EnumSet.allOf(EffectKind.class), 300_000);
  }

  public synchronized List<Effect> claimEffects(
      long now, int limit, Set<EffectKind> kinds, long leaseMillis) {
    if (leaseMillis < 1) throw new IllegalArgumentException("lease must be positive");
    if (kinds.isEmpty()) return List.of();
    if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be 1..1000");
    return atomic(
        () -> {
          try (PreparedStatement s =
              connection.prepareStatement(
                  "SELECT * FROM effect WHERE due_at<=? AND lease_until<=? AND kind IN ("
                      + String.join(",", Collections.nCopies(kinds.size(), "?"))
                      + ") ORDER BY due_at,rowid LIMIT ?")) {
            s.setLong(1, now);
            s.setLong(2, now);
            int index = 3;
            for (EffectKind kind : kinds) s.setString(index++, kind.name());
            s.setInt(index, limit);
            List<Effect> result = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
              while (r.next()) {
                @SuppressWarnings("unchecked")
                Map<String, String> payload = json.readValue(r.getString("payload"), Map.class);
                result.add(
                    new Effect(
                        r.getString("id"),
                        EffectKind.valueOf(r.getString("kind")),
                        r.getString("work_key"),
                        payload,
                        r.getInt("attempts"),
                        r.getLong("due_at"),
                        r.getString("last_error"),
                        r.getLong("generation") + 1));
              }
            }
            for (Effect e : result)
              try (PreparedStatement update =
                  connection.prepareStatement(
                      "UPDATE effect SET lease_until=?,generation=generation+1 WHERE id=?")) {
                update.setLong(1, now + leaseMillis);
                update.setString(2, e.id());
                update.executeUpdate();
              }
            return result;
          } catch (Exception e) {
            throw failure(e);
          }
        });
  }

  /** 调度器用 generation fencing：旧租约的迟到完成不能删除新一轮任务。 */
  public synchronized boolean completeEffect(String id, long generation) {
    try (PreparedStatement s =
        connection.prepareStatement("DELETE FROM effect WHERE id=? AND generation=?")) {
      s.setString(1, id);
      s.setLong(2, generation);
      return s.executeUpdate() == 1;
    } catch (SQLException e) {
      throw failure(e);
    }
  }

  /** 没有资源可运行不是失败；只推迟本代任务，不增加 attempts。 */
  public synchronized boolean deferEffect(String id, long generation, long dueAt) {
    try (PreparedStatement s =
        connection.prepareStatement(
            "UPDATE effect SET due_at=?,lease_until=0 WHERE id=? AND generation=?")) {
      s.setLong(1, dueAt);
      s.setString(2, id);
      s.setLong(3, generation);
      return s.executeUpdate() == 1;
    } catch (SQLException e) {
      throw failure(e);
    }
  }

  public synchronized boolean renewEffect(String id, long generation, long leaseUntil) {
    try (PreparedStatement s =
        connection.prepareStatement(
            "UPDATE effect SET lease_until=? WHERE id=? AND generation=?")) {
      s.setLong(1, leaseUntil);
      s.setString(2, id);
      s.setLong(3, generation);
      return s.executeUpdate() == 1;
    } catch (SQLException e) {
      throw failure(e);
    }
  }

  public synchronized boolean failEffect(
      String id, long generation, String error, long now, long initialDelay, long maxDelay) {
    if (initialDelay < 1 || maxDelay < initialDelay)
      throw new IllegalArgumentException("Invalid backoff");
    try (PreparedStatement s =
        connection.prepareStatement(
            "UPDATE effect SET attempts=attempts+1,last_error=?,due_at=? + min(?,?*(1 <<"
                + " min(attempts,20))),lease_until=0 WHERE id=? AND generation=?")) {
      s.setString(1, error);
      s.setLong(2, now);
      s.setLong(3, maxDelay);
      s.setLong(4, initialDelay);
      s.setString(5, id);
      s.setLong(6, generation);
      return s.executeUpdate() == 1;
    } catch (SQLException e) {
      throw failure(e);
    }
  }

  public synchronized void putState(String namespace, String key, Object value) {
    save("state:" + namespace, key, value);
  }

  public synchronized <T> T getState(String namespace, String key, Class<T> type) {
    return get("state:" + namespace, key, type);
  }

  public synchronized List<Effect> effects() {
    try (Statement s = connection.createStatement();
        ResultSet r = s.executeQuery("SELECT * FROM effect ORDER BY rowid")) {
      List<Effect> result = new ArrayList<>();
      while (r.next()) {
        @SuppressWarnings("unchecked")
        Map<String, String> payload = json.readValue(r.getString("payload"), Map.class);
        result.add(
            new Effect(
                r.getString("id"),
                EffectKind.valueOf(r.getString("kind")),
                r.getString("work_key"),
                payload,
                r.getInt("attempts"),
                r.getLong("due_at"),
                r.getString("last_error"),
                r.getLong("generation")));
      }
      return result;
    } catch (Exception e) {
      throw failure(e);
    }
  }

  public synchronized void appendEvent(String type, Map<String, String> payload, long at) {
    try (PreparedStatement s =
        connection.prepareStatement("INSERT INTO domain_event(type,payload,at) VALUES(?,?,?)")) {
      s.setString(1, type);
      s.setString(2, json.writeValueAsString(payload));
      s.setLong(3, at);
      s.executeUpdate();
    } catch (Exception e) {
      throw failure(e);
    }
  }

  public synchronized List<DomainEvent> events(long afterSequence, int limit) {
    if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be 1..1000");
    try (PreparedStatement s =
        connection.prepareStatement(
            "SELECT * FROM domain_event WHERE seq>? ORDER BY seq LIMIT ?")) {
      s.setLong(1, afterSequence);
      s.setInt(2, limit);
      List<DomainEvent> result = new ArrayList<>();
      try (ResultSet r = s.executeQuery()) {
        while (r.next()) {
          @SuppressWarnings("unchecked")
          Map<String, String> payload = json.readValue(r.getString("payload"), Map.class);
          result.add(
              new DomainEvent(r.getLong("seq"), r.getString("type"), payload, r.getLong("at")));
        }
      }
      return result;
    } catch (Exception e) {
      throw failure(e);
    }
  }

  public synchronized void completeEffect(String id) {
    try (PreparedStatement s = connection.prepareStatement("DELETE FROM effect WHERE id=?")) {
      s.setString(1, id);
      s.executeUpdate();
    } catch (SQLException e) {
      throw failure(e);
    }
  }

  public synchronized void failEffect(String id, String error, long now) {
    try (PreparedStatement s =
        connection.prepareStatement(
            "UPDATE effect SET attempts=attempts+1,last_error=?,due_at=? + min(300000,1000*(1 <<"
                + " min(attempts,8))),lease_until=0 WHERE id=?")) {
      s.setString(1, error);
      s.setLong(2, now);
      s.setString(3, id);
      s.executeUpdate();
    } catch (SQLException e) {
      throw failure(e);
    }
  }

  public synchronized int effectCount() {
    try (Statement s = connection.createStatement();
        ResultSet r = s.executeQuery("SELECT count(*) FROM effect")) {
      r.next();
      return r.getInt(1);
    } catch (SQLException e) {
      throw failure(e);
    }
  }

  /** 单实例启动时才调用：旧进程已不存在，因此可以立即回收所有租约。 */
  public synchronized void recoverEffects() {
    try (Statement s = connection.createStatement()) {
      s.executeUpdate("UPDATE effect SET lease_until=0,generation=generation+1");
    } catch (SQLException e) {
      throw failure(e);
    }
  }

  private static RuntimeException failure(Exception e) {
    return e instanceof RuntimeException r ? r : new IllegalStateException(e);
  }

  @Override
  public synchronized void close() {
    try {
      connection.close();
    } catch (SQLException e) {
      throw failure(e);
    }
  }
}

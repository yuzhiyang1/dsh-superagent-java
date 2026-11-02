package io.github.dsh.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;
import static io.github.dsh.core.Model.*;

/** 一个 SQLite 连接、一个进程。事务同时保存领域状态和副作用，避免「状态提交了但忘记启动执行器」。 */
public final class SqliteStore implements AutoCloseable {
    private final Connection connection;
    private final ObjectMapper json = new ObjectMapper();
    private int transactionDepth;
    public SqliteStore(Path file) {
        try {
            if (file.toAbsolutePath().getParent() != null) Files.createDirectories(file.toAbsolutePath().getParent());
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA busy_timeout=5000");
                int version;
                try (ResultSet r = s.executeQuery("PRAGMA user_version")) { r.next(); version = r.getInt(1); }
                if (version > 1) throw new IllegalStateException("Database schema is newer than this program");
                s.execute("CREATE TABLE IF NOT EXISTS entity (kind TEXT NOT NULL,id TEXT NOT NULL,json TEXT NOT NULL,PRIMARY KEY(kind,id))");
                s.execute("CREATE TABLE IF NOT EXISTS effect (id TEXT PRIMARY KEY,kind TEXT NOT NULL,work_key TEXT NOT NULL,payload TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,due_at INTEGER NOT NULL,last_error TEXT,lease_until INTEGER NOT NULL DEFAULT 0,UNIQUE(kind,work_key))");
                s.execute("CREATE INDEX IF NOT EXISTS effect_due ON effect(due_at,lease_until)");
                s.execute("PRAGMA user_version=1");
            }
        } catch (Exception e) { throw failure(e); }
    }
    public synchronized <T> T atomic(Supplier<T> operation) {
        if (transactionDepth > 0) return operation.get();
        try {
            connection.setAutoCommit(false); transactionDepth++;
            T value = operation.get(); connection.commit(); return value;
        } catch (Throwable e) {
            try { connection.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
            if (e instanceof Error error) throw error;
            throw failure((Exception) e);
        } finally {
            transactionDepth = 0;
            try { connection.setAutoCommit(true); } catch (SQLException e) { throw failure(e); }
        }
    }
    public synchronized <T> T get(String kind, String id, Class<T> type) {
        try (PreparedStatement s = connection.prepareStatement("SELECT json FROM entity WHERE kind=? AND id=?")) {
            s.setString(1,kind); s.setString(2,id);
            try (ResultSet r = s.executeQuery()) { return r.next() ? json.readValue(r.getString(1),type) : null; }
        } catch (Exception e) { throw failure(e); }
    }
    public synchronized <T> List<T> list(String kind, Class<T> type) {
        try (PreparedStatement s = connection.prepareStatement("SELECT json FROM entity WHERE kind=? ORDER BY rowid")) {
            s.setString(1,kind); List<T> result = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) { while (r.next()) result.add(json.readValue(r.getString(1),type)); }
            return result;
        } catch (Exception e) { throw failure(e); }
    }
    synchronized void save(String kind, String id, Object value) {
        try (PreparedStatement s = connection.prepareStatement("INSERT INTO entity(kind,id,json) VALUES(?,?,?) ON CONFLICT(kind,id) DO UPDATE SET json=excluded.json")) {
            s.setString(1,kind); s.setString(2,id); s.setString(3,json.writeValueAsString(value)); s.executeUpdate();
        } catch (Exception e) { throw failure(e); }
    }
    public synchronized String cursor(String source) { return get("cursor",source,String.class); }
    synchronized void cursor(String source, String cursor) { save("cursor",source,cursor); }
    synchronized void enqueue(EffectKind kind, String key, Map<String,String> payload, long now) {
        try (PreparedStatement s = connection.prepareStatement("INSERT OR IGNORE INTO effect(id,kind,work_key,payload,due_at) VALUES(?,?,?,?,?)")) {
            s.setString(1,UUID.randomUUID().toString()); s.setString(2,kind.name()); s.setString(3,key);
            s.setString(4,json.writeValueAsString(payload)); s.setLong(5,now); s.executeUpdate();
        } catch (Exception e) { throw failure(e); }
    }
    /** 租约到期后重新认领；崩溃不会永久丢任务。外部适配器仍须用 key 保证幂等。 */
    public synchronized List<Effect> claimEffects(long now, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be 1..1000");
        return atomic(() -> {
            try (PreparedStatement s = connection.prepareStatement("SELECT * FROM effect WHERE due_at<=? AND lease_until<=? ORDER BY due_at,rowid LIMIT ?")) {
                s.setLong(1,now); s.setLong(2,now); s.setInt(3,limit); List<Effect> result = new ArrayList<>();
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        @SuppressWarnings("unchecked") Map<String,String> payload = json.readValue(r.getString("payload"),Map.class);
                        result.add(new Effect(r.getString("id"),EffectKind.valueOf(r.getString("kind")),r.getString("work_key"),payload,r.getInt("attempts"),r.getLong("due_at"),r.getString("last_error")));
                    }
                }
                for (Effect e : result) try (PreparedStatement update = connection.prepareStatement("UPDATE effect SET lease_until=? WHERE id=?")) {
                    update.setLong(1,now + 300_000); update.setString(2,e.id()); update.executeUpdate();
                }
                return result;
            } catch (Exception e) { throw failure(e); }
        });
    }
    public synchronized void completeEffect(String id) {
        try (PreparedStatement s = connection.prepareStatement("DELETE FROM effect WHERE id=?")) { s.setString(1,id); s.executeUpdate(); }
        catch (SQLException e) { throw failure(e); }
    }
    public synchronized void failEffect(String id, String error, long now) {
        try (PreparedStatement s = connection.prepareStatement("UPDATE effect SET attempts=attempts+1,last_error=?,due_at=? + min(300000,1000*(1 << min(attempts,8))),lease_until=0 WHERE id=?")) {
            s.setString(1,error); s.setLong(2,now); s.setString(3,id); s.executeUpdate();
        } catch (SQLException e) { throw failure(e); }
    }
    public synchronized int effectCount() {
        try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery("SELECT count(*) FROM effect")) { r.next(); return r.getInt(1); }
        catch (SQLException e) { throw failure(e); }
    }
    /** 单实例启动时才调用：旧进程已不存在，因此可以立即回收所有租约。 */
    public synchronized void recoverEffects() {
        try (Statement s = connection.createStatement()) { s.executeUpdate("UPDATE effect SET lease_until=0"); }
        catch (SQLException e) { throw failure(e); }
    }
    private static RuntimeException failure(Exception e) { return e instanceof RuntimeException r ? r : new IllegalStateException(e); }
    @Override public synchronized void close() { try { connection.close(); } catch (SQLException e) { throw failure(e); } }
}

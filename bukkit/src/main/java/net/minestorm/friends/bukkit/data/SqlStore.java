package net.minestorm.friends.bukkit.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.minestorm.friends.bukkit.MineStormFriendsPlugin;
import net.minestorm.friends.common.Friend;
import net.minestorm.friends.common.storage.PlayerData;

import java.io.File;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTransientException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SQLite or MySQL through JDBC.
 *
 * With storage.type: MYSQL the plugin connects using only host / database /
 * username / password from config.yml. If the database does not exist it is
 * created automatically along with the tables and indexes. A HikariCP pool
 * keeps connections alive so per-query overhead is negligible.
 *
 * FIXES in this version (see fixer.py):
 *  - isRemote() was never overridden -> the CacheRefresher never started and
 *    servers never saw each other's changes.
 *  - SQL errors were swallowed -> callers reported success although nothing
 *    was stored. Writes now return a boolean and are retried on transient errors.
 *  - friendships are written in ONE transaction (no half friendships).
 *  - INSERT OR REPLACE (SQLite) wiped last_seen / allow_requests and an empty
 *    name overwrote the stored name -> replaced by real upserts.
 *  - SQLite schema used an inline INDEX clause (invalid SQLite) -> fixed.
 *  - the global "synchronized" made the connection pool pointless -> removed.
 *  - friend requests are persisted (msf_requests) so they survive restarts,
 *    server switches and lost proxy messages.
 */
public final class SqlStore implements DataStore {

    /** One unit of work on a pooled connection. */
    private interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private final MineStormFriendsPlugin plugin;
    private final boolean mysql;

    // SQLite
    private final File sqliteFile;

    // MySQL
    private final String jdbcUrl;      // full url (db guaranteed to exist)
    private final String serverUrl;    // url without db, used for CREATE DATABASE
    private final String dbName;
    private final String username;
    private final String password;

    private volatile HikariDataSource pool;
    private volatile int lifeSeconds = 1500;

    public SqlStore(MineStormFriendsPlugin plugin, boolean mysql) {
        this.plugin = plugin;
        this.mysql = mysql;

        if (mysql) {
            String host = plugin.getConfig().getString("storage.mysql.host", "127.0.0.1");
            int port = plugin.getConfig().getInt("storage.mysql.port", 3306);
            this.dbName = plugin.getConfig().getString("storage.mysql.database", "minestormfriends");
            this.username = plugin.getConfig().getString("storage.mysql.username", "root");
            this.password = plugin.getConfig().getString("storage.mysql.password", "");
            boolean useSSL = plugin.getConfig().getBoolean("storage.mysql.useSSL", false);
            String tz = plugin.getConfig().getString("storage.mysql.serverTimezone", "UTC");
            String enc = plugin.getConfig().getString("storage.mysql.characterEncoding", "utf8");

            String base = "jdbc:mysql://" + host + ":" + port;
            String opts = "?useSSL=" + useSSL
                    + "&serverTimezone=" + tz
                    + "&characterEncoding=" + enc
                    + "&useUnicode=true"
                    + "&allowPublicKeyRetrieval=true"
                    + "&autoReconnect=true"
                    + "&createDatabaseIfNotExist=true";
            this.serverUrl = base + "/" + opts;
            this.jdbcUrl = base + "/" + dbName + opts;
            this.sqliteFile = null;
        } else {
            this.sqliteFile = new File(plugin.getDataFolder(), "friends.db");
            this.jdbcUrl = null;
            this.serverUrl = null;
            this.dbName = null;
            this.username = null;
            this.password = null;
        }
    }

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------

    @Override public void init() throws Exception {
        if (mysql) ensureDatabaseExists();
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                open();
                createSchema();
                return;
            } catch (Exception ex) {
                last = ex;
                closePool();
                if (attempt < 3) {
                    plugin.getLogger().warning("Database not ready (attempt " + attempt
                            + "/3): " + ex.getMessage());
                    sleep(3000L);
                }
            }
        }
        throw last;
    }

    @Override public void close() { closePool(); }

    private void closePool() {
        HikariDataSource p = pool;
        pool = null;
        if (p != null && !p.isClosed()) p.close();
    }

    /** FIX: this was missing, so the cache refresher never ran for MySQL. */
    @Override public boolean isRemote() { return mysql; }

    @Override public boolean supportsRequests() { return true; }

    // ------------------------------------------------------------------
    // connections
    // ------------------------------------------------------------------

    private void open() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl != null ? jdbcUrl : "jdbc:sqlite:" + sqliteFile.getAbsolutePath());
        cfg.setPoolName("MineStormFriends-" + (mysql ? "MySQL" : "SQLite"));
        cfg.setLeakDetectionThreshold(60000L);

        if (mysql) {
            cfg.setDriverClassName("com.mysql.cj.jdbc.Driver");
            cfg.setUsername(username);
            cfg.setPassword(password);
            cfg.setMaximumPoolSize(Math.max(2, plugin.getConfig().getInt("storage.mysql.poolSize", 10)));
            cfg.setMinimumIdle(1);
            // short timeout: a dead database must not freeze the main thread for 30 s
            cfg.setConnectionTimeout(Math.max(1, plugin.getConfig().getInt("storage.mysql.connectionTimeoutSeconds", 5)) * 1000L);
            int life = Math.max(60, plugin.getConfig().getInt("storage.mysql.maxLifetimeSeconds", 1500));
            lifeSeconds = life;
            cfg.setMaxLifetime(life * 1000L);
            cfg.setIdleTimeout(Math.min(600000L, life * 500L));
            // keep idle connections alive so hosts with a short wait_timeout do not kill them
            int keepalive = plugin.getConfig().getInt("storage.mysql.keepaliveSeconds", 60);
            if (keepalive >= 30 && keepalive < life) cfg.setKeepaliveTime(keepalive * 1000L);
        } else {
            cfg.setDriverClassName("org.sqlite.JDBC");
            cfg.setMaximumPoolSize(1); // SQLite is a single-file DB
            cfg.setConnectionTimeout(10000L);
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        }

        pool = new HikariDataSource(cfg);
    }

    /** Connect to the server (no db) and CREATE DATABASE if needed. */
    private void ensureDatabaseExists() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException ex) {
            throw new SQLException("MySQL driver not found", ex);
        }
        try (Connection c = DriverManager.getConnection(serverUrl, username, password);
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + dbName + "` "
                    + "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        } catch (Throwable ex) {
            plugin.getLogger().warning(
                "Could not auto-create database '" + dbName + "': " + ex.getMessage());
            // continue - the DB likely exists and we lack CREATE permission,
            // or the credentials are wrong; open() will report the real error.
        }
    }

    private void createSchema() throws SQLException {
        String engineSuffix = mysql
                ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
                : "";
        try (Connection c = c();
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS msf_players ("
                    + " uuid VARCHAR(36) NOT NULL PRIMARY KEY,"
                    + " name VARCHAR(32),"
                    + " allow_requests INTEGER NOT NULL DEFAULT 1,"
                    + " last_seen BIGINT NOT NULL DEFAULT 0"
                    + (mysql ? ", INDEX idx_msf_players_name (name)" : "")
                    + ")" + engineSuffix);
            st.executeUpdate("CREATE TABLE IF NOT EXISTS msf_friends ("
                    + " owner VARCHAR(36) NOT NULL,"
                    + " friend VARCHAR(36) NOT NULL,"
                    + " friend_name VARCHAR(32),"
                    + " PRIMARY KEY (owner, friend)"
                    + (mysql ? ", INDEX idx_msf_friends_friend (friend)" : "")
                    + ")" + engineSuffix);
            st.executeUpdate("CREATE TABLE IF NOT EXISTS msf_requests ("
                    + " to_uuid VARCHAR(36) NOT NULL,"
                    + " from_uuid VARCHAR(36) NOT NULL,"
                    + " to_name VARCHAR(32),"
                    + " from_name VARCHAR(32),"
                    + " created_at BIGINT NOT NULL DEFAULT 0,"
                    + " PRIMARY KEY (to_uuid, from_uuid)"
                    + (mysql ? ", INDEX idx_msf_requests_created (created_at)" : "")
                    + ")" + engineSuffix);

            if (mysql) {
                // upgrade databases created by older versions (errors = index already exists)
                tryIgnore(st, "ALTER TABLE msf_players ADD INDEX idx_msf_players_name (name)");
                tryIgnore(st, "ALTER TABLE msf_friends ADD INDEX idx_msf_friends_friend (friend)");
            } else {
                // SQLite does not allow an inline INDEX clause - separate statements
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_msf_players_name ON msf_players (name)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_msf_friends_friend ON msf_friends (friend)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_msf_requests_created ON msf_requests (created_at)");
            }
        }
    }

    private static void tryIgnore(Statement st, String sql) {
        try {
            st.executeUpdate(sql);
        } catch (SQLException ignored) { }
    }

    private Connection c() throws SQLException {
        HikariDataSource p = pool;
        if (p == null || p.isClosed()) throw new SQLNonTransientConnectionException("SqlStore is not initialised / already closed");
        return p.getConnection();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isTransient(SQLException e) {
        if (e instanceof SQLIntegrityConstraintViolationException || e instanceof SQLSyntaxErrorException) return false;
        if (e instanceof SQLTransientException || e instanceof SQLRecoverableException) return true;
        String state = e.getSQLState();
        if (state != null && state.startsWith("08")) return true;
        int code = e.getErrorCode();
        return code == 1213 || code == 1205 || code == 2006 || code == 2013; // deadlock, lock wait, server gone
    }

    /**
     * Runs {@code w} on a pooled connection. Transient failures (dropped connection,
     * deadlock, pool timeout) are retried - except on the main thread, where waiting
     * would freeze the server.
     */
    private <T> T run(String op, boolean transactional, Work<T> w) throws SQLException {
        int attempts = plugin.isMainThread() ? 1 : 3;
        SQLException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try (Connection c = c()) {
                if (!transactional) return w.run(c);
                boolean oldAuto = c.getAutoCommit();
                c.setAutoCommit(false);
                try {
                    T result = w.run(c);
                    c.commit();
                    return result;
                } catch (SQLException | RuntimeException ex) {
                    try { c.rollback(); } catch (SQLException ignored) { }
                    throw ex;
                } finally {
                    try { c.setAutoCommit(oldAuto); } catch (SQLException ignored) { }
                }
            } catch (SQLException ex) {
                last = ex;
                if (attempt >= attempts || !isTransient(ex)) break;
                plugin.getLogger().warning("SQL " + op + " failed (attempt " + attempt + "/" + attempts
                        + "), retrying: " + ex.getMessage());
                sleep(200L * attempt);
            }
        }
        throw last;
    }

    private void warn(String op, SQLException e) {
        plugin.getLogger().warning("SQL error in " + op + ": " + e.getMessage()
                + " (SQLState " + e.getSQLState() + ", code " + e.getErrorCode() + ")");
    }

    // ------------------------------------------------------------------
    // statements shared by several operations (all take an open connection)
    // ------------------------------------------------------------------

    private void upsertProfile(Connection c, UUID uuid, String name, long seen) throws SQLException {
        String nm = name == null ? "" : name;
        if (mysql) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO msf_players (uuid, name, allow_requests, last_seen) VALUES (?,?,1,?) "
                    + "ON DUPLICATE KEY UPDATE name=IF(VALUES(name)='', name, VALUES(name)), "
                    + "last_seen=GREATEST(last_seen, VALUES(last_seen))")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, nm);
                ps.setLong(3, seen);
                ps.executeUpdate();
            }
            return;
        }
        // SQLite: INSERT OR REPLACE would reset allow_requests / last_seen -> UPDATE first
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE msf_players SET name=CASE WHEN ?='' THEN name ELSE ? END, "
                + "last_seen=MAX(last_seen, ?) WHERE uuid=?")) {
            ps.setString(1, nm);
            ps.setString(2, nm);
            ps.setLong(3, seen);
            ps.setString(4, uuid.toString());
            if (ps.executeUpdate() > 0) return;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO msf_players (uuid, name, allow_requests, last_seen) VALUES (?,?,1,?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, nm);
            ps.setLong(3, seen);
            ps.executeUpdate();
        }
    }

    private void upsertAllow(Connection c, UUID uuid, String name, boolean allow) throws SQLException {
        String nm = name == null ? "" : name;
        if (mysql) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO msf_players (uuid, name, allow_requests, last_seen) VALUES (?,?,?,0) "
                    + "ON DUPLICATE KEY UPDATE allow_requests=VALUES(allow_requests), "
                    + "name=IF(VALUES(name)='', name, VALUES(name))")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, nm);
                ps.setInt(3, allow ? 1 : 0);
                ps.executeUpdate();
            }
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE msf_players SET allow_requests=?, name=CASE WHEN ?='' THEN name ELSE ? END WHERE uuid=?")) {
            ps.setInt(1, allow ? 1 : 0);
            ps.setString(2, nm);
            ps.setString(3, nm);
            ps.setString(4, uuid.toString());
            if (ps.executeUpdate() > 0) return;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO msf_players (uuid, name, allow_requests, last_seen) VALUES (?,?,?,0)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, nm);
            ps.setInt(3, allow ? 1 : 0);
            ps.executeUpdate();
        }
    }

    private void upsertFriend(Connection c, UUID owner, UUID friend, String friendName) throws SQLException {
        String sql = mysql
                ? "INSERT INTO msf_friends (owner, friend, friend_name) VALUES (?,?,?) "
                  + "ON DUPLICATE KEY UPDATE friend_name=VALUES(friend_name)"
                : "INSERT OR REPLACE INTO msf_friends (owner, friend, friend_name) VALUES (?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, owner.toString());
            ps.setString(2, friend.toString());
            ps.setString(3, friendName == null || friendName.isEmpty() ? "unknown" : friendName);
            ps.executeUpdate();
        }
    }

    private void deleteFriend(Connection c, UUID owner, UUID friend) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM msf_friends WHERE owner=? AND friend=?")) {
            ps.setString(1, owner.toString());
            ps.setString(2, friend.toString());
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------
    // DataStore implementation
    // ------------------------------------------------------------------

    @Override public PlayerData load(final UUID uuid, final String name) {
        try {
            return run("load", false, c -> {
                PlayerData d = new PlayerData(uuid);
                d.setLastName(name);
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT name, allow_requests, last_seen FROM msf_players WHERE uuid=?")) {
                    ps.setString(1, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            if (name == null) d.setLastName(rs.getString(1));
                            d.setAllowRequests(rs.getInt(2) != 0);
                            d.setLastSeen(rs.getLong(3));
                        }
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT friend, friend_name FROM msf_friends WHERE owner=?")) {
                    ps.setString(1, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            try {
                                d.getFriends().add(new Friend(UUID.fromString(rs.getString(1)), rs.getString(2)));
                            } catch (IllegalArgumentException ignored) { }
                        }
                    }
                }
                return d;
            });
        } catch (SQLException e) {
            warn("load", e);
            return null; // NOT an empty PlayerData: the caller must know the load failed
        }
    }

    @Override public void saveProfile(final UUID uuid, final String name, final boolean markSeen) {
        if (name == null || name.isEmpty()) return;
        try {
            final long now = markSeen ? System.currentTimeMillis() : 0L;
            run("saveProfile", false, c -> {
                upsertProfile(c, uuid, name, now);
                return Boolean.TRUE;
            });
        } catch (SQLException e) {
            warn("saveProfile", e);
        }
    }

    @Override public void addFriend(final UUID owner, final UUID friend, final String friendName) {
        try {
            run("addFriend", false, c -> {
                upsertFriend(c, owner, friend, friendName);
                return Boolean.TRUE;
            });
        } catch (SQLException e) {
            warn("addFriend", e);
        }
    }

    @Override public void removeFriend(final UUID owner, final UUID friend) {
        try {
            run("removeFriend", false, c -> {
                deleteFriend(c, owner, friend);
                return Boolean.TRUE;
            });
        } catch (SQLException e) {
            warn("removeFriend", e);
        }
    }

    @Override public void setAllowRequests(UUID uuid, String name, boolean allow) {
        updateAllowRequests(uuid, name, allow);
    }

    @Override public boolean updateAllowRequests(final UUID uuid, final String name, final boolean allow) {
        try {
            run("setAllowRequests", false, c -> {
                upsertAllow(c, uuid, name, allow);
                return Boolean.TRUE;
            });
            return true;
        } catch (SQLException e) {
            warn("setAllowRequests", e);
            return false;
        }
    }

    @Override public boolean linkFriends(final UUID a, final String an, final UUID b, final String bn) {
        try {
            run("linkFriends", true, c -> {
                upsertProfile(c, a, an, 0L);
                upsertProfile(c, b, bn, 0L);
                upsertFriend(c, a, b, bn);
                upsertFriend(c, b, a, an);
                return Boolean.TRUE;
            });
            return true;
        } catch (SQLException e) {
            warn("linkFriends", e);
            return false;
        }
    }

    @Override public boolean unlinkFriends(final UUID a, final UUID b) {
        try {
            run("unlinkFriends", true, c -> {
                deleteFriend(c, a, b);
                deleteFriend(c, b, a);
                return Boolean.TRUE;
            });
            return true;
        } catch (SQLException e) {
            warn("unlinkFriends", e);
            return false;
        }
    }

    @Override public boolean clearFriends(final UUID owner) {
        try {
            run("clearFriends", false, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM msf_friends WHERE owner=? OR friend=?")) {
                    ps.setString(1, owner.toString());
                    ps.setString(2, owner.toString());
                    ps.executeUpdate();
                }
                return Boolean.TRUE;
            });
            return true;
        } catch (SQLException e) {
            warn("clearFriends", e);
            return false;
        }
    }

    @Override public UUID findUuid(final String name) {
        if (name == null || name.isEmpty()) return null;
        try {
            return run("findUuid", false, c -> {
                // utf8mb4_unicode_ci is case-insensitive, so no LOWER() (it would defeat the index)
                String sql = "SELECT uuid FROM msf_players WHERE name=?"
                        + (mysql ? "" : " COLLATE NOCASE")
                        + " ORDER BY last_seen DESC LIMIT 1";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, name);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            try {
                                return UUID.fromString(rs.getString(1));
                            } catch (IllegalArgumentException ignored) { }
                        }
                    }
                }
                return null;
            });
        } catch (SQLException e) {
            warn("findUuid", e);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // persisted requests
    // ------------------------------------------------------------------

    @Override public boolean saveRequest(final UUID from, final String fromName,
                                         final UUID to, final String toName, final long created) {
        try {
            run("saveRequest", false, c -> {
                String sql = mysql
                        ? "INSERT INTO msf_requests (to_uuid, from_uuid, to_name, from_name, created_at) VALUES (?,?,?,?,?) "
                          + "ON DUPLICATE KEY UPDATE to_name=VALUES(to_name), from_name=VALUES(from_name), created_at=VALUES(created_at)"
                        : "INSERT OR REPLACE INTO msf_requests (to_uuid, from_uuid, to_name, from_name, created_at) VALUES (?,?,?,?,?)";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, to.toString());
                    ps.setString(2, from.toString());
                    ps.setString(3, toName == null ? "" : toName);
                    ps.setString(4, fromName == null ? "" : fromName);
                    ps.setLong(5, created);
                    ps.executeUpdate();
                }
                return Boolean.TRUE;
            });
            return true;
        } catch (SQLException e) {
            warn("saveRequest", e);
            return false;
        }
    }

    @Override public void deleteRequest(final UUID to, final UUID from) {
        try {
            run("deleteRequest", false, c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM msf_requests WHERE to_uuid=? AND from_uuid=?")) {
                    ps.setString(1, to.toString());
                    ps.setString(2, from.toString());
                    ps.executeUpdate();
                }
                return Boolean.TRUE;
            });
        } catch (SQLException e) {
            warn("deleteRequest", e);
        }
    }

    @Override public void deleteRequestsFor(final UUID to) {
        try {
            run("deleteRequestsFor", false, c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM msf_requests WHERE to_uuid=?")) {
                    ps.setString(1, to.toString());
                    ps.executeUpdate();
                }
                return Boolean.TRUE;
            });
        } catch (SQLException e) {
            warn("deleteRequestsFor", e);
        }
    }

    @Override public void purgeRequestsOlderThan(final long cutoffMillis) {
        try {
            run("purgeRequests", false, c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM msf_requests WHERE created_at < ?")) {
                    ps.setLong(1, cutoffMillis);
                    ps.executeUpdate();
                }
                return Boolean.TRUE;
            });
        } catch (SQLException e) {
            warn("purgeRequests", e);
        }
    }

    @Override public List<StoredRequest> loadRequests(final UUID to) {
        try {
            return run("loadRequests", false, c -> {
                List<StoredRequest> list = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT from_uuid, from_name, to_name, created_at FROM msf_requests WHERE to_uuid=?")) {
                    ps.setString(1, to.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            try {
                                list.add(new StoredRequest(UUID.fromString(rs.getString(1)), rs.getString(2),
                                        to, rs.getString(3), rs.getLong(4)));
                            } catch (IllegalArgumentException ignored) { }
                        }
                    }
                }
                return list;
            });
        } catch (SQLException e) {
            warn("loadRequests", e);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // diagnostics
    // ------------------------------------------------------------------

    @Override public List<String> diagnose() {
        final List<String> out = new ArrayList<>();
        out.add("Storage: " + (mysql ? "MySQL (shared between servers)" : "SQLite (local file - NOT shared)"));
        long t0 = System.nanoTime();
        try (Connection c = c()) {
            long ms = (System.nanoTime() - t0) / 1000000L;
            DatabaseMetaData md = c.getMetaData();
            out.add("Connected in " + ms + " ms - " + md.getDatabaseProductName() + " " + md.getDatabaseProductVersion());

            if (mysql) {
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT DATABASE(), @@session.wait_timeout")) {
                    if (rs.next()) {
                        long wait = rs.getLong(2);
                        out.add("Database: " + rs.getString(1) + " (server wait_timeout " + wait + "s)");
                        if (wait < lifeSeconds) {
                            out.add("WARNING: wait_timeout is lower than storage.mysql.maxLifetimeSeconds ("
                                    + lifeSeconds + "s) - lower maxLifetimeSeconds in config.yml.");
                        }
                    }
                }
            }

            String[] tables = {"msf_players", "msf_friends", "msf_requests"};
            for (String t : tables) {
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + t)) {
                    out.add("Table " + t + ": " + (rs.next() ? rs.getLong(1) : 0L) + " rows");
                }
            }

            String probe = UUID.randomUUID().toString();
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO msf_players (uuid, name, allow_requests, last_seen) VALUES (?,?,1,0)")) {
                    ps.setString(1, probe);
                    ps.setString(2, "~dbcheck~");
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("SELECT name FROM msf_players WHERE uuid=?")) {
                    ps.setString(1, probe);
                    try (ResultSet rs = ps.executeQuery()) {
                        out.add(rs.next() ? "Write/read test: OK" : "Write/read test: FAILED (row not found after insert)");
                    }
                }
            } finally {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM msf_players WHERE uuid=?")) {
                    ps.setString(1, probe);
                    ps.executeUpdate();
                } catch (SQLException ignored) { }
            }
        } catch (SQLException e) {
            out.add("FAILED: " + e.getMessage());
        }
        return out;
    }
}

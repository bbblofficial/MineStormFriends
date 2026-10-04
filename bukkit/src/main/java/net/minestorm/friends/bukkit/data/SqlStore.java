package net.minestorm.friends.bukkit.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.minestorm.friends.bukkit.MineStormFriendsPlugin;
import net.minestorm.friends.common.Friend;
import net.minestorm.friends.common.storage.PlayerData;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * SQLite or MySQL through JDBC.
 *
 * With storage.type: MYSQL the plugin connects using only host / database /
 * username / password from config.yml. If the database does not exist it is
 * created automatically along with the tables and indexes. A HikariCP pool
 * keeps connections alive so per-query overhead is negligible.
 *
 * All SQL is written so that any number of servers may apply the same event
 * without conflict (INSERT ... ON DUPLICATE KEY UPDATE on MySQL,
 * INSERT OR REPLACE on SQLite).
 */
public final class SqlStore implements DataStore {

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

    private HikariDataSource pool;

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
    // DataStore
    // ------------------------------------------------------------------

    @Override public synchronized void init() throws Exception {
        if (mysql) ensureDatabaseExists();
        open();
        createSchema();
    }

    @Override public synchronized void close() {
        if (pool != null && !pool.isClosed()) pool.close();
        pool = null;
    }

    // ------------------------------------------------------------------
    // connections
    // ------------------------------------------------------------------

    private void open() throws SQLException {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl != null ? jdbcUrl : "jdbc:sqlite:" + sqliteFile.getAbsolutePath());
        cfg.setPoolName("MineStormFriends-" + (mysql ? "MySQL" : "SQLite"));
        cfg.setLeakDetectionThreshold(60000L);

        if (mysql) {
            cfg.setDriverClassName("com.mysql.cj.jdbc.Driver");
            cfg.setUsername(username);
            cfg.setPassword(password);
            cfg.setMaximumPoolSize(plugin.getConfig().getInt("storage.mysql.poolSize", 10));
            cfg.setMinimumIdle(1);
            cfg.setConnectionTimeout(10000L);
            cfg.setIdleTimeout(600000L);
            cfg.setMaxLifetime(1800000L);
        } else {
            cfg.setDriverClassName("org.sqlite.JDBC");
            cfg.setMaximumPoolSize(1); // SQLite is a single-file DB
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
        } catch (SQLException ex) {
            plugin.getLogger().warning(
                "Could not auto-create database '" + dbName + "': " + ex.getMessage());
            // continue - the DB likely exists and we lack CREATE permission
        }
    }

    private void createSchema() throws SQLException {
        String engineSuffix = mysql
                ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
                : "";
        try (Connection c = pool.getConnection();
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS msf_players ("
                    + " uuid VARCHAR(36) NOT NULL PRIMARY KEY,"
                    + " name VARCHAR(32),"
                    + " allow_requests INTEGER NOT NULL DEFAULT 1,"
                    + " last_seen BIGINT NOT NULL DEFAULT 0)" + engineSuffix);
            st.executeUpdate("CREATE TABLE IF NOT EXISTS msf_friends ("
                    + " owner VARCHAR(36) NOT NULL,"
                    + " friend VARCHAR(36) NOT NULL,"
                    + " friend_name VARCHAR(32),"
                    + " PRIMARY KEY (owner, friend),"
                    + " INDEX idx_msf_friends_owner (owner),"
                    + " INDEX idx_msf_friends_friend (friend))" + engineSuffix);
        }
    }

    private Connection c() throws SQLException {
        if (pool == null) throw new SQLException("SqlStore not initialised");
        return pool.getConnection();
    }

    private int exec(String sql, Object... params) throws SQLException {
        try (Connection c = c();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            return ps.executeUpdate();
        }
    }

    private void warn(SQLException e) {
        plugin.getLogger().warning("SQL error: " + e.getMessage());
    }

    // ------------------------------------------------------------------
    // portable upsert helpers
    // ------------------------------------------------------------------

    private String upsertPlayerSql() {
        return mysql
                ? "INSERT INTO msf_players (uuid, name, allow_requests, last_seen) VALUES (?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE name=VALUES(name), allow_requests=VALUES(allow_requests), "
                + "last_seen=GREATEST(last_seen, VALUES(last_seen))"
                : "INSERT OR REPLACE INTO msf_players (uuid, name, allow_requests, last_seen) VALUES (?,?,?,?)";
    }

    private String upsertFriendSql() {
        return mysql
                ? "INSERT INTO msf_friends (owner, friend, friend_name) VALUES (?,?,?) "
                + "ON DUPLICATE KEY UPDATE friend_name=VALUES(friend_name)"
                : "INSERT OR REPLACE INTO msf_friends (owner, friend, friend_name) VALUES (?,?,?)";
    }

    // ------------------------------------------------------------------
    // DataStore implementation
    // ------------------------------------------------------------------

    @Override public synchronized PlayerData load(UUID uuid, String name) {
        PlayerData d = new PlayerData(uuid);
        d.setLastName(name);
        try (Connection c = c()) {
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
        } catch (SQLException e) {
            warn(e);
        }
        return d;
    }

    @Override public synchronized void saveProfile(UUID uuid, String name, boolean markSeen) {
        if (name == null) return;
        try {
            long now = markSeen ? System.currentTimeMillis() : 0L;
            // read current allow_requests first to avoid clobbering it
            boolean allow = true;
            try (Connection c = c();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT allow_requests FROM msf_players WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) allow = rs.getInt(1) != 0;
                }
            }
            exec(upsertPlayerSql(), uuid.toString(), name, allow ? 1 : 0, now);
        } catch (SQLException e) {
            warn(e);
        }
    }

    @Override public synchronized void addFriend(UUID owner, UUID friend, String friendName) {
        try {
            exec(upsertFriendSql(),
                    owner.toString(), friend.toString(),
                    friendName == null ? "unknown" : friendName);
        } catch (SQLException e) {
            warn(e);
        }
    }

    @Override public synchronized void removeFriend(UUID owner, UUID friend) {
        try {
            exec("DELETE FROM msf_friends WHERE owner=? AND friend=?",
                    owner.toString(), friend.toString());
        } catch (SQLException e) {
            warn(e);
        }
    }

    @Override public synchronized void setAllowRequests(UUID uuid, String name, boolean allow) {
        try {
            exec(upsertPlayerSql(), uuid.toString(),
                    name == null ? "" : name, allow ? 1 : 0, 0L);
        } catch (SQLException e) {
            warn(e);
        }
    }

    @Override public synchronized UUID findUuid(String name) {
        try (Connection c = c();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT uuid FROM msf_players WHERE LOWER(name)=LOWER(?) "
                     + "ORDER BY last_seen DESC LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return UUID.fromString(rs.getString(1));
            }
        } catch (SQLException | IllegalArgumentException e) {
            plugin.getLogger().warning("Lookup failed: " + e.getMessage());
        }
        return null;
    }
}

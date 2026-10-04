package net.minestorm.friends.bukkit.data;

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
 * SQLite or MySQL through plain JDBC (both drivers ship with Spigot / Paper).
 * Uses only portable SQL (UPDATE, then INSERT when nothing was updated).
 */
public final class SqlStore implements DataStore {
    private final MineStormFriendsPlugin plugin;
    private final boolean mysql;
    private Connection conn;

    public SqlStore(MineStormFriendsPlugin plugin, boolean mysql) {
        this.plugin = plugin;
        this.mysql = mysql;
    }

    @Override public synchronized void init() throws Exception {
        open();
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS msf_players ("
                    + "uuid VARCHAR(36) NOT NULL PRIMARY KEY, "
                    + "name VARCHAR(32), "
                    + "allow_requests INTEGER NOT NULL DEFAULT 1, "
                    + "last_seen BIGINT NOT NULL DEFAULT 0)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS msf_friends ("
                    + "owner VARCHAR(36) NOT NULL, "
                    + "friend VARCHAR(36) NOT NULL, "
                    + "friend_name VARCHAR(32), "
                    + "PRIMARY KEY (owner, friend))");
        }
    }

    @Override public synchronized void close() {
        try { if (conn != null) conn.close(); } catch (SQLException ignored) { }
        conn = null;
    }

    private void open() throws SQLException {
        try { if (conn != null) conn.close(); } catch (SQLException ignored) { }
        if (mysql) {
            String host = plugin.getConfig().getString("storage.mysql.host", "127.0.0.1");
            int port = plugin.getConfig().getInt("storage.mysql.port", 3306);
            String db = plugin.getConfig().getString("storage.mysql.database", "minestormfriends");
            String params = plugin.getConfig().getString("storage.mysql.params", "useSSL=false");
            conn = DriverManager.getConnection(
                    "jdbc:mysql://" + host + ":" + port + "/" + db + "?" + params,
                    plugin.getConfig().getString("storage.mysql.username", "root"),
                    plugin.getConfig().getString("storage.mysql.password", ""));
        } else {
            try { Class.forName("org.sqlite.JDBC"); } catch (ClassNotFoundException ignored) { }
            File f = new File(plugin.getDataFolder(), "friends.db");
            plugin.getDataFolder().mkdirs();
            conn = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
        }
    }

    private Connection c() throws SQLException {
        if (conn == null || conn.isClosed() || !conn.isValid(2)) open();
        return conn;
    }

    private int exec(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            return ps.executeUpdate();
        }
    }

    private void warn(SQLException e) {
        plugin.getLogger().warning("SQL error: " + e.getMessage());
    }

    private void insertPlayer(UUID id, String name, boolean allow, long seen) {
        try {
            exec("INSERT INTO msf_players (uuid, name, allow_requests, last_seen) VALUES (?,?,?,?)",
                    id.toString(), name, allow ? 1 : 0, seen);
        } catch (SQLException duplicate) {
            // another server inserted the row first - fine
        }
    }

    @Override public synchronized PlayerData load(UUID uuid, String name) {
        PlayerData d = new PlayerData(uuid);
        d.setLastName(name);
        try {
            try (PreparedStatement ps = c().prepareStatement(
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
            try (PreparedStatement ps = c().prepareStatement(
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
            long now = System.currentTimeMillis();
            int n = markSeen
                    ? exec("UPDATE msf_players SET name=?, last_seen=? WHERE uuid=?", name, now, uuid.toString())
                    : exec("UPDATE msf_players SET name=? WHERE uuid=?", name, uuid.toString());
            if (n == 0) insertPlayer(uuid, name, true, markSeen ? now : 0L);
        } catch (SQLException e) {
            warn(e);
        }
    }

    @Override public synchronized void addFriend(UUID owner, UUID friend, String friendName) {
        try {
            int n = exec("UPDATE msf_friends SET friend_name=? WHERE owner=? AND friend=?",
                    friendName, owner.toString(), friend.toString());
            if (n == 0) {
                try {
                    exec("INSERT INTO msf_friends (owner, friend, friend_name) VALUES (?,?,?)",
                            owner.toString(), friend.toString(), friendName);
                } catch (SQLException duplicate) {
                    // already inserted by another server
                }
            }
        } catch (SQLException e) {
            warn(e);
        }
    }

    @Override public synchronized void removeFriend(UUID owner, UUID friend) {
        try {
            exec("DELETE FROM msf_friends WHERE owner=? AND friend=?", owner.toString(), friend.toString());
        } catch (SQLException e) {
            warn(e);
        }
    }

    @Override public synchronized void setAllowRequests(UUID uuid, String name, boolean allow) {
        try {
            int n = exec("UPDATE msf_players SET allow_requests=? WHERE uuid=?", allow ? 1 : 0, uuid.toString());
            if (n == 0) insertPlayer(uuid, name, allow, 0L);
        } catch (SQLException e) {
            warn(e);
        }
    }

    @Override public synchronized UUID findUuid(String name) {
        try (PreparedStatement ps = c().prepareStatement(
                "SELECT uuid FROM msf_players WHERE LOWER(name)=LOWER(?) ORDER BY last_seen DESC LIMIT 1")) {
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

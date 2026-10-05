package net.minestorm.friends.bukkit.data;

import net.minestorm.friends.bukkit.MineStormFriendsPlugin;
import net.minestorm.friends.common.Friend;
import net.minestorm.friends.common.storage.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the data of online players in memory and applies network events to both the
 * backend store and the cached copies. Offline players are read straight from the store.
 *
 * FIXES: database writes run on the DB thread (never on the main thread), a failed
 * load is never cached as "empty friend list", failed writes are reported to the
 * player, and refreshOnline() no longer overwrites changes that are still being written.
 */
public final class FriendManager {
    private final MineStormFriendsPlugin plugin;
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();

    public FriendManager(MineStormFriendsPlugin plugin) { this.plugin = plugin; }

    /** Thread-safe: may be called from AsyncPlayerPreLoginEvent. */
    public void cacheLoad(UUID id, String name) {
        PlayerData d = plugin.data().load(id, name);
        if (d != null) {
            cache.put(id, d);
        } else {
            plugin.getLogger().warning("Could not load friend data of " + name
                    + " (database unreachable?) - retrying automatically.");
        }
    }

    public void unload(UUID id) { cache.remove(id); }

    public PlayerData cached(UUID id) { return cache.get(id); }

    /** Cached data if the player is online here, otherwise a fresh read from the store. */
    public PlayerData get(UUID id, String name) {
        PlayerData d = cache.get(id);
        if (d != null) return d;
        d = plugin.data().load(id, name);
        if (d == null) { // database down: behave as empty but do NOT cache it
            d = new PlayerData(id);
            d.setLastName(name);
        }
        return d;
    }

    public void link(final UUID a, final String an, final UUID b, final String bn) {
        PlayerData da = cache.get(a);
        if (da != null) da.getFriends().add(new Friend(b, bn));
        PlayerData db = cache.get(b);
        if (db != null) db.getFriends().add(new Friend(a, an));
        plugin.runDbWrite(() -> {
            if (!plugin.data().linkFriends(a, an, b, bn)) saveFailed(a, b);
        });
    }

    public void unlink(final UUID a, final UUID b) {
        PlayerData da = cache.get(a);
        if (da != null) da.getFriends().remove(b);
        PlayerData db = cache.get(b);
        if (db != null) db.getFriends().remove(a);
        plugin.runDbWrite(() -> {
            if (!plugin.data().unlinkFriends(a, b)) saveFailed(a, b);
        });
    }

    /** Removes every friendship of {@code owner}; returns who was removed. */
    public List<Friend> clearAll(final UUID owner) {
        PlayerData d = get(owner, null);
        List<Friend> removed = new ArrayList<>(d.getFriends().all());

        PlayerData mine = cache.get(owner);
        if (mine != null) mine.getFriends().clear();
        for (Friend f : removed) {
            PlayerData pf = cache.get(f.getUuid());
            if (pf != null) pf.getFriends().remove(owner);
        }
        plugin.runDbWrite(() -> {
            if (!plugin.data().clearFriends(owner)) saveFailed(owner);
        });
        return removed;
    }

    public void setAllow(final UUID id, final String name, final boolean value) {
        PlayerData d = cache.get(id);
        if (d != null) d.setAllowRequests(value);
        plugin.runDbWrite(() -> {
            if (!plugin.data().updateAllowRequests(id, name, value)) saveFailed(id);
        });
    }

    private void saveFailed(final UUID... ids) {
        plugin.getLogger().severe("A friend change could NOT be written to the database (all retries failed). "
                + "The cache will be corrected from the database on the next refresh.");
        plugin.sync(() -> {
            for (UUID id : ids) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) plugin.messages().send(p, "db-error");
            }
        });
    }

    /**
     * Re-reads the cached entries of every player online on THIS server from the
     * backend, so changes made on other servers become visible without a relog.
     * Must be called on the main thread; the database work happens on the DB thread.
     */
    public void refreshOnline() {
        final long seq = plugin.writeSeq();
        final Map<UUID, String> online = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) online.put(p.getUniqueId(), p.getName());
        if (online.isEmpty()) return;

        plugin.runDb(() -> {
            final Map<UUID, PlayerData> fresh = new HashMap<>();
            for (Map.Entry<UUID, String> e : online.entrySet()) {
                PlayerData d = plugin.data().load(e.getKey(), e.getValue());
                if (d != null) fresh.put(e.getKey(), d);
            }
            plugin.sync(() -> {
                // a local change was queued while we were reading -> our snapshot may be stale
                if (plugin.writeSeq() != seq) return;
                for (Map.Entry<UUID, PlayerData> e : fresh.entrySet()) {
                    if (Bukkit.getPlayer(e.getKey()) != null) cache.put(e.getKey(), e.getValue());
                }
            });
        });
    }
}

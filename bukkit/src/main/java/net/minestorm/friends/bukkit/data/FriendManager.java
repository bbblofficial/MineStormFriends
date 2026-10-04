package net.minestorm.friends.bukkit.data;

import net.minestorm.friends.bukkit.MineStormFriendsPlugin;
import net.minestorm.friends.common.Friend;
import net.minestorm.friends.common.storage.PlayerData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the data of online players in memory and applies network events to both the
 * backend store and the cached copies. Offline players are read straight from the store.
 */
public final class FriendManager {
    private final MineStormFriendsPlugin plugin;
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();

    public FriendManager(MineStormFriendsPlugin plugin) { this.plugin = plugin; }

    /** Thread-safe: may be called from AsyncPlayerPreLoginEvent. */
    public void cacheLoad(UUID id, String name) { cache.put(id, plugin.data().load(id, name)); }

    public void unload(UUID id) { cache.remove(id); }

    public PlayerData cached(UUID id) { return cache.get(id); }

    /** Cached data if the player is online here, otherwise a fresh read from the store. */
    public PlayerData get(UUID id, String name) {
        PlayerData d = cache.get(id);
        return d != null ? d : plugin.data().load(id, name);
    }

    public void link(UUID a, String an, UUID b, String bn) {
        DataStore s = plugin.data();
        s.saveProfile(a, an, false);
        s.saveProfile(b, bn, false);
        s.addFriend(a, b, bn);
        s.addFriend(b, a, an);
        PlayerData da = cache.get(a);
        if (da != null) da.getFriends().add(new Friend(b, bn));
        PlayerData db = cache.get(b);
        if (db != null) db.getFriends().add(new Friend(a, an));
    }

    public void unlink(UUID a, UUID b) {
        DataStore s = plugin.data();
        s.removeFriend(a, b);
        s.removeFriend(b, a);
        PlayerData da = cache.get(a);
        if (da != null) da.getFriends().remove(b);
        PlayerData db = cache.get(b);
        if (db != null) db.getFriends().remove(a);
    }

    /** Removes every friendship of {@code owner}; returns who was removed. */
    public List<Friend> clearAll(UUID owner) {
        PlayerData d = get(owner, null);
        List<Friend> removed = new ArrayList<>(d.getFriends().all());
        for (Friend f : removed) unlink(owner, f.getUuid());
        return removed;
    }

    public void setAllow(UUID id, String name, boolean value) {
        plugin.data().setAllowRequests(id, name, value);
        PlayerData d = cache.get(id);
        if (d != null) d.setAllowRequests(value);
    }
}

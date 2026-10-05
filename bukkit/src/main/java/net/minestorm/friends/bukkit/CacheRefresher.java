package net.minestorm.friends.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Periodically reloads the in-memory friend cache AND the pending friend requests
 * from the shared MySQL database so changes made on OTHER servers become visible
 * here without a relog - and without depending on the proxy relay.
 * Only active when storage.type is MYSQL and storage.refresh-interval-seconds > 0.
 *
 * FIX: this class never started because SqlStore did not override isRemote().
 * It now runs on the main thread (cheap: only snapshots the online players);
 * all database work is done on the DB thread by FriendManager / RequestManager.
 */
public final class CacheRefresher implements Runnable {

    private final MineStormFriendsPlugin plugin;
    private int taskId = -1;

    public CacheRefresher(MineStormFriendsPlugin plugin) { this.plugin = plugin; }

    public void start() {
        if (plugin.data() == null || !plugin.data().isRemote()) return;
        int sec = plugin.getConfig().getInt("storage.refresh-interval-seconds", 5);
        if (sec <= 0) return;
        long ticks = sec * 20L;
        taskId = Bukkit.getScheduler()
                .runTaskTimer(plugin, this, ticks, ticks)
                .getTaskId();
        plugin.getLogger().info("Friend cache refresher started (every " + sec + "s).");
    }

    public void stop() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        taskId = -1;
    }

    @Override public void run() {
        try {
            plugin.friends().refreshOnline();
            List<UUID> ids = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) ids.add(p.getUniqueId());
            plugin.requests().syncFromStore(ids, true);
        } catch (Throwable t) {
            plugin.getLogger().warning("Cache refresh failed: " + t.getMessage());
        }
    }
}

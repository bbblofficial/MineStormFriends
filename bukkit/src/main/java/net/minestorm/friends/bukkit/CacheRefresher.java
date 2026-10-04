package net.minestorm.friends.bukkit;

import org.bukkit.Bukkit;

/**
 * Periodically reloads the in-memory friend cache from the shared MySQL
 * database so changes made on OTHER servers become visible here without a
 * relog. Only active when storage.type is MYSQL and
 * storage.refresh-interval-seconds > 0.
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
                .runTaskTimerAsynchronously(plugin, this, ticks, ticks)
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
        } catch (Throwable t) {
            plugin.getLogger().warning("Cache refresh failed: " + t.getMessage());
        }
    }
}

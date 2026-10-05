package net.minestorm.friends.bukkit.listener;

import net.minestorm.friends.bukkit.MineStormFriendsPlugin;
import net.minestorm.friends.common.net.Packet;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Collections;
import java.util.UUID;

public final class PlayerListener implements Listener {
    private final MineStormFriendsPlugin plugin;

    public PlayerListener(MineStormFriendsPlugin plugin) { this.plugin = plugin; }

    /** Database / file access happens off the main thread. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        plugin.friends().cacheLoad(e.getUniqueId(), e.getName());
    }

    /** Login was refused later on - do not leak the cache entry. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogin(PlayerLoginEvent e) {
        if (e.getResult() != PlayerLoginEvent.Result.ALLOWED)
            plugin.friends().unload(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        final UUID id = p.getUniqueId();
        final String name = p.getName();
        if (plugin.friends().cached(id) == null) plugin.friends().cacheLoad(id, name);

        // FIX: no database write on the main thread
        plugin.runDb(() -> plugin.data().saveProfile(id, name, true));
        plugin.net().broadcast(new Packet(Packet.JOIN, id.toString(), name, plugin.serverName()));

        // requests that were sent while the player was offline / on another server
        plugin.requests().syncFromStore(Collections.singletonList(id), false);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            int n = plugin.requests().get(id).size();
            if (n > 0) plugin.messages().send(p, "pending-requests", n);
        }, 60L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        final UUID id = p.getUniqueId();
        final String name = p.getName();
        plugin.net().broadcast(new Packet(Packet.QUIT, id.toString(), name, plugin.serverName()));
        plugin.runDb(() -> plugin.data().saveProfile(id, name, true));
        plugin.friends().unload(id);
    }
}

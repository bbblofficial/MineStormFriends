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
        if (plugin.friends().cached(id) == null) plugin.friends().cacheLoad(id, p.getName());

        plugin.data().saveProfile(id, p.getName(), true);
        plugin.net().broadcast(new Packet(Packet.JOIN, id.toString(), p.getName(), plugin.serverName()));

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            int n = plugin.requests().get(id).size();
            if (n > 0) plugin.messages().send(p, "pending-requests", n);
        }, 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        plugin.net().broadcast(new Packet(Packet.QUIT, id.toString(), p.getName(), plugin.serverName()));
        plugin.data().saveProfile(id, p.getName(), true);
        plugin.friends().unload(id);
    }
}

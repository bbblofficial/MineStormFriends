package net.minestorm.friends.bukkit.net;

import net.minestorm.friends.bukkit.MineStormFriendsPlugin;
import net.minestorm.friends.common.net.Packet;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class BukkitNet {
    private static final String DEFAULT_SECRET = "change-me-to-a-long-random-string";

    private final MineStormFriendsPlugin plugin;
    private boolean proxy;
    private String secret;
    private long lastWarn;

    public BukkitNet(MineStormFriendsPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        proxy = plugin.getConfig().getBoolean("network.proxy", true);
        secret = plugin.getConfig().getString("network.secret", DEFAULT_SECRET);
        if (secret == null || secret.isEmpty()) secret = DEFAULT_SECRET;
        if (proxy && DEFAULT_SECRET.equals(secret)) {
            plugin.getLogger().warning("network.secret is still the default - set a unique random "
                    + "value (identical on all servers)!");
        }
    }

    /**
     * Delivers a packet to EVERY server (including this one) through the proxy.
     * Without a proxy (or without a player to carry the message) it is applied locally.
     */
    public void broadcast(Packet p) {
        if (proxy) {
            Player carrier = firstPlayer();
            if (carrier != null) {
                carrier.sendPluginMessage(plugin, Packet.CHANNEL, p.encode(secret));
                return;
            }
        }
        plugin.handler().handle(p);
    }

    /** Called for every plugin message on our channel (main thread). */
    public void receive(byte[] data) {
        if (!proxy) return; // never trust the channel without a proxy relay
        Packet p = Packet.decode(data, secret);
        if (p == null) {
            long now = System.currentTimeMillis();
            if (now - lastWarn > 30_000L) {
                lastWarn = now;
                plugin.getLogger().warning("Rejected a plugin message with an invalid signature "
                        + "(is network.secret identical on all servers?)");
            }
            return;
        }
        plugin.handler().handle(p);
    }

    private static Player firstPlayer() {
        for (Player p : Bukkit.getOnlinePlayers()) return p;
        return null;
    }
}

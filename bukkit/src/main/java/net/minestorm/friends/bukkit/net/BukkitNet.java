package net.minestorm.friends.bukkit.net;

import net.minestorm.friends.bukkit.MineStormFriendsPlugin;
import net.minestorm.friends.common.net.Packet;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

public final class BukkitNet {
    private static final String DEFAULT_SECRET = "change-me-to-a-long-random-string";

    private final MineStormFriendsPlugin plugin;
    /** Random per start: lets a backend recognise (and skip) the echo of its own packets. */
    private final String instanceId = UUID.randomUUID().toString();
    private boolean proxy;
    private String secret;
    private long lastWarn;
    private long lastChannelWarn;

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
     * Delivers a packet to EVERY server through the proxy and ALWAYS applies it on
     * this server immediately.
     *
     * FIX: before, a packet was only applied here when it came back from the proxy.
     * If the relay was missing / the channel not registered / the packet got lost,
     * the action silently did nothing. Now this server never depends on the proxy for
     * its own actions; the echo of our own packet is ignored in receive(). Other
     * servers are additionally kept in sync through the database (CacheRefresher).
     */
    public void broadcast(Packet p) {
        if (proxy) {
            Player carrier = firstPlayer();
            if (carrier != null) {
                if (carrier.getListeningPluginChannels().contains(Packet.CHANNEL)) {
                    try {
                        carrier.sendPluginMessage(plugin, Packet.CHANNEL, p.withOrigin(instanceId).encode(secret));
                    } catch (RuntimeException ex) {
                        plugin.getLogger().warning("Could not send a plugin message: " + ex);
                    }
                } else {
                    long now = System.currentTimeMillis();
                    if (now - lastChannelWarn > 60_000L) {
                        lastChannelWarn = now;
                        plugin.getLogger().warning("The proxy did not register the channel '" + Packet.CHANNEL
                                + "' - is MineStormFriends-Bungee / -Velocity installed on the proxy? "
                                + "Cross-server changes now only sync through the database.");
                    }
                }
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
        if (instanceId.equals(p.origin())) return; // our own packet, already applied in broadcast()
        plugin.handler().handle(p);
    }

    private static Player firstPlayer() {
        for (Player p : Bukkit.getOnlinePlayers()) return p;
        return null;
    }
}

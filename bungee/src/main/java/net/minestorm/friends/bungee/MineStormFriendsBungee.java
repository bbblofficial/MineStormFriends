package net.minestorm.friends.bungee;

import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

/**
 * Pure relay: every packet a backend sends on our channel is forwarded to ALL backends.
 * Backends sign/verify the packets themselves, the proxy never parses them.
 */
public final class MineStormFriendsBungee extends Plugin implements Listener {
    /** Must match Packet.CHANNEL in :common. */
    public static final String CHANNEL = "msfriends:main";

    @Override public void onEnable() {
        getProxy().registerChannel(CHANNEL);
        getProxy().getPluginManager().registerListener(this, this);
        getLogger().info("MineStormFriends (Bungee) enabled - Created by Muvixo");
    }

    @EventHandler
    public void onPluginMessage(PluginMessageEvent e) {
        if (!CHANNEL.equals(e.getTag())) return;

        // Never let this channel pass through to clients or other servers untouched.
        e.setCancelled(true);

        // Only backend servers may publish - ignore anything a client tries to send.
        if (!(e.getSender() instanceof Server)) return;

        byte[] data = e.getData();
        for (ServerInfo info : getProxy().getServers().values())
            info.sendData(CHANNEL, data, false);
    }
}

package net.minestorm.friends.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

/**
 * Pure relay: every packet a backend sends on our channel is forwarded to ALL backends.
 * Backends sign/verify the packets themselves, the proxy never parses them.
 */
@Plugin(
        id = "minestormfriends",
        name = "MineStormFriends",
        version = "1.0.0",
        description = "Cross-server friends relay - Created by Muvixo",
        authors = {"Muvixo"}
)
public final class MineStormFriendsVelocity {
    /** Must match Packet.CHANNEL in :common ("msfriends:main"). */
    public static final ChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("msfriends", "main");

    private final ProxyServer server;
    private final Logger logger;

    @Inject
    public MineStormFriendsVelocity(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent e) {
        server.getChannelRegistrar().register(CHANNEL);
        logger.info("MineStormFriends (Velocity) enabled - Created by Muvixo");
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent e) {
        if (!e.getIdentifier().equals(CHANNEL)) return;

        // Never forward this channel to clients / servers untouched.
        e.setResult(PluginMessageEvent.ForwardResult.handled());

        // Only backend servers may publish - ignore anything a client tries to send.
        if (!(e.getSource() instanceof ServerConnection)) return;

        byte[] data = e.getData();
        for (RegisteredServer rs : server.getAllServers()) rs.sendPluginMessage(CHANNEL, data);
    }
}

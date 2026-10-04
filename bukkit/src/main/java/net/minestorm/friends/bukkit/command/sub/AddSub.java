package net.minestorm.friends.bukkit.command.sub;

import net.minestorm.friends.bukkit.MineStormFriendsPlugin;
import net.minestorm.friends.bukkit.command.MSFCommand;
import net.minestorm.friends.bukkit.command.Sub;
import net.minestorm.friends.common.net.Packet;
import net.minestorm.friends.common.storage.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

public final class AddSub implements Sub {
    private final MineStormFriendsPlugin plugin;
    public AddSub(MineStormFriendsPlugin plugin) { this.plugin = plugin; }

    @Override public void run(CommandSender s, String[] a) {
        if (!MSFCommand.has(s, "minestormfriends.add")) { plugin.messages().send(s, "no-permission"); return; }
        if (!(s instanceof Player)) { plugin.messages().send(s, "player-only"); return; }
        if (a.length < 2) { plugin.messages().send(s, "invalid-arguments", "msf add <player>"); return; }

        final Player me = (Player) s;
        final UUID myId = me.getUniqueId();
        final String targetName = a[1];

        if (targetName.equalsIgnoreCase(me.getName())) { plugin.messages().send(s, "cannot-friend-yourself"); return; }

        PlayerData mine = plugin.friends().get(myId, me.getName());
        if (mine.getFriends().findByName(targetName) != null) { plugin.messages().send(s, "already-friends"); return; }

        int limit = MSFCommand.limitFor(plugin, me);
        if (mine.getFriends().size() >= limit) { plugin.messages().send(s, "max-friends", limit); return; }

        // The target may be on any server: ask the network, the hosting server answers.
        plugin.requests().await(myId);
        plugin.net().broadcast(new Packet(Packet.REQUEST_SEND, myId.toString(), me.getName(), targetName));

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (plugin.requests().clearAwaiting(myId) && me.isOnline())
                plugin.messages().send(me, "player-not-found", targetName);
        }, 40L);
    }
}

package net.minestorm.friends.bukkit.command.sub;

import net.minestorm.friends.bukkit.MineStormFriendsPlugin;
import net.minestorm.friends.bukkit.command.MSFCommand;
import net.minestorm.friends.bukkit.command.Sub;
import net.minestorm.friends.bukkit.request.RequestManager;
import net.minestorm.friends.common.net.Packet;
import net.minestorm.friends.common.storage.PlayerData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class AcceptSub implements Sub {
    private final MineStormFriendsPlugin plugin;
    public AcceptSub(MineStormFriendsPlugin plugin) { this.plugin = plugin; }

    @Override public void run(CommandSender s, String[] a) {
        if (!MSFCommand.has(s, "minestormfriends.accept")) { plugin.messages().send(s, "no-permission"); return; }
        if (!(s instanceof Player)) { plugin.messages().send(s, "player-only"); return; }
        if (a.length < 2) { plugin.messages().send(s, "invalid-arguments", "msf accept <player>"); return; }

        Player me = (Player) s;
        RequestManager.Req r = plugin.requests().find(me.getUniqueId(), a[1]);
        if (r == null) { plugin.messages().send(s, "no-request", a[1]); return; }

        PlayerData mine = plugin.friends().get(me.getUniqueId(), me.getName());
        if (mine.getFriends().contains(r.from)) {
            plugin.net().broadcast(new Packet(Packet.REQUEST_REMOVE, me.getUniqueId().toString(), r.from.toString()));
            plugin.messages().send(s, "already-friends");
            return;
        }
        int limit = MSFCommand.limitFor(plugin, me);
        if (mine.getFriends().size() >= limit) { plugin.messages().send(s, "max-friends", limit); return; }

        // Both players get their "request-accepted" message when the event is applied.
        plugin.net().broadcast(new Packet(Packet.FRIEND_ADD,
                me.getUniqueId().toString(), me.getName(),
                r.from.toString(), r.fromName));
    }
}

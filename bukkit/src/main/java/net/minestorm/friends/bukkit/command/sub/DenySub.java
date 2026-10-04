package net.minestorm.friends.bukkit.command.sub;

import net.minestorm.friends.bukkit.MineStormFriendsPlugin;
import net.minestorm.friends.bukkit.command.MSFCommand;
import net.minestorm.friends.bukkit.command.Sub;
import net.minestorm.friends.bukkit.request.RequestManager;
import net.minestorm.friends.common.net.Packet;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class DenySub implements Sub {
    private final MineStormFriendsPlugin plugin;
    public DenySub(MineStormFriendsPlugin plugin) { this.plugin = plugin; }

    @Override public void run(CommandSender s, String[] a) {
        if (!MSFCommand.has(s, "minestormfriends.deny")) { plugin.messages().send(s, "no-permission"); return; }
        if (!(s instanceof Player)) { plugin.messages().send(s, "player-only"); return; }
        if (a.length < 2) { plugin.messages().send(s, "invalid-arguments", "msf deny <player>"); return; }

        Player me = (Player) s;
        RequestManager.Req r = plugin.requests().find(me.getUniqueId(), a[1]);
        if (r == null) { plugin.messages().send(s, "no-request", a[1]); return; }

        plugin.net().broadcast(new Packet(Packet.REQUEST_REMOVE, me.getUniqueId().toString(), r.from.toString()));
        plugin.requests().remove(me.getUniqueId(), r.from); // immediate, the echo is idempotent
        plugin.messages().send(s, "request-denied", r.fromName);
        plugin.handler().reply(r.from, "request-denied-sender", me.getName());
    }
}

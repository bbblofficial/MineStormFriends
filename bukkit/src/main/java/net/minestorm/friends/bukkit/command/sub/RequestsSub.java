package net.minestorm.friends.bukkit.command.sub;

import net.minestorm.friends.bukkit.MineStormFriendsPlugin;
import net.minestorm.friends.bukkit.command.MSFCommand;
import net.minestorm.friends.bukkit.command.Sub;
import net.minestorm.friends.bukkit.request.RequestManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public final class RequestsSub implements Sub {
    private final MineStormFriendsPlugin plugin;
    public RequestsSub(MineStormFriendsPlugin plugin) { this.plugin = plugin; }

    @Override public void run(CommandSender s, String[] a) {
        if (!MSFCommand.has(s, "minestormfriends.requests")) { plugin.messages().send(s, "no-permission"); return; }
        if (!(s instanceof Player)) { plugin.messages().send(s, "player-only"); return; }

        Player me = (Player) s;
        List<RequestManager.Req> list = plugin.requests().get(me.getUniqueId());
        if (list.isEmpty()) { plugin.messages().send(s, "no-requests"); return; }

        plugin.messages().send(s, "requests-header", list.size());
        for (RequestManager.Req r : list) {
            plugin.messages().line(s, "requests-line", r.fromName);
            plugin.messages().sendRequestButtons(me, r.fromName);
        }
    }
}

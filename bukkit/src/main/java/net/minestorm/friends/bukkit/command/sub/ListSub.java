package net.minestorm.friends.bukkit.command.sub;

import net.minestorm.friends.bukkit.MineStormFriendsPlugin;
import net.minestorm.friends.bukkit.command.MSFCommand;
import net.minestorm.friends.bukkit.command.Sub;
import net.minestorm.friends.bukkit.config.Messages;
import net.minestorm.friends.common.Friend;
import net.minestorm.friends.common.storage.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class ListSub implements Sub {
    private static final int PER_PAGE = 8;

    private final MineStormFriendsPlugin plugin;
    public ListSub(MineStormFriendsPlugin plugin) { this.plugin = plugin; }

    @Override public void run(CommandSender s, String[] a) {
        if (!MSFCommand.has(s, "minestormfriends.list")) { plugin.messages().send(s, "no-permission"); return; }
        if (!(s instanceof Player)) { plugin.messages().send(s, "player-only"); return; }
        Player me = (Player) s;
        PlayerData d = plugin.friends().get(me.getUniqueId(), me.getName());
        print(plugin, s, d, parsePage(a, 1));
    }

    /** @param idx index of the page argument inside {@code a}. */
    public static int parsePage(String[] a, int idx) {
        if (a.length <= idx) return 1;
        try {
            return Math.max(1, Integer.parseInt(a[idx]));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    public static void print(MineStormFriendsPlugin plugin, CommandSender to, PlayerData d, int page) {
        Messages m = plugin.messages();
        int size = d.getFriends().size();
        int total = Math.max(1, (size + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(1, Math.min(page, total));

        m.sendList(to, "list-header");
        if (size == 0) {
            m.send(to, "no-friends");
        } else {
            m.line(to, "list-page", page, total);
            for (Friend f : d.getFriends().page(page, PER_PAGE)) {
                String where = Bukkit.getPlayer(f.getUuid()) != null
                        ? plugin.serverName()
                        : plugin.presence().get(f.getUuid());
                String status = where != null
                        ? m.format("list-status.online", where)
                        : m.format("list-status.offline");
                m.line(to, "list-format", f.getName() == null ? "unknown" : f.getName(), status);
            }
        }
        m.sendList(to, "list-footer");
    }
}

package net.minestorm.friends.bukkit.command.sub;

import net.minestorm.friends.bukkit.MineStormFriendsPlugin;
import net.minestorm.friends.bukkit.command.Sub;
import org.bukkit.command.CommandSender;

public final class HelpSub implements Sub {
    private final MineStormFriendsPlugin plugin;
    public HelpSub(MineStormFriendsPlugin plugin) { this.plugin = plugin; }

    @Override public void run(CommandSender s, String[] a) {
        plugin.messages().sendList(s, "help");
        if (AdminSub.isAdmin(s)) plugin.messages().sendList(s, "admin-help");
    }
}

package net.minestorm.friends.bukkit.command.sub;

import net.minestorm.friends.bukkit.MineStormFriendsPlugin;
import net.minestorm.friends.bukkit.command.Sub;
import org.bukkit.command.CommandSender;

public final class CreatorSub implements Sub {
    private final MineStormFriendsPlugin plugin;
    public CreatorSub(MineStormFriendsPlugin plugin) { this.plugin = plugin; }

    @Override public void run(CommandSender s, String[] a) {
        plugin.messages().sendList(s, "creator", plugin.getDescription().getVersion());
    }
}

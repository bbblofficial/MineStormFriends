package net.minestorm.friends.bukkit.command.sub;

import net.minestorm.friends.bukkit.MineStormFriendsPlugin;
import net.minestorm.friends.bukkit.command.MSFCommand;
import net.minestorm.friends.bukkit.command.Sub;
import net.minestorm.friends.common.net.Packet;
import net.minestorm.friends.common.storage.PlayerData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class ToggleSub implements Sub {
    private final MineStormFriendsPlugin plugin;
    public ToggleSub(MineStormFriendsPlugin plugin) { this.plugin = plugin; }

    @Override public void run(CommandSender s, String[] a) {
        if (!MSFCommand.has(s, "minestormfriends.toggle")) { plugin.messages().send(s, "no-permission"); return; }
        if (!(s instanceof Player)) { plugin.messages().send(s, "player-only"); return; }

        Player me = (Player) s;
        PlayerData d = plugin.friends().get(me.getUniqueId(), me.getName());
        boolean value = !d.isAllowRequests();

        plugin.friends().setAllow(me.getUniqueId(), me.getName(), value); // instant locally
        plugin.net().broadcast(new Packet(Packet.TOGGLE,
                me.getUniqueId().toString(), String.valueOf(value), me.getName()));
        plugin.messages().send(s, "toggle",
                plugin.messages().format(value ? "state-enabled" : "state-disabled"));
    }
}

package net.minestorm.friends.bukkit.command.sub;

import net.minestorm.friends.bukkit.MineStormFriendsPlugin;
import net.minestorm.friends.bukkit.command.MSFCommand;
import net.minestorm.friends.bukkit.command.Sub;
import net.minestorm.friends.common.net.Packet;
import net.minestorm.friends.common.storage.PlayerData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class RemoveAllSub implements Sub {
    private final MineStormFriendsPlugin plugin;
    public RemoveAllSub(MineStormFriendsPlugin plugin) { this.plugin = plugin; }

    @Override public void run(CommandSender s, String[] a) {
        if (!MSFCommand.has(s, "minestormfriends.removeall")) { plugin.messages().send(s, "no-permission"); return; }
        if (!(s instanceof Player)) { plugin.messages().send(s, "player-only"); return; }

        Player me = (Player) s;
        PlayerData mine = plugin.friends().get(me.getUniqueId(), me.getName());
        if (mine.getFriends().size() == 0) { plugin.messages().send(s, "removeall-empty"); return; }

        plugin.net().broadcast(new Packet(Packet.FRIEND_CLEAR, me.getUniqueId().toString(), me.getName()));
        plugin.messages().send(s, "removeall");
    }
}

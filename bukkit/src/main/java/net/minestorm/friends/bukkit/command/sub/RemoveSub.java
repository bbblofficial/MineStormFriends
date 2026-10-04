package net.minestorm.friends.bukkit.command.sub;

import net.minestorm.friends.bukkit.MineStormFriendsPlugin;
import net.minestorm.friends.bukkit.command.MSFCommand;
import net.minestorm.friends.bukkit.command.Sub;
import net.minestorm.friends.common.Friend;
import net.minestorm.friends.common.net.Packet;
import net.minestorm.friends.common.storage.PlayerData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class RemoveSub implements Sub {
    private final MineStormFriendsPlugin plugin;
    public RemoveSub(MineStormFriendsPlugin plugin) { this.plugin = plugin; }

    @Override public void run(CommandSender s, String[] a) {
        if (!MSFCommand.has(s, "minestormfriends.remove")) { plugin.messages().send(s, "no-permission"); return; }
        if (!(s instanceof Player)) { plugin.messages().send(s, "player-only"); return; }
        if (a.length < 2) { plugin.messages().send(s, "invalid-arguments", "msf remove <player>"); return; }

        Player me = (Player) s;
        // resolved from the player's OWN list: works for offline friends, no Mojang lookups
        PlayerData mine = plugin.friends().get(me.getUniqueId(), me.getName());
        Friend f = mine.getFriends().findByName(a[1]);
        if (f == null) { plugin.messages().send(s, "not-friends", a[1]); return; }

        plugin.net().broadcast(new Packet(Packet.FRIEND_REMOVE,
                me.getUniqueId().toString(), f.getUuid().toString(), me.getName()));
        plugin.messages().send(s, "unfriend-sender", f.getName());
    }
}

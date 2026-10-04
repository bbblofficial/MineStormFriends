package net.minestorm.friends.bukkit.command.sub;

import net.minestorm.friends.bukkit.MineStormFriendsPlugin;
import net.minestorm.friends.bukkit.command.MSFCommand;
import net.minestorm.friends.bukkit.command.Sub;
import net.minestorm.friends.bukkit.config.Messages;
import net.minestorm.friends.common.net.Packet;
import net.minestorm.friends.common.storage.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class AdminSub implements Sub {
    private static final List<String> ACTIONS = Arrays.asList(
            "help", "reload", "list", "add", "remove", "removeall", "clearrequests", "toggle", "info");

    public static List<String> actions() { return ACTIONS; }

    /** Parent permission (or OP) OR any single admin sub-permission. */
    public static boolean isAdmin(CommandSender s) {
        if (MSFCommand.has(s, "minestormfriends.admin")) return true;
        for (String a : ACTIONS)
            if (!a.equals("help") && s.hasPermission("minestormfriends.admin." + a)) return true;
        return false;
    }

    private static final class Target {
        final UUID id;
        final String name;
        Target(UUID id, String name) { this.id = id; this.name = name; }
    }

    private final MineStormFriendsPlugin plugin;
    public AdminSub(MineStormFriendsPlugin plugin) { this.plugin = plugin; }

    @Override public void run(CommandSender s, String[] a) {
        Messages m = plugin.messages();
        if (!isAdmin(s)) { m.send(s, "no-permission"); return; }
        if (a.length < 2 || a[1].equalsIgnoreCase("help")) { m.sendList(s, "admin-help"); return; }

        String action = a[1].toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) { m.sendList(s, "admin-help"); return; }
        // every action has its own permission - the parent grants all of them
        if (!MSFCommand.has(s, "minestormfriends.admin." + action)) { m.send(s, "no-permission"); return; }

        switch (action) {
            case "reload": {
                plugin.reloadEverything();
                m.send(s, "admin.reloaded");
                return;
            }
            case "list": {
                if (a.length < 3) { m.send(s, "invalid-arguments", "msf admin list <player> [page]"); return; }
                Target t = resolve(a[2]);
                if (t == null) { m.send(s, "player-not-found", a[2]); return; }
                PlayerData d = plugin.friends().get(t.id, t.name);
                ListSub.print(plugin, s, d, ListSub.parsePage(a, 3));
                return;
            }
            case "add": {
                if (a.length < 4) { m.send(s, "invalid-arguments", "msf admin add <player> <target>"); return; }
                Target t1 = resolve(a[2]);
                if (t1 == null) { m.send(s, "player-not-found", a[2]); return; }
                Target t2 = resolve(a[3]);
                if (t2 == null) { m.send(s, "player-not-found", a[3]); return; }
                if (t1.id.equals(t2.id)) { m.send(s, "cannot-friend-yourself"); return; }
                plugin.net().broadcast(new Packet(Packet.FRIEND_ADD,
                        t1.id.toString(), t1.name, t2.id.toString(), t2.name));
                m.send(s, "admin.forced-add", t1.name, t2.name);
                return;
            }
            case "remove": {
                if (a.length < 4) { m.send(s, "invalid-arguments", "msf admin remove <player> <target>"); return; }
                Target t1 = resolve(a[2]);
                if (t1 == null) { m.send(s, "player-not-found", a[2]); return; }
                Target t2 = resolve(a[3]);
                if (t2 == null) { m.send(s, "player-not-found", a[3]); return; }
                plugin.net().broadcast(new Packet(Packet.FRIEND_REMOVE,
                        t1.id.toString(), t2.id.toString(), ""));
                m.send(s, "admin.forced-remove", t1.name, t2.name);
                return;
            }
            case "removeall": {
                if (a.length < 3) { m.send(s, "invalid-arguments", "msf admin removeall <player>"); return; }
                Target t = resolve(a[2]);
                if (t == null) { m.send(s, "player-not-found", a[2]); return; }
                plugin.net().broadcast(new Packet(Packet.FRIEND_CLEAR, t.id.toString(), ""));
                m.send(s, "admin.forced-removeall", t.name);
                return;
            }
            case "clearrequests": {
                if (a.length < 3) { m.send(s, "invalid-arguments", "msf admin clearrequests <player>"); return; }
                Target t = resolve(a[2]);
                if (t == null) { m.send(s, "player-not-found", a[2]); return; }
                plugin.net().broadcast(new Packet(Packet.REQUEST_CLEAR, t.id.toString()));
                m.send(s, "admin.cleared-requests", t.name);
                return;
            }
            case "toggle": {
                if (a.length < 3) { m.send(s, "invalid-arguments", "msf admin toggle <player>"); return; }
                Target t = resolve(a[2]);
                if (t == null) { m.send(s, "player-not-found", a[2]); return; }
                PlayerData d = plugin.friends().get(t.id, t.name);
                boolean value = !d.isAllowRequests();
                plugin.net().broadcast(new Packet(Packet.TOGGLE, t.id.toString(), String.valueOf(value), t.name));
                m.send(s, "admin.toggled", t.name, m.format(value ? "state-enabled" : "state-disabled"));
                return;
            }
            case "info": {
                if (a.length < 3) { m.send(s, "invalid-arguments", "msf admin info <player>"); return; }
                Target t = resolve(a[2]);
                if (t == null) { m.send(s, "player-not-found", a[2]); return; }
                PlayerData d = plugin.friends().get(t.id, t.name);
                String where = Bukkit.getPlayer(t.id) != null ? plugin.serverName() : plugin.presence().get(t.id);
                m.line(s, "admin.info-header", t.name);
                m.line(s, "admin.info-line", "UUID", t.id);
                m.line(s, "admin.info-line", "Online", where == null ? "no" : where);
                m.line(s, "admin.info-line", "Friends", d.getFriends().size());
                m.line(s, "admin.info-line", "Allow requests", d.isAllowRequests() ? "yes" : "no");
                m.line(s, "admin.info-line", "Pending requests", plugin.requests().get(t.id).size());
                m.line(s, "admin.info-line", "Last seen",
                        d.getLastSeen() == 0 ? "never" : new Date(d.getLastSeen()).toString());
                return;
            }
            default:
                m.sendList(s, "admin-help");
        }
    }

    /** Online player first, then the plugin's own name index (no Mojang lookups). */
    private Target resolve(String input) {
        Player online = Bukkit.getPlayerExact(input);
        if (online != null) return new Target(online.getUniqueId(), online.getName());
        UUID id = plugin.data().findUuid(input);
        if (id == null) return null;
        PlayerData d = plugin.friends().get(id, null);
        return new Target(id, d.getLastName() != null ? d.getLastName() : input);
    }
}

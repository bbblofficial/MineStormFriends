package net.minestorm.friends.bukkit.command;

import net.minestorm.friends.bukkit.MineStormFriendsPlugin;
import net.minestorm.friends.bukkit.command.sub.*;
import net.minestorm.friends.bukkit.request.RequestManager;
import net.minestorm.friends.common.Friend;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MSFCommand implements CommandExecutor, TabCompleter {
    private static final List<String> PLAYER_ADMIN_ACTIONS =
            Arrays.asList("list", "removeall", "clearrequests", "toggle", "info");

    private final MineStormFriendsPlugin plugin;
    private final Map<String, Sub> subs = new LinkedHashMap<>();

    public MSFCommand(MineStormFriendsPlugin plugin) {
        this.plugin = plugin;
        subs.put("help",      new HelpSub(plugin));
        subs.put("add",       new AddSub(plugin));
        subs.put("accept",    new AcceptSub(plugin));
        subs.put("deny",      new DenySub(plugin));
        subs.put("list",      new ListSub(plugin));
        subs.put("remove",    new RemoveSub(plugin));
        subs.put("removeall", new RemoveAllSub(plugin));
        subs.put("requests",  new RequestsSub(plugin));
        subs.put("toggle",    new ToggleSub(plugin));
        subs.put("creator",   new CreatorSub(plugin));
        subs.put("admin",     new AdminSub(plugin));
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) { subs.get("help").run(s, a); return true; }
        Sub sub = subs.get(a[0].toLowerCase(Locale.ROOT));
        if (sub == null) { plugin.messages().send(s, "invalid-input", a[0]); return true; }
        sub.run(s, a);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);

        if (a.length == 1) {
            for (String k : subs.keySet()) {
                if (k.equals("admin") && !AdminSub.isAdmin(s)) continue;
                if (k.startsWith(last)) out.add(k);
            }
            return out;
        }

        String sub = a[0].toLowerCase(Locale.ROOT);
        Player me = s instanceof Player ? (Player) s : null;

        if (sub.equals("admin") && AdminSub.isAdmin(s)) {
            if (a.length == 2) {
                for (String k : AdminSub.actions()) if (k.startsWith(last)) out.add(k);
            } else if (a.length == 3 && (PLAYER_ADMIN_ACTIONS.contains(a[1].toLowerCase(Locale.ROOT))
                    || a[1].equalsIgnoreCase("add") || a[1].equalsIgnoreCase("remove"))) {
                addOnline(out, last);
            } else if (a.length == 4 && (a[1].equalsIgnoreCase("add") || a[1].equalsIgnoreCase("remove"))) {
                addOnline(out, last);
            }
            return out;
        }

        if (a.length != 2 || me == null) return out;
        switch (sub) {
            case "add":
                addOnline(out, last);
                out.remove(me.getName());
                break;
            case "accept":
            case "deny":
                for (RequestManager.Req r : plugin.requests().get(me.getUniqueId()))
                    if (r.fromName != null && r.fromName.toLowerCase(Locale.ROOT).startsWith(last)) out.add(r.fromName);
                break;
            case "remove": {
                net.minestorm.friends.common.storage.PlayerData d = plugin.friends().cached(me.getUniqueId());
                if (d != null)
                    for (Friend f : d.getFriends().all())
                        if (f.getName() != null && f.getName().toLowerCase(Locale.ROOT).startsWith(last)) out.add(f.getName());
                break;
            }
            default: break;
        }
        return out;
    }

    private static void addOnline(List<String> out, String prefix) {
        for (Player p : Bukkit.getOnlinePlayers())
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
    }

    /** OP bypass, otherwise the real permission. */
    public static boolean has(CommandSender s, String perm) {
        return s.isOp() || s.hasPermission(perm);
    }

    /** Friend limit for a player (highest minestormfriends.limit.<n> wins). */
    public static int limitFor(MineStormFriendsPlugin plugin, Player p) {
        if (has(p, "minestormfriends.bypass")) return Integer.MAX_VALUE;
        int best = plugin.getConfig().getInt("options.max-friends", 25);
        if (plugin.getConfig().getBoolean("options.use-permission-limits", true)) {
            String pre = "minestormfriends.limit.";
            for (PermissionAttachmentInfo info : p.getEffectivePermissions()) {
                String perm = info.getPermission();
                if (!info.getValue() || !perm.startsWith(pre)) continue;
                try {
                    best = Math.max(best, Integer.parseInt(perm.substring(pre.length())));
                } catch (NumberFormatException ignored) { }
            }
        }
        return best;
    }
}

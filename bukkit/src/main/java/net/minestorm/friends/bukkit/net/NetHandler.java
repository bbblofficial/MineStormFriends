package net.minestorm.friends.bukkit.net;

import net.minestorm.friends.bukkit.MineStormFriendsPlugin;
import net.minestorm.friends.common.Friend;
import net.minestorm.friends.common.net.Packet;
import net.minestorm.friends.common.storage.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Applies network events. Every server runs every event (including the one that
 * created it), so all state changes happen in exactly one place.
 */
public final class NetHandler {
    /** Message keys a MESSAGE packet may trigger. */
    private static final Set<String> MSG_KEYS = new HashSet<>(Arrays.asList(
            "request-sent", "request-not-allowed", "already-friends", "player-not-found",
            "request-expired-sender", "request-expired-receiver", "request-denied-sender",
            "request-failed", "db-error"));
    /** Replies that answer a pending /msf add. */
    private static final Set<String> ANSWER_KEYS = new HashSet<>(Arrays.asList(
            "request-sent", "request-not-allowed", "already-friends", "request-failed"));

    private final MineStormFriendsPlugin plugin;

    public NetHandler(MineStormFriendsPlugin plugin) { this.plugin = plugin; }

    public void handle(Packet p) {
        try {
            switch (p.type()) {
                case Packet.FRIEND_ADD:     onFriendAdd(p); break;
                case Packet.FRIEND_REMOVE:  onFriendRemove(p); break;
                case Packet.FRIEND_CLEAR:   onFriendClear(p); break;
                case Packet.REQUEST_SEND:   onRequestSend(p); break;
                case Packet.REQUEST_REG:    onRequestReg(p); break;
                case Packet.REQUEST_REMOVE: onRequestRemove(p); break;
                case Packet.REQUEST_CLEAR:  onRequestClear(p); break;
                case Packet.TOGGLE:         onToggle(p); break;
                case Packet.MESSAGE:        onMessage(p); break;
                case Packet.JOIN:           onJoin(p); break;
                case Packet.QUIT:           onQuit(p); break;
                case Packet.HERE:           onHere(p); break;
                default: break;
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Failed to handle packet " + p.type() + ": " + ex);
        }
    }

    /** Sends a whitelisted message to a player on whichever server he is. */
    public void reply(UUID to, String key, String... args) {
        String[] all = new String[args.length + 2];
        all[0] = to.toString();
        all[1] = key;
        System.arraycopy(args, 0, all, 2, args.length);
        plugin.net().broadcast(new Packet(Packet.MESSAGE, all));
    }

    // ── friends ─────────────────────────────────────────────────────

    private void onFriendAdd(Packet p) {
        UUID a = uuid(p.arg(0)), b = uuid(p.arg(2));
        String an = p.arg(1), bn = p.arg(3);
        if (a == null || b == null || a.equals(b)) return;

        plugin.friends().link(a, an, b, bn);
        plugin.requests().remove(a, b);
        plugin.requests().remove(b, a);

        Player pa = Bukkit.getPlayer(a);
        if (pa != null) plugin.messages().send(pa, "request-accepted", bn);
        Player pb = Bukkit.getPlayer(b);
        if (pb != null) plugin.messages().send(pb, "request-accepted", an);
    }

    private void onFriendRemove(Packet p) {
        UUID a = uuid(p.arg(0)), b = uuid(p.arg(1));
        String actor = p.arg(2);
        if (a == null || b == null) return;

        plugin.friends().unlink(a, b);
        if (!actor.isEmpty()) {
            Player pb = Bukkit.getPlayer(b);
            if (pb != null) plugin.messages().send(pb, "unfriend-receiver", actor);
        }
    }

    private void onFriendClear(Packet p) {
        UUID owner = uuid(p.arg(0));
        String actor = p.arg(1);
        if (owner == null) return;

        List<Friend> removed = plugin.friends().clearAll(owner);
        if (actor.isEmpty()) return;
        for (Friend f : removed) {
            Player pf = Bukkit.getPlayer(f.getUuid());
            if (pf != null) plugin.messages().send(pf, "unfriend-receiver", actor);
        }
    }

    private void onToggle(Packet p) {
        UUID id = uuid(p.arg(0));
        if (id == null) return;
        plugin.friends().setAllow(id, p.arg(2), Boolean.parseBoolean(p.arg(1)));
    }

    // ── requests ────────────────────────────────────────────────────

    /**
     * Only the server that hosts the target answers.
     * FIX: the request is written to the database FIRST; "request sent" is only
     * confirmed to the sender (and broadcast to the other servers) when that worked.
     */
    private void onRequestSend(Packet p) {
        final UUID from = uuid(p.arg(0));
        final String fromName = p.arg(1);
        Player target = Bukkit.getPlayerExact(p.arg(2));
        if (from == null || target == null || target.getUniqueId().equals(from)) return;

        final UUID to = target.getUniqueId();
        final String toName = target.getName();
        PlayerData td = plugin.friends().get(to, toName);
        if (td.getFriends().contains(from)) { reply(from, "already-friends"); return; }
        if (!td.isAllowRequests())          { reply(from, "request-not-allowed"); return; }

        final int timeout = plugin.getConfig().getInt("options.friend-add-timeout", 5);
        final long created = System.currentTimeMillis();

        plugin.runDbWrite(() -> {
            final boolean ok = plugin.data().saveRequest(from, fromName, to, toName, created);
            plugin.sync(() -> {
                if (!ok) { reply(from, "request-failed"); return; }

                // every server learns about the request, so it survives server switches
                plugin.net().broadcast(new Packet(Packet.REQUEST_REG,
                        from.toString(), fromName, to.toString(), toName, String.valueOf(created)));

                reply(from, "request-sent", toName, String.valueOf(timeout));

                Player t = Bukkit.getPlayer(to);
                if (t != null) {
                    plugin.requests().markPrompted(to, from);
                    plugin.requests().showPrompt(t, fromName);
                }
            });
        });
    }

    private void onRequestReg(Packet p) {
        UUID from = uuid(p.arg(0)), to = uuid(p.arg(2));
        if (from == null || to == null) return;
        long created;
        try {
            created = Long.parseLong(p.arg(4));
        } catch (NumberFormatException e) {
            created = System.currentTimeMillis();
        }
        plugin.requests().add(from, p.arg(1), to, p.arg(3), created);
    }

    private void onRequestRemove(Packet p) {
        UUID to = uuid(p.arg(0)), from = uuid(p.arg(1));
        if (to != null && from != null) plugin.requests().remove(to, from);
    }

    private void onRequestClear(Packet p) {
        UUID to = uuid(p.arg(0));
        if (to != null) plugin.requests().clearFor(to);
    }

    private void onMessage(Packet p) {
        UUID id = uuid(p.arg(0));
        String key = p.arg(1);
        if (id == null || !MSG_KEYS.contains(key)) return;
        if (ANSWER_KEYS.contains(key)) plugin.requests().clearAwaiting(id);

        Player pl = Bukkit.getPlayer(id);
        if (pl == null) return;
        String[] all = p.args();
        Object[] fmt = Arrays.copyOfRange(all, 2, Math.max(2, all.length));
        plugin.messages().send(pl, key, fmt);
    }

    // ── presence ────────────────────────────────────────────────────

    private void onJoin(Packet p) {
        UUID id = uuid(p.arg(0));
        String name = p.arg(1), server = p.arg(2);
        if (id == null) return;

        String previous = plugin.presence().get(id);
        plugin.presence().set(id, server);
        boolean realJoin = previous == null; // otherwise: server switch

        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.getUniqueId().equals(id)) continue;
            PlayerData od = plugin.friends().cached(other.getUniqueId());
            if (od == null) continue;
            Friend f = od.getFriends().find(id);
            if (f == null) continue;

            if (!name.equals(f.getName())) { // keep stored names fresh (name changes)
                f.setName(name);
                final UUID ownerId = other.getUniqueId();
                final String newName = name;
                plugin.runDb(() -> plugin.data().addFriend(ownerId, id, newName));
            }
            if (realJoin) plugin.messages().send(other, "friend-join", name);

            // tell the joiner's server that this friend is online here
            plugin.net().broadcast(new Packet(Packet.HERE,
                    other.getUniqueId().toString(), other.getName(), plugin.serverName()));
        }
    }

    private void onQuit(Packet p) {
        UUID id = uuid(p.arg(0));
        String name = p.arg(1), server = p.arg(2);
        if (id == null) return;

        String current = plugin.presence().get(id);
        if (current == null || !current.equals(server)) return; // moved to another server
        plugin.presence().remove(id);

        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.getUniqueId().equals(id)) continue;
            PlayerData od = plugin.friends().cached(other.getUniqueId());
            if (od != null && od.getFriends().contains(id))
                plugin.messages().send(other, "friend-quit", name);
        }
    }

    private void onHere(Packet p) {
        UUID id = uuid(p.arg(0));
        if (id != null) plugin.presence().set(id, p.arg(2));
    }

    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

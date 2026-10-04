package net.minestorm.friends.bukkit.request;

import net.minestorm.friends.bukkit.MineStormFriendsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Pending requests, keyed by receiver. Main-thread only (except the awaiting set). */
public final class RequestManager {
    public static final class Req {
        public final UUID from, to;
        public final String fromName, toName;
        public final long sent;

        Req(UUID from, String fromName, UUID to, String toName) {
            this.from = from; this.fromName = fromName;
            this.to = to; this.toName = toName;
            this.sent = System.currentTimeMillis();
        }
    }

    private final MineStormFriendsPlugin plugin;
    private final Map<UUID, List<Req>> inbound = new HashMap<>();
    /** Senders still waiting for any answer to their /msf add. */
    private final Set<UUID> awaiting = ConcurrentHashMap.newKeySet();
    private final BukkitTask task;

    public RequestManager(MineStormFriendsPlugin plugin) {
        this.plugin = plugin;
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::expire, 200L, 200L);
    }

    public void shutdown() { task.cancel(); }

    public void add(UUID from, String fromName, UUID to, String toName) {
        List<Req> list = inbound.computeIfAbsent(to, k -> new ArrayList<>());
        list.removeIf(r -> r.from.equals(from));
        list.add(new Req(from, fromName, to, toName));
    }

    public List<Req> get(UUID to) {
        List<Req> l = inbound.get(to);
        return l == null ? new ArrayList<Req>() : new ArrayList<>(l);
    }

    public Req find(UUID to, String fromName) {
        for (Req r : get(to)) if (r.fromName != null && r.fromName.equalsIgnoreCase(fromName)) return r;
        return null;
    }

    public void remove(UUID to, UUID from) {
        List<Req> l = inbound.get(to);
        if (l == null) return;
        l.removeIf(r -> r.from.equals(from));
        if (l.isEmpty()) inbound.remove(to);
    }

    public void clearFor(UUID to) { inbound.remove(to); }

    public void await(UUID sender) { awaiting.add(sender); }

    /** @return true if the sender was still waiting. */
    public boolean clearAwaiting(UUID sender) { return awaiting.remove(sender); }

    private void expire() {
        long ttl = plugin.getConfig().getInt("options.friend-add-timeout", 5) * 60_000L;
        long now = System.currentTimeMillis();
        List<Req> dead = new ArrayList<>();

        for (Iterator<Map.Entry<UUID, List<Req>>> it = inbound.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, List<Req>> e = it.next();
            for (Iterator<Req> ri = e.getValue().iterator(); ri.hasNext(); ) {
                Req r = ri.next();
                if (now - r.sent >= ttl) { dead.add(r); ri.remove(); }
            }
            if (e.getValue().isEmpty()) it.remove();
        }

        // only the server hosting the receiver announces the expiry (no duplicates)
        for (Req r : dead) {
            Player receiver = Bukkit.getPlayer(r.to);
            if (receiver == null) continue;
            plugin.messages().send(receiver, "request-expired-receiver", r.fromName);
            plugin.handler().reply(r.from, "request-expired-sender", r.toName);
        }
    }
}

package net.minestorm.friends.bukkit.request;

import net.minestorm.friends.bukkit.MineStormFriendsPlugin;
import net.minestorm.friends.bukkit.data.DataStore;
import net.minestorm.friends.common.storage.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pending requests, keyed by receiver. Main-thread only (except the awaiting set).
 *
 * FIX: requests used to live ONLY in memory and depended on a plugin message
 * reaching every server. They are now stored in the database (msf_requests):
 *  - the receiver's server writes the row before it confirms "request sent",
 *  - every server re-reads the rows of its online players (CacheRefresher / join),
 *  - a request can still be sent through the database if nobody answered the
 *    proxy broadcast (sendViaStore), also to players that are offline right now.
 */
public final class RequestManager {
    public static final class Req {
        public final UUID from, to;
        public final String fromName, toName;
        public final long sent;
        /** true once the clickable ACCEPT/DENY prompt was shown on this server (main thread only). */
        boolean prompted;

        Req(UUID from, String fromName, UUID to, String toName) {
            this(from, fromName, to, toName, System.currentTimeMillis());
        }

        Req(UUID from, String fromName, UUID to, String toName, long sent) {
            this.from = from; this.fromName = fromName;
            this.to = to; this.toName = toName;
            this.sent = sent;
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

    private long ttlMillis() {
        return plugin.getConfig().getInt("options.friend-add-timeout", 5) * 60_000L;
    }

    // ------------------------------------------------------------------
    // in-memory state (mirrors the database)
    // ------------------------------------------------------------------

    public void add(UUID from, String fromName, UUID to, String toName) {
        add(from, fromName, to, toName, System.currentTimeMillis());
    }

    public void add(UUID from, String fromName, UUID to, String toName, long sent) {
        List<Req> list = inbound.computeIfAbsent(to, k -> new ArrayList<>());
        list.removeIf(r -> r.from.equals(from));
        list.add(new Req(from, fromName, to, toName, sent));
    }

    public List<Req> get(UUID to) {
        List<Req> l = inbound.get(to);
        return l == null ? new ArrayList<Req>() : new ArrayList<>(l);
    }

    public Req find(UUID to, String fromName) {
        Req r = findLocal(to, fromName);
        if (r != null || !plugin.data().supportsRequests()) return r;

        // Not in memory (e.g. the proxy message was lost): ask the database once.
        List<DataStore.StoredRequest> rows = plugin.data().loadRequests(to);
        if (rows == null) return null;
        long ttl = ttlMillis();
        long now = System.currentTimeMillis();
        for (DataStore.StoredRequest s : rows) {
            if (now - s.created < ttl) add(s.from, s.fromName, to, s.toName, s.created);
        }
        return findLocal(to, fromName);
    }

    private Req findLocal(UUID to, String fromName) {
        for (Req r : get(to)) if (r.fromName != null && r.fromName.equalsIgnoreCase(fromName)) return r;
        return null;
    }

    public void remove(final UUID to, final UUID from) {
        List<Req> l = inbound.get(to);
        if (l != null) {
            l.removeIf(r -> r.from.equals(from));
            if (l.isEmpty()) inbound.remove(to);
        }
        if (plugin.data().supportsRequests()) {
            plugin.runDbWrite(() -> plugin.data().deleteRequest(to, from));
        }
    }

    public void clearFor(final UUID to) {
        inbound.remove(to);
        if (plugin.data().supportsRequests()) {
            plugin.runDbWrite(() -> plugin.data().deleteRequestsFor(to));
        }
    }

    public void markPrompted(UUID to, UUID from) {
        List<Req> l = inbound.get(to);
        if (l == null) return;
        for (Req r : l) if (r.from.equals(from)) r.prompted = true;
    }

    /** The clickable "X wants to be your friend - [ACCEPT] [DENY]" block. */
    public void showPrompt(Player target, String fromName) {
        plugin.messages().sendList(target, "friend-request-header");
        plugin.messages().line(target, "friend-request-from", fromName);
        plugin.messages().sendRequestButtons(target, fromName);
        plugin.messages().sendList(target, "friend-request-footer");
    }

    public void await(UUID sender) { awaiting.add(sender); }

    /** @return true if the sender was still waiting. */
    public boolean clearAwaiting(UUID sender) { return awaiting.remove(sender); }

    // ------------------------------------------------------------------
    // database synchronisation
    // ------------------------------------------------------------------

    /**
     * Re-reads the stored requests of the given ONLINE players (main thread; the
     * query runs on the DB thread). With {@code prompt} a request that this server
     * has not shown yet (its proxy message never arrived) is announced now.
     */
    public void syncFromStore(Collection<UUID> ids, final boolean prompt) {
        if (!plugin.data().supportsRequests() || ids.isEmpty()) return;
        final long seq = plugin.writeSeq();
        final List<UUID> copy = new ArrayList<>(ids);
        plugin.runDb(() -> {
            final Map<UUID, List<DataStore.StoredRequest>> rows = new HashMap<>();
            for (UUID id : copy) {
                List<DataStore.StoredRequest> l = plugin.data().loadRequests(id);
                if (l != null) rows.put(id, l);
            }
            plugin.sync(() -> apply(rows, seq, prompt));
        });
    }

    private void apply(Map<UUID, List<DataStore.StoredRequest>> rows, long seq, boolean prompt) {
        if (plugin.writeSeq() != seq) return; // a local change is still being written - try again next time
        long ttl = ttlMillis();
        long now = System.currentTimeMillis();

        for (Map.Entry<UUID, List<DataStore.StoredRequest>> e : rows.entrySet()) {
            UUID to = e.getKey();
            Player pl = Bukkit.getPlayer(to);
            if (pl == null) continue;

            List<Req> old = inbound.get(to);
            List<Req> fresh = new ArrayList<>();
            for (DataStore.StoredRequest s : e.getValue()) {
                if (now - s.created >= ttl) continue;
                Req r = new Req(s.from, s.fromName, to, s.toName, s.created);
                if (old != null) {
                    for (Req prev : old) {
                        if (prev.from.equals(s.from) && prev.sent == s.created) r.prompted = prev.prompted;
                    }
                }
                fresh.add(r);
            }
            if (fresh.isEmpty()) inbound.remove(to); else inbound.put(to, fresh);

            for (Req r : fresh) {
                if (r.prompted) continue;
                r.prompted = true;
                if (prompt && r.fromName != null && !r.fromName.isEmpty()) showPrompt(pl, r.fromName);
            }
        }
    }

    /**
     * Fallback for /msf add: nobody answered over the proxy, so write the request
     * straight into the database. The receiver's server picks it up on the next
     * refresh (or when the receiver joins). Runs the lookup off the main thread.
     */
    public void sendViaStore(final Player sender, final String targetName) {
        if (!plugin.data().supportsRequests()) {
            plugin.messages().send(sender, "player-not-found", targetName);
            return;
        }
        final UUID fromId = sender.getUniqueId();
        final String fromName = sender.getName();
        final int timeout = plugin.getConfig().getInt("options.friend-add-timeout", 5);

        plugin.runDbWrite(() -> {
            final UUID to = plugin.data().findUuid(targetName);
            if (to == null || to.equals(fromId)) {
                plugin.sync(() -> { if (sender.isOnline()) plugin.messages().send(sender, "player-not-found", targetName); });
                return;
            }
            final PlayerData td = plugin.data().load(to, null);
            if (td == null) {
                plugin.sync(() -> { if (sender.isOnline()) plugin.messages().send(sender, "request-failed"); });
                return;
            }
            final String toName = td.getLastName() != null ? td.getLastName() : targetName;
            if (td.getFriends().contains(fromId)) {
                plugin.sync(() -> { if (sender.isOnline()) plugin.messages().send(sender, "already-friends"); });
                return;
            }
            if (!td.isAllowRequests()) {
                plugin.sync(() -> { if (sender.isOnline()) plugin.messages().send(sender, "request-not-allowed"); });
                return;
            }
            final boolean ok = plugin.data().saveRequest(fromId, fromName, to, toName, System.currentTimeMillis());
            plugin.sync(() -> {
                if (!sender.isOnline()) return;
                if (ok) plugin.messages().send(sender, "request-sent", toName, timeout);
                else plugin.messages().send(sender, "request-failed");
            });
        });
    }

    // ------------------------------------------------------------------
    // expiry
    // ------------------------------------------------------------------

    private void expire() {
        long ttl = ttlMillis();
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

        if (plugin.data().supportsRequests()) {
            final long cutoff = now - ttl;
            plugin.runDb(() -> plugin.data().purgeRequestsOlderThan(cutoff));
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

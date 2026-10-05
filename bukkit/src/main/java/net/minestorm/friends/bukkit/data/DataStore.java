package net.minestorm.friends.bukkit.data;

import net.minestorm.friends.common.Friend;
import net.minestorm.friends.common.storage.PlayerData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Persistent backend. All operations are idempotent so every server may apply
 * the same network event without corrupting anything.
 * Implementations are safe to call from any thread.
 *
 * FIX: the boolean-returning methods tell the caller whether the write really
 * reached the database (the old void methods swallowed every SQL error, so the
 * player was told "saved" while nothing was stored).
 */
public interface DataStore {
    void init() throws Exception;
    void close();

    /**
     * Reads a player from the backend (never cached here).
     * @return the data, or {@code null} if the backend could not be reached
     *         (callers must NOT treat that as "player has no friends").
     */
    PlayerData load(UUID uuid, String name);

    /** Stores the last known name (and optionally the last-seen time). */
    void saveProfile(UUID uuid, String name, boolean markSeen);

    /** Adds {@code friend} to {@code owner}'s list (or refreshes its name). */
    void addFriend(UUID owner, UUID friend, String friendName);

    void removeFriend(UUID owner, UUID friend);

    void setAllowRequests(UUID uuid, String name, boolean allow);

    /** Case-insensitive lookup by last known name, or null. */
    UUID findUuid(String name);

    /** True if this backend is a shared remote database (MySQL). SqlStore MUST override this. */
    default boolean isRemote() { return false; }

    // ── result-aware / atomic operations ────────────────────────────

    /** Makes a and b friends of each other in ONE transaction. */
    default boolean linkFriends(UUID a, String an, UUID b, String bn) {
        saveProfile(a, an, false);
        saveProfile(b, bn, false);
        addFriend(a, b, bn);
        addFriend(b, a, an);
        return true;
    }

    /** Removes the friendship in both directions in ONE transaction. */
    default boolean unlinkFriends(UUID a, UUID b) {
        removeFriend(a, b);
        removeFriend(b, a);
        return true;
    }

    /** Removes every friendship of {@code owner} (both directions). */
    default boolean clearFriends(UUID owner) {
        PlayerData d = load(owner, null);
        if (d == null) return false;
        for (Friend f : new ArrayList<>(d.getFriends().all())) unlinkFriends(owner, f.getUuid());
        return true;
    }

    default boolean updateAllowRequests(UUID uuid, String name, boolean allow) {
        setAllowRequests(uuid, name, allow);
        return true;
    }

    // ── persisted friend requests ───────────────────────────────────

    /** A pending friend request as stored in the database. */
    final class StoredRequest {
        public final UUID from, to;
        public final String fromName, toName;
        public final long created;

        public StoredRequest(UUID from, String fromName, UUID to, String toName, long created) {
            this.from = from; this.fromName = fromName;
            this.to = to; this.toName = toName;
            this.created = created;
        }
    }

    /** True if requests are persisted by this backend. */
    default boolean supportsRequests() { return false; }

    default boolean saveRequest(UUID from, String fromName, UUID to, String toName, long created) { return true; }

    default void deleteRequest(UUID to, UUID from) { }

    default void deleteRequestsFor(UUID to) { }

    default void purgeRequestsOlderThan(long cutoffMillis) { }

    /** @return the pending requests for {@code to}, or null if the backend could not be reached. */
    default List<StoredRequest> loadRequests(UUID to) { return Collections.emptyList(); }

    /** Human readable health report (used by /msf admin dbcheck and the startup log). */
    default List<String> diagnose() {
        return Collections.singletonList("This storage type has no diagnostics.");
    }
}

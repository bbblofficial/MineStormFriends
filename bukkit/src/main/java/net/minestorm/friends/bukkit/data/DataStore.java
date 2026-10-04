package net.minestorm.friends.bukkit.data;

import net.minestorm.friends.common.storage.PlayerData;

import java.util.UUID;

/**
 * Persistent backend. All operations are idempotent so every server may apply
 * the same network event without corrupting anything.
 * Implementations are synchronized and safe to call from any thread.
 */
public interface DataStore {
    void init() throws Exception;
    void close();

    /** Reads a player from the backend (never cached here). */
    PlayerData load(UUID uuid, String name);

    /** Stores the last known name (and optionally the last-seen time). */
    void saveProfile(UUID uuid, String name, boolean markSeen);

    /** Adds {@code friend} to {@code owner}'s list (or refreshes its name). */
    void addFriend(UUID owner, UUID friend, String friendName);

    void removeFriend(UUID owner, UUID friend);

    void setAllowRequests(UUID uuid, String name, boolean allow);

    /** Case-insensitive lookup by last known name, or null. */
    UUID findUuid(String name);

    /** True if this backend is a shared remote database (MySQL). */
    default boolean isRemote() { return false; }
}

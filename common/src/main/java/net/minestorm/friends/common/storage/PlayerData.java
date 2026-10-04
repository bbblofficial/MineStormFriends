package net.minestorm.friends.common.storage;

import net.minestorm.friends.common.FriendList;

import java.util.UUID;

public final class PlayerData {
    private final UUID uuid;
    private final FriendList friends = new FriendList();
    private volatile boolean allowRequests = true;
    private volatile long lastSeen;
    private volatile String lastName;

    public PlayerData(UUID uuid) { this.uuid = uuid; }

    public UUID getUuid() { return uuid; }
    public FriendList getFriends() { return friends; }
    public boolean isAllowRequests() { return allowRequests; }
    public void setAllowRequests(boolean v) { this.allowRequests = v; }
    public long getLastSeen() { return lastSeen; }
    public void setLastSeen(long t) { this.lastSeen = t; }
    public String getLastName() { return lastName; }
    public void setLastName(String n) { this.lastName = n; }
}

package net.minestorm.friends.common;

import java.util.UUID;

public final class Friend {
    private final UUID uuid;
    private volatile String name;

    public Friend(UUID uuid, String name) { this.uuid = uuid; this.name = name; }

    public UUID getUuid() { return uuid; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    @Override public boolean equals(Object o) {
        return o instanceof Friend && uuid.equals(((Friend) o).uuid);
    }
    @Override public int hashCode() { return uuid.hashCode(); }
}

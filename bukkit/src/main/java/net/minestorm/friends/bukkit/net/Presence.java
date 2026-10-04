package net.minestorm.friends.bukkit.net;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Which server (by name) a player is currently on - fed by JOIN / QUIT / HERE packets. */
public final class Presence {
    private final Map<UUID, String> map = new ConcurrentHashMap<>();

    public String get(UUID id) { return map.get(id); }
    public void set(UUID id, String server) { map.put(id, server); }
    public void remove(UUID id) { map.remove(id); }
}

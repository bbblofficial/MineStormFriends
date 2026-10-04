package net.minestorm.friends.bukkit.data;

import net.minestorm.friends.bukkit.MineStormFriendsPlugin;
import net.minestorm.friends.common.Friend;
import net.minestorm.friends.common.storage.PlayerData;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class FlatFileStore implements DataStore {
    private final MineStormFriendsPlugin plugin;
    private final File folder;

    public FlatFileStore(MineStormFriendsPlugin plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "playerdata");
    }

    @Override public void init() {
        if (!folder.exists() && !folder.mkdirs())
            plugin.getLogger().warning("Could not create " + folder);
    }

    @Override public void close() { }

    private File file(UUID id) { return new File(folder, id + ".yml"); }

    private YamlConfiguration read(UUID id) {
        File f = file(id);
        return f.exists() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
    }

    private void write(UUID id, YamlConfiguration y) {
        try {
            y.save(file(id));
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save " + id + ": " + e.getMessage());
        }
    }

    @Override public synchronized PlayerData load(UUID uuid, String name) {
        YamlConfiguration y = read(uuid);
        PlayerData d = new PlayerData(uuid);
        d.setLastName(name != null ? name : y.getString("last-name"));
        d.setAllowRequests(y.getBoolean("allow-requests", true));
        d.setLastSeen(y.getLong("last-seen", 0L));
        for (String raw : y.getStringList("friends")) {
            String[] parts = raw.split("\\|", 2);
            if (parts.length != 2) continue;
            try {
                d.getFriends().add(new Friend(UUID.fromString(parts[0]), parts[1]));
            } catch (IllegalArgumentException ignored) { }
        }
        return d;
    }

    @Override public synchronized void saveProfile(UUID uuid, String name, boolean markSeen) {
        if (name == null) return;
        YamlConfiguration y = read(uuid);
        y.set("last-name", name);
        if (markSeen) y.set("last-seen", System.currentTimeMillis());
        write(uuid, y);
    }

    @Override public synchronized void addFriend(UUID owner, UUID friend, String friendName) {
        YamlConfiguration y = read(owner);
        List<String> list = new ArrayList<>(y.getStringList("friends"));
        String prefix = friend + "|";
        list.removeIf(s -> s.startsWith(prefix));
        list.add(prefix + (friendName == null ? "unknown" : friendName));
        y.set("friends", list);
        write(owner, y);
    }

    @Override public synchronized void removeFriend(UUID owner, UUID friend) {
        if (!file(owner).exists()) return;
        YamlConfiguration y = read(owner);
        List<String> list = new ArrayList<>(y.getStringList("friends"));
        String prefix = friend + "|";
        if (list.removeIf(s -> s.startsWith(prefix))) {
            y.set("friends", list);
            write(owner, y);
        }
    }

    @Override public synchronized void setAllowRequests(UUID uuid, String name, boolean allow) {
        YamlConfiguration y = read(uuid);
        y.set("allow-requests", allow);
        if (name != null) y.set("last-name", name);
        write(uuid, y);
    }

    @Override public synchronized UUID findUuid(String name) {
        File[] files = folder.listFiles((dir, n) -> n.endsWith(".yml"));
        if (files == null) return null;
        for (File f : files) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            if (name.equalsIgnoreCase(y.getString("last-name"))) {
                try {
                    String n = f.getName();
                    return UUID.fromString(n.substring(0, n.length() - 4));
                } catch (IllegalArgumentException ignored) { }
            }
        }
        return null;
    }
}

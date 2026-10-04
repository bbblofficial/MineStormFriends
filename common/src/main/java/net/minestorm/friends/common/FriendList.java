package net.minestorm.friends.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Thread-safe friend list (iteration works on a snapshot). */
public final class FriendList {
    private final List<Friend> friends = new CopyOnWriteArrayList<>();

    public int size() { return friends.size(); }

    public List<Friend> all() { return friends; }

    public Friend find(UUID id) {
        for (Friend f : friends) if (f.getUuid().equals(id)) return f;
        return null;
    }

    public Friend findByName(String name) {
        if (name == null) return null;
        for (Friend f : friends) if (name.equalsIgnoreCase(f.getName())) return f;
        return null;
    }

    public boolean contains(UUID id) { return find(id) != null; }

    /** Adds the friend; if already present only refreshes the stored name. */
    public boolean add(Friend f) {
        Friend existing = find(f.getUuid());
        if (existing != null) {
            if (f.getName() != null) existing.setName(f.getName());
            return false;
        }
        friends.add(f);
        return true;
    }

    public boolean remove(UUID id) { return friends.removeIf(f -> f.getUuid().equals(id)); }

    public void clear() { friends.clear(); }

    public List<Friend> sorted() {
        List<Friend> copy = new ArrayList<>(friends);
        copy.sort((x, y) -> String.valueOf(x.getName()).compareToIgnoreCase(String.valueOf(y.getName())));
        return copy;
    }

    /** 1-based page of the name-sorted list. */
    public List<Friend> page(int page, int pageSize) {
        List<Friend> sorted = sorted();
        int from = Math.max(0, (page - 1) * pageSize);
        int to = Math.min(sorted.size(), page * pageSize);
        if (from >= to) return Collections.<Friend>emptyList();
        return new ArrayList<>(sorted.subList(from, to));
    }
}

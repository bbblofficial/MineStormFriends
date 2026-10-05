# MineStormFriends
Cross-server friend system for **Bukkit / Spigot / Paper 1.8 → latest**
with optional **BungeeCord** and **Velocity** proxy relays.

> **Created by Muvixo**

## Install
1. Put `MineStormFriends-Bukkit.jar` on **every backend server**.
2. Put `MineStormFriends-Bungee.jar` **or** `MineStormFriends-Velocity.jar` on the proxy.
3. Edit `plugins/MineStormFriends/config.yml` on every backend:
   * `network.server-name` – unique name per server (use the proxy's server name)
   * `network.secret` – **same long random string on all servers** (packets are HMAC-signed)
   * `storage.type` – use `MYSQL` for a network (all servers share one database)
4. Single server without proxy? Set `network.proxy: false`.

## Commands
`/msf`, `/minestormfriends`, `/friends`, `/f`

| Command | Description |
|---|---|
| `/msf add <player>` | Send a friend request (works across servers) |
| `/msf accept <player>` / `deny <player>` | Answer a request (clickable too) |
| `/msf list [page]` | List friends + where they are online |
| `/msf remove <player>` | Remove a friend (offline friends too) |
| `/msf removeall` | Clear your friend list |
| `/msf requests` | Pending requests |
| `/msf toggle` | Allow / block incoming requests |
| `/msf creator` | Plugin info |
| `/msf admin <reload\|list\|add\|remove\|removeall\|clearrequests\|toggle\|info\|help>` | Admin tools |

## Permissions (LuckPerms)
```text
minestormfriends.use                 (parent of all user permissions)
minestormfriends.add
minestormfriends.accept
minestormfriends.deny
minestormfriends.list
minestormfriends.remove
minestormfriends.removeall
minestormfriends.requests
minestormfriends.toggle
minestormfriends.limit.<n>           (numeric, highest wins)
minestormfriends.admin               (parent of all admin permissions, OP bypasses)
minestormfriends.admin.reload
minestormfriends.admin.list
minestormfriends.admin.add
minestormfriends.admin.remove
minestormfriends.admin.removeall
minestormfriends.admin.clearrequests
minestormfriends.admin.toggle
minestormfriends.admin.info
minestormfriends.bypass              (ignore friend limit)
```
Each admin sub-permission works on its own – the parent is **not** required.

## Build
```bash
gradle clean build        # JDK 17 or 21
```
Jars end up in `bukkit|bungee|velocity/build/libs/`.

## Notes
* Friend requests are stored in the database (`msf_requests`, MYSQL / SQLITE) and expire after
  `options.friend-add-timeout` minutes. `/msf admin dbcheck` tests the database connection.
* With `FLATFILE` each server keeps its own copy and syncs via proxy messages
  (best effort). Use `MYSQL` for real networks.

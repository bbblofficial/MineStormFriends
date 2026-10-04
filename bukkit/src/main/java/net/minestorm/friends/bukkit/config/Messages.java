package net.minestorm.friends.bukkit.config;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.minestorm.friends.bukkit.MineStormFriendsPlugin;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class Messages {
    private final MineStormFriendsPlugin plugin;
    private FileConfiguration cfg;
    private String prefix = "";

    public Messages(MineStormFriendsPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File f = new File(plugin.getDataFolder(), "messages.yml");
        if (!f.exists()) plugin.saveResource("messages.yml", false);
        cfg = YamlConfiguration.loadConfiguration(f);
        // keys added in newer versions fall back to the bundled defaults
        InputStream in = plugin.getResource("messages.yml");
        if (in != null) {
            cfg.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8)));
        }
        prefix = color(cfg.getString("prefix", ""));
    }

    public String prefix() { return prefix; }

    public String raw(String path) {
        String s = cfg.getString(path);
        return s == null ? "" : s;
    }

    public List<String> rawList(String path) { return cfg.getStringList(path); }

    /** Formatted + coloured string (no prefix). */
    public String format(String path, Object... args) {
        String s = cfg.getString(path);
        if (s == null) return color("&c[missing message: " + path + "]");
        return color(fill(s, args));
    }

    /** Message with prefix. */
    public void send(CommandSender to, String path, Object... args) {
        String s = format(path, args);
        if (!s.isEmpty()) to.sendMessage(prefix + s);
    }

    /** Message without prefix. */
    public void line(CommandSender to, String path, Object... args) {
        String s = format(path, args);
        if (!s.isEmpty()) to.sendMessage(s);
    }

    /** List of lines without prefix. */
    public void sendList(CommandSender to, String path, Object... args) {
        for (String l : rawList(path)) to.sendMessage(color(fill(l, args)));
    }

    /** Clickable [ACCEPT] - [DENY] line. */
    public void sendRequestButtons(Player to, String fromName) {
        TextComponent line = new TextComponent("");
        for (BaseComponent c : TextComponent.fromLegacyText(color(raw("friend-request-options"))))
            line.addExtra(c);
        for (BaseComponent c : clickable(raw("request-option-accept"), "/msf accept " + fromName))
            line.addExtra(c);
        for (BaseComponent c : TextComponent.fromLegacyText(color(raw("spacer"))))
            line.addExtra(c);
        for (BaseComponent c : clickable(raw("request-option-deny"), "/msf deny " + fromName))
            line.addExtra(c);
        to.spigot().sendMessage(line);
    }

    private BaseComponent[] clickable(String text, String command) {
        BaseComponent[] parts = TextComponent.fromLegacyText(color(text));
        for (BaseComponent c : parts) c.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
        return parts;
    }

    private static String fill(String s, Object... args) {
        for (int i = 0; i < args.length; i++) s = s.replace("%" + i + "%", String.valueOf(args[i]));
        return s;
    }

    public static String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
}

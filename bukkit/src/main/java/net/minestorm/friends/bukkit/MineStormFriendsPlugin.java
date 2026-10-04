package net.minestorm.friends.bukkit;

import net.minestorm.friends.bukkit.command.MSFCommand;
import net.minestorm.friends.bukkit.config.Messages;
import net.minestorm.friends.bukkit.data.DataStore;
import net.minestorm.friends.bukkit.data.FlatFileStore;
import net.minestorm.friends.bukkit.data.FriendManager;
import net.minestorm.friends.bukkit.data.SqlStore;
import net.minestorm.friends.bukkit.listener.PlayerListener;
import net.minestorm.friends.bukkit.net.BukkitNet;
import net.minestorm.friends.bukkit.net.NetHandler;
import net.minestorm.friends.bukkit.net.Presence;
import net.minestorm.friends.bukkit.request.RequestManager;
import net.minestorm.friends.common.net.Packet;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;

import java.util.Locale;
import java.util.logging.Level;

public final class MineStormFriendsPlugin extends JavaPlugin {
    private static MineStormFriendsPlugin instance;

    private Messages messages;
    private DataStore store;
    private FriendManager friends;
    private RequestManager requests;
    private Presence presence;
    private NetHandler handler;
    private BukkitNet net;
    private CacheRefresher refresher;

    @Override public void onEnable() {
        instance = this;
        saveDefaultConfig();
        messages = new Messages(this);

        store = createStore();
        try {
            store.init();
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Could not initialise storage - disabling plugin.", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        friends  = new FriendManager(this);
        requests = new RequestManager(this);
        presence = new Presence();
        handler  = new NetHandler(this);
        net      = new BukkitNet(this);

        Messenger m = getServer().getMessenger();
        m.registerOutgoingPluginChannel(this, Packet.CHANNEL);
        m.registerIncomingPluginChannel(this, Packet.CHANNEL,
                (channel, player, bytes) -> net.receive(bytes));

        MSFCommand cmd = new MSFCommand(this);
        PluginCommand pc = getCommand("minestormfriends");
        pc.setExecutor(cmd);
        pc.setTabCompleter(cmd);

        Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);

        // plugin enabled while players are online (e.g. /reload)
        for (Player p : Bukkit.getOnlinePlayers()) friends.cacheLoad(p.getUniqueId(), p.getName());

        refresher = new CacheRefresher(this);
        refresher.start();

        getLogger().info("MineStormFriends enabled (storage: " + store.getClass().getSimpleName()
                + (store.isRemote() ? " [shared]" : "") + ") - Created by Muvixo");
    }

    @Override public void onDisable() {
        if (refresher != null) refresher.stop();
        if (requests != null) requests.shutdown();
        if (store != null) store.close();
    }

    private DataStore createStore() {
        String type = getConfig().getString("storage.type", "FLATFILE").toUpperCase(Locale.ROOT);
        switch (type) {
            case "MYSQL":  return new SqlStore(this, true);
            case "SQLITE": return new SqlStore(this, false);
            default:       return new FlatFileStore(this);
        }
    }

    public void reloadEverything() {
        reloadConfig();
        messages.reload();
        net.reload();
    }

    public String serverName() { return getConfig().getString("network.server-name", "server"); }

    public static MineStormFriendsPlugin get() { return instance; }
    public Messages messages()        { return messages; }
    public DataStore data()           { return store; }
    public FriendManager friends()    { return friends; }
    public RequestManager requests()  { return requests; }
    public Presence presence()        { return presence; }
    public NetHandler handler()       { return handler; }
    public BukkitNet net()            { return net; }
}

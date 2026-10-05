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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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

    /** Single DB thread: keeps writes in order and keeps SQL off the main thread. */
    private ExecutorService dbExecutor;
    /** Bumped for every queued friend / request change (see FriendManager.refreshOnline). */
    private final AtomicLong writeSeq = new AtomicLong();
    private volatile Thread mainThread;

    @Override public void onEnable() {
        instance = this;
        mainThread = Thread.currentThread();
        saveDefaultConfig();
        messages = new Messages(this);

        dbExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MSF-DB");
            t.setDaemon(true);
            return t;
        });

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

        // health report in the console: shows immediately whether the database really works
        runDb(() -> {
            for (String line : store.diagnose()) getLogger().info("[db] " + line);
        });
    }

    @Override public void onDisable() {
        if (refresher != null) refresher.stop();
        if (requests != null) requests.shutdown();

        // let queued writes finish BEFORE the pool is closed (they used to be lost)
        if (dbExecutor != null) {
            dbExecutor.shutdown();
            try {
                if (!dbExecutor.awaitTermination(10, TimeUnit.SECONDS))
                    getLogger().warning("Some database writes did not finish in time.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
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

    // ── threading helpers ───────────────────────────────────────────

    /** Runs {@code r} on the database thread (inline if the plugin is shutting down). */
    public void runDb(final Runnable r) {
        final Runnable safe = () -> {
            try {
                r.run();
            } catch (Throwable t) {
                getLogger().log(Level.SEVERE, "Database task failed", t);
            }
        };
        ExecutorService ex = dbExecutor;
        if (ex == null) { safe.run(); return; }
        try {
            ex.execute(safe);
        } catch (RejectedExecutionException e) {
            safe.run(); // shutting down: do the work now instead of losing it
        }
    }

    /** Like {@link #runDb} for a friend / request CHANGE (invalidates in-flight cache refreshes). */
    public void runDbWrite(Runnable r) {
        writeSeq.incrementAndGet();
        runDb(r);
    }

    public long writeSeq() { return writeSeq.get(); }

    /** Runs {@code r} on the main thread (next tick). Silently dropped if the plugin is disabled. */
    public void sync(Runnable r) {
        try {
            Bukkit.getScheduler().runTask(this, r);
        } catch (RuntimeException ignored) {
            // plugin disabled while shutting down
        }
    }

    public boolean isMainThread() { return Thread.currentThread() == mainThread; }

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

package org.example.easyban;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class EasyBan extends JavaPlugin implements Listener {

    private BanManager banManager;
    private MessageManager messageManager;
    private DatabaseManager databaseManager;
    private UpdateChecker updateChecker;
    private DiscordManager discordManager;

    private static boolean isFolia;

    @Override
    public void onEnable() {
        // Detect Folia environment (shared with the scheduler bridge).
        isFolia = SchedulerCompat.isFolia();

        // 1. Create config.yml if it does not exist yet.
        saveDefaultConfig();
        // 2. Append every NEW option of this version to the existing config.
        //    Nothing the admin wrote is ever removed or overwritten.
        int added = ConfigUpdater.update(this, "config.yml", new File(getDataFolder(), "config.yml"));
        if (added > 0) {
            reloadConfig();
            getLogger().info("Added " + added + " new option(s) to config.yml - your existing settings were kept.");
        }

        // Language files are created/updated by MessageManager, which also migrates
        // an existing Messages.yml from EasyBan 1.6 and earlier.
        this.messageManager = new MessageManager(this);
        this.databaseManager = new DatabaseManager(this);
        this.banManager = new BanManager(this, databaseManager, messageManager);
        this.discordManager = new DiscordManager(this, messageManager);

        // Update Checker Initialization
        if (getConfig().getBoolean("checkupdates", true)) {
            this.updateChecker = new UpdateChecker(this);
            // Immediate check at start and then every 12 hours (Folia-safe).
            SchedulerCompat.runAsyncTimer(this, () -> this.updateChecker.fetch(), 20L, 20L * 60 * 60 * 12);
        }

        // Developer API registration (only when explicitly enabled in the config).
        if (getConfig().getBoolean("api.enabled", false)) {
            EasyBanAPI.register(this, banManager);
            getLogger().warning("The EasyBan API is ENABLED. Make sure you trust every plugin that uses it -");
            getLogger().warning("a malicious plugin could punish players without permission. Disable 'api.enabled' in the config if you don't need it.");
        }

        if (getCommand("eb") != null) {
            EBCommands cmds = new EBCommands(this, banManager, messageManager);
            getCommand("eb").setExecutor(cmds);
            getCommand("eb").setTabCompleter(cmds);
        }

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("EasyBan " + getDescription().getVersion() + " enabled! Folia Environment: " + isFolia);
    }

    @Override
    public void onDisable() {
        SchedulerCompat.cancelAll(this);
        EasyBanAPI.unregister();
        if (banManager != null) banManager.shutdown();
        if (messageManager != null) messageManager.shutdown();
        getLogger().info("EasyBan disabled!");
    }

    // ------------------------------------------------------------------ //
    //  API event bridges. These return true when the action should go    //
    //  ahead. When the API is disabled they always allow the action.     //
    // ------------------------------------------------------------------ //

    /** Fires the API PunishEvent. @return false if an API listener cancelled the punishment. */
    public boolean firePunishEvent(String type, String target, String admin, String reason, long durationMillis, String server) {
        if (EasyBanAPI.get() == null) return true;
        EasyBanAPI.PunishEvent event = new EasyBanAPI.PunishEvent(
                type, target, admin, reason, durationMillis, server, !isPrimaryThreadSafe());
        getServer().getPluginManager().callEvent(event);
        return !event.isCancelled();
    }

    /** Fires the API UnpunishEvent. @return false if an API listener cancelled the removal. */
    public boolean fireUnpunishEvent(String type, String target, String server) {
        if (EasyBanAPI.get() == null) return true;
        EasyBanAPI.UnpunishEvent event = new EasyBanAPI.UnpunishEvent(
                type, target, server, !isPrimaryThreadSafe());
        getServer().getPluginManager().callEvent(event);
        return !event.isCancelled();
    }

    /** Fires the API KickEvent. @return false if an API listener cancelled the kick. */
    public boolean fireKickEvent(String target, String admin, String reason) {
        if (EasyBanAPI.get() == null) return true;
        EasyBanAPI.KickEvent event = new EasyBanAPI.KickEvent(
                target, admin, reason, !isPrimaryThreadSafe());
        getServer().getPluginManager().callEvent(event);
        return !event.isCancelled();
    }

    /**
     * Bukkit.isPrimaryThread() is not supported on Folia, where it can throw.
     * On Folia we conservatively report "primary" so events are created as
     * synchronous; on regular Paper/Bukkit the real value is used.
     */
    private static boolean isPrimaryThreadSafe() {
        try {
            return org.bukkit.Bukkit.isPrimaryThread();
        } catch (Throwable ignored) {
            return true;
        }
    }

    // ------------------------------------------------------------------ //
    //  Listeners                                                          //
    // ------------------------------------------------------------------ //

    @EventHandler
    public void onLogin(AsyncPlayerPreLoginEvent event) {
        String name = event.getName();
        if (event.getAddress() == null) return;
        String ip = event.getAddress().getHostAddress();

        // 1. IP Bans
        if (banManager.isIpBanned(ip)) {
            if (!banManager.isExempt("IPBAN", ip, name)) {
                if (banManager.isExpired("IPBAN", ip)) {
                    banManager.purgeIfExpired("IPBAN", ip);
                } else {
                    String reason = banManager.getIpBanReason(ip);
                    String admin = banManager.getIpBanAdmin(ip);
                    String server = banManager.getPunishmentServer("IPBAN", ip);
                    long expiry = banManager.getIpBanExpiry(ip);
                    String timeStr = (expiry == -1)
                            ? messageManager.getRaw("status.permanent") : banManager.formatDate(expiry);
                    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                            messageManager.getBannedLayoutComponent(reason, admin, timeStr, server));
                    return;
                }
            }
        }

        // 2. Name Bans
        if (banManager.isBanned(name)) {
            if (banManager.isExpired("BAN", name)) {
                banManager.purgeIfExpired("BAN", name);
            } else {
                String reason = banManager.getBanReason(name);
                String admin = banManager.getBanAdmin(name);
                String server = banManager.getPunishmentServer("BAN", name);
                long expiry = banManager.getBanExpiry(name);
                String timeStr = (expiry == -1)
                        ? messageManager.getRaw("status.permanent") : banManager.formatDate(expiry);
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                        messageManager.getBannedLayoutComponent(reason, admin, timeStr, server));
            }
        }
    }

    // Paper's native async chat event (Adventure-based). Using this instead of
    // the deprecated AsyncPlayerChatEvent keeps the plugin future-proof on
    // modern Paper versions, including 26.x.
    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String name = player.getName();
        if (player.getAddress() == null || player.getAddress().getAddress() == null) {
            return;
        }
        String ip = player.getAddress().getAddress().getHostAddress();

        if (banManager.isIpMuted(ip)) {
            if (!banManager.isExempt("IPMUTE", ip, name)) {
                if (banManager.isExpired("IPMUTE", ip)) {
                    banManager.purgeIfExpired("IPMUTE", ip);
                } else {
                    event.setCancelled(true);
                    messageManager.send(player, "ipmuted-message",
                            "%reason%", banManager.getIpMuteReason(ip),
                            "%server%", banManager.getPunishmentServer("IPMUTE", ip));
                    return;
                }
            }
        }

        if (banManager.isMuted(name)) {
            if (banManager.isExpired("MUTE", name)) {
                banManager.purgeIfExpired("MUTE", name);
            } else {
                event.setCancelled(true);
                messageManager.send(player, "muted-message",
                        "%reason%", banManager.getMuteReason(name),
                        "%server%", banManager.getPunishmentServer("MUTE", name));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        String name = event.getPlayer().getName();
        if (banManager.isBlockBreakingBanned(name)) {
            event.setCancelled(true);
            messageManager.send(event.getPlayer(), "cant-break-message",
                    "%reason%", banManager.getBlockBreakingReason(name),
                    "%server%", banManager.getPunishmentServer("DONOTBREAK", name));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("easyban.use") && updateChecker != null && updateChecker.isUpdateAvailable()) {
            // 40 ticks (2 seconds) delay so the client has time to load the UI
            // and properly register the clickable components.
            SchedulerCompat.runLater(this, () -> {
                if (player.isOnline()) { // Ensure player hasn't disconnected during the delay
                    messageManager.sendUpdateNotification(player, getDescription().getVersion(), updateChecker.getLatestVersion());
                }
            }, 40L);
        }
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                            //
    // ------------------------------------------------------------------ //

    /**
     * Kicks a player with an Adventure component. On Folia the entity scheduler
     * is used so this also works on regionised servers; on regular Paper/Bukkit
     * a normal synced task is scheduled instead.
     */
    public void safeKick(Player player, Component message) {
        if (player == null) return;
        SchedulerCompat.runEntity(this, player, () -> {
            if (player.isOnline()) {
                player.kick(message);
            }
        });
    }

    public BanManager getBanManager() {
        return banManager;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }

    public DiscordManager getDiscordManager() {
        return discordManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }
}
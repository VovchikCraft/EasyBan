package org.example.easyban;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;

public class EasyBan extends JavaPlugin implements Listener {

    private BanManager banManager;
    private MessageManager messageManager;
    private DatabaseManager databaseManager;
    private UpdateChecker updateChecker;

    private static boolean isFolia;

    @Override
    public void onEnable() {
        // Detect Folia
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            isFolia = true;
        } catch (ClassNotFoundException e) {
            isFolia = false;
        }

        saveDefaultConfig();
        saveResource("Messages.yml", false);

        this.messageManager = new MessageManager(this);
        this.databaseManager = new DatabaseManager(this);
        this.banManager = new BanManager(this, databaseManager, messageManager);

        // Update Checker Init
        if (getConfig().getBoolean("checkupdates", true)) {
            this.updateChecker = new UpdateChecker(this);
            this.updateChecker.fetch();
        }

        if (getCommand("eb") != null) {
            EBCommands cmds = new EBCommands(this, banManager, messageManager);
            getCommand("eb").setExecutor(cmds);
            getCommand("eb").setTabCompleter(cmds);
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("EasyBan 1.4 enabled! Folia Environment: " + isFolia);
    }

    @Override
    public void onDisable() {
        banManager.shutdown();
        messageManager.shutdown();
        getLogger().info("EasyBan disabled!");
    }

    @EventHandler
    public void onLogin(AsyncPlayerPreLoginEvent event) {
        String name = event.getName();
        String ip = event.getAddress().getHostAddress();

        // 1. IP Bans
        if (banManager.isIpBanned(ip)) {
            long expiry = banManager.getIpBanExpiry(ip);
            if (expiry > 0 && System.currentTimeMillis() > expiry) {
                banManager.unbanIp(ip);
            } else {
                String reason = banManager.getIpBanReason(ip);
                String admin = banManager.getIpBanAdmin(ip);
                String server = banManager.getPunishmentServer("IPBAN", ip);
                String timeStr = (expiry == -1) ? "Permanent" : banManager.formatDate(expiry);

                String layout = messageManager.getBannedLayout(reason, admin, timeStr, server);
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, layout);
                return;
            }
        }

        // 2. Name Bans
        if (banManager.isBanned(name)) {
            long expiry = banManager.getBanExpiry(name);
            if (expiry > 0 && System.currentTimeMillis() > expiry) {
                banManager.unban(name);
            } else {
                String reason = banManager.getBanReason(name);
                String admin = banManager.getBanAdmin(name);
                String server = banManager.getPunishmentServer("BAN", name);
                String timeStr = (expiry == -1) ? "Permanent" : banManager.formatDate(expiry);

                String layout = messageManager.getBannedLayout(reason, admin, timeStr, server);
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, layout);
            }
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String name = player.getName();
        String ip = player.getAddress().getAddress().getHostAddress();

        if (banManager.isIpMuted(ip)) {
            long expiry = banManager.getIpMuteExpiry(ip);
            if (expiry > 0 && System.currentTimeMillis() > expiry) {
                banManager.unmuteIp(ip);
            } else {
                event.setCancelled(true);
                messageManager.send(player, "ipmuted-message",
                        "%reason%", banManager.getIpMuteReason(ip),
                        "%server%", banManager.getPunishmentServer("IPMUTE", ip));
                return;
            }
        }

        if (banManager.isMuted(name)) {
            long expiry = banManager.getMuteExpiry(name);
            if (expiry > 0 && System.currentTimeMillis() > expiry) {
                banManager.unmute(name);
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
        // Надсилаємо повідомлення про апдейт ТІЛЬКИ адмінам
        if (player.hasPermission("easyban.use") && updateChecker != null && updateChecker.isUpdateAvailable()) {
            messageManager.send(player, "update-available",
                    "%current%", getDescription().getVersion(),
                    "%newest%", updateChecker.getLatestVersion());
        }
    }

    /**
     * Safely kicks a player across Spigot, Paper, and Folia.
     */
    public void safeKick(Player player, String message) {
        if (isFolia) {
            try {
                // Folia requires entity tasks to be run on the entity's region
                Method getScheduler = player.getClass().getMethod("getScheduler");
                Object entityScheduler = getScheduler.invoke(player);
                Method run = entityScheduler.getClass().getMethod("run", org.bukkit.plugin.Plugin.class, java.util.function.Consumer.class, Runnable.class);
                run.invoke(entityScheduler, this, (java.util.function.Consumer<?>) task -> player.kickPlayer(message), null);
            } catch (Exception e) {
                player.kickPlayer(message); // Fallback
            }
        } else {
            Bukkit.getScheduler().runTask(this, () -> player.kickPlayer(message));
        }
    }
}
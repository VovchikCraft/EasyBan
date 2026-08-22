package org.example.easyban;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BanManager {

    private final EasyBan plugin;
    private final DatabaseManager db;
    private final MessageManager msg;

    private File dataFile;
    private FileConfiguration dataConfig;
    private boolean useMySQL;
    private String localServerName;

    private ScheduledExecutorService syncScheduler;
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    public BanManager(EasyBan plugin, DatabaseManager db, MessageManager msg) {
        this.plugin = plugin;
        this.db = db;
        this.msg = msg;
        this.useMySQL = plugin.getConfig().getString("storage.type", "local").equalsIgnoreCase("mysql");
        this.localServerName = plugin.getConfig().getString("server-name", "survival");

        setupLocalFile();

        if (useMySQL) {
            int interval = plugin.getConfig().getInt("storage.sync-interval", 4);
            syncScheduler = Executors.newSingleThreadScheduledExecutor();
            syncScheduler.scheduleAtFixedRate(this::syncFromDatabase, 0, interval, TimeUnit.SECONDS);
        }
    }

    private void setupLocalFile() {
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();

        dataFile = new File(plugin.getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        if (!dataConfig.contains("bans")) dataConfig.createSection("bans");
        if (!dataConfig.contains("ipbans")) dataConfig.createSection("ipbans");
        if (!dataConfig.contains("mutes")) dataConfig.createSection("mutes");
        if (!dataConfig.contains("ipmutes")) dataConfig.createSection("ipmutes");
        if (!dataConfig.contains("nobraking")) dataConfig.createSection("nobraking");
        saveLocal();
    }

    private void saveLocal() {
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void shutdown() {
        if (syncScheduler != null && !syncScheduler.isShutdown()) {
            syncScheduler.shutdownNow();
        }
        if (dbExecutor != null && !dbExecutor.isShutdown()) {
            dbExecutor.shutdown();
        }
    }

    // --- SYNC ENGINE ---

    private void syncFromDatabase() {
        String sql = "SELECT * FROM " + db.getTable() + " WHERE server = ? OR server = 'all'";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, localServerName);
            ResultSet rs = ps.executeQuery();

            // Clear current cache maps before populating
            for (String key : dataConfig.getKeys(false)) {
                dataConfig.set(key, null);
            }

            while (rs.next()) {
                String type = rs.getString("type");
                String target = rs.getString("target");
                String admin = rs.getString("admin");
                String reason = rs.getString("reason");
                long expiry = rs.getLong("expiry");
                String server = rs.getString("server");
                String exempt = rs.getString("exempt");

                if (expiry > 0 && System.currentTimeMillis() > expiry) {
                    continue; // Skip expired
                }

                String path = getPath(type, target);
                dataConfig.set(path + ".reason", reason);
                dataConfig.set(path + ".admin", admin);
                dataConfig.set(path + ".expiry", expiry);
                dataConfig.set(path + ".server", server);
                dataConfig.set(path + ".exempt", exempt == null ? "" : exempt);

                // Enforce live kicks if a new ban synced
                if (type.equals("BAN")) {
                    Player p = Bukkit.getPlayer(target);
                    if (p != null && p.isOnline()) {
                        String timeStr = (expiry == -1) ? "Permanent" : formatDate(expiry);
                        plugin.safeKick(p, msg.getBannedLayout(reason, admin, timeStr, server));
                    }
                } else if (type.equals("IPBAN")) {
                    String[] exempts = exempt != null ? exempt.toLowerCase().split(",") : new String[0];
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.getAddress() != null && p.getAddress().getAddress() != null && p.getAddress().getAddress().getHostAddress().equals(target)) {
                            boolean isExempt = false;
                            for (String ex : exempts) {
                                if (ex.equals(p.getName().toLowerCase())) {
                                    isExempt = true;
                                    break;
                                }
                            }
                            if (!isExempt) {
                                String timeStr = (expiry == -1) ? "Permanent" : formatDate(expiry);
                                plugin.safeKick(p, msg.getBannedLayout(reason, admin, timeStr, server));
                            }
                        }
                    }
                }
            }
            saveLocal();
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to sync from database: " + e.getMessage());
        }
    }

    private void pushToDatabase(String type, String target, String admin, String reason, long expiry, String server, String exempt) {
        if (!useMySQL) return;

        // Upsert statement (INSERT OR UPDATE)
        String sql = "INSERT INTO " + db.getTable() + " (type, target, admin, reason, expiry, server, exempt) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE admin=VALUES(admin), reason=VALUES(reason), expiry=VALUES(expiry), server=VALUES(server), exempt=VALUES(exempt)";

        dbExecutor.execute(() -> {
            try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, type);
                ps.setString(2, target);
                ps.setString(3, admin);
                ps.setString(4, reason);
                ps.setLong(5, expiry);
                ps.setString(6, server);
                ps.setString(7, exempt == null ? "" : exempt);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to save to database: " + e.getMessage());
            }
        });
    }

    private void removeFromDatabase(String type, String target, String server) {
        if (!useMySQL) return;

        String sql;
        if (server.equalsIgnoreCase("all")) {
            sql = "DELETE FROM " + db.getTable() + " WHERE type = ? AND target = ?";
        } else {
            // FIXED: Removed "OR server = 'all'". We only want to delete the exact server match
            // to avoid wiping out global bans when an admin types a specific server unban.
            sql = "DELETE FROM " + db.getTable() + " WHERE type = ? AND target = ? AND server = ?";
        }

        dbExecutor.execute(() -> {
            try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, type);
                ps.setString(2, target);
                if (!server.equalsIgnoreCase("all")) {
                    ps.setString(3, server);
                }
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to delete from database: " + e.getMessage());
            }
        });
    }

    // --- PUNISHMENT LOGIC ---

    private String getPath(String type, String target) {
        String safeTarget = target.replace(".", "_");
        switch (type) {
            case "BAN": return "bans." + safeTarget;
            case "IPBAN": return "ipbans." + safeTarget;
            case "MUTE": return "mutes." + safeTarget;
            case "IPMUTE": return "ipmutes." + safeTarget;
            case "DONOTBREAK": return "nobraking." + safeTarget;
            default: return "unknown." + safeTarget;
        }
    }

    private void applyPunishment(String type, String target, String admin, String reason, long durationMillis, String server, String exempt) {
        long expiry = (durationMillis == -1) ? -1 : System.currentTimeMillis() + durationMillis;
        String path = getPath(type, target);

        dataConfig.set(path + ".reason", reason);
        dataConfig.set(path + ".admin", admin);
        dataConfig.set(path + ".expiry", expiry);
        dataConfig.set(path + ".server", server);
        dataConfig.set(path + ".exempt", exempt == null ? "" : exempt);
        saveLocal();

        pushToDatabase(type, target, admin, reason, expiry, server, exempt);
    }

    private void removePunishment(String type, String target, String server) {
        String path = getPath(type, target);

        // Always try to remove from database first to ensure multi-server consistency
        removeFromDatabase(type, target, server);

        // Update local cache if applicable
        if (dataConfig.contains(path)) {
            String punishmentServer = getPunishmentServer(type, target);
            // FIXED: Removed punishmentServer.equalsIgnoreCase("all").
            // Now, if you unban on 'main', it won't clear the local cache if the ban is global ('all').
            if (server.equalsIgnoreCase("all") || punishmentServer.equalsIgnoreCase(server)) {
                dataConfig.set(path, null);
                saveLocal();
            }
        }
    }

    public void banPlayer(String target, String admin, String reason, long durationMillis, String server) {
        applyPunishment("BAN", target, admin, reason, durationMillis, server, "");
    }

    public void banIp(String ip, String admin, String reason, long durationMillis, String server, String exempt) {
        applyPunishment("IPBAN", ip, admin, reason, durationMillis, server, exempt);
    }

    public void mutePlayer(String target, String admin, String reason, long durationMillis, String server) {
        applyPunishment("MUTE", target, admin, reason, durationMillis, server, "");
    }

    public void muteIp(String ip, String admin, String reason, long durationMillis, String server, String exempt) {
        applyPunishment("IPMUTE", ip, admin, reason, durationMillis, server, exempt);
    }

    public void banBlockBreaking(String target, String admin, String reason, String server) {
        applyPunishment("DONOTBREAK", target, admin, reason, -1, server, "");
    }

    // Server-aware unban methods
    public void unban(String target, String server) { removePunishment("BAN", target, server); }
    public void unbanIp(String ip, String server) { removePunishment("IPBAN", ip, server); }
    public void unmute(String target, String server) { removePunishment("MUTE", target, server); }
    public void unmuteIp(String ip, String server) { removePunishment("IPMUTE", ip, server); }
    public void unbanBlockBreaking(String target, String server) { removePunishment("DONOTBREAK", target, server); }

    // Fallbacks for unbanning on all servers
    public void unban(String target) { unban(target, "all"); }
    public void unbanIp(String ip) { unbanIp(ip, "all"); }
    public void unmute(String target) { unmute(target, "all"); }
    public void unmuteIp(String ip) { unmuteIp(ip, "all"); }
    public void unbanBlockBreaking(String target) { unbanBlockBreaking(target, "all"); }

    // --- CHECKERS & GETTERS ---

    public boolean isBanned(String name) { return dataConfig.contains("bans." + name); }
    public boolean isIpBanned(String ip) { return dataConfig.contains("ipbans." + ip.replace(".", "_")); }
    public boolean isMuted(String name) { return dataConfig.contains("mutes." + name); }
    public boolean isIpMuted(String ip) { return dataConfig.contains("ipmutes." + ip.replace(".", "_")); }
    public boolean isBlockBreakingBanned(String name) { return dataConfig.contains("nobraking." + name); }

    public long getBanExpiry(String name) { return dataConfig.getLong(getPath("BAN", name) + ".expiry"); }
    public String getBanReason(String name) { return dataConfig.getString(getPath("BAN", name) + ".reason"); }
    public String getBanAdmin(String name) { return dataConfig.getString(getPath("BAN", name) + ".admin"); }

    public long getIpBanExpiry(String ip) { return dataConfig.getLong(getPath("IPBAN", ip) + ".expiry"); }
    public String getIpBanReason(String ip) { return dataConfig.getString(getPath("IPBAN", ip) + ".reason"); }
    public String getIpBanAdmin(String ip) { return dataConfig.getString(getPath("IPBAN", ip) + ".admin"); }

    public long getMuteExpiry(String name) { return dataConfig.getLong(getPath("MUTE", name) + ".expiry"); }
    public String getMuteReason(String name) { return dataConfig.getString(getPath("MUTE", name) + ".reason"); }

    public long getIpMuteExpiry(String ip) { return dataConfig.getLong(getPath("IPMUTE", ip) + ".expiry"); }
    public String getIpMuteReason(String ip) { return dataConfig.getString(getPath("IPMUTE", ip) + ".reason"); }

    public String getBlockBreakingReason(String name) { return dataConfig.getString(getPath("DONOTBREAK", name) + ".reason"); }

    public String getPunishmentServer(String type, String target) {
        return dataConfig.getString(getPath(type, target) + ".server", localServerName);
    }

    public String getPunishmentExempt(String type, String target) {
        return dataConfig.getString(getPath(type, target) + ".exempt", "");
    }

    // --- UTILS ---

    public String formatDate(long expiryMillis) {
        return new Date(expiryMillis).toString();
    }

    public long parseDuration(String amountStr, String unitStr) {
        try {
            long amount = Long.parseLong(amountStr);
            switch (unitStr.toLowerCase()) {
                case "s": return amount * 1000;
                case "m": return amount * 60 * 1000;
                case "h": return amount * 60 * 60 * 1000;
                case "d": return amount * 24 * 60 * 60 * 1000;
                case "w": return amount * 7 * 24 * 60 * 60 * 1000;
                default: return 0;
            }
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
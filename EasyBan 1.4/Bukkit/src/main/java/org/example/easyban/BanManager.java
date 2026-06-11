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
import java.util.UUID;
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
    }

    // --- SYNC ENGINE ---

    private void syncFromDatabase() {
        String sql = "SELECT * FROM " + db.getTable() + " WHERE server = ? OR server = 'all'";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, localServerName);
            ResultSet rs = ps.executeQuery();

            // Clear current cache maps before populating to ensure expired ones are removed
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

                if (expiry > 0 && System.currentTimeMillis() > expiry) {
                    continue; // Skip expired
                }

                String path = getPath(type, target);
                dataConfig.set(path + ".reason", reason);
                dataConfig.set(path + ".admin", admin);
                dataConfig.set(path + ".expiry", expiry);
                dataConfig.set(path + ".server", server);

                // Enforce live kicks if a new ban synced
                if (type.equals("BAN")) {
                    Player p = Bukkit.getPlayer(target);
                    if (p != null && p.isOnline()) {
                        String timeStr = (expiry == -1) ? "Permanent" : formatDate(expiry);
                        plugin.safeKick(p, msg.getBannedLayout(reason, admin, timeStr, server));
                    }
                } else if (type.equals("IPBAN")) {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.getAddress() != null && p.getAddress().getAddress().getHostAddress().equals(target)) {
                            String timeStr = (expiry == -1) ? "Permanent" : formatDate(expiry);
                            plugin.safeKick(p, msg.getBannedLayout(reason, admin, timeStr, server));
                        }
                    }
                }
            }
            saveLocal();
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to sync from database: " + e.getMessage());
        }
    }

    private void pushToDatabase(String type, String target, String admin, String reason, long expiry, String server) {
        if (!useMySQL) return;

        // Upsert statement (INSERT OR UPDATE)
        String sql = "INSERT INTO " + db.getTable() + " (type, target, admin, reason, expiry, server) " +
                "VALUES (?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE admin=VALUES(admin), reason=VALUES(reason), expiry=VALUES(expiry), server=VALUES(server)";

        // Run async
        Executors.newSingleThreadExecutor().execute(() -> {
            try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, type);
                ps.setString(2, target);
                ps.setString(3, admin);
                ps.setString(4, reason);
                ps.setLong(5, expiry);
                ps.setString(6, server);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to save to database: " + e.getMessage());
            }
        });
    }

    private void removeFromDatabase(String type, String target) {
        if (!useMySQL) return;
        String sql = "DELETE FROM " + db.getTable() + " WHERE type = ? AND target = ?";
        Executors.newSingleThreadExecutor().execute(() -> {
            try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, type);
                ps.setString(2, target);
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

    private void applyPunishment(String type, String target, String admin, String reason, long durationMillis, String server) {
        long expiry = (durationMillis == -1) ? -1 : System.currentTimeMillis() + durationMillis;
        String path = getPath(type, target);

        dataConfig.set(path + ".reason", reason);
        dataConfig.set(path + ".admin", admin);
        dataConfig.set(path + ".expiry", expiry);
        dataConfig.set(path + ".server", server);
        saveLocal();

        pushToDatabase(type, target, admin, reason, expiry, server);
    }

    private void removePunishment(String type, String target) {
        dataConfig.set(getPath(type, target), null);
        saveLocal();
        removeFromDatabase(type, target);
    }

    public void banPlayer(String target, String admin, String reason, long durationMillis, String server) {
        applyPunishment("BAN", target, admin, reason, durationMillis, server);
    }

    public void banIp(String ip, String admin, String reason, long durationMillis, String server) {
        applyPunishment("IPBAN", ip, admin, reason, durationMillis, server);
    }

    public void mutePlayer(String target, String admin, String reason, long durationMillis, String server) {
        applyPunishment("MUTE", target, admin, reason, durationMillis, server);
    }

    public void muteIp(String ip, String admin, String reason, long durationMillis, String server) {
        applyPunishment("IPMUTE", ip, admin, reason, durationMillis, server);
    }

    public void banBlockBreaking(String target, String admin, String reason, String server) {
        applyPunishment("DONOTBREAK", target, admin, reason, -1, server);
    }

    public void unban(String target) { removePunishment("BAN", target); }
    public void unbanIp(String ip) { removePunishment("IPBAN", ip); }
    public void unmute(String target) { removePunishment("MUTE", target); }
    public void unmuteIp(String ip) { removePunishment("IPMUTE", ip); }
    public void unbanBlockBreaking(String target) { removePunishment("DONOTBREAK", target); }


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
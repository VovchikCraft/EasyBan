package org.example.easyban;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Storage + punishment logic of EasyBan.
 *
 * <p>1.7.1 rewrite of the storage engine:</p>
 * <ul>
 *   <li>ALL database work (sync, insert, delete) runs through ONE single-threaded
 *       queue. Previously the sync ran on its own thread and could re-read a row
 *       before the pending DELETE was executed, resurrecting removed punishments.</li>
 *   <li>Local changes that the database has not confirmed yet are kept in a pending
 *       journal and are re-applied on top of every sync result.</li>
 *   <li>The YAML cache is guarded by a lock; a failed sync never wipes it.</li>
 *   <li>Removals report REMOVED / NOT_FOUND / CANCELLED instead of always "success".</li>
 *   <li>Removing on server X also removes an "all"-scoped punishment.</li>
 *   <li>Player names are case-insensitive; expired entries are cleaned up.</li>
 * </ul>
 */
public class BanManager {

    /** Result of a removal request. */
    public enum RemovalResult {
        /** The punishment existed and was removed. */
        REMOVED,
        /** There was no matching punishment - nothing was changed. */
        NOT_FOUND,
        /** An API listener cancelled the UnpunishEvent. */
        CANCELLED
    }

    private record PendingWrite(String type, String target, String admin, String reason,
                                long expiry, String server, String exempt) {
    }

    private record PendingRemoval(String type, String target, String server) {
    }

    private static final String[] TYPES = {"BAN", "IPBAN", "MUTE", "IPMUTE", "DONOTBREAK"};
    private static final String[] SECTIONS = {"bans", "ipbans", "mutes", "ipmutes", "nobraking"};
    private static final Pattern DURATION_PART = Pattern.compile("(\\d+)([smhdw])");
    private static final long MAX_DURATION = 3650L * 24L * 60L * 60L * 1000L; // 10 years

    private final EasyBan plugin;
    private final DatabaseManager db;
    private final MessageManager msg;

    private final Object dataLock = new Object();
    private final Map<String, PendingWrite> pendingWrites = new HashMap<>();
    private final Map<String, PendingRemoval> pendingRemovals = new HashMap<>();

    private File dataFile;
    private FileConfiguration dataConfig;

    private volatile boolean useMySQL;
    private volatile String localServerName;
    private volatile int syncInterval;
    private volatile boolean removeExpired;

    private ScheduledExecutorService dbExecutor;
    private ScheduledFuture<?> syncTask;
    private ScheduledFuture<?> cleanupTask;

    public BanManager(EasyBan plugin, DatabaseManager db, MessageManager msg) {
        this.plugin = plugin;
        this.db = db;
        this.msg = msg;
        readSettings();
        setupLocalFile();
        startTasks();
    }

    private void readSettings() {
        this.useMySQL = plugin.getConfig().getString("storage.type", "local").equalsIgnoreCase("mysql");
        String name = plugin.getConfig().getString("server-name", "survival");
        this.localServerName = (name == null || name.trim().isEmpty()) ? "survival" : name.trim();
        this.syncInterval = Math.max(1, plugin.getConfig().getInt("storage.sync-interval", 4));
        this.removeExpired = plugin.getConfig().getBoolean("storage.remove-expired", true);
    }

    private void setupLocalFile() {
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        dataFile = new File(plugin.getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().warning("Could not create data.yml: " + e.getMessage());
            }
        }
        synchronized (dataLock) {
            dataConfig = YamlConfiguration.loadConfiguration(dataFile);
            for (String section : SECTIONS) {
                if (!dataConfig.contains(section)) dataConfig.createSection(section);
            }
            normalizeLegacyKeys();
            saveLocal();
        }
    }

    /**
     * One-time migration: player names used to be stored with the exact case the
     * admin typed, so "steve" did not match "Steve". Everything is lowercase now.
     * No entry is lost - duplicates are merged, keeping the longer punishment.
     */
    private void normalizeLegacyKeys() {
        boolean changed = false;
        for (String sectionName : new String[]{"bans", "mutes", "nobraking"}) {
            ConfigurationSection section = dataConfig.getConfigurationSection(sectionName);
            if (section == null) continue;
            for (String key : new ArrayList<>(section.getKeys(false))) {
                String lower = key.toLowerCase(Locale.ROOT);
                if (lower.equals(key)) continue;

                ConfigurationSection old = section.getConfigurationSection(key);
                if (old == null) {
                    section.set(key, null);
                    changed = true;
                    continue;
                }
                ConfigurationSection existing = section.getConfigurationSection(lower);
                boolean keepOld = existing == null
                        || old.getLong("expiry", -1) == -1
                        || (existing.getLong("expiry", -1) != -1
                        && old.getLong("expiry", -1) > existing.getLong("expiry", -1));
                if (keepOld) {
                    for (String field : old.getKeys(false)) {
                        section.set(lower + "." + field, old.get(field));
                    }
                }
                section.set(key, null);
                changed = true;
            }
        }
        if (changed) {
            plugin.getLogger().info("Normalized player names in data.yml to lowercase.");
        }
    }

    /** Caller must hold dataLock. */
    private void saveLocal() {
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save data.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ //
    //  Task / lifecycle management                                        //
    // ------------------------------------------------------------------ //

    private void startTasks() {
        stopTasks(2);
        dbExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "EasyBan-Storage");
            t.setDaemon(true);
            return t;
        });
        if (useMySQL) {
            syncTask = dbExecutor.scheduleAtFixedRate(this::safeSync, 1L, syncInterval, TimeUnit.SECONDS);
        }
        cleanupTask = dbExecutor.scheduleAtFixedRate(this::safePurgeExpired, 30L, 30L, TimeUnit.SECONDS);
    }

    private void stopTasks(int awaitSeconds) {
        if (syncTask != null) {
            syncTask.cancel(false);
            syncTask = null;
        }
        if (cleanupTask != null) {
            cleanupTask.cancel(false);
            cleanupTask = null;
        }
        if (dbExecutor != null && !dbExecutor.isShutdown()) {
            dbExecutor.shutdown();
            try {
                if (!dbExecutor.awaitTermination(awaitSeconds, TimeUnit.SECONDS)) {
                    dbExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                dbExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void execute(Runnable task) {
        ScheduledExecutorService exec = dbExecutor;
        if (exec == null || exec.isShutdown()) return;
        try {
            exec.execute(task);
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not queue a storage task: " + t.getMessage());
        }
    }

    public void shutdown() {
        stopTasks(5);
        synchronized (dataLock) {
            if (dataConfig != null) saveLocal();
        }
    }

    /** Re-reads storage settings (used by /eb reload) without losing any data. */
    public void reload() {
        readSettings();
        db.reload();
        startTasks();
    }

    // ------------------------------------------------------------------ //
    //  SYNC ENGINE                                                        //
    // ------------------------------------------------------------------ //

    private void safeSync() {
        try {
            syncFromDatabase();
        } catch (Throwable t) {
            // scheduleAtFixedRate silently cancels the task on any exception.
            plugin.getLogger().warning("Punishment sync failed: " + t);
        }
    }

    private void safePurgeExpired() {
        try {
            purgeExpired();
        } catch (Throwable t) {
            plugin.getLogger().warning("Expiry cleanup failed: " + t);
        }
    }

    private void syncFromDatabase() {
        if (!useMySQL) return;
        String table = db.getTable();
        if (table == null) return;

        YamlConfiguration fresh = new YamlConfiguration();
        for (String section : SECTIONS) fresh.createSection(section);

        long now = System.currentTimeMillis();
        String sql = "SELECT type, target, admin, reason, expiry, server, exempt FROM " + table
                + " WHERE server = ? OR server = 'all'";

        try (Connection conn = db.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, localServerName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String rawType = rs.getString("type");
                        String target = rs.getString("target");
                        if (rawType == null || target == null || target.isEmpty()) continue;
                        String type = rawType.toUpperCase(Locale.ROOT);
                        long expiry = rs.getLong("expiry");
                        if (expiry > 0 && now > expiry) continue;

                        String path = getPath(type, target);
                        fresh.set(path + ".reason", rs.getString("reason"));
                        fresh.set(path + ".admin", rs.getString("admin"));
                        fresh.set(path + ".expiry", expiry);
                        fresh.set(path + ".server", rs.getString("server"));
                        String exempt = rs.getString("exempt");
                        fresh.set(path + ".exempt", exempt == null ? "" : exempt);
                    }
                }
            }
            if (removeExpired) {
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM " + table + " WHERE expiry > 0 AND expiry < ?")) {
                    del.setLong(1, now);
                    del.executeUpdate();
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to sync from database: " + e.getMessage());
            return; // keep the current cache instead of wiping everything
        }

        List<PendingWrite> retry = new ArrayList<>();
        synchronized (dataLock) {
            // Local changes the database has not confirmed yet must survive the sync.
            for (PendingWrite w : pendingWrites.values()) {
                String path = getPath(w.type(), w.target());
                fresh.set(path + ".reason", w.reason());
                fresh.set(path + ".admin", w.admin());
                fresh.set(path + ".expiry", w.expiry());
                fresh.set(path + ".server", w.server());
                fresh.set(path + ".exempt", w.exempt());
                retry.add(w);
            }
            for (String path : pendingRemovals.keySet()) {
                fresh.set(path, null);
            }
            dataConfig = fresh;
            saveLocal();
        }

        // Retry writes that failed earlier (e.g. the database was offline).
        for (PendingWrite w : retry) {
            pushToDatabase(w.type(), w.target(), w.admin(), w.reason(), w.expiry(), w.server(), w.exempt());
        }

        // Enforcement touches Bukkit API -> always on the main / global thread.
        SchedulerCompat.runSync(plugin, this::enforceOnlinePlayers);
    }

    /** Kicks online players that are (now) banned. Runs on the main/global thread. */
    public void enforceOnlinePlayers() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            String name = p.getName();
            if (isBanned(name) && !isExpired("BAN", name)) {
                plugin.safeKick(p, msg.getBannedLayoutComponent(
                        getBanReason(name), getBanAdmin(name),
                        expiryLabel(getBanExpiry(name)), getPunishmentServer("BAN", name)));
                continue;
            }
            String ip = getPlayerIp(p);
            if (ip != null && isIpBanned(ip) && !isExpired("IPBAN", ip) && !isExempt("IPBAN", ip, name)) {
                plugin.safeKick(p, msg.getBannedLayoutComponent(
                        getIpBanReason(ip), getIpBanAdmin(ip),
                        expiryLabel(getIpBanExpiry(ip)), getPunishmentServer("IPBAN", ip)));
            }
        }
    }

    private String getPlayerIp(Player p) {
        if (p.getAddress() == null || p.getAddress().getAddress() == null) return null;
        return p.getAddress().getAddress().getHostAddress();
    }

    private String expiryLabel(long expiry) {
        return (expiry == -1) ? msg.getRaw("status.permanent") : formatDate(expiry);
    }

    private void pushToDatabase(String type, String target, String admin, String reason,
                                long expiry, String server, String exempt) {
        if (!useMySQL) return;
        final String path = getPath(type, target);
        final PendingWrite write = new PendingWrite(type, target, admin, reason, expiry, server,
                exempt == null ? "" : exempt);
        synchronized (dataLock) {
            pendingWrites.put(path, write);
            pendingRemovals.remove(path);
        }
        execute(() -> {
            String sql = "INSERT INTO " + db.getTable()
                    + " (type, target, admin, reason, expiry, server, exempt) VALUES (?, ?, ?, ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE admin=VALUES(admin), reason=VALUES(reason), "
                    + "expiry=VALUES(expiry), server=VALUES(server), exempt=VALUES(exempt)";
            try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, type);
                ps.setString(2, target);
                ps.setString(3, admin);
                ps.setString(4, reason);
                ps.setLong(5, expiry);
                ps.setString(6, server);
                ps.setString(7, write.exempt());
                ps.executeUpdate();
                synchronized (dataLock) {
                    pendingWrites.remove(path);
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to save to database: " + e.getMessage());
            }
        });
    }

    private void removeFromDatabase(String type, String target, String server) {
        queueRemoval(type, target, server, null);
    }

    /**
     * Queues a DELETE. When a callback is given it receives the number of deleted
     * rows on the main thread, which is what makes an accurate "not found" answer
     * possible for punishments that only exist on another server.
     */
    private void queueRemoval(String type, String target, String server, Consumer<Integer> callback) {
        if (!useMySQL) {
            if (callback != null) callback.accept(0);
            return;
        }
        final String path = getPath(type, target);
        synchronized (dataLock) {
            pendingWrites.remove(path);
            pendingRemovals.put(path, new PendingRemoval(type, target, server));
        }
        execute(() -> {
            int rows = 0;
            try {
                rows = deleteRows(type, target, server);
                synchronized (dataLock) {
                    pendingRemovals.remove(path);
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to delete from database: " + e.getMessage());
            }
            if (callback != null) {
                final int result = rows;
                SchedulerCompat.runSync(plugin, () -> callback.accept(result));
            }
        });
    }

    /**
     * The actual DELETE.
     *
     * FIX: removing on server X now also removes an "all"-scoped punishment.
     * The old query only matched server = X, so a global ban was never deleted
     * even though the plugin printed "unbanned successfully".
     */
    private int deleteRows(String type, String target, String server) throws SQLException {
        boolean all = server.equalsIgnoreCase("all");
        String sql = "DELETE FROM " + db.getTable() + " WHERE type = ? AND LOWER(target) = LOWER(?)"
                + (all ? "" : " AND (server = ? OR server = 'all')");
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, type);
            ps.setString(2, target);
            if (!all) ps.setString(3, server);
            return ps.executeUpdate();
        }
    }

    /** Deletes exactly one scope - used by the expiry cleanup. */
    private void queueExactDelete(String type, String target, String server) {
        if (!useMySQL) return;
        execute(() -> {
            String sql = "DELETE FROM " + db.getTable()
                    + " WHERE type = ? AND LOWER(target) = LOWER(?) AND server = ? AND expiry > 0 AND expiry < ?";
            try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, type);
                ps.setString(2, target);
                ps.setString(3, server);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to clean up an expired punishment: " + e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------ //
    //  PUNISHMENT LOGIC                                                   //
    // ------------------------------------------------------------------ //

    private boolean isIpType(String type) {
        return "IPBAN".equals(type) || "IPMUTE".equals(type);
    }

    private String normalizeTarget(String type, String target) {
        if (target == null) return "";
        String t = target.trim();
        return isIpType(type) ? t : t.toLowerCase(Locale.ROOT);
    }

    private String normalizeServer(String server) {
        if (server == null || server.trim().isEmpty()) return localServerName;
        return server.trim();
    }

    private String getPath(String type, String target) {
        String safeTarget = normalizeTarget(type, target).replace(".", "_");
        switch (type) {
            case "BAN": return "bans." + safeTarget;
            case "IPBAN": return "ipbans." + safeTarget;
            case "MUTE": return "mutes." + safeTarget;
            case "IPMUTE": return "ipmutes." + safeTarget;
            case "DONOTBREAK": return "nobraking." + safeTarget;
            default: return "unknown." + safeTarget;
        }
    }

    private String sectionName(String type) {
        switch (type) {
            case "BAN": return "bans";
            case "IPBAN": return "ipbans";
            case "MUTE": return "mutes";
            case "IPMUTE": return "ipmutes";
            case "DONOTBREAK": return "nobraking";
            default: return "unknown";
        }
    }

    private String restoreTarget(String type, String key) {
        return isIpType(type) ? key.replace("_", ".") : key;
    }

    /**
     * Applies a punishment locally and (if configured) to the MySQL database.
     * Fires the API PunishEvent first and announces it to Discord afterwards.
     *
     * @return true if the punishment was applied, false if an API event cancelled it.
     */
    private boolean applyPunishment(String type, String target, String admin, String reason,
                                    long durationMillis, String server, String exempt) {
        final String t = normalizeTarget(type, target);
        final String s = normalizeServer(server);
        if (t.isEmpty()) return false;

        if (!plugin.firePunishEvent(type, t, admin, reason, durationMillis, s)) {
            return false;
        }

        long expiry = (durationMillis == -1) ? -1 : System.currentTimeMillis() + durationMillis;
        String path = getPath(type, t);
        String safeExempt = (exempt == null) ? "" : exempt;

        synchronized (dataLock) {
            dataConfig.set(path + ".reason", reason);
            dataConfig.set(path + ".admin", admin);
            dataConfig.set(path + ".expiry", expiry);
            dataConfig.set(path + ".server", s);
            dataConfig.set(path + ".exempt", safeExempt);
            saveLocal();
        }

        pushToDatabase(type, t, admin, reason, expiry, s, safeExempt);
        plugin.getDiscordManager().sendPunishment(type, t, admin, reason, expiry, s, safeExempt);
        return true;
    }

    /**
     * Removes a punishment and reports what really happened.
     *
     * <p>The callback is invoked synchronously whenever the answer is already known
     * (local storage, or a punishment present in this server's cache). Only the
     * "it might exist on another server" case waits for the database.</p>
     */
    public void removePunishmentAsync(String type, String target, String server, Consumer<RemovalResult> callback) {
        final String t = normalizeTarget(type, target);
        final String s = normalizeServer(server);
        final String path = getPath(type, t);

        boolean exists;
        String storedServer;
        String admin;
        synchronized (dataLock) {
            exists = dataConfig.contains(path);
            storedServer = dataConfig.getString(path + ".server", localServerName);
            admin = dataConfig.getString(path + ".admin", "Unknown");
        }

        // A punishment scoped to "all" also applies to (and is removable from) any server.
        boolean localMatch = exists
                && (s.equalsIgnoreCase("all") || storedServer.equalsIgnoreCase(s) || storedServer.equalsIgnoreCase("all"));

        if (!useMySQL && !localMatch) {
            callback.accept(RemovalResult.NOT_FOUND);
            return;
        }
        if (!plugin.fireUnpunishEvent(type, t, s)) {
            callback.accept(RemovalResult.CANCELLED);
            return;
        }

        if (localMatch) {
            synchronized (dataLock) {
                dataConfig.set(path, null);
                pendingWrites.remove(path);
                saveLocal();
            }
        }

        if (!useMySQL) {
            plugin.getDiscordManager().sendRemoval(type, t, admin, s);
            callback.accept(RemovalResult.REMOVED);
            return;
        }

        if (localMatch) {
            queueRemoval(type, t, s, null);
            plugin.getDiscordManager().sendRemoval(type, t, admin, s);
            callback.accept(RemovalResult.REMOVED);
            return;
        }

        // Not in this server's cache: it may still exist on another server.
        queueRemoval(type, t, s, rows -> {
            if (rows != null && rows > 0) {
                plugin.getDiscordManager().sendRemoval(type, t, admin, s);
                callback.accept(RemovalResult.REMOVED);
            } else {
                callback.accept(RemovalResult.NOT_FOUND);
            }
        });
    }

    /**
     * Synchronous removal kept for the public API.
     *
     * @return true if a punishment was removed, false if none was found or an API
     *         listener cancelled it. (A removal that only exists on a remote server
     *         is still executed in the background; use
     *         {@link #removePunishmentAsync} when you need the exact result.)
     */
    private boolean removePunishment(String type, String target, String server) {
        boolean[] result = {false};
        removePunishmentAsync(type, target, server, r -> result[0] = (r == RemovalResult.REMOVED));
        return result[0];
    }

    private void addExempt(String type, String ip, String playerName, String server) {
        String path = getPath(type, ip);
        String admin;
        String reason;
        long expiry;
        String pServer;
        String newExempt;

        synchronized (dataLock) {
            if (!dataConfig.contains(path)) return;
            String currentExempt = dataConfig.getString(path + ".exempt", "");
            List<String> list = new ArrayList<>();
            if (currentExempt != null && !currentExempt.trim().isEmpty()) {
                for (String s : currentExempt.split(",")) {
                    if (!s.trim().isEmpty()) list.add(s.trim());
                }
            }
            for (String s : list) {
                if (s.equalsIgnoreCase(playerName)) return; // already exempt
            }
            list.add(playerName);
            newExempt = String.join(",", list);
            dataConfig.set(path + ".exempt", newExempt);
            saveLocal();

            admin = dataConfig.getString(path + ".admin", "Console");
            reason = dataConfig.getString(path + ".reason", "");
            expiry = dataConfig.getLong(path + ".expiry", -1);
            pServer = dataConfig.getString(path + ".server", normalizeServer(server));
        }
        pushToDatabase(type, normalizeTarget(type, ip), admin, reason, expiry, pServer, newExempt);
    }

    public void addExemptToIpBan(String ip, String playerName, String server) {
        addExempt("IPBAN", ip, playerName, server);
    }

    public void addExemptToIpMute(String ip, String playerName, String server) {
        addExempt("IPMUTE", ip, playerName, server);
    }

    public boolean banPlayer(String target, String admin, String reason, long durationMillis, String server) {
        return applyPunishment("BAN", target, admin, reason, durationMillis, server, "");
    }

    public boolean banIp(String ip, String admin, String reason, long durationMillis, String server, String exempt) {
        return applyPunishment("IPBAN", ip, admin, reason, durationMillis, server, exempt);
    }

    public boolean mutePlayer(String target, String admin, String reason, long durationMillis, String server) {
        return applyPunishment("MUTE", target, admin, reason, durationMillis, server, "");
    }

    public boolean muteIp(String ip, String admin, String reason, long durationMillis, String server, String exempt) {
        return applyPunishment("IPMUTE", ip, admin, reason, durationMillis, server, exempt);
    }

    public boolean banBlockBreaking(String target, String admin, String reason, String server) {
        return applyPunishment("DONOTBREAK", target, admin, reason, -1, server, "");
    }

    public boolean unban(String target, String server) { return removePunishment("BAN", target, server); }
    public boolean unbanIp(String ip, String server) { return removePunishment("IPBAN", ip, server); }
    public boolean unmute(String target, String server) { return removePunishment("MUTE", target, server); }
    public boolean unmuteIp(String ip, String server) { return removePunishment("IPMUTE", ip, server); }
    public boolean unbanBlockBreaking(String target, String server) { return removePunishment("DONOTBREAK", target, server); }

    public boolean unban(String target) { return unban(target, "all"); }
    public boolean unbanIp(String ip) { return unbanIp(ip, "all"); }
    public boolean unmute(String target) { return unmute(target, "all"); }
    public boolean unmuteIp(String ip) { return unmuteIp(ip, "all"); }
    public boolean unbanBlockBreaking(String target) { return unbanBlockBreaking(target, "all"); }

    /** Result-aware variants used by the commands. */
    public void unbanAsync(String target, String server, Consumer<RemovalResult> cb) { removePunishmentAsync("BAN", target, server, cb); }
    public void unbanIpAsync(String ip, String server, Consumer<RemovalResult> cb) { removePunishmentAsync("IPBAN", ip, server, cb); }
    public void unmuteAsync(String target, String server, Consumer<RemovalResult> cb) { removePunishmentAsync("MUTE", target, server, cb); }
    public void unmuteIpAsync(String ip, String server, Consumer<RemovalResult> cb) { removePunishmentAsync("IPMUTE", ip, server, cb); }
    public void unbanBlockBreakingAsync(String target, String server, Consumer<RemovalResult> cb) { removePunishmentAsync("DONOTBREAK", target, server, cb); }

    // ------------------------------------------------------------------ //
    //  EXPIRY                                                             //
    // ------------------------------------------------------------------ //

    public boolean isExpired(String type, String target) {
        long expiry = getPunishmentExpiry(type, target);
        return expiry > 0 && System.currentTimeMillis() > expiry;
    }

    /**
     * Removes a punishment whose time has run out. Natural expiry is not an admin
     * action, so no cancellable UnpunishEvent is fired here.
     *
     * @return true when something was actually removed.
     */
    public boolean purgeIfExpired(String type, String target) {
        final String t = normalizeTarget(type, target);
        final String path = getPath(type, t);
        String admin;
        String server;
        synchronized (dataLock) {
            if (!dataConfig.contains(path)) return false;
            long expiry = dataConfig.getLong(path + ".expiry", -1);
            if (!(expiry > 0 && System.currentTimeMillis() > expiry)) return false;
            admin = dataConfig.getString(path + ".admin", "Unknown");
            server = dataConfig.getString(path + ".server", localServerName);
            dataConfig.set(path, null);
            pendingWrites.remove(path);
            saveLocal();
        }
        if (useMySQL && removeExpired) {
            queueExactDelete(type, t, server);
        }
        plugin.getDiscordManager().sendRemoval(type, t, admin, server);
        return true;
    }

    private void purgeExpired() {
        if (!removeExpired) return;
        long now = System.currentTimeMillis();
        List<String[]> expired = new ArrayList<>();
        synchronized (dataLock) {
            for (String type : TYPES) {
                ConfigurationSection section = dataConfig.getConfigurationSection(sectionName(type));
                if (section == null) continue;
                for (String key : section.getKeys(false)) {
                    long expiry = section.getLong(key + ".expiry", -1);
                    if (expiry > 0 && now > expiry) {
                        expired.add(new String[]{type, restoreTarget(type, key)});
                    }
                }
            }
        }
        for (String[] entry : expired) {
            purgeIfExpired(entry[0], entry[1]);
        }
    }

    // ------------------------------------------------------------------ //
    //  CHECKERS & GETTERS                                                 //
    // ------------------------------------------------------------------ //

    public boolean isBanned(String name) { return hasPunishment("BAN", name); }
    public boolean isIpBanned(String ip) { return hasPunishment("IPBAN", ip); }
    public boolean isMuted(String name) { return hasPunishment("MUTE", name); }
    public boolean isIpMuted(String ip) { return hasPunishment("IPMUTE", ip); }
    public boolean isBlockBreakingBanned(String name) { return hasPunishment("DONOTBREAK", name); }

    /** Generic existence check used by the API for any punishment type. */
    public boolean hasPunishment(String type, String target) {
        synchronized (dataLock) {
            return dataConfig.contains(getPath(type, target));
        }
    }

    /** True when the player is on the exempt list of an IP punishment. */
    public boolean isExempt(String type, String target, String playerName) {
        String exempt = getPunishmentExempt(type, target);
        if (exempt == null || exempt.isEmpty() || playerName == null) return false;
        for (String ex : exempt.split(",")) {
            if (ex.trim().equalsIgnoreCase(playerName)) return true;
        }
        return false;
    }

    /** Lists all targets currently punished with the given type (used by the API). */
    public List<String> getPunishedTargets(String type) {
        synchronized (dataLock) {
            ConfigurationSection section = dataConfig.getConfigurationSection(sectionName(type));
            if (section == null) {
                return Collections.emptyList();
            }
            List<String> targets = new ArrayList<>();
            for (String key : section.getKeys(false)) {
                targets.add(restoreTarget(type, key));
            }
            return targets;
        }
    }

    public long getBanExpiry(String name) { return getPunishmentExpiry("BAN", name); }
    public String getBanReason(String name) { return getPunishmentReason("BAN", name); }
    public String getBanAdmin(String name) { return getPunishmentAdmin("BAN", name); }
    public long getIpBanExpiry(String ip) { return getPunishmentExpiry("IPBAN", ip); }
    public String getIpBanReason(String ip) { return getPunishmentReason("IPBAN", ip); }
    public String getIpBanAdmin(String ip) { return getPunishmentAdmin("IPBAN", ip); }
    public long getMuteExpiry(String name) { return getPunishmentExpiry("MUTE", name); }
    public String getMuteReason(String name) { return getPunishmentReason("MUTE", name); }
    public long getIpMuteExpiry(String ip) { return getPunishmentExpiry("IPMUTE", ip); }
    public String getIpMuteReason(String ip) { return getPunishmentReason("IPMUTE", ip); }
    public String getBlockBreakingReason(String name) { return getPunishmentReason("DONOTBREAK", name); }

    /** Generic getter used by the API for any punishment type. */
    public String getPunishmentAdmin(String type, String target) {
        synchronized (dataLock) {
            return dataConfig.getString(getPath(type, target) + ".admin", "Unknown");
        }
    }

    /** Generic getter used by the API for any punishment type. */
    public String getPunishmentReason(String type, String target) {
        synchronized (dataLock) {
            return dataConfig.getString(getPath(type, target) + ".reason", "");
        }
    }

    /** Generic getter used by the API for any punishment type. */
    public long getPunishmentExpiry(String type, String target) {
        synchronized (dataLock) {
            return dataConfig.getLong(getPath(type, target) + ".expiry", -1);
        }
    }

    public String getPunishmentServer(String type, String target) {
        synchronized (dataLock) {
            return dataConfig.getString(getPath(type, target) + ".server", localServerName);
        }
    }

    public String getPunishmentExempt(String type, String target) {
        synchronized (dataLock) {
            return dataConfig.getString(getPath(type, target) + ".exempt", "");
        }
    }

    public String getLocalServerName() {
        return localServerName;
    }

    // ------------------------------------------------------------------ //
    //  UTILS                                                              //
    // ------------------------------------------------------------------ //

    public String formatDate(long expiryMillis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(expiryMillis));
    }

    /**
     * Parses "30s", "10m", "2h", "1d", "1w" - and now also combinations like "1d12h".
     * Returns 0 for invalid input, values are clamped so they can never overflow.
     */
    public long parseDuration(String input) {
        if (input == null) return 0;
        String s = input.toLowerCase(Locale.ROOT).trim();
        if (s.isEmpty()) return 0;

        Matcher m = DURATION_PART.matcher(s);
        long total = 0;
        int end = 0;
        int parts = 0;
        while (m.find()) {
            if (m.start() != end) return 0; // junk between the parts
            end = m.end();
            long amount;
            try {
                amount = Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                return 0;
            }
            long unit;
            switch (m.group(2)) {
                case "s": unit = 1000L; break;
                case "m": unit = 60L * 1000L; break;
                case "h": unit = 60L * 60L * 1000L; break;
                case "d": unit = 24L * 60L * 60L * 1000L; break;
                case "w": unit = 7L * 24L * 60L * 60L * 1000L; break;
                default: return 0;
            }
            if (amount > MAX_DURATION / unit) return MAX_DURATION;
            total += amount * unit;
            parts++;
            if (total >= MAX_DURATION) return MAX_DURATION;
        }
        if (parts == 0 || end != s.length()) return 0;
        return total;
    }
}
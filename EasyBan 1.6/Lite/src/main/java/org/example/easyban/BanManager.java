package org.example.easyban;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

public class BanManager {

    private final EasyBan plugin;
    private final MessageManager msg;

    private File dataFile;
    private FileConfiguration dataConfig;

    public BanManager(EasyBan plugin, MessageManager msg) {
        this.plugin = plugin;
        this.msg = msg;
        setupLocalFile();
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
        // У Lite-версії немає БД-з'єднань, тому тут залишається порожнім.
    }

    // --- PUNISHMENT LOGIC ---

    private String getPath(String type, String target) {
        String safeTarget = target.replace(".", "_");
        switch (type) {
            case "BAN": return "bans." + safeTarget;
            case "IPBAN": return "ipbans." + safeTarget;
            case "MUTE": return "mutes." + safeTarget;
            case "IPMUTE": return "ipmutes." + safeTarget;
            default: return "unknown." + safeTarget;
        }
    }

    private void applyPunishment(String type, String target, String admin, String reason, long durationMillis, String exempt) {
        long expiry = (durationMillis == -1) ? -1 : System.currentTimeMillis() + durationMillis;
        String path = getPath(type, target);

        dataConfig.set(path + ".reason", reason);
        dataConfig.set(path + ".admin", admin);
        dataConfig.set(path + ".expiry", expiry);
        dataConfig.set(path + ".exempt", exempt == null ? "" : exempt);
        saveLocal();
    }

    private void removePunishment(String type, String target) {
        String path = getPath(type, target);
        if (dataConfig.contains(path)) {
            dataConfig.set(path, null);
            saveLocal();
        }
    }

    public void addExemptToIpBan(String ip, String playerName) {
        String path = getPath("IPBAN", ip);
        if (dataConfig.contains(path)) {
            String currentExempt = dataConfig.getString(path + ".exempt", "");
            List<String> list = new ArrayList<>();
            if (!currentExempt.trim().isEmpty()) {
                list.addAll(Arrays.asList(currentExempt.split(",")));
            }
            boolean exists = false;
            for (String s : list) {
                if (s.equalsIgnoreCase(playerName)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                list.add(playerName);
                String newExempt = String.join(",", list);
                dataConfig.set(path + ".exempt", newExempt);
                saveLocal();
            }
        }
    }

    public void addExemptToIpMute(String ip, String playerName) {
        String path = getPath("IPMUTE", ip);
        if (dataConfig.contains(path)) {
            String currentExempt = dataConfig.getString(path + ".exempt", "");
            List<String> list = new ArrayList<>();
            if (!currentExempt.trim().isEmpty()) {
                list.addAll(Arrays.asList(currentExempt.split(",")));
            }
            boolean exists = false;
            for (String s : list) {
                if (s.equalsIgnoreCase(playerName)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                list.add(playerName);
                String newExempt = String.join(",", list);
                dataConfig.set(path + ".exempt", newExempt);
                saveLocal();
            }
        }
    }

    public void banPlayer(String target, String admin, String reason, long durationMillis) {
        applyPunishment("BAN", target, admin, reason, durationMillis, "");
    }

    public void banIp(String ip, String admin, String reason, long durationMillis, String exempt) {
        applyPunishment("IPBAN", ip, admin, reason, durationMillis, exempt);
    }

    public void mutePlayer(String target, String admin, String reason, long durationMillis) {
        applyPunishment("MUTE", target, admin, reason, durationMillis, "");
    }

    public void muteIp(String ip, String admin, String reason, long durationMillis, String exempt) {
        applyPunishment("IPMUTE", ip, admin, reason, durationMillis, exempt);
    }

    public void unban(String target) { removePunishment("BAN", target); }
    public void unbanIp(String ip) { removePunishment("IPBAN", ip); }
    public void unmute(String target) { removePunishment("MUTE", target); }
    public void unmuteIp(String ip) { removePunishment("IPMUTE", ip); }

    // --- CHECKERS & GETTERS ---

    public boolean isBanned(String name) { return dataConfig.contains("bans." + name); }
    public boolean isIpBanned(String ip) { return dataConfig.contains("ipbans." + ip.replace(".", "_")); }
    public boolean isMuted(String name) { return dataConfig.contains("mutes." + name); }
    public boolean isIpMuted(String ip) { return dataConfig.contains("ipmutes." + ip.replace(".", "_")); }

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

    public String getPunishmentExempt(String type, String target) {
        return dataConfig.getString(getPath(type, target) + ".exempt", "");
    }

    // --- UTILS ---

    public String formatDate(long expiryMillis) {
        return new Date(expiryMillis).toString();
    }

    public long parseDuration(String input) {
        if (input == null || input.trim().isEmpty()) return 0;
        try {
            input = input.toLowerCase().trim();
            char unit = input.charAt(input.length() - 1);
            String numberPart = input.substring(0, input.length() - 1);
            long amount = Long.parseLong(numberPart);
            switch (unit) {
                case 's': return amount * 1000L;
                case 'm': return amount * 60 * 1000L;
                case 'h': return amount * 60 * 60 * 1000L;
                case 'd': return amount * 24 * 60 * 60 * 1000L;
                case 'w': return amount * 7 * 24 * 60 * 60 * 1000L;
                default: return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }
}
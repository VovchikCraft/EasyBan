package org.example.easyban;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.ArrayList;
import java.util.List;

/**
 * EasyBan Developer API (introduced in 1.7).
 *
 * <p>Other plugins can use every punishment feature of EasyBan through this
 * class, listen to its events, and query the current punishment state. The API
 * is intentionally compact: it covers the full feature set without exposing
 * internal storage details.</p>
 *
 * <p><b>Usage:</b></p>
 * <pre>{@code
 * EasyBanAPI api = EasyBanAPI.get();
 * if (api != null) {
 *     api.banPlayer("Notch", "MyPlugin", "Automated ban", api.parseDuration("1d"));
 * }
 * }</pre>
 *
 * <p>The API is only available when "api.enabled" is set to true in the
 * EasyBan config, and EasyBan must be loaded before the consuming plugin
 * (add {@code softdepend: [EasyBan]} to your plugin.yml).</p>
 */
public final class EasyBanAPI {

    private static EasyBanAPI instance;

    private final EasyBan plugin;
    private final BanManager banManager;

    private EasyBanAPI(EasyBan plugin, BanManager banManager) {
        this.plugin = plugin;
        this.banManager = banManager;
    }

    /**
     * Called by EasyBan itself during onEnable. Do not call this from other plugins.
     */
    public static void register(EasyBan plugin, BanManager banManager) {
        instance = new EasyBanAPI(plugin, banManager);
    }

    /**
     * Called by EasyBan itself during onDisable. Do not call this from other plugins.
     */
    public static void unregister() {
        instance = null;
    }

    /**
     * Returns the API instance, or {@code null} if the API is disabled in the
     * EasyBan config or EasyBan is not loaded/enabled.
     */
    public static EasyBanAPI get() {
        return instance;
    }

    // ------------------------------------------------------------------ //
    //  Applying punishments (all of them fire the cancellable PunishEvent)//
    // ------------------------------------------------------------------ //

    /** Permanently bans a player by name. Returns true if the ban was applied. */
    public boolean banPlayer(String name, String admin, String reason) {
        return banPlayer(name, admin, reason, -1, "all");
    }

    /** Bans a player by name for the given duration in milliseconds (-1 = permanent). */
    public boolean banPlayer(String name, String admin, String reason, long durationMillis) {
        return banPlayer(name, admin, reason, durationMillis, "all");
    }

    /** Bans a player by name with full control over duration and server scope. */
    public boolean banPlayer(String name, String admin, String reason, long durationMillis, String server) {
        return banManager.banPlayer(name, admin, reason, durationMillis, server);
    }

    /** Permanently bans an IP address. */
    public boolean banIp(String ip, String admin, String reason) {
        return banIp(ip, admin, reason, -1, "all", "");
    }

    /** Bans an IP address for the given duration in milliseconds (-1 = permanent). */
    public boolean banIp(String ip, String admin, String reason, long durationMillis) {
        return banIp(ip, admin, reason, durationMillis, "all", "");
    }

    /** Bans an IP address with full control over duration, server scope and exempted players. */
    public boolean banIp(String ip, String admin, String reason, long durationMillis, String server, String exempt) {
        return banManager.banIp(ip, admin, reason, durationMillis, server, exempt);
    }

    /** Permanently mutes a player by name. */
    public boolean mutePlayer(String name, String admin, String reason) {
        return mutePlayer(name, admin, reason, -1, "all");
    }

    /** Mutes a player by name for the given duration in milliseconds (-1 = permanent). */
    public boolean mutePlayer(String name, String admin, String reason, long durationMillis) {
        return mutePlayer(name, admin, reason, durationMillis, "all");
    }

    /** Mutes a player by name with full control over duration and server scope. */
    public boolean mutePlayer(String name, String admin, String reason, long durationMillis, String server) {
        return banManager.mutePlayer(name, admin, reason, durationMillis, server);
    }

    /** Permanently mutes an IP address. */
    public boolean muteIp(String ip, String admin, String reason) {
        return muteIp(ip, admin, reason, -1, "all", "");
    }

    /** Mutes an IP address for the given duration in milliseconds (-1 = permanent). */
    public boolean muteIp(String ip, String admin, String reason, long durationMillis) {
        return muteIp(ip, admin, reason, durationMillis, "all", "");
    }

    /** Mutes an IP address with full control over duration, server scope and exempted players. */
    public boolean muteIp(String ip, String admin, String reason, long durationMillis, String server, String exempt) {
        return banManager.muteIp(ip, admin, reason, durationMillis, server, exempt);
    }

    /** Prevents a player from breaking blocks until the restriction is removed. */
    public boolean restrictBlockBreaking(String name, String admin, String reason) {
        return restrictBlockBreaking(name, admin, reason, "all");
    }

    /** Prevents a player from breaking blocks, scoped to a specific server. */
    public boolean restrictBlockBreaking(String name, String admin, String reason, String server) {
        return banManager.banBlockBreaking(name, admin, reason, server);
    }

    /** Kicks an online player. Fires the cancellable KickEvent. Returns true if kicked. */
    public boolean kickPlayer(String name, String admin, String reason) {
        if (!plugin.fireKickEvent(name, admin, reason)) {
            return false;
        }
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            return false;
        }
        String server = plugin.getConfig().getString("server-name", "survival");
        plugin.safeKick(target, plugin.getMessageManager().getBannedLayoutComponent(reason, admin, "Kicked", server));
        plugin.getDiscordManager().sendKick(name, admin, reason, server);
        return true;
    }

    // ------------------------------------------------------------------ //
    //  Removing punishments (fire the cancellable UnpunishEvent)          //
    // ------------------------------------------------------------------ //

    public boolean unbanPlayer(String name) { return unbanPlayer(name, "all"); }
    public boolean unbanPlayer(String name, String server) { return banManager.unban(name, server); }

    public boolean unbanIp(String ip) { return unbanIp(ip, "all"); }
    public boolean unbanIp(String ip, String server) { return banManager.unbanIp(ip, server); }

    public boolean unmutePlayer(String name) { return unmutePlayer(name, "all"); }
    public boolean unmutePlayer(String name, String server) { return banManager.unmute(name, server); }

    public boolean unmuteIp(String ip) { return unmuteIp(ip, "all"); }
    public boolean unmuteIp(String ip, String server) { return banManager.unmuteIp(ip, server); }

    public boolean allowBlockBreaking(String name) { return allowBlockBreaking(name, "all"); }
    public boolean allowBlockBreaking(String name, String server) { return banManager.unbanBlockBreaking(name, server); }

    // ------------------------------------------------------------------ //
    //  Queries                                                            //
    // ------------------------------------------------------------------ //

    public boolean isBanned(String name) { return banManager.isBanned(name); }
    public boolean isIpBanned(String ip) { return banManager.isIpBanned(ip); }
    public boolean isMuted(String name) { return banManager.isMuted(name); }
    public boolean isIpMuted(String ip) { return banManager.isIpMuted(ip); }
    public boolean isBlockBreakingRestricted(String name) { return banManager.isBlockBreakingBanned(name); }

    /**
     * Returns full information about one active punishment, or {@code null} if
     * the target is not punished with the given type. Types: BAN, IPBAN, MUTE,
     * IPMUTE, DONOTBREAK.
     */
    public PunishmentInfo getPunishmentInfo(String type, String target) {
        if (!banManager.hasPunishment(type, target)) {
            return null;
        }
        return new PunishmentInfo(
                type,
                target,
                banManager.getPunishmentAdmin(type, target),
                banManager.getPunishmentReason(type, target),
                banManager.getPunishmentExpiry(type, target),
                banManager.getPunishmentServer(type, target),
                banManager.getPunishmentExempt(type, target));
    }

    /** Returns a snapshot of every active punishment currently known to EasyBan. */
    public List<PunishmentInfo> getActivePunishments() {
        List<PunishmentInfo> result = new ArrayList<>();
        for (String type : new String[]{"BAN", "IPBAN", "MUTE", "IPMUTE", "DONOTBREAK"}) {
            for (String target : banManager.getPunishedTargets(type)) {
                PunishmentInfo info = getPunishmentInfo(type, target);
                if (info != null) {
                    result.add(info);
                }
            }
        }
        return result;
    }

    /** Parses a duration string such as "30s", "10m", "2h", "1d", "1w" into milliseconds. */
    public long parseDuration(String input) {
        return banManager.parseDuration(input);
    }

    /** Formats an expiry timestamp the same way EasyBan shows it to players. */
    public String formatExpiry(long expiryMillis) {
        return (expiryMillis == -1) ? "Permanent" : banManager.formatDate(expiryMillis);
    }

    // ------------------------------------------------------------------ //
    //  Data holder                                                        //
    // ------------------------------------------------------------------ //

    /** Immutable snapshot of a single punishment. */
    public record PunishmentInfo(String type, String target, String admin, String reason,
                                 long expiryMillis, String server, String exempt) {
        /** @return true when the punishment never expires. */
        public boolean isPermanent() {
            return expiryMillis == -1;
        }

        /** @return true when the punishment's time has already run out. */
        public boolean isExpired() {
            return expiryMillis > 0 && System.currentTimeMillis() > expiryMillis;
        }
    }

    // ------------------------------------------------------------------ //
    //  Events (fired by EasyBan when the API is enabled)                  //
    // ------------------------------------------------------------------ //

    /** Fired before a punishment is applied. Cancelling it blocks the punishment. */
    public static class PunishEvent extends Event implements Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        private final String type;
        private final String target;
        private final String admin;
        private final String reason;
        private final long durationMillis;
        private final String server;
        private boolean cancelled;

        public PunishEvent(String type, String target, String admin, String reason,
                           long durationMillis, String server, boolean async) {
            super(async);
            this.type = type;
            this.target = target;
            this.admin = admin;
            this.reason = reason;
            this.durationMillis = durationMillis;
            this.server = server;
        }

        /** Punishment type: BAN, IPBAN, MUTE, IPMUTE or DONOTBREAK. */
        public String getType() { return type; }
        /** Punished player name or IP address. */
        public String getTarget() { return target; }
        /** Who applied the punishment (player name, "Console" or a plugin name). */
        public String getAdmin() { return admin; }
        public String getReason() { return reason; }
        /** Requested duration in milliseconds, or -1 for permanent. */
        public long getDurationMillis() { return durationMillis; }
        /** Server scope of the punishment ("all" or a server name). */
        public String getServer() { return server; }

        @Override
        public boolean isCancelled() { return cancelled; }

        @Override
        public void setCancelled(boolean cancel) { this.cancelled = cancel; }

        @Override
        public HandlerList getHandlers() { return HANDLERS; }

        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    /** Fired before a punishment is removed. Cancelling it keeps the punishment. */
    public static class UnpunishEvent extends Event implements Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        private final String type;
        private final String target;
        private final String server;
        private boolean cancelled;

        public UnpunishEvent(String type, String target, String server, boolean async) {
            super(async);
            this.type = type;
            this.target = target;
            this.server = server;
        }

        /** Punishment type being removed: BAN, IPBAN, MUTE, IPMUTE or DONOTBREAK. */
        public String getType() { return type; }
        /** Player name or IP address being un-punished. */
        public String getTarget() { return target; }
        /** Server scope of the removal ("all" or a server name). */
        public String getServer() { return server; }

        @Override
        public boolean isCancelled() { return cancelled; }

        @Override
        public void setCancelled(boolean cancel) { this.cancelled = cancel; }

        @Override
        public HandlerList getHandlers() { return HANDLERS; }

        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    /** Fired before a player is kicked through EasyBan. Cancelling it blocks the kick. */
    public static class KickEvent extends Event implements Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        private final String target;
        private final String admin;
        private final String reason;
        private boolean cancelled;

        public KickEvent(String target, String admin, String reason, boolean async) {
            super(async);
            this.target = target;
            this.admin = admin;
            this.reason = reason;
        }

        /** Name of the player about to be kicked. */
        public String getTarget() { return target; }
        /** Who issued the kick. */
        public String getAdmin() { return admin; }
        public String getReason() { return reason; }

        @Override
        public boolean isCancelled() { return cancelled; }

        @Override
        public void setCancelled(boolean cancel) { this.cancelled = cancel; }

        @Override
        public HandlerList getHandlers() { return HANDLERS; }

        public static HandlerList getHandlerList() { return HANDLERS; }
    }
}

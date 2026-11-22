package org.example.easyban;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.example.easyban.BanManager.RemovalResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

public class EBCommands implements CommandExecutor, TabCompleter {

    private static final Pattern IP_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
    );

    private static final String USAGE_MAIN =
            "/eb <notbreak|ban|tempban|mute|tempmute|delban|delmute|kick|status|reload|discordtest> ...";

    private final EasyBan plugin;
    private final BanManager banManager;
    private final MessageManager msg;

    public EBCommands(EasyBan plugin, BanManager banManager, MessageManager msg) {
        this.plugin = plugin;
        this.banManager = banManager;
        this.msg = msg;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("easyban.use")) {
            msg.send(sender, "no-permission");
            return true;
        }

        if (args.length == 0) {
            msg.send(sender, "invalid-usage", "%usage%", USAGE_MAIN);
            return true;
        }

        String sub = args[0].toLowerCase();
        String admin = sender.getName();

        switch (sub) {
            case "notbreak":
                return handleNotBreak(sender, admin, args);
            case "ban":
                return handleBan(sender, admin, args);
            case "tempban":
                return handleTempBan(sender, admin, args);
            case "mute":
                return handleMute(sender, admin, args);
            case "tempmute":
                return handleTempMute(sender, admin, args);
            case "delban":
                return handleDelBan(sender, admin, args);
            case "delmute":
                return handleDelMute(sender, admin, args);
            case "kick":
                return handleKick(sender, admin, args);
            case "status":
                return handleStatus(sender, args);
            case "reload":
                return handleReload(sender);
            case "discordtest":
                return handleDiscordTest(sender);
            default:
                msg.send(sender, "invalid-usage", "%usage%", USAGE_MAIN);
                return true;
        }
    }

    private boolean handleNotBreak(CommandSender sender, String admin, String[] args) {
        if (args.length < 3) {
            msg.send(sender, "invalid-usage", "%usage%", "/eb notbreak <give|remove> <player> [reason] [server:name]");
            return true;
        }
        String action = args[1].toLowerCase();
        String target = args[2];

        if (action.equals("give")) {
            ParsedArgs pr = parseArgs(args, 3);
            if (!banManager.banBlockBreaking(target, admin, pr.reason, pr.server)) {
                msg.send(sender, "punishment-cancelled");
                return true;
            }
            msg.send(sender, "donotbreak-success", "%player%", target, "%server%", pr.server);
            return true;
        } else if (action.equals("remove")) {
            ParsedArgs pr = parseArgs(args, 3);
            banManager.unbanBlockBreakingAsync(target, pr.server, result ->
                    finishRemoval(sender, result, target, pr.server, () ->
                            msg.send(sender, "deldonotbreak-success", "%player%", target)));
            return true;
        } else {
            msg.send(sender, "invalid-usage", "%usage%", "/eb notbreak <give|remove> <player> [reason] [server:name]");
            return true;
        }
    }

    private boolean handleBan(CommandSender sender, String admin, String[] args) {
        if (args.length < 4) {
            msg.send(sender, "invalid-usage", "%usage%", "/eb ban <player|pl|ip> <nickname/Ip> <reason> [server:name] [not:nickname]");
            return true;
        }
        String mode = args[1].toLowerCase();
        String targetArg = args[2];

        if (mode.equals("player") || mode.equals("pl")) {
            ParsedArgs pr = parseArgs(args, 3);
            if (!banManager.banPlayer(targetArg, admin, pr.reason, -1, pr.server)) {
                msg.send(sender, "punishment-cancelled");
                return true;
            }
            kickIfExists(targetArg, pr.reason, admin, permanentLabel(), pr.server);
            msg.send(sender, "ban-success", "%player%", targetArg, "%server%", pr.server);
            return true;
        } else if (mode.equals("ip")) {
            String targetIp = resolveIp(targetArg);
            if (targetIp == null) {
                msg.send(sender, "player-not-found");
                return true;
            }
            ParsedArgs pr = parseArgs(args, 3);
            if (!banManager.banIp(targetIp, admin, pr.reason, -1, pr.server, pr.exempt)) {
                msg.send(sender, "punishment-cancelled");
                return true;
            }
            kickIp(targetIp, pr.reason, admin, permanentLabel(), pr.server, pr.exempt);
            msg.send(sender, "ban-success", "%player%", targetIp, "%server%", pr.server);
            return true;
        } else {
            msg.send(sender, "invalid-usage", "%usage%", "/eb ban <player|pl|ip> <nickname/Ip> <reason> [server:name] [not:nickname]");
            return true;
        }
    }

    private boolean handleTempBan(CommandSender sender, String admin, String[] args) {
        if (args.length < 5) {
            msg.send(sender, "invalid-usage", "%usage%", "/eb tempban <player|pl|ip> <nickname/Ip> <time> <reason> [server:name] [not:nickname]");
            return true;
        }
        String mode = args[1].toLowerCase();
        String targetArg = args[2];
        long duration = banManager.parseDuration(args[3]);
        if (duration <= 0) {
            msg.send(sender, "invalid-duration");
            return true;
        }
        String timeStr = banManager.formatDate(System.currentTimeMillis() + duration);

        if (mode.equals("player") || mode.equals("pl")) {
            ParsedArgs pr = parseArgs(args, 4);
            if (!banManager.banPlayer(targetArg, admin, pr.reason, duration, pr.server)) {
                msg.send(sender, "punishment-cancelled");
                return true;
            }
            kickIfExists(targetArg, pr.reason, admin, timeStr, pr.server);
            msg.send(sender, "ban-success", "%player%", targetArg, "%server%", pr.server);
            return true;
        } else if (mode.equals("ip")) {
            String targetIp = resolveIp(targetArg);
            if (targetIp == null) {
                msg.send(sender, "player-not-found");
                return true;
            }
            ParsedArgs pr = parseArgs(args, 4);
            if (!banManager.banIp(targetIp, admin, pr.reason, duration, pr.server, pr.exempt)) {
                msg.send(sender, "punishment-cancelled");
                return true;
            }
            kickIp(targetIp, pr.reason, admin, timeStr, pr.server, pr.exempt);
            msg.send(sender, "ban-success", "%player%", targetIp, "%server%", pr.server);
            return true;
        } else {
            msg.send(sender, "invalid-usage", "%usage%", "/eb tempban <player|pl|ip> <nickname/Ip> <time> <reason> [server:name] [not:nickname]");
            return true;
        }
    }

    private boolean handleMute(CommandSender sender, String admin, String[] args) {
        if (args.length < 4) {
            msg.send(sender, "invalid-usage", "%usage%", "/eb mute <player|pl|ip> <nickname/Ip> <reason> [server:name] [not:nickname]");
            return true;
        }
        String mode = args[1].toLowerCase();
        String targetArg = args[2];

        if (mode.equals("player") || mode.equals("pl")) {
            ParsedArgs pr = parseArgs(args, 3);
            if (!banManager.mutePlayer(targetArg, admin, pr.reason, -1, pr.server)) {
                msg.send(sender, "punishment-cancelled");
                return true;
            }
            msg.send(sender, "mute-success", "%player%", targetArg, "%server%", pr.server);
            return true;
        } else if (mode.equals("ip")) {
            String targetIp = resolveIp(targetArg);
            if (targetIp == null) {
                msg.send(sender, "player-not-found");
                return true;
            }
            ParsedArgs pr = parseArgs(args, 3);
            if (!banManager.muteIp(targetIp, admin, pr.reason, -1, pr.server, pr.exempt)) {
                msg.send(sender, "punishment-cancelled");
                return true;
            }
            msg.send(sender, "ipmute-success", "%ip%", targetIp, "%server%", pr.server);
            return true;
        } else {
            msg.send(sender, "invalid-usage", "%usage%", "/eb mute <player|pl|ip> <nickname/Ip> <reason> [server:name] [not:nickname]");
            return true;
        }
    }

    private boolean handleTempMute(CommandSender sender, String admin, String[] args) {
        if (args.length < 5) {
            msg.send(sender, "invalid-usage", "%usage%", "/eb tempmute <player|pl|ip> <nickname/Ip> <time> <reason> [server:name] [not:nickname]");
            return true;
        }
        String mode = args[1].toLowerCase();
        String targetArg = args[2];
        long duration = banManager.parseDuration(args[3]);
        if (duration <= 0) {
            msg.send(sender, "invalid-duration");
            return true;
        }

        if (mode.equals("player") || mode.equals("pl")) {
            ParsedArgs pr = parseArgs(args, 4);
            if (!banManager.mutePlayer(targetArg, admin, pr.reason, duration, pr.server)) {
                msg.send(sender, "punishment-cancelled");
                return true;
            }
            msg.send(sender, "mute-success", "%player%", targetArg, "%server%", pr.server);
            return true;
        } else if (mode.equals("ip")) {
            String targetIp = resolveIp(targetArg);
            if (targetIp == null) {
                msg.send(sender, "player-not-found");
                return true;
            }
            ParsedArgs pr = parseArgs(args, 4);
            if (!banManager.muteIp(targetIp, admin, pr.reason, duration, pr.server, pr.exempt)) {
                msg.send(sender, "punishment-cancelled");
                return true;
            }
            msg.send(sender, "ipmute-success", "%ip%", targetIp, "%server%", pr.server);
            return true;
        } else {
            msg.send(sender, "invalid-usage", "%usage%", "/eb tempmute <player|pl|ip> <nickname/Ip> <time> <reason> [server:name] [not:nickname]");
            return true;
        }
    }

    private boolean handleDelBan(CommandSender sender, String admin, String[] args) {
        if (args.length < 3) {
            msg.send(sender, "invalid-usage", "%usage%", "/eb delban <player|pl|ip> <nickname/Ip> [server:name]");
            return true;
        }
        String mode = args[1].toLowerCase();
        String targetArg = args[2];
        ParsedArgs pr = parseArgs(args, 3);

        if (mode.equals("player") || mode.equals("pl")) {
            banManager.unbanAsync(targetArg, pr.server, result ->
                    finishRemoval(sender, result, targetArg, pr.server, () -> {
                        String ip = resolveIp(targetArg);
                        if (ip != null && banManager.isIpBanned(ip)) {
                            banManager.addExemptToIpBan(ip, targetArg, pr.server);
                        }
                        msg.send(sender, "unban-success", "%player%", targetArg);
                    }));
            return true;
        } else if (mode.equals("ip")) {
            String targetIp = resolveIp(targetArg);
            if (targetIp == null) {
                msg.send(sender, "player-not-found");
                return true;
            }
            banManager.unbanIpAsync(targetIp, pr.server, result ->
                    finishRemoval(sender, result, targetIp, pr.server, () ->
                            msg.send(sender, "unban-ip-success", "%ip%", targetIp)));
            return true;
        } else {
            msg.send(sender, "invalid-usage", "%usage%", "/eb delban <player|pl|ip> <nickname/Ip> [server:name]");
            return true;
        }
    }

    private boolean handleDelMute(CommandSender sender, String admin, String[] args) {
        if (args.length < 3) {
            msg.send(sender, "invalid-usage", "%usage%", "/eb delmute <player|pl|ip> <nickname/Ip> [server:name]");
            return true;
        }
        String mode = args[1].toLowerCase();
        String targetArg = args[2];
        ParsedArgs pr = parseArgs(args, 3);

        if (mode.equals("player") || mode.equals("pl")) {
            banManager.unmuteAsync(targetArg, pr.server, result ->
                    finishRemoval(sender, result, targetArg, pr.server, () -> {
                        String ip = resolveIp(targetArg);
                        if (ip != null && banManager.isIpMuted(ip)) {
                            banManager.addExemptToIpMute(ip, targetArg, pr.server);
                        }
                        msg.send(sender, "unmute-success", "%player%", targetArg);
                    }));
            return true;
        } else if (mode.equals("ip")) {
            String targetIp = resolveIp(targetArg);
            if (targetIp == null) {
                msg.send(sender, "player-not-found");
                return true;
            }
            banManager.unmuteIpAsync(targetIp, pr.server, result ->
                    finishRemoval(sender, result, targetIp, pr.server, () ->
                            msg.send(sender, "unmute-ip-success", "%ip%", targetIp)));
            return true;
        } else {
            msg.send(sender, "invalid-usage", "%usage%", "/eb delmute <player|pl|ip> <nickname/Ip> [server:name]");
            return true;
        }
    }

    /**
     * Single place that decides which feedback a removal gets.
     * This is what fixes "it says removed although nothing was removed".
     */
    private void finishRemoval(CommandSender sender, RemovalResult result, String target, String server, Runnable onSuccess) {
        switch (result) {
            case NOT_FOUND -> msg.send(sender, "punishment-not-found", "%target%", target, "%server%", server);
            case CANCELLED -> msg.send(sender, "punishment-cancelled");
            case REMOVED -> onSuccess.run();
        }
    }

    private boolean handleKick(CommandSender sender, String admin, String[] args) {
        if (args.length < 3) {
            msg.send(sender, "invalid-usage", "%usage%", "/eb kick <player> <reason>");
            return true;
        }
        Player targetP = Bukkit.getPlayerExact(args[1]);
        if (targetP == null) {
            msg.send(sender, "player-not-found");
            return true;
        }
        ParsedArgs pr = parseArgs(args, 2);

        // Let API listeners veto the kick before it happens.
        if (!plugin.fireKickEvent(targetP.getName(), admin, pr.reason)) {
            msg.send(sender, "punishment-cancelled");
            return true;
        }

        String server = plugin.getConfig().getString("server-name", "survival");
        plugin.safeKick(targetP, msg.getBannedLayoutComponent(pr.reason, admin, msg.getRaw("status.kicked"), server));
        plugin.getDiscordManager().sendKick(targetP.getName(), admin, pr.reason, server);
        msg.send(sender, "kick-success", "%player%", targetP.getName());
        return true;
    }

    private boolean handleStatus(CommandSender sender, String[] args) {
        if (args.length < 3) {
            msg.send(sender, "invalid-usage", "%usage%", "/eb status <player|pl|ip> <nickname/Ip>");
            return true;
        }
        String mode = args[1].toLowerCase();
        String targetArg = args[2];

        if (mode.equals("player") || mode.equals("pl")) {
            sendPlayerStatus(sender, targetArg);
            return true;
        } else if (mode.equals("ip")) {
            String targetIp = resolveIp(targetArg);
            if (targetIp == null) {
                msg.send(sender, "player-not-found");
                return true;
            }
            sendIpStatus(sender, targetIp);
            return true;
        } else {
            msg.send(sender, "invalid-usage", "%usage%", "/eb status <player|pl|ip> <nickname/Ip>");
            return true;
        }
    }

    /** Reloads config, language files, storage settings and the Discord webhook. */
    private boolean handleReload(CommandSender sender) {
        plugin.reloadConfig();
        int added = ConfigUpdater.update(plugin, "config.yml", new java.io.File(plugin.getDataFolder(), "config.yml"));
        if (added > 0) {
            plugin.reloadConfig();
        }
        msg.reload();
        plugin.getDiscordManager().reload();
        banManager.reload();
        msg.send(sender, "reload-success");
        return true;
    }

    /** Sends a test embed to the configured Discord webhook. */
    private boolean handleDiscordTest(CommandSender sender) {
        if (!plugin.getDiscordManager().isEnabled()) {
            msg.send(sender, "discord-test-not-configured");
            return true;
        }
        plugin.getDiscordManager().sendTestMessage(success -> {
            // The webhook response arrives on a network thread; hop back onto
            // the main thread before sending the in-game feedback message.
            SchedulerCompat.runSync(plugin, () ->
                    msg.send(sender, success ? "discord-test-sent" : "discord-test-failed"));
        });
        return true;
    }

    // --- STATUS OUTPUT ---

    private void sendPlayerStatus(CommandSender sender, String playerName) {
        String prefix = msg.getRaw("prefix");
        StringBuilder sb = new StringBuilder();
        sb.append(msg.getRaw("status.header-player").replace("%player%", msg.escape(playerName)));
        boolean found = false;

        // Check BAN
        if (banManager.isBanned(playerName)) {
            found = true;
            String expiryStr = formatExpiryForStatus(banManager.getBanExpiry(playerName));
            sb.append("<newline>").append(msg.getRaw("status.entry")
                    .replace("%type%", msg.getRaw("status.type-ban"))
                    .replace("%reason%", safe(banManager.getBanReason(playerName)))
                    .replace("%admin%", safe(banManager.getBanAdmin(playerName)))
                    .replace("%expiry%", msg.escape(expiryStr))
                    .replace("%server%", safe(banManager.getPunishmentServer("BAN", playerName)))
                    .replace("%extra%", ""));
        }

        // Check MUTE
        if (banManager.isMuted(playerName)) {
            found = true;
            String expiryStr = formatExpiryForStatus(banManager.getMuteExpiry(playerName));
            sb.append("<newline>").append(msg.getRaw("status.entry")
                    .replace("%type%", msg.getRaw("status.type-mute"))
                    .replace("%reason%", safe(banManager.getMuteReason(playerName)))
                    .replace("%admin%", safe(banManager.getPunishmentAdmin("MUTE", playerName)))
                    .replace("%expiry%", msg.escape(expiryStr))
                    .replace("%server%", safe(banManager.getPunishmentServer("MUTE", playerName)))
                    .replace("%extra%", ""));
        }

        // Check DONOTBREAK
        if (banManager.isBlockBreakingBanned(playerName)) {
            found = true;
            sb.append("<newline>").append(msg.getRaw("status.entry")
                    .replace("%type%", msg.getRaw("status.type-donotbreak"))
                    .replace("%reason%", safe(banManager.getBlockBreakingReason(playerName)))
                    .replace("%admin%", safe(banManager.getPunishmentAdmin("DONOTBREAK", playerName)))
                    .replace("%expiry%", msg.getRaw("status.permanent"))
                    .replace("%server%", safe(banManager.getPunishmentServer("DONOTBREAK", playerName)))
                    .replace("%extra%", ""));
        }

        if (!found) {
            sb.append("<newline>").append(msg.getRaw("status.no-punishments"));
        }

        Component component = MiniMessage.miniMessage().deserialize(prefix + sb);
        sender.sendMessage(component);
    }

    private void sendIpStatus(CommandSender sender, String ip) {
        String prefix = msg.getRaw("prefix");
        StringBuilder sb = new StringBuilder();
        sb.append(msg.getRaw("status.header-ip").replace("%ip%", msg.escape(ip)));
        boolean found = false;

        // Check IPBAN
        if (banManager.isIpBanned(ip)) {
            found = true;
            String expiryStr = formatExpiryForStatus(banManager.getIpBanExpiry(ip));
            String extra = buildExemptPart(banManager.getPunishmentExempt("IPBAN", ip));
            sb.append("<newline>").append(msg.getRaw("status.entry")
                    .replace("%type%", msg.getRaw("status.type-ipban"))
                    .replace("%reason%", safe(banManager.getIpBanReason(ip)))
                    .replace("%admin%", safe(banManager.getIpBanAdmin(ip)))
                    .replace("%expiry%", msg.escape(expiryStr))
                    .replace("%server%", safe(banManager.getPunishmentServer("IPBAN", ip)))
                    .replace("%extra%", extra));
        }

        // Check IPMUTE
        if (banManager.isIpMuted(ip)) {
            found = true;
            String expiryStr = formatExpiryForStatus(banManager.getIpMuteExpiry(ip));
            String extra = buildExemptPart(banManager.getPunishmentExempt("IPMUTE", ip));
            sb.append("<newline>").append(msg.getRaw("status.entry")
                    .replace("%type%", msg.getRaw("status.type-ipmute"))
                    .replace("%reason%", safe(banManager.getIpMuteReason(ip)))
                    .replace("%admin%", safe(banManager.getPunishmentAdmin("IPMUTE", ip)))
                    .replace("%expiry%", msg.escape(expiryStr))
                    .replace("%server%", safe(banManager.getPunishmentServer("IPMUTE", ip)))
                    .replace("%extra%", extra));
        }

        if (!found) {
            sb.append("<newline>").append(msg.getRaw("status.no-punishments"));
        }

        Component component = MiniMessage.miniMessage().deserialize(prefix + sb);
        sender.sendMessage(component);
    }

    private String formatExpiryForStatus(long expiry) {
        if (expiry == -1) {
            return msg.getRaw("status.permanent");
        }
        if (expiry > 0 && System.currentTimeMillis() > expiry) {
            return msg.getRaw("status.expired");
        }
        return banManager.formatDate(expiry);
    }

    private String buildExemptPart(String exempt) {
        if (exempt == null || exempt.trim().isEmpty()) {
            return "";
        }
        return msg.getRaw("status.exempt-label").replace("%exempt%", msg.escape(exempt));
    }

    /** Null-safe + MiniMessage-safe so reasons can never inject formatting/commands. */
    private String safe(String value) {
        return (value == null || value.isEmpty()) ? "-" : msg.escape(value);
    }

    private String permanentLabel() {
        return msg.getRaw("status.permanent");
    }

    // --- TAB COMPLETER ---

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("easyban.use")) return Collections.emptyList();
        String currentServer = plugin.getConfig().getString("server-name", "survival");

        if (args.length == 1) {
            List<String> subcommands = Arrays.asList(
                    "notbreak", "ban", "tempban", "mute", "tempmute", "delban", "delmute", "kick", "status", "reload", "discordtest"
            );
            return StringUtil.copyPartialMatches(args[0], subcommands, new ArrayList<>());
        }

        String sub = args[0].toLowerCase();
        boolean punish = Arrays.asList("ban", "tempban", "mute", "tempmute").contains(sub);
        boolean targetCmd = punish || Arrays.asList("delban", "delmute", "status").contains(sub);

        if (args.length == 2) {
            if (sub.equals("notbreak")) {
                return StringUtil.copyPartialMatches(args[1], Arrays.asList("give", "remove"), new ArrayList<>());
            }
            if (targetCmd) {
                return StringUtil.copyPartialMatches(args[1], Arrays.asList("player", "pl", "ip"), new ArrayList<>());
            }
            if (sub.equals("kick")) {
                return null; // default online-player completion
            }
            if (Arrays.asList("reload", "discordtest").contains(sub)) {
                return Collections.emptyList();
            }
        }

        if (args.length == 3) {
            if (sub.equals("notbreak") || targetCmd) {
                return null; // default online-player completion
            }
        }

        if (args.length == 4) {
            if (sub.equals("tempban") || sub.equals("tempmute")) {
                List<String> timeSuggestions = Arrays.asList("30s", "5m", "10m", "1h", "1d", "1w", "1d12h");
                return StringUtil.copyPartialMatches(args[3], timeSuggestions, new ArrayList<>());
            }
            if (sub.equals("status") || sub.equals("kick")) {
                return Collections.emptyList();
            }
            return StringUtil.copyPartialMatches(args[3], scopeSuggestions(currentServer, punish), new ArrayList<>());
        }

        if (args.length >= 5) {
            if (sub.equals("status")) {
                return Collections.emptyList();
            }
            return StringUtil.copyPartialMatches(args[args.length - 1],
                    scopeSuggestions(currentServer, punish), new ArrayList<>());
        }

        return Collections.emptyList();
    }

    private List<String> scopeSuggestions(String currentServer, boolean allowExempt) {
        List<String> suggestions = new ArrayList<>();
        suggestions.add("server:all");
        suggestions.add("server:" + currentServer);
        if (allowExempt) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                suggestions.add("not:" + p.getName());
            }
        }
        return suggestions;
    }

    // --- HELPERS ---

    private ParsedArgs parseArgs(String[] args, int startIdx) {
        String localServer = plugin.getConfig().getString("server-name", "survival");
        String server = localServer;
        List<String> exempts = new ArrayList<>();
        List<String> reasonParts = new ArrayList<>();

        for (int i = startIdx; i < args.length; i++) {
            String arg = args[i];
            if (arg.toLowerCase().startsWith("server:")) {
                server = arg.substring(7);
            } else if (arg.equalsIgnoreCase("all") && i == args.length - 1) {
                server = "all";
            } else if (arg.toLowerCase().startsWith("not:")) {
                exempts.add(arg.substring(4));
            } else {
                reasonParts.add(arg);
            }
        }

        String reason = reasonParts.isEmpty() ? "No reason provided." : String.join(" ", reasonParts);
        String exemptStr = String.join(",", exempts);
        return new ParsedArgs(reason, server, exemptStr);
    }

    private void kickIfExists(String name, String reason, String admin, String time, String server) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) {
            plugin.safeKick(p, msg.getBannedLayoutComponent(reason, admin, time, server));
        }
    }

    private void kickIp(String ip, String reason, String admin, String time, String server, String exemptStr) {
        List<String> exempts = Arrays.asList(exemptStr.toLowerCase().split(","));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getAddress() != null && p.getAddress().getAddress() != null
                    && p.getAddress().getAddress().getHostAddress().equals(ip)) {
                if (!exempts.contains(p.getName().toLowerCase())) {
                    plugin.safeKick(p, msg.getBannedLayoutComponent(reason, admin, time, server));
                }
            }
        }
    }

    private String resolveIp(String arg) {
        if (IP_PATTERN.matcher(arg).matches()) {
            return arg;
        }
        Player p = Bukkit.getPlayerExact(arg);
        if (p != null && p.getAddress() != null && p.getAddress().getAddress() != null) {
            return p.getAddress().getAddress().getHostAddress();
        }
        return null;
    }

    private static class ParsedArgs {
        final String reason;
        final String server;
        final String exempt;

        ParsedArgs(String reason, String server, String exempt) {
            this.reason = reason;
            this.server = server;
            this.exempt = exempt;
        }
    }
}
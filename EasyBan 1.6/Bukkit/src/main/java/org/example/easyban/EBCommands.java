package org.example.easyban;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

public class EBCommands implements CommandExecutor, TabCompleter {

    private static final Pattern IP_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
    );

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
            msg.send(sender, "invalid-usage", "%usage%", "/eb <notbreak|ban|tempban|mute|tempmute|delban|delmute|kick> ...");
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
                return handleDelBan(sender, args);
            case "delmute":
                return handleDelMute(sender, args);
            case "kick":
                return handleKick(sender, admin, args);
            default:
                msg.send(sender, "invalid-usage", "%usage%", "/eb <notbreak|ban|tempban|mute|tempmute|delban|delmute|kick> ...");
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
            banManager.banBlockBreaking(target, admin, pr.reason, pr.server);
            msg.send(sender, "donotbreak-success", "%player%", target, "%server%", pr.server);
            return true;
        } else if (action.equals("remove")) {
            ParsedArgs pr = parseArgs(args, 3);
            banManager.unbanBlockBreaking(target, pr.server);
            msg.send(sender, "deldonotbreak-success", "%player%", target);
            return true;
        } else {
            msg.send(sender, "invalid-usage", "%usage%", "/eb notbreak <give|remove> <player> [reason] [server:name]");
            return true;
        }
    }

    private boolean handleBan(CommandSender sender, String admin, String[] args) {
        if (args.length < 4) {
            msg.send(sender, "invalid-usage", "%usage%", "/eb ban <player|ip> <nickname/Ip> <reason> [server:name] [not:nickname]");
            return true;
        }
        String mode = args[1].toLowerCase();
        String targetArg = args[2];

        if (mode.equals("player")) {
            ParsedArgs pr = parseArgs(args, 3);
            banManager.banPlayer(targetArg, admin, pr.reason, -1, pr.server);
            kickIfExists(targetArg, pr.reason, admin, "Permanent", pr.server);
            msg.send(sender, "ban-success", "%player%", targetArg, "%server%", pr.server);
            return true;
        } else if (mode.equals("ip")) {
            String targetIp = resolveIp(targetArg);
            if (targetIp == null) {
                msg.send(sender, "player-not-found");
                return true;
            }
            ParsedArgs pr = parseArgs(args, 3);
            banManager.banIp(targetIp, admin, pr.reason, -1, pr.server, pr.exempt);
            kickIp(targetIp, pr.reason, admin, "Permanent", pr.server, pr.exempt);
            msg.send(sender, "ban-success", "%player%", targetIp, "%server%", pr.server);
            return true;
        } else {
            msg.send(sender, "invalid-usage", "%usage%", "/eb ban <player|ip> <nickname/Ip> <reason> [server:name] [not:nickname]");
            return true;
        }
    }

    private boolean handleTempBan(CommandSender sender, String admin, String[] args) {
        if (args.length < 5) {
            msg.send(sender, "invalid-usage", "%usage%", "/eb tempban <player|ip> <nickname/Ip> <time> <reason> [server:name] [not:nickname]");
            return true;
        }
        String mode = args[1].toLowerCase();
        String targetArg = args[2];
        String timeStr = args[3];

        long duration = banManager.parseDuration(timeStr);
        if (duration <= 0) {
            msg.send(sender, "invalid-duration");
            return true;
        }

        if (mode.equals("player")) {
            ParsedArgs pr = parseArgs(args, 4);
            banManager.banPlayer(targetArg, admin, pr.reason, duration, pr.server);
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
            banManager.banIp(targetIp, admin, pr.reason, duration, pr.server, pr.exempt);
            kickIp(targetIp, pr.reason, admin, timeStr, pr.server, pr.exempt);
            msg.send(sender, "ban-success", "%player%", targetIp, "%server%", pr.server);
            return true;
        } else {
            msg.send(sender, "invalid-usage", "%usage%", "/eb tempban <player|ip> <nickname/Ip> <time> <reason> [server:name] [not:nickname]");
            return true;
        }
    }

    private boolean handleMute(CommandSender sender, String admin, String[] args) {
        if (args.length < 4) {
            msg.send(sender, "invalid-usage", "%usage%", "/eb mute <player|ip> <nickname/Ip> <reason> [server:name] [not:nickname]");
            return true;
        }
        String mode = args[1].toLowerCase();
        String targetArg = args[2];

        if (mode.equals("player")) {
            ParsedArgs pr = parseArgs(args, 3);
            banManager.mutePlayer(targetArg, admin, pr.reason, -1, pr.server);
            msg.send(sender, "mute-success", "%player%", targetArg, "%server%", pr.server);
            return true;
        } else if (mode.equals("ip")) {
            String targetIp = resolveIp(targetArg);
            if (targetIp == null) {
                msg.send(sender, "player-not-found");
                return true;
            }
            ParsedArgs pr = parseArgs(args, 3);
            banManager.muteIp(targetIp, admin, pr.reason, -1, pr.server, pr.exempt);
            msg.send(sender, "ipmute-success", "%ip%", targetIp, "%server%", pr.server);
            return true;
        } else {
            msg.send(sender, "invalid-usage", "%usage%", "/eb mute <player|ip> <nickname/Ip> <reason> [server:name] [not:nickname]");
            return true;
        }
    }

    private boolean handleTempMute(CommandSender sender, String admin, String[] args) {
        if (args.length < 5) {
            msg.send(sender, "invalid-usage", "%usage%", "/eb tempmute <player|ip> <nickname/Ip> <time> <reason> [server:name] [not:nickname]");
            return true;
        }
        String mode = args[1].toLowerCase();
        String targetArg = args[2];
        String timeStr = args[3];

        long duration = banManager.parseDuration(timeStr);
        if (duration <= 0) {
            msg.send(sender, "invalid-duration");
            return true;
        }

        if (mode.equals("player")) {
            ParsedArgs pr = parseArgs(args, 4);
            banManager.mutePlayer(targetArg, admin, pr.reason, duration, pr.server);
            msg.send(sender, "mute-success", "%player%", targetArg, "%server%", pr.server);
            return true;
        } else if (mode.equals("ip")) {
            String targetIp = resolveIp(targetArg);
            if (targetIp == null) {
                msg.send(sender, "player-not-found");
                return true;
            }
            ParsedArgs pr = parseArgs(args, 4);
            banManager.muteIp(targetIp, admin, pr.reason, duration, pr.server, pr.exempt);
            msg.send(sender, "ipmute-success", "%ip%", targetIp, "%server%", pr.server);
            return true;
        } else {
            msg.send(sender, "invalid-usage", "%usage%", "/eb tempmute <player|ip> <nickname/Ip> <time> <reason> [server:name] [not:nickname]");
            return true;
        }
    }

    private boolean handleDelBan(CommandSender sender, String[] args) {
        if (args.length < 3) {
            msg.send(sender, "invalid-usage", "%usage%", "/eb delban <player|ip> <nickname/Ip> [server:name]");
            return true;
        }
        String mode = args[1].toLowerCase();
        String targetArg = args[2];
        ParsedArgs pr = parseArgs(args, 3);

        if (mode.equals("player")) {
            banManager.unban(targetArg, pr.server);
            String ip = resolveIp(targetArg);
            if (ip != null && banManager.isIpBanned(ip)) {
                banManager.addExemptToIpBan(ip, targetArg, pr.server);
            }
            msg.send(sender, "unban-success", "%player%", targetArg);
            return true;
        } else if (mode.equals("ip")) {
            String targetIp = resolveIp(targetArg);
            if (targetIp == null) {
                msg.send(sender, "player-not-found");
                return true;
            }
            banManager.unbanIp(targetIp, pr.server);
            msg.send(sender, "unban-ip-success", "%ip%", targetIp);
            return true;
        } else {
            msg.send(sender, "invalid-usage", "%usage%", "/eb delban <player|ip> <nickname/Ip> [server:name]");
            return true;
        }
    }

    private boolean handleDelMute(CommandSender sender, String[] args) {
        if (args.length < 3) {
            msg.send(sender, "invalid-usage", "%usage%", "/eb delmute <player|ip> <nickname/Ip> [server:name]");
            return true;
        }
        String mode = args[1].toLowerCase();
        String targetArg = args[2];
        ParsedArgs pr = parseArgs(args, 3);

        if (mode.equals("player")) {
            banManager.unmute(targetArg, pr.server);
            String ip = resolveIp(targetArg);
            if (ip != null && banManager.isIpMuted(ip)) {
                banManager.addExemptToIpMute(ip, targetArg, pr.server);
            }
            msg.send(sender, "unmute-success", "%player%", targetArg);
            return true;
        } else if (mode.equals("ip")) {
            String targetIp = resolveIp(targetArg);
            if (targetIp == null) {
                msg.send(sender, "player-not-found");
                return true;
            }
            banManager.unmuteIp(targetIp, pr.server);
            msg.send(sender, "unmute-ip-success", "%ip%", targetIp);
            return true;
        } else {
            msg.send(sender, "invalid-usage", "%usage%", "/eb delmute <player|ip> <nickname/Ip> [server:name]");
            return true;
        }
    }

    private boolean handleKick(CommandSender sender, String admin, String[] args) {
        if (args.length < 3) {
            msg.send(sender, "invalid-usage", "%usage%", "/eb kick <player> <reason>");
            return true;
        }
        Player targetP = Bukkit.getPlayer(args[1]);
        if (targetP == null) {
            msg.send(sender, "player-not-found");
            return true;
        }
        ParsedArgs pr = parseArgs(args, 2);
        plugin.safeKick(targetP, msg.getBannedLayout(pr.reason, admin, "Kicked", plugin.getConfig().getString("server-name", "survival")));
        msg.send(sender, "kick-success", "%player%", args[1]);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("easyban.use")) return Collections.emptyList();

        String currentServer = plugin.getConfig().getString("server-name", "survival");

        if (args.length == 1) {
            List<String> subcommands = Arrays.asList(
                    "notbreak", "ban", "tempban", "mute", "tempmute", "delban", "delmute", "kick"
            );
            return StringUtil.copyPartialMatches(args[0], subcommands, new ArrayList<>());
        }

        String sub = args[0].toLowerCase();

        if (args.length == 2) {
            if (sub.equals("notbreak")) {
                return StringUtil.copyPartialMatches(args[1], Arrays.asList("give", "remove"), new ArrayList<>());
            }
            if (Arrays.asList("ban", "tempban", "mute", "tempmute", "delban", "delmute").contains(sub)) {
                return StringUtil.copyPartialMatches(args[1], Arrays.asList("player", "ip"), new ArrayList<>());
            }
            if (sub.equals("kick")) {
                return null;
            }
        }

        if (args.length == 3) {
            if (sub.equals("notbreak") || Arrays.asList("ban", "tempban", "mute", "tempmute", "delban", "delmute").contains(sub)) {
                return null;
            }
        }

        if (args.length == 4) {
            if (sub.equals("tempban") || sub.equals("tempmute")) {
                List<String> timeSuggestions = Arrays.asList("30s", "5m", "10m", "1h", "1d", "1w");
                return StringUtil.copyPartialMatches(args[3], timeSuggestions, new ArrayList<>());
            }

            List<String> suggestions = new ArrayList<>();
            suggestions.add("server:all");
            suggestions.add("server:" + currentServer);

            String mode = args.length > 1 ? args[1].toLowerCase() : "";
            if (mode.equals("ip") || Arrays.asList("ban", "tempban", "mute", "tempmute").contains(sub)) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    suggestions.add("not:" + p.getName());
                }
            }
            return StringUtil.copyPartialMatches(args[3], suggestions, new ArrayList<>());
        }

        if (args.length >= 5) {
            List<String> suggestions = new ArrayList<>();
            suggestions.add("server:all");
            suggestions.add("server:" + currentServer);

            for (Player p : Bukkit.getOnlinePlayers()) {
                suggestions.add("not:" + p.getName());
            }
            return StringUtil.copyPartialMatches(args[args.length - 1], suggestions, new ArrayList<>());
        }

        return Collections.emptyList();
    }

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
        Player p = Bukkit.getPlayer(name);
        if (p != null) {
            plugin.safeKick(p, msg.getBannedLayout(reason, admin, time, server));
        }
    }

    private void kickIp(String ip, String reason, String admin, String time, String server, String exemptStr) {
        List<String> exempts = Arrays.asList(exemptStr.toLowerCase().split(","));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getAddress() != null && p.getAddress().getAddress() != null && p.getAddress().getAddress().getHostAddress().equals(ip)) {
                if (!exempts.contains(p.getName().toLowerCase())) {
                    plugin.safeKick(p, msg.getBannedLayout(reason, admin, time, server));
                }
            }
        }
    }

    private String resolveIp(String arg) {
        if (IP_PATTERN.matcher(arg).matches()) {
            return arg;
        }
        Player p = Bukkit.getPlayer(arg);
        if (p != null && p.getAddress() != null && p.getAddress().getAddress() != null) {
            return p.getAddress().getAddress().getHostAddress();
        }
        return null;
    }

    private static class ParsedArgs {
        String reason;
        String server;
        String exempt;
        ParsedArgs(String reason, String server, String exempt) {
            this.reason = reason;
            this.server = server;
            this.exempt = exempt;
        }
    }
}
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
            msg.send(sender, "invalid-usage", "%usage%", "/eb <ban|tempban|kick|mute|tempmute|mute-ip|tempmute-ip|delban|delban-ip|delmute|delmute-ip|ban-ip|tempban-ip|donotbrake|deldonotbrake> ...");
            return true;
        }

        String sub = args[0].toLowerCase();
        String admin = sender.getName();

        // /eb ban (nick) (reason) [server:]
        if (sub.equals("ban")) {
            if (args.length < 3) {
                msg.send(sender, "invalid-usage", "%usage%", "/eb ban <player> <reason> [server:name/all]");
                return true;
            }
            ParsedArgs pr = parseArgs(args, 2);
            banManager.banPlayer(args[1], admin, pr.reason, -1, pr.server);
            kickIfExists(args[1], pr.reason, admin, "Permanent", pr.server);
            msg.send(sender, "ban-success", "%player%", args[1], "%server%", pr.server);
            return true;
        }

        // /eb tempban (nick) (time) (unit) (reason) [server:]
        else if (sub.equals("tempban")) {
            if (args.length < 5) {
                msg.send(sender, "invalid-usage", "%usage%", "/eb tempban <player> <time> <s/m/h/d/w> <reason> [server:name/all]");
                return true;
            }
            long duration = banManager.parseDuration(args[2], args[3]);
            if (duration == 0) return true;

            ParsedArgs pr = parseArgs(args, 4);
            String displayTime = args[2] + args[3];

            banManager.banPlayer(args[1], admin, pr.reason, duration, pr.server);
            kickIfExists(args[1], pr.reason, admin, displayTime, pr.server);
            msg.send(sender, "ban-success", "%player%", args[1], "%server%", pr.server);
            return true;
        }

        // /eb delban (nick) [server:]
        else if (sub.equals("delban")) {
            if (args.length < 2) {
                msg.send(sender, "invalid-usage", "%usage%", "/eb delban <player> [server:name/all]");
                return true;
            }
            ParsedArgs pr = parseArgs(args, 2);
            banManager.unban(args[1], pr.server);
            msg.send(sender, "unban-success", "%player%", args[1]);
            return true;
        }

        // /eb delban-ip (ip/nick) [server:]
        else if (sub.equals("delban-ip")) {
            if (args.length < 2) {
                msg.send(sender, "invalid-usage", "%usage%", "/eb delban-ip <ip/player> [server:name/all]");
                return true;
            }
            String targetIp = resolveIp(args[1]);
            if (targetIp == null) {
                msg.send(sender, "player-not-found");
                return true;
            }
            ParsedArgs pr = parseArgs(args, 2);
            banManager.unbanIp(targetIp, pr.server);
            msg.send(sender, "unban-ip-success", "%ip%", targetIp);
            return true;
        }

        // /eb kick (nick) (reason)
        else if (sub.equals("kick")) {
            if (args.length < 3) return true;
            Player targetP = Bukkit.getPlayer(args[1]);
            if (targetP == null) {
                msg.send(sender, "player-not-found");
                return true;
            }
            ParsedArgs pr = parseArgs(args, 2);
            plugin.safeKick(targetP, msg.getBannedLayout(pr.reason, admin, "Kicked", plugin.getConfig().getString("server-name")));
            msg.send(sender, "kick-success", "%player%", args[1]);
            return true;
        }

        // /eb mute (nick) (reason) [server:]
        else if (sub.equals("mute")) {
            if (args.length < 3) return true;
            ParsedArgs pr = parseArgs(args, 2);
            banManager.mutePlayer(args[1], admin, pr.reason, -1, pr.server);
            msg.send(sender, "mute-success", "%player%", args[1], "%server%", pr.server);
            return true;
        }

        // /eb tempmute (nick) (time) (unit) (reason) [server:]
        else if (sub.equals("tempmute")) {
            if (args.length < 5) return true;
            long duration = banManager.parseDuration(args[2], args[3]);
            if (duration == 0) return true;
            ParsedArgs pr = parseArgs(args, 4);
            banManager.mutePlayer(args[1], admin, pr.reason, duration, pr.server);
            msg.send(sender, "mute-success", "%player%", args[1], "%server%", pr.server);
            return true;
        }

        // /eb mute-ip (ip/player) (reason) [server:] [not:(nick)]
        else if (sub.equals("mute-ip")) {
            if (args.length < 3) return true;
            String targetIp = resolveIp(args[1]);
            if (targetIp == null) {
                msg.send(sender, "player-not-found");
                return true;
            }
            ParsedArgs pr = parseArgs(args, 2);
            banManager.muteIp(targetIp, admin, pr.reason, -1, pr.server, pr.exempt);
            msg.send(sender, "ipmute-success", "%ip%", targetIp, "%server%", pr.server);
            return true;
        }

        // /eb tempmute-ip (ip/player) (time) (unit) (reason) [server:] [not:(nick)]
        else if (sub.equals("tempmute-ip")) {
            if (args.length < 5) return true;
            String targetIp = resolveIp(args[1]);
            if (targetIp == null) return true;
            long duration = banManager.parseDuration(args[2], args[3]);
            if (duration == 0) return true;
            ParsedArgs pr = parseArgs(args, 4);
            banManager.muteIp(targetIp, admin, pr.reason, duration, pr.server, pr.exempt);
            msg.send(sender, "ipmute-success", "%ip%", targetIp, "%server%", pr.server);
            return true;
        }

        // /eb delmute (nick) [server:]
        else if (sub.equals("delmute")) {
            if (args.length < 2) {
                msg.send(sender, "invalid-usage", "%usage%", "/eb delmute <player> [server:name/all]");
                return true;
            }
            ParsedArgs pr = parseArgs(args, 2);
            banManager.unmute(args[1], pr.server);
            String ip = resolveIp(args[1]);
            if (ip != null) {
                banManager.unmuteIp(ip, pr.server);
                msg.send(sender, "unmute-ip-success", "%ip%", ip);
            }
            msg.send(sender, "unmute-success", "%player%", args[1]);
            return true;
        }

        // /eb delmute-ip (ip/nick) [server:]
        else if (sub.equals("delmute-ip")) {
            if (args.length < 2) {
                msg.send(sender, "invalid-usage", "%usage%", "/eb delmute-ip <ip/player> [server:name/all]");
                return true;
            }
            String targetIp = resolveIp(args[1]);
            if (targetIp == null) {
                msg.send(sender, "player-not-found");
                return true;
            }
            ParsedArgs pr = parseArgs(args, 2);
            banManager.unmuteIp(targetIp, pr.server);
            msg.send(sender, "unmute-ip-success", "%ip%", targetIp);
            return true;
        }

        // /eb ban-ip (ip/nick) (reason) [server:] [not:(nick)]
        else if (sub.equals("ban-ip")) {
            if (args.length < 3) return true;
            String targetIp = resolveIp(args[1]);
            if (targetIp == null) return true;
            ParsedArgs pr = parseArgs(args, 2);
            banManager.banIp(targetIp, admin, pr.reason, -1, pr.server, pr.exempt);
            kickIp(targetIp, pr.reason, admin, "Permanent", pr.server, pr.exempt);
            msg.send(sender, "ban-success", "%player%", targetIp, "%server%", pr.server);
            return true;
        }

        // /eb tempban-ip (ip/nick) (time) (unit) (reason) [server:] [not:(nick)]
        else if (sub.equals("tempban-ip")) {
            if (args.length < 5) return true;
            String targetIp = resolveIp(args[1]);
            if (targetIp == null) return true;
            long duration = banManager.parseDuration(args[2], args[3]);
            if (duration == 0) return true;
            ParsedArgs pr = parseArgs(args, 4);
            banManager.banIp(targetIp, admin, pr.reason, duration, pr.server, pr.exempt);
            kickIp(targetIp, pr.reason, admin, args[2]+args[3], pr.server, pr.exempt);
            msg.send(sender, "ban-success", "%player%", targetIp, "%server%", pr.server);
            return true;
        }

        // /eb donotbrake (player) (reason) [server:]
        else if (sub.equals("donotbrake")) {
            if (args.length < 3) return true;
            ParsedArgs pr = parseArgs(args, 2);
            banManager.banBlockBreaking(args[1], admin, pr.reason, pr.server);
            msg.send(sender, "donotbreak-success", "%player%", args[1], "%server%", pr.server);
            return true;
        }

        // /eb deldonotbrake (player) [server:]
        else if (sub.equals("deldonotbrake")) {
            if (args.length < 2) {
                msg.send(sender, "invalid-usage", "%usage%", "/eb deldonotbrake <player> [server:name/all]");
                return true;
            }
            ParsedArgs pr = parseArgs(args, 2);
            banManager.unbanBlockBreaking(args[1], pr.server);
            msg.send(sender, "deldonotbreak-success", "%player%", args[1]);
            return true;
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("easyban.use")) return Collections.emptyList();

        if (args.length == 1) {
            List<String> subcommands = Arrays.asList(
                    "ban", "tempban", "kick", "mute", "tempmute",
                    "delban", "delmute", "ban-ip", "tempban-ip", "delban-ip",
                    "mute-ip", "tempmute-ip", "delmute-ip", "donotbrake", "deldonotbrake"
            );
            return StringUtil.copyPartialMatches(args[0], subcommands, new ArrayList<>());
        }

        if (args.length == 3) {
            String sub = args[0].toLowerCase();
            if (sub.startsWith("del")) {
                List<String> serverOptions = Arrays.asList("server:all", "server:" + plugin.getConfig().getString("server-name", "survival"));
                return StringUtil.copyPartialMatches(args[2], serverOptions, new ArrayList<>());
            }
        }

        if (args.length == 4) {
            String sub = args[0].toLowerCase();
            if (sub.contains("temp")) {
                return StringUtil.copyPartialMatches(args[3], Arrays.asList("s", "m", "h", "d", "w"), new ArrayList<>());
            }
        }
        return Collections.emptyList();
    }

    /**
     * Parses arguments to extract reason, server:xxx, and not:xxx modifiers.
     */
    private ParsedArgs parseArgs(String[] args, int startIdx) {
        String localServer = plugin.getConfig().getString("server-name", "local");
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
        Player p = Bukkit.getPlayer(arg);
        if (p != null && p.getAddress() != null && p.getAddress().getAddress() != null) {
            return p.getAddress().getAddress().getHostAddress();
        }
        if (IP_PATTERN.matcher(arg).matches()) {
            return arg;
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
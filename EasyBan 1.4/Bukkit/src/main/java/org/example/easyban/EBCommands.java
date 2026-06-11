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

public class EBCommands implements CommandExecutor, TabCompleter {

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
            msg.send(sender, "invalid-usage", "%usage%", "/eb <ban|tempban|kick|mute|tempmute|mute-ip|tempmute-ip|delban|delmute|ban-ip|tempban-ip|donotbrake|deldonotbrake> ...");
            return true;
        }

        String sub = args[0].toLowerCase();
        String admin = sender.getName();

        // /eb ban (nick) (reason) [server]
        if (sub.equals("ban")) {
            if (args.length < 3) {
                msg.send(sender, "invalid-usage", "%usage%", "/eb ban <player> <reason> [server/all]");
                return true;
            }
            ParsedReason pr = parseReasonAndServer(args, 2);
            banManager.banPlayer(args[1], admin, pr.reason, -1, pr.server);
            kickIfExists(args[1], pr.reason, admin, "Permanent", pr.server);
            msg.send(sender, "ban-success", "%player%", args[1], "%server%", pr.server);
            return true;
        }

        // /eb tempban (nick) (time) (unit) (reason) [server]
        else if (sub.equals("tempban")) {
            if (args.length < 5) {
                msg.send(sender, "invalid-usage", "%usage%", "/eb tempban <player> <time> <s/m/h/d/w> <reason> [server/all]");
                return true;
            }
            long duration = banManager.parseDuration(args[2], args[3]);
            if (duration == 0) return true;

            ParsedReason pr = parseReasonAndServer(args, 4);
            String displayTime = args[2] + args[3];

            banManager.banPlayer(args[1], admin, pr.reason, duration, pr.server);
            kickIfExists(args[1], pr.reason, admin, displayTime, pr.server);
            msg.send(sender, "ban-success", "%player%", args[1], "%server%", pr.server);
            return true;
        }

        // /eb delban (nick)
        else if (sub.equals("delban")) {
            if (args.length < 2) return true;
            banManager.unban(args[1]);
            msg.send(sender, "unban-success", "%player%", args[1]);
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
            String reason = join(args, 2, args.length);
            plugin.safeKick(targetP, msg.getBannedLayout(reason, admin, "Kicked", plugin.getConfig().getString("server-name")));
            msg.send(sender, "kick-success", "%player%", args[1]);
            return true;
        }

        // /eb mute (nick) (reason) [server]
        else if (sub.equals("mute")) {
            if (args.length < 3) return true;
            ParsedReason pr = parseReasonAndServer(args, 2);
            banManager.mutePlayer(args[1], admin, pr.reason, -1, pr.server);
            msg.send(sender, "mute-success", "%player%", args[1], "%server%", pr.server);
            return true;
        }

        // /eb tempmute (nick) (time) (unit) (reason) [server]
        else if (sub.equals("tempmute")) {
            if (args.length < 5) return true;
            long duration = banManager.parseDuration(args[2], args[3]);
            if (duration == 0) return true;
            ParsedReason pr = parseReasonAndServer(args, 4);
            banManager.mutePlayer(args[1], admin, pr.reason, duration, pr.server);
            msg.send(sender, "mute-success", "%player%", args[1], "%server%", pr.server);
            return true;
        }

        // /eb mute-ip (ip/player) (reason) [server]
        else if (sub.equals("mute-ip")) {
            if (args.length < 3) return true;
            String targetIp = resolveIp(args[1]);
            if (targetIp == null) {
                msg.send(sender, "player-not-found");
                return true;
            }
            ParsedReason pr = parseReasonAndServer(args, 2);
            banManager.muteIp(targetIp, admin, pr.reason, -1, pr.server);
            msg.send(sender, "ipmute-success", "%ip%", targetIp, "%server%", pr.server);
            return true;
        }

        // /eb tempmute-ip (ip/player) (time) (unit) (reason) [server]
        else if (sub.equals("tempmute-ip")) {
            if (args.length < 5) return true;
            String targetIp = resolveIp(args[1]);
            if (targetIp == null) return true;
            long duration = banManager.parseDuration(args[2], args[3]);
            if (duration == 0) return true;
            ParsedReason pr = parseReasonAndServer(args, 4);
            banManager.muteIp(targetIp, admin, pr.reason, duration, pr.server);
            msg.send(sender, "ipmute-success", "%ip%", targetIp, "%server%", pr.server);
            return true;
        }

        // /eb delmute (nick)
        else if (sub.equals("delmute")) {
            if (args.length < 2) return true;
            banManager.unmute(args[1]);
            String ip = resolveIp(args[1]);
            if (ip != null) {
                banManager.unmuteIp(ip);
                msg.send(sender, "unmute-ip-success", "%ip%", ip);
            }
            msg.send(sender, "unmute-success", "%player%", args[1]);
            return true;
        }

        // /eb ban-ip (ip/nick) (reason) [server]
        else if (sub.equals("ban-ip")) {
            if (args.length < 3) return true;
            String targetIp = resolveIp(args[1]);
            if (targetIp == null) return true;
            ParsedReason pr = parseReasonAndServer(args, 2);
            banManager.banIp(targetIp, admin, pr.reason, -1, pr.server);
            kickIp(targetIp, pr.reason, admin, "Permanent", pr.server);
            msg.send(sender, "ban-success", "%player%", targetIp, "%server%", pr.server);
            return true;
        }

        // /eb tempban-ip (ip/nick) (time) (unit) (reason) [server]
        else if (sub.equals("tempban-ip")) {
            if (args.length < 5) return true;
            String targetIp = resolveIp(args[1]);
            if (targetIp == null) return true;
            long duration = banManager.parseDuration(args[2], args[3]);
            if (duration == 0) return true;
            ParsedReason pr = parseReasonAndServer(args, 4);
            banManager.banIp(targetIp, admin, pr.reason, duration, pr.server);
            kickIp(targetIp, pr.reason, admin, args[2]+args[3], pr.server);
            msg.send(sender, "ban-success", "%player%", targetIp, "%server%", pr.server);
            return true;
        }

        // /eb donotbrake (player) (reason) [server]
        else if (sub.equals("donotbrake")) {
            if (args.length < 3) return true;
            ParsedReason pr = parseReasonAndServer(args, 2);
            banManager.banBlockBreaking(args[1], admin, pr.reason, pr.server);
            msg.send(sender, "donotbreak-success", "%player%", args[1], "%server%", pr.server);
            return true;
        }

        // /eb deldonotbrake (player)
        else if (sub.equals("deldonotbrake")) {
            if (args.length < 2) return true;
            banManager.unbanBlockBreaking(args[1]);
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
                    "delban", "delmute", "ban-ip", "tempban-ip",
                    "mute-ip", "tempmute-ip", "donotbrake", "deldonotbrake"
            );
            return StringUtil.copyPartialMatches(args[0], subcommands, new ArrayList<>());
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
     * Parses the reason vararg array. If the last argument is "all" or prefixed with "server:",
     * it correctly identifies the target server. Otherwise, it defaults to the local server config name.
     */
    private ParsedReason parseReasonAndServer(String[] args, int startIdx) {
        String localServer = plugin.getConfig().getString("server-name", "local");
        String lastArg = args[args.length - 1];

        if (lastArg.equalsIgnoreCase("all")) {
            return new ParsedReason(join(args, startIdx, args.length - 1), "all");
        } else if (lastArg.startsWith("server:")) {
            return new ParsedReason(join(args, startIdx, args.length - 1), lastArg.substring(7));
        }
        // If not specified, the entire remaining string is the reason and target is local server
        return new ParsedReason(join(args, startIdx, args.length), localServer);
    }

    private String join(String[] args, int start, int end) {
        if (start >= end) return "No reason provided.";
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) sb.append(args[i]).append(" ");
        return sb.toString().trim();
    }

    private void kickIfExists(String name, String reason, String admin, String time, String server) {
        Player p = Bukkit.getPlayer(name);
        if (p != null) {
            plugin.safeKick(p, msg.getBannedLayout(reason, admin, time, server));
        }
    }

    private void kickIp(String ip, String reason, String admin, String time, String server) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getAddress() != null && p.getAddress().getAddress().getHostAddress().equals(ip)) {
                plugin.safeKick(p, msg.getBannedLayout(reason, admin, time, server));
            }
        }
    }

    private String resolveIp(String arg) {
        Player p = Bukkit.getPlayer(arg);
        if (p != null && p.getAddress() != null) return p.getAddress().getAddress().getHostAddress();
        if (arg.contains(".")) return arg;
        return null;
    }

    private static class ParsedReason {
        String reason;
        String server;
        ParsedReason(String reason, String server) {
            this.reason = reason.isEmpty() ? "No reason provided." : reason;
            this.server = server;
        }
    }
}
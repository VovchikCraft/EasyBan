package org.example.easyban;

import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

public class MessageManager {
    private final EasyBan plugin;
    private FileConfiguration messagesConfig;
    private BukkitAudiences adventure;

    public MessageManager(EasyBan plugin) {
        this.plugin = plugin;
        this.adventure = BukkitAudiences.create(plugin);
        loadMessages();
    }

    private void loadMessages() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        File file = new File(plugin.getDataFolder(), "Messages.yml");
        if (!file.exists()) {
            plugin.saveResource("Messages.yml", false);
        }
        messagesConfig = YamlConfiguration.loadConfiguration(file);
    }

    public void send(CommandSender sender, String key, String... placeholders) {
        String msg = messagesConfig.getString(key, "<red>Missing message: " + key + "</red>");
        String prefix = messagesConfig.getString("prefix", "");

        for (int i = 0; i < placeholders.length; i += 2) {
            msg = msg.replace(placeholders[i], placeholders[i + 1]);
        }

        Component component = MiniMessage.miniMessage().deserialize(prefix + msg);
        adventure.sender(sender).sendMessage(component);
    }

    public void sendUpdateNotification(CommandSender sender, String currentVersion, String newestVersion) {
        if (!(sender instanceof org.bukkit.entity.Player)) {
            sender.sendMessage("A new version of EasyBan is available! (" + newestVersion + ")");
            return;
        }

        org.bukkit.entity.Player player = (org.bukkit.entity.Player) sender;

        ComponentBuilder builder = new ComponentBuilder("--------------------------------------------------\n").color(ChatColor.GOLD)
                .append("Hey, your version is '").color(ChatColor.YELLOW)
                .append(currentVersion).color(ChatColor.RED)
                .append("' and the newest is '").color(ChatColor.YELLOW)
                .append(newestVersion).color(ChatColor.GREEN)
                .append("'!\n").color(ChatColor.YELLOW)
                .append("You can download new version on these resources:\n").color(ChatColor.YELLOW)

                .append("[").color(ChatColor.WHITE)
                .append("GitHub").color(ChatColor.AQUA)
                .event(new ClickEvent(ClickEvent.Action.OPEN_URL, "https://github.com/VovchikCraft/EasyBan"))
                .event(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder("Click to open GitHub").color(ChatColor.GRAY).create()))
                .append("] ").color(ChatColor.WHITE).event((ClickEvent) null).event((HoverEvent) null)

                .append("[").color(ChatColor.WHITE)
                .append("CurseForge").color(ChatColor.GOLD)
                .event(new ClickEvent(ClickEvent.Action.OPEN_URL, "https://www.curseforge.com/minecraft/bukkit-plugins/easyban-"))
                .event(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder("Click to open CurseForge").color(ChatColor.GRAY).create()))
                .append("] ").color(ChatColor.WHITE).event((ClickEvent) null).event((HoverEvent) null)

                .append("[").color(ChatColor.WHITE)
                .append("Hangar").color(ChatColor.BLUE)
                .event(new ClickEvent(ClickEvent.Action.OPEN_URL, "https://hangar.papermc.io/VovchikCraft/EasyBan"))
                .event(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder("Click to open Hangar").color(ChatColor.GRAY).create()))
                .append("]\n").color(ChatColor.WHITE).event((ClickEvent) null).event((HoverEvent) null)

                // Твоє нове повідомлення
                .append("Note: It can be that there is no EasyBan-lite version in this update yet!\n").color(ChatColor.GRAY)
                .append("I'm very thankful that you are using EasyBan, really thanks!\n").color(ChatColor.GOLD)
                .append("--------------------------------------------------").color(ChatColor.GOLD);

        player.spigot().sendMessage(builder.create());
    }

    public String getBannedLayout(String reason, String admin, String time) {
        String raw = messagesConfig.getString("banned-layout", "<red>BANNED: %reason%</red>");
        raw = raw.replace("%reason%", reason)
                .replace("%admin%", admin)
                .replace("%time%", time);

        Component component = MiniMessage.miniMessage().deserialize(raw);
        return LegacyComponentSerializer.legacySection().serialize(component);
    }

    public void shutdown() {
        if (this.adventure != null) {
            this.adventure.close();
        }
    }
}
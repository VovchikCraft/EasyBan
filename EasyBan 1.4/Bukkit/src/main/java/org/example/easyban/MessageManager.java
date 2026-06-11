package org.example.easyban;

import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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

    public String getBannedLayout(String reason, String admin, String time, String server) {
        String raw = messagesConfig.getString("banned-layout", "<red>BANNED: %reason%</red>");
        raw = raw.replace("%reason%", reason)
                .replace("%admin%", admin)
                .replace("%server%", server)
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
package org.example.easyban;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Handles all player-facing messages. Since 1.7 every message is loaded from a
 * per-language file inside the "languages" folder (en, fr, de, uk, es by default).
 * Any key missing from the selected language falls back to the English file that
 * is bundled inside the jar, so partial custom translations never break anything.
 *
 * <p>1.7.1: language files are now auto-completed with new keys (ConfigUpdater)
 * and every user-supplied placeholder value is escaped, so a ban reason can no
 * longer inject MiniMessage tags (clickable commands, colors, ...).</p>
 */
public class MessageManager {

    private final EasyBan plugin;
    private FileConfiguration langConfig;      // The currently selected language file
    private FileConfiguration defaultConfig;   // Bundled en.yml used as a safety fallback
    private String language;                   // Currently active language code

    public MessageManager(EasyBan plugin) {
        this.plugin = plugin;
        loadDefaults();
        loadLanguage();
    }

    /** Loads the bundled English file from the jar as the fallback source. */
    private void loadDefaults() {
        try (Reader reader = new InputStreamReader(
                plugin.getResource("languages/en.yml"), StandardCharsets.UTF_8)) {
            defaultConfig = YamlConfiguration.loadConfiguration(reader);
        } catch (Exception e) {
            plugin.getLogger().severe("Could not load bundled language defaults: " + e.getMessage());
            defaultConfig = new YamlConfiguration();
        }
    }

    /** (Re)loads the language file selected in config.yml. */
    public void loadLanguage() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        File langFolder = new File(plugin.getDataFolder(), "languages");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }

        language = plugin.getConfig().getString("language", "en");
        if (language == null || language.trim().isEmpty()) language = "en";
        File langFile = new File(langFolder, language + ".yml");

        // Unknown language code -> warn and safely fall back to English.
        if (plugin.getResource("languages/" + language + ".yml") == null && !langFile.exists()) {
            plugin.getLogger().warning("Language '" + language + "' is not available, falling back to 'en'.");
            language = "en";
            langFile = new File(langFolder, "en.yml");
        }

        if (!langFile.exists()) {
            // Migration from EasyBan 1.6 and earlier: keep the admin's customized Messages.yml.
            File legacy = new File(plugin.getDataFolder(), "Messages.yml");
            if (legacy.exists()) {
                try {
                    Files.copy(legacy.toPath(), langFile.toPath());
                    plugin.getLogger().info("Migrated your old Messages.yml to " + langFile.getName() + ".");
                } catch (IOException e) {
                    plugin.getLogger().warning("Could not migrate Messages.yml: " + e.getMessage());
                }
            } else if (plugin.getResource("languages/" + language + ".yml") != null) {
                plugin.saveResource("languages/" + language + ".yml", false);
            } else {
                try {
                    langFile.createNewFile();
                } catch (IOException e) {
                    plugin.getLogger().warning("Could not create " + langFile.getName() + ": " + e.getMessage());
                }
            }
        }

        // Append any message keys added by this version - never overwrite custom text.
        String template = (plugin.getResource("languages/" + language + ".yml") != null)
                ? "languages/" + language + ".yml" : "languages/en.yml";
        int added = ConfigUpdater.update(plugin, template, langFile);
        if (added > 0) {
            plugin.getLogger().info("Added " + added + " new message key(s) to " + langFile.getName()
                    + " - your translations were kept.");
        }

        langConfig = YamlConfiguration.loadConfiguration(langFile);
        plugin.getLogger().info("Loaded language: " + language);
    }

    /**
     * Returns a raw (unformatted) message string from the language file.
     * Falls back to English and finally to a visible placeholder so a missing
     * key can never throw an exception.
     */
    public String getRaw(String key) {
        String value = (langConfig == null) ? null : langConfig.getString(key);
        if (value == null) {
            value = defaultConfig.getString(key);
        }
        return (value != null) ? value : "<red>Missing message: " + key + "</red>";
    }

    /**
     * Escapes user-supplied text so MiniMessage renders it literally.
     * Without this a reason like {@code <click:run_command:'/op x'>} would become
     * a real clickable command inside the admin's chat message.
     */
    public String escape(String value) {
        return (value == null) ? "" : MiniMessage.miniMessage().escapeTags(value);
    }

    /** Sends a MiniMessage-formatted language message to any command sender. */
    public void send(CommandSender sender, String key, String... placeholders) {
        String msg = getRaw(key);
        String prefix = getRaw("prefix");
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            msg = msg.replace(placeholders[i], escape(placeholders[i + 1]));
        }
        Component component = MiniMessage.miniMessage().deserialize(prefix + msg);
        // Paper's CommandSender is a native Adventure Audience, no platform needed.
        sender.sendMessage(component);
    }

    /**
     * Sends formatted, non-configurable update message with clickable resource buttons.
     * Since 1.7.1 this uses native Adventure (MiniMessage) instead of the legacy
     * BungeeCord chat API, which keeps it working on the newest Paper versions.
     */
    public void sendUpdateNotification(CommandSender sender, String currentVersion, String newestVersion) {
        if (!(sender instanceof org.bukkit.entity.Player)) {
            // Console gets a simple text line without clickable buttons.
            sender.sendMessage("A new version of EasyBan is available! (" + newestVersion + ")");
            return;
        }

        String message = "<gold>--------------------------------------------------</gold><newline>"
                + "<yellow>Hey, your version is '</yellow><red>" + escape(currentVersion)
                + "</red><yellow>' and the newest is '</yellow><green>" + escape(newestVersion)
                + "</green><yellow>'!</yellow><newline>"
                + "<yellow>You can download new version on these resources:</yellow><newline>"
                + "<white>[</white><aqua><click:open_url:'https://github.com/VovchikCraft/EasyBan'>"
                + "<hover:show_text:'<gray>Click to open GitHub</gray>'>GitHub</hover></click></aqua><white>]</white> "
                + "<white>[</white><gold><click:open_url:'https://www.curseforge.com/minecraft/bukkit-plugins/easybann'>"
                + "<hover:show_text:'<gray>Click to open CurseForge</gray>'>CurseForge</hover></click></gold><white>]</white> "
                + "<white>[</white><blue><click:open_url:'https://hangar.papermc.io/VovchikCraft/EasyBan'>"
                + "<hover:show_text:'<gray>Click to open Hangar</gray>'>Hangar</hover></click></blue><white>]</white><newline>"
                + "<gold>I'm very thankful that you are using EasyBan, really thanks!</gold><newline>"
                + "<gold>--------------------------------------------------</gold>";

        sender.sendMessage(MiniMessage.miniMessage().deserialize(message));
    }

    /**
     * Adventure Component version of the punishment screen (used for kicks and
     * login denials on modern Paper versions).
     */
    public Component getBannedLayoutComponent(String reason, String admin, String time, String server) {
        String raw = getRaw("banned-layout");
        raw = raw.replace("%reason%", escape(reason))
                .replace("%admin%", escape(admin))
                .replace("%server%", escape(server))
                .replace("%time%", escape(time));
        return MiniMessage.miniMessage().deserialize(raw);
    }

    /**
     * Legacy section-string version of the punishment screen, kept for
     * backwards compatibility with anything that still expects a plain String.
     */
    public String getBannedLayout(String reason, String admin, String time, String server) {
        return LegacyComponentSerializer.legacySection()
                .serialize(getBannedLayoutComponent(reason, admin, time, server));
    }

    /** Returns the currently active language code (e.g. "en"). */
    public String getLanguage() {
        return language;
    }

    /** Reloads the language file from disk (used by /eb reload). */
    public void reload() {
        loadDefaults();
        loadLanguage();
    }

    public void shutdown() {
        // Nothing to close anymore: Paper provides Adventure natively.
    }
}
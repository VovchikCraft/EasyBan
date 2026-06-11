package org.example.easybanvelocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Plugin(
        id = "easyban-velocity",
        name = "EasyBan Velocity",
        version = "1.4",
        authors = {"VovchikCraft"}
)
public class EasyBanVelocity {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private String dbUrl;
    private String dbUser;
    private String dbPass;
    private String dbTable;
    private int syncInterval = 4;

    // Кеш банів (ключ - IP або нік у нижньому регістрі)
    private final Map<String, BanRecord> activeBans = new ConcurrentHashMap<>();

    @Inject
    public EasyBanVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        loadConfig();


        if (dbUrl != null) {
            server.getScheduler()
                    .buildTask(this, this::syncFromDatabase)
                    .repeat(syncInterval, TimeUnit.SECONDS)
                    .schedule();
            logger.info("EasyBan Velocity Enabled! Syncing MySQL every " + syncInterval + " seconds.");
        }
    }

    @SuppressWarnings("unchecked")
    private void loadConfig() {
        File folder = dataDirectory.toFile();
        if (!folder.exists()) folder.mkdirs();

        File configFile = new File(folder, "config.yml");
        if (!configFile.exists()) {
            try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                if (in != null) Files.copy(in, configFile.toPath());
            } catch (Exception e) {
                logger.error("Could not save config.yml", e);
            }
        }

        try (InputStream inputStream = new FileInputStream(configFile)) {
            Yaml yaml = new Yaml();
            Map<String, Object> config = yaml.load(inputStream);
            Map<String, Object> storage = (Map<String, Object>) config.get("storage");
            Map<String, Object> mysql = (Map<String, Object>) storage.get("mysql");

            // Читання значень безпечно
            this.syncInterval = storage.containsKey("sync-interval") ? ((Number) storage.get("sync-interval")).intValue() : 4;
            String host = String.valueOf(mysql.get("host"));
            int port = ((Number) mysql.get("port")).intValue();
            String database = String.valueOf(mysql.get("database"));

            this.dbUser = String.valueOf(mysql.get("username"));
            this.dbPass = String.valueOf(mysql.get("password"));
            this.dbTable = mysql.get("table-prefix") + "punishments";
            this.dbUrl = "jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false&autoReconnect=true";

            // ЯВНО завантажуємо драйвер MySQL, щоб уникнути помилок підключення
            Class.forName("com.mysql.cj.jdbc.Driver");

        } catch (Exception e) {
            logger.error("Failed to load config.yml or MySQL Driver! Check your setup.", e);
        }
    }

    private void syncFromDatabase() {
        String sql = "SELECT * FROM " + dbTable + " WHERE server = 'all' AND type IN ('BAN', 'IPBAN')";

        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPass);
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            activeBans.clear();

            while (rs.next()) {
                String target = rs.getString("target").toLowerCase();
                long expiry = rs.getLong("expiry");

                // Якщо термін бану вийшов - пропускаємо
                if (expiry > 0 && System.currentTimeMillis() > expiry) continue;

                BanRecord record = new BanRecord(
                        rs.getString("reason"),
                        rs.getString("admin"),
                        expiry
                );
                activeBans.put(target, record);
            }

            // Перевіряємо онлайн гравців
            for (Player player : server.getAllPlayers()) {
                String name = player.getUsername().toLowerCase();
                String ip = player.getRemoteAddress().getAddress().getHostAddress();

                if (activeBans.containsKey(name)) {
                    player.disconnect(getKickMessage(activeBans.get(name)));
                } else if (activeBans.containsKey(ip)) {
                    player.disconnect(getKickMessage(activeBans.get(ip)));
                }
            }

        } catch (SQLException e) {
            logger.warn("Failed to sync from database: " + e.getMessage());
        }
    }

    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        String ip = event.getConnection().getRemoteAddress().getAddress().getHostAddress();
        if (activeBans.containsKey(ip)) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(getKickMessage(activeBans.get(ip))));
        }
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        String name = event.getPlayer().getUsername().toLowerCase();
        if (activeBans.containsKey(name)) {
            event.setResult(LoginEvent.ComponentResult.denied(getKickMessage(activeBans.get(name))));
        }
    }

    private Component getKickMessage(BanRecord record) {
        String raw = "<red><bold>YOU ARE BANNED FROM THE NETWORK!</bold></red><newline><newline>" +
                "<gray>Reason:</gray> <white>" + record.reason + "</white><newline>" +
                "<gray>Banned by:</gray> <white>" + record.admin + "</white><newline>" +
                "<gray>Expires:</gray> <white>" + record.getFormattedTime() + "</white>";
        return MiniMessage.miniMessage().deserialize(raw);
    }

    // Клас для утримання даних
    private static class BanRecord {
        final String reason;
        final String admin;
        final long expiry;

        BanRecord(String reason, String admin, long expiry) {
            this.reason = reason;
            this.admin = admin;
            this.expiry = expiry;
        }

        String getFormattedTime() {
            return expiry == -1 ? "Permanent" : new Date(expiry).toString();
        }
    }
}
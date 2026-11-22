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
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * EasyBan Velocity 1.7 - network-wide ban enforcement for the proxy.
 *
 * <p>Changes since 1.6:</p>
 * <ul>
 *   <li>configurable SSL via {@code storage.mysql.ssl-mode} (same values as the backend)</li>
 *   <li>config.yml is auto-completed with new options, nothing is ever overwritten</li>
 *   <li>the ban cache is swapped atomically (the old clear()/putAll() left a window
 *       where nobody was banned)</li>
 *   <li>IP bans are also enforced in LoginEvent, null-safe IPs, Locale.ROOT casing</li>
 *   <li>customizable ban screen + MiniMessage escaping of user supplied text</li>
 * </ul>
 */
@Plugin(
        id = "easyban-velocity",
        name = "EasyBan Velocity",
        version = "1.7",
        authors = {"VovchikCraft"}
)
public class EasyBanVelocity {

    private static final String DEFAULT_LAYOUT =
            "<red><bold>YOU ARE BANNED FROM THE NETWORK!</bold></red><newline><newline>"
                    + "<gray>Reason:</gray> <white>%reason%</white><newline>"
                    + "<gray>Banned by:</gray> <white>%admin%</white><newline>"
                    + "<gray>Expires:</gray> <white>%time%</white>";

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private String dbUrl;
    private String dbUser;
    private String dbPass;
    private String dbTable;
    private int syncInterval = 4;

    private String bannedLayout = DEFAULT_LAYOUT;
    private String permanentLabel = "Permanent";

    /**
     * Snapshot of all network-wide bans (key = lowercase name or IP).
     * Replaced as a whole on every sync so lookups never see a half-empty map.
     */
    private volatile Map<String, BanRecord> activeBans = new ConcurrentHashMap<>();

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
            checkDatabaseAndServers();
            server.getScheduler()
                    .buildTask(this, this::safeSync)
                    .repeat(syncInterval, TimeUnit.SECONDS)
                    .schedule();
        }
    }

    // ------------------------------------------------------------------ //
    //  Configuration                                                      //
    // ------------------------------------------------------------------ //

    private void loadConfig() {
        File folder = dataDirectory.toFile();
        if (!folder.exists()) folder.mkdirs();

        Path configPath = dataDirectory.resolve("config.yml");
        File configFile = configPath.toFile();
        if (!configFile.exists()) {
            try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                if (in != null) Files.copy(in, configPath);
            } catch (Exception e) {
                logger.error("Could not save config.yml", e);
            }
        }

        // Append new options of this version without touching anything the admin wrote.
        int added = ConfigUpdater.update(getClass(), logger, "config.yml", configPath);
        if (added > 0) {
            logger.info("Added {} new option(s) to config.yml - your existing settings were kept.", added);
        }

        Map<String, Object> config;
        try (InputStream inputStream = new FileInputStream(configFile)) {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            config = yaml.load(inputStream);
        } catch (Exception e) {
            logger.error("Failed to read config.yml! Check your setup.", e);
            return;
        }
        if (config == null) {
            logger.error("config.yml is empty - EasyBan Velocity will stay inactive.");
            return;
        }

        Map<String, Object> storage = section(config, "storage");
        Map<String, Object> mysql = section(storage, "mysql");
        Map<String, Object> messages = section(config, "messages");

        this.syncInterval = Math.max(1, asInt(storage.get("sync-interval"), 4));

        String host = asString(mysql.get("host"), "localhost");
        int port = asInt(mysql.get("port"), 3306);
        String database = asString(mysql.get("database"), "easyban");
        this.dbUser = asString(mysql.get("username"), "root");
        this.dbPass = asString(mysql.get("password"), "");
        this.dbTable = sanitizePrefix(asString(mysql.get("table-prefix"), "eb_")) + "punishments";

        String sslMode = asString(mysql.get("ssl-mode"), "disable");
        this.dbUrl = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=UTF-8"
                + "&autoReconnect=true&tcpKeepAlive=true"
                + "&connectTimeout=5000&socketTimeout=15000"
                + "&" + sslParameters(sslMode);

        this.bannedLayout = asString(messages.get("banned-layout"), DEFAULT_LAYOUT);
        this.permanentLabel = asString(messages.get("permanent"), "Permanent");

        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            logger.error("MySQL JDBC driver not found - EasyBan Velocity will stay inactive.", e);
            this.dbUrl = null;
        }
    }

    /**
     * Translates the friendly ssl-mode into Connector/J parameters.
     * Both the modern "sslMode" and the legacy "useSSL" family are sent so this
     * works with old and new MySQL drivers alike. Identical to the backend plugin.
     */
    private String sslParameters(String mode) {
        String m = (mode == null) ? "disable" : mode.trim().toLowerCase(Locale.ROOT);
        switch (m) {
            case "enable":
            case "enabled":
            case "true":
            case "require":
            case "required":
                return "sslMode=REQUIRED&useSSL=true&requireSSL=true&verifyServerCertificate=false";
            case "verify":
            case "verify-ca":
            case "verify_ca":
                return "sslMode=VERIFY_CA&useSSL=true&requireSSL=true&verifyServerCertificate=true";
            case "verify-full":
            case "verify_full":
                return "sslMode=VERIFY_FULL&useSSL=true&requireSSL=true&verifyServerCertificate=true";
            case "disable":
            case "disabled":
            case "false":
            case "":
                return "sslMode=DISABLED&useSSL=false&requireSSL=false&verifyServerCertificate=false"
                        + "&allowPublicKeyRetrieval=true";
            default:
                logger.warn("Unknown storage.mysql.ssl-mode '{}' - using 'disable'. Valid values: disable, enable, verify.", mode);
                return "sslMode=DISABLED&useSSL=false&requireSSL=false&verifyServerCertificate=false"
                        + "&allowPublicKeyRetrieval=true";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> section(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        if (value != null) {
            logger.warn("Config section '{}' is not a section - using defaults for it.", key);
        } else {
            logger.warn("Config section '{}' is missing - using defaults for it.", key);
        }
        return Collections.emptyMap();
    }

    private String asString(Object value, String def) {
        return (value == null) ? def : String.valueOf(value);
    }

    private int asInt(Object value, int def) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    /** Only letters, digits and underscores are allowed in a table prefix. */
    private String sanitizePrefix(String prefix) {
        if (prefix == null) return "";
        String clean = prefix.replaceAll("[^A-Za-z0-9_]", "");
        if (!clean.equals(prefix)) {
            logger.warn("storage.mysql.table-prefix contained invalid characters and was cleaned to '{}'.", clean);
        }
        return clean;
    }

    private void checkDatabaseAndServers() {
        boolean connected = false;
        boolean foundServers = false;

        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPass)) {
            connected = true;
            String sql = "SELECT COUNT(DISTINCT server) FROM " + dbTable;
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    foundServers = true;
                }
            }
        } catch (SQLException e) {
            logger.warn("Could not connect to database: {}", e.getMessage());
        }

        if (!connected || !foundServers) {
            logger.warn("EasyBan-Velocity enabled, but no backend EasyBan servers were found in the database. EasyBan-Velocity works only as an addition to main EasyBan.");
        } else {
            logger.info("EasyBan-Velocity enabled successfully! Connected to database and synced with backend servers.");
        }
    }

    // ------------------------------------------------------------------ //
    //  Sync engine                                                        //
    // ------------------------------------------------------------------ //

    /** A repeating task must never die because of an unexpected exception. */
    private void safeSync() {
        try {
            syncFromDatabase();
        } catch (Throwable t) {
            logger.warn("Punishment sync failed: {}", t.toString());
        }
    }

    private void syncFromDatabase() {
        if (dbUrl == null) return;

        // SELECT * on purpose: tables created by very old EasyBan versions may not
        // have the "exempt" column yet, and that must not break the whole sync.
        String sql = "SELECT * FROM " + dbTable + " WHERE server = 'all' AND type IN ('BAN', 'IPBAN')";
        Map<String, BanRecord> fetchedBans = new ConcurrentHashMap<>();

        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPass);
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            long now = System.currentTimeMillis();
            while (rs.next()) {
                String target = rs.getString("target");
                if (target == null || target.isEmpty()) continue;

                long expiry = rs.getLong("expiry");
                if (expiry > 0 && now > expiry) continue;

                String exempt = "";
                try {
                    exempt = rs.getString("exempt");
                } catch (SQLException ignored) {
                }

                BanRecord record = new BanRecord(
                        rs.getString("reason"),
                        rs.getString("admin"),
                        expiry,
                        exempt
                );
                fetchedBans.put(target.toLowerCase(Locale.ROOT), record);
            }
        } catch (SQLException e) {
            logger.warn("Failed to sync from database: {}", e.getMessage());
            return; // keep the previous snapshot instead of unbanning everyone
        }

        // Atomic swap: the old clear() + putAll() left a window with an empty ban list.
        activeBans = fetchedBans;
        enforceOnlinePlayers();
    }

    /** Disconnects everyone who is banned network-wide right now. */
    private void enforceOnlinePlayers() {
        Map<String, BanRecord> snapshot = activeBans;
        for (Player player : server.getAllPlayers()) {
            BanRecord record = findBan(player.getUsername(), addressOf(player.getRemoteAddress()), snapshot);
            if (record != null) {
                player.disconnect(getKickMessage(record));
            }
        }
    }

    /** Name ban first, then IP ban (respecting the exempt list). */
    private BanRecord findBan(String name, String ip, Map<String, BanRecord> bans) {
        if (name != null && !name.isEmpty()) {
            BanRecord record = bans.get(name.toLowerCase(Locale.ROOT));
            if (record != null) {
                return record;
            }
        }
        if (ip != null && !ip.isEmpty()) {
            BanRecord record = bans.get(ip.toLowerCase(Locale.ROOT));
            if (record != null && !isExempt(name, record.exempt)) {
                return record;
            }
        }
        return null;
    }

    private String addressOf(InetSocketAddress address) {
        if (address == null || address.getAddress() == null) return null;
        return address.getAddress().getHostAddress();
    }

    // ------------------------------------------------------------------ //
    //  Listeners                                                          //
    // ------------------------------------------------------------------ //

    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        String ip = addressOf(event.getConnection().getRemoteAddress());
        String name = event.getUsername();

        BanRecord record = findBan(name, ip, activeBans);
        if (record != null) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(getKickMessage(record)));
        }
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        // 1.7: IP bans are checked here too, not only during PreLogin.
        BanRecord record = findBan(player.getUsername(), addressOf(player.getRemoteAddress()), activeBans);
        if (record != null) {
            event.setResult(LoginEvent.ComponentResult.denied(getKickMessage(record)));
        }
    }

    private boolean isExempt(String name, String exemptStr) {
        if (name == null || exemptStr == null || exemptStr.isEmpty()) return false;
        for (String ex : exemptStr.split(",")) {
            if (ex.trim().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ //
    //  Ban screen                                                         //
    // ------------------------------------------------------------------ //

    private Component getKickMessage(BanRecord record) {
        String raw = bannedLayout
                .replace("%reason%", escape(record.reason))
                .replace("%admin%", escape(record.admin))
                .replace("%time%", escape(record.getFormattedTime()));
        return MiniMessage.miniMessage().deserialize(raw);
    }

    /** User supplied text must never be able to inject MiniMessage tags. */
    private String escape(String value) {
        return (value == null) ? "" : MiniMessage.miniMessage().escapeTags(value);
    }

    private class BanRecord {
        final String reason;
        final String admin;
        final long expiry;
        final String exempt;

        BanRecord(String reason, String admin, long expiry, String exempt) {
            this.reason = reason;
            this.admin = admin;
            this.expiry = expiry;
            this.exempt = exempt == null ? "" : exempt;
        }

        String getFormattedTime() {
            if (expiry == -1 || expiry == 0) {
                return permanentLabel;
            }
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(expiry));
        }
    }
}
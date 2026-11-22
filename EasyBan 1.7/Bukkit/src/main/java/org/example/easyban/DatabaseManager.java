package org.example.easyban;

import org.bukkit.configuration.file.FileConfiguration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * MySQL backend of EasyBan.
 *
 * <p>1.7.1 changes:</p>
 * <ul>
 *   <li>configurable SSL via {@code storage.mysql.ssl-mode} (disable / enable / verify)</li>
 *   <li>connection + socket timeouts so a dead database can never freeze the storage thread</li>
 *   <li>the unique key now includes the server column, so the same player can be
 *       punished independently on different servers (old tables are migrated)</li>
 * </ul>
 */
public class DatabaseManager {

    private final EasyBan plugin;

    private String url;
    private String username;
    private String password;
    private String table;
    private boolean mysql;
    private boolean ready;

    public DatabaseManager(EasyBan plugin) {
        this.plugin = plugin;
        reload();
    }

    /** (Re)reads every storage.mysql.* option. Called on start and by /eb reload. */
    public void reload() {
        FileConfiguration config = plugin.getConfig();
        this.mysql = config.getString("storage.type", "local").equalsIgnoreCase("mysql");
        this.ready = false;
        if (!mysql) {
            return;
        }

        String host = config.getString("storage.mysql.host", "localhost");
        int port = config.getInt("storage.mysql.port", 3306);
        String database = config.getString("storage.mysql.database", "easyban");
        this.username = config.getString("storage.mysql.username", "root");
        this.password = config.getString("storage.mysql.password", "");
        this.table = sanitizePrefix(config.getString("storage.mysql.table-prefix", "eb_")) + "punishments";

        String sslMode = config.getString("storage.mysql.ssl-mode", "disable");
        this.url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=UTF-8"
                + "&autoReconnect=true&tcpKeepAlive=true"
                + "&connectTimeout=5000&socketTimeout=15000"
                + "&" + sslParameters(sslMode);

        loadDriver();
        setupTable();
    }

    /**
     * Translates the friendly ssl-mode into Connector/J parameters.
     * Both the modern "sslMode" and the legacy "useSSL" family are sent, so this
     * works with old (5.1) and new (8.x / 9.x) MySQL drivers alike.
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
                plugin.getLogger().warning("Unknown storage.mysql.ssl-mode '" + mode
                        + "' - using 'disable'. Valid values: disable, enable, verify.");
                return "sslMode=DISABLED&useSSL=false&requireSSL=false&verifyServerCertificate=false"
                        + "&allowPublicKeyRetrieval=true";
        }
    }

    /** Only letters, digits and underscores are allowed in a table prefix. */
    private String sanitizePrefix(String prefix) {
        if (prefix == null) return "";
        String clean = prefix.replaceAll("[^A-Za-z0-9_]", "");
        if (!clean.equals(prefix)) {
            plugin.getLogger().warning("storage.mysql.table-prefix contained invalid characters and was cleaned to '"
                    + clean + "'.");
        }
        return clean;
    }

    private void loadDriver() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            try {
                Class.forName("com.mysql.jdbc.Driver");
            } catch (ClassNotFoundException ignored) {
                plugin.getLogger().severe("No MySQL JDBC driver was found on this server. "
                        + "MySQL storage will not work until a driver is available.");
            }
        }
    }

    public Connection getConnection() throws SQLException {
        if (!mysql || url == null) {
            throw new SQLException("MySQL storage is not enabled.");
        }
        return DriverManager.getConnection(url, username, password);
    }

    private void setupTable() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY,"
                    + "type VARCHAR(16) NOT NULL,"
                    + "target VARCHAR(64) NOT NULL,"
                    + "admin VARCHAR(64) NOT NULL,"
                    + "reason TEXT,"
                    + "expiry BIGINT NOT NULL,"
                    + "server VARCHAR(64) NOT NULL,"
                    + "exempt VARCHAR(255) DEFAULT '',"
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                    + "UNIQUE KEY idx_type_target_server (type, target, server)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            // Safe update for tables created by EasyBan 1.6 and earlier.
            if (!columnExists(conn, "exempt")) {
                try {
                    stmt.execute("ALTER TABLE " + table + " ADD COLUMN exempt VARCHAR(255) DEFAULT ''");
                } catch (SQLException e) {
                    plugin.getLogger().warning("Could not add the 'exempt' column: " + e.getMessage());
                }
            }

            /*
             * CRITICAL MIGRATION (1.7.1):
             * The old unique key was (type, target) WITHOUT the server column.
             * Because of that, a ban issued on server A silently overwrote the ban
             * of the same player on server B, and removals deleted the wrong row.
             */
            boolean oldKey = indexExists(conn, "idx_type_target");
            boolean newKey = indexExists(conn, "idx_type_target_server");
            if (!newKey) {
                try {
                    if (oldKey) {
                        stmt.execute("ALTER TABLE " + table
                                + " DROP INDEX idx_type_target,"
                                + " ADD UNIQUE KEY idx_type_target_server (type, target, server)");
                    } else {
                        stmt.execute("ALTER TABLE " + table
                                + " ADD UNIQUE KEY idx_type_target_server (type, target, server)");
                    }
                    plugin.getLogger().info("MySQL table migrated: punishments are now stored per server.");
                } catch (SQLException e) {
                    plugin.getLogger().warning("Could not migrate the punishment index: " + e.getMessage());
                }
            }

            ready = true;
            plugin.getLogger().info("MySQL storage ready (table '" + table + "', ssl-mode: "
                    + plugin.getConfig().getString("storage.mysql.ssl-mode", "disable") + ").");
        } catch (SQLException e) {
            ready = false;
            plugin.getLogger().severe("Failed to connect or create MySQL table: " + e.getMessage());
        }
    }

    private boolean columnExists(Connection conn, String column) throws SQLException {
        String sql = "SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private boolean indexExists(Connection conn, String index) throws SQLException {
        String sql = "SELECT COUNT(*) FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, index);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    public String getTable() {
        return table;
    }

    /** True when storage.type is mysql. */
    public boolean isMySQL() {
        return mysql;
    }

    /** True when the table was created/verified successfully. */
    public boolean isReady() {
        return ready;
    }
}
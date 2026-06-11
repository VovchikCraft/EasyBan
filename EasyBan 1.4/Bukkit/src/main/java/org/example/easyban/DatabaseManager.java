package org.example.easyban;

import org.bukkit.configuration.file.FileConfiguration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {
    private final EasyBan plugin;
    private String url;
    private String username;
    private String password;
    private String table;

    public DatabaseManager(EasyBan plugin) {
        this.plugin = plugin;
        FileConfiguration config = plugin.getConfig();

        if (config.getString("storage.type", "local").equalsIgnoreCase("mysql")) {
            String host = config.getString("storage.mysql.host", "localhost");
            int port = config.getInt("storage.mysql.port", 3306);
            String database = config.getString("storage.mysql.database", "easyban");
            this.username = config.getString("storage.mysql.username", "root");
            this.password = config.getString("storage.mysql.password", "");
            String prefix = config.getString("storage.mysql.table-prefix", "eb_");

            this.table = prefix + "punishments";
            this.url = "jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false&autoReconnect=true";

            setupTable();
        }
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    private void setupTable() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            String sql = "CREATE TABLE IF NOT EXISTS " + table + " (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY," +
                    "type VARCHAR(16) NOT NULL," +
                    "target VARCHAR(64) NOT NULL," +
                    "admin VARCHAR(64) NOT NULL," +
                    "reason TEXT," +
                    "expiry BIGINT NOT NULL," +
                    "server VARCHAR(64) NOT NULL," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "UNIQUE KEY idx_type_target (type, target)" +
                    ")";
            stmt.execute(sql);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to connect or create MySQL table: " + e.getMessage());
        }
    }

    public String getTable() {
        return table;
    }
}
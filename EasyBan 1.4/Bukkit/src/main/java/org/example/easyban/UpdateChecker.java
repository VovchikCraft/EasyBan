package org.example.easyban;

import org.bukkit.Bukkit;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateChecker {
    private final EasyBan plugin;
    private String latestVersion = null;
    private boolean updateAvailable = false;

    public UpdateChecker(EasyBan plugin) {
        this.plugin = plugin;
    }

    public void fetch() {
        // Виконуємо асинхронно, щоб не зупиняти завантаження сервера
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // Використовуємо raw посилання для отримання чистого тексту
                URL url = new URL("https://raw.githubusercontent.com/VovchikCraft/EasyBan/main/newestversion.yml");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String firstLine = reader.readLine();
                reader.close();

                if (firstLine != null && !firstLine.isEmpty()) {
                    // Якщо у файлі написано "version: 1.5", залишаємо тільки цифри
                    this.latestVersion = firstLine.replace("version:", "").replace("\"", "").replace("'", "").trim();
                    String currentVersion = plugin.getDescription().getVersion();

                    if (!currentVersion.equalsIgnoreCase(latestVersion)) {
                        updateAvailable = true;
                        plugin.getLogger().info("A new version of EasyBan is available! Version: " + latestVersion);
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Unable to check for EasyBan updates: " + e.getMessage());
            }
        });
    }

    public boolean isUpdateAvailable() {
        return updateAvailable;
    }

    public String getLatestVersion() {
        return latestVersion;
    }
}
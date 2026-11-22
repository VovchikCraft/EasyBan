package org.example.easyban;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Checks GitHub for a newer EasyBan release.
 *
 * <p>1.7.1: the version is now really compared (numerically), so a newer local
 * build is no longer reported as "outdated", the request has a User-Agent and
 * proper timeouts, and fetch() must be called from an async task
 * (EasyBan schedules it through SchedulerCompat, which also works on Folia).</p>
 */
public class UpdateChecker {

    private static final String UPDATE_URL =
            "https://raw.githubusercontent.com/VovchikCraft/EasyBan/main/newestversion.yml";

    private final EasyBan plugin;
    private volatile String latestVersion = null;
    private volatile boolean updateAvailable = false;

    public UpdateChecker(EasyBan plugin) {
        this.plugin = plugin;
    }

    /** Performs the check. MUST be called off the main thread. */
    public void fetch() {
        HttpURLConnection connection = null;
        try {
            URL url = URI.create(UPDATE_URL).toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("User-Agent", "EasyBan/" + plugin.getDescription().getVersion());

            if (connection.getResponseCode() != 200) {
                plugin.getLogger().warning("Update check returned HTTP " + connection.getResponseCode());
                return;
            }

            String version = null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                    int idx = trimmed.indexOf(':');
                    if (idx < 0) continue;
                    if (!trimmed.substring(0, idx).trim().equalsIgnoreCase("version")) continue;
                    version = trimmed.substring(idx + 1).trim()
                            .replace("\"", "").replace("'", "");
                    break;
                }
            }

            if (version == null || version.isEmpty()) return;

            this.latestVersion = version;
            String currentVersion = plugin.getDescription().getVersion();
            if (compareVersions(version, currentVersion) > 0) {
                if (!updateAvailable) {
                    plugin.getLogger().info("A new version of EasyBan is available! Version: " + version);
                }
                updateAvailable = true;
            } else {
                updateAvailable = false;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Unable to check for EasyBan updates: " + e.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Numeric, part-by-part comparison: 1.7.1 &gt; 1.7, 1.10 &gt; 1.9. */
    private static int compareVersions(String a, String b) {
        String[] pa = a.trim().split("[^0-9]+");
        String[] pb = b.trim().split("[^0-9]+");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int va = parse(pa, i);
            int vb = parse(pb, i);
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    private static int parse(String[] parts, int i) {
        if (i >= parts.length || parts[i].isEmpty()) return 0;
        try {
            return Integer.parseInt(parts[i]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public boolean isUpdateAvailable() {
        return updateAvailable;
    }

    public String getLatestVersion() {
        return latestVersion;
    }
}
package org.example.easyban;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.CompletionException;

/**
 * Discord webhook integration for EasyBan (introduced in 1.7).
 *
 * This class does NOT need a Discord bot - it simply posts JSON embeds to the
 * webhook URL that a server administrator creates in their Discord server
 * settings (Server Settings -> Integrations -> Webhooks).
 *
 * All network traffic runs on Java's built-in async HTTP client, so the game
 * thread is never blocked by slow networks or Discord outages.
 */
public class DiscordManager {

    // Embed accent colors (decimal, as required by the Discord API).
    private static final int COLOR_BAN = 15158332;        // red
    private static final int COLOR_MUTE = 15105570;       // orange
    private static final int COLOR_KICK = 15844367;       // gold
    private static final int COLOR_REMOVAL = 3066993;     // green
    private static final int COLOR_BLOCKBREAK = 10181046; // purple
    private static final int COLOR_TEST = 5763719;        // blue

    private static final DateTimeFormatter ISO_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneId.of("UTC"));

    private final EasyBan plugin;
    private final MessageManager msg;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // Cached config values, refreshed by reload().
    private boolean enabled;
    private String url;
    private String username;
    private String avatarUrl;
    private boolean notifyBan;
    private boolean notifyMute;
    private boolean notifyKick;
    private boolean notifyUnpunish;
    private boolean notifyBlockBreak;

    public DiscordManager(EasyBan plugin, MessageManager msg) {
        this.plugin = plugin;
        this.msg = msg;
        reload();
    }

    /** Re-reads every discord.* option from the config. */
    public void reload() {
        enabled = plugin.getConfig().getBoolean("discord.enabled", false);
        url = plugin.getConfig().getString("discord.url", "");
        username = plugin.getConfig().getString("discord.username", "EasyBan");
        avatarUrl = plugin.getConfig().getString("discord.avatar-url", "");
        notifyBan = plugin.getConfig().getBoolean("discord.notify.ban", true);
        notifyMute = plugin.getConfig().getBoolean("discord.notify.mute", true);
        notifyKick = plugin.getConfig().getBoolean("discord.notify.kick", true);
        notifyUnpunish = plugin.getConfig().getBoolean("discord.notify.unpunish", true);
        notifyBlockBreak = plugin.getConfig().getBoolean("discord.notify.blockbreak", true);

        if (enabled && (url == null || url.isEmpty() || !url.startsWith("http"))) {
            plugin.getLogger().warning("Discord integration is enabled but no valid webhook URL is set in config.yml!");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ------------------------------------------------------------------ //
    //  Public notification methods (called by commands / BanManager)     //
    // ------------------------------------------------------------------ //

    /**
     * Announces a newly applied punishment as a rich embed.
     *
     * @param type         internal punishment type (BAN, IPBAN, MUTE, IPMUTE, DONOTBREAK)
     * @param target       player name or IP address
     * @param admin        who applied the punishment
     * @param reason       punishment reason
     * @param expiryMillis expiry timestamp (-1 = permanent, -2 = not applicable, e.g. kick)
     * @param server       scope the punishment applies to
     * @param exempt       comma-separated exempted players for IP punishments (may be empty)
     */
    public void sendPunishment(String type, String target, String admin, String reason,
                               long expiryMillis, String server, String exempt) {
        if (!enabled) return;

        String title;
        int color;
        boolean showDuration = true;

        switch (type) {
            case "BAN":
            case "IPBAN":
                if (!notifyBan) return;
                title = msg.getRaw("discord.ban-title");
                color = COLOR_BAN;
                break;
            case "MUTE":
            case "IPMUTE":
                if (!notifyMute) return;
                title = msg.getRaw("discord.mute-title");
                color = COLOR_MUTE;
                break;
            case "DONOTBREAK":
                if (!notifyBlockBreak) return;
                title = msg.getRaw("discord.blockbreak-title");
                color = COLOR_BLOCKBREAK;
                showDuration = false;
                break;
            default:
                return;
        }

        StringBuilder fields = new StringBuilder();
        addField(fields, msg.getRaw("discord.field-target"), target, true);
        addField(fields, msg.getRaw("discord.field-mode"), isIpType(type) ? msg.getRaw("discord.mode-ip") : msg.getRaw("discord.mode-player"), true);
        addField(fields, msg.getRaw("discord.field-admin"), admin, true);
        addField(fields, msg.getRaw("discord.field-reason"), reason, false);
        if (showDuration) {
            addField(fields, msg.getRaw("discord.field-duration"), formatDuration(expiryMillis), false);
        }
        addField(fields, msg.getRaw("discord.field-server"), server, true);
        if (exempt != null && !exempt.isEmpty()) {
            addField(fields, msg.getRaw("discord.field-exempt"), exempt, false);
        }

        sendEmbed(title, null, color, fields.toString(), server);
    }

    /**
     * Announces that a punishment was removed (unban / unmute / expiry cleanup).
     */
    public void sendRemoval(String type, String target, String admin, String server) {
        if (!enabled || !notifyUnpunish) return;

        String title = msg.getRaw("discord.unpunish-title");

        StringBuilder fields = new StringBuilder();
        addField(fields, msg.getRaw("discord.field-target"), target, true);
        addField(fields, msg.getRaw("discord.field-mode"), isIpType(type) ? msg.getRaw("discord.mode-ip") : msg.getRaw("discord.mode-player"), true);
        addField(fields, msg.getRaw("discord.field-admin"), admin, true);
        addField(fields, msg.getRaw("discord.field-server"), server, false);

        sendEmbed(title, null, COLOR_REMOVAL, fields.toString(), server);
    }

    /**
     * Announces a kick. Kicks are not stored, so this is called directly by
     * the command/API layer rather than BanManager.
     */
    public void sendKick(String target, String admin, String reason, String server) {
        if (!enabled || !notifyKick) return;

        StringBuilder fields = new StringBuilder();
        addField(fields, msg.getRaw("discord.field-target"), target, true);
        addField(fields, msg.getRaw("discord.field-mode"), msg.getRaw("discord.mode-player"), true);
        addField(fields, msg.getRaw("discord.field-admin"), admin, true);
        addField(fields, msg.getRaw("discord.field-reason"), reason, false);
        addField(fields, msg.getRaw("discord.field-server"), server, false);

        sendEmbed(msg.getRaw("discord.kick-title"), null, COLOR_KICK, fields.toString(), server);
    }

    /** Sends a manual test message (used by /eb discordtest). Result is reported back. */
    public void sendTestMessage(TestCallback callback) {
        if (!enabled || url == null || url.isEmpty()) {
            callback.onResult(false);
            return;
        }
        String fields = ""; // Test embed has no fields, just a description.
        dispatch(buildPayload(msg.getRaw("discord.test-title"), msg.getRaw("discord.test-description"),
                COLOR_TEST, fields, plugin.getConfig().getString("server-name", "survival")), callback);
    }

    // ------------------------------------------------------------------ //
    //  Internals                                                          //
    // ------------------------------------------------------------------ //

    private void sendEmbed(String title, String description, int color, String fieldsJson, String server) {
        if (url == null || url.isEmpty() || !url.startsWith("http")) {
            return; // Already warned during reload().
        }
        dispatch(buildPayload(title, description, color, fieldsJson, server), null);
    }

    /**
     * Builds the complete webhook JSON payload. The structure follows the
     * official Discord webhook spec: username/avatar overrides at the top
     * level and one embed object with title, color, fields, footer, timestamp.
     */
    private String buildPayload(String title, String description, int color, String fieldsJson, String server) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        json.append("\"username\":\"").append(escape(username)).append('\"');
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            json.append(",\"avatar_url\":\"").append(escape(avatarUrl)).append('\"');
        }
        json.append(",\"embeds\":[{");
        json.append("\"title\":\"").append(escape(title)).append('\"');
        if (description != null && !description.isEmpty()) {
            json.append(",\"description\":\"").append(escape(description)).append('\"');
        }
        json.append(",\"color\":").append(color);
        if (fieldsJson != null && !fieldsJson.isEmpty()) {
            json.append(",\"fields\":[").append(fieldsJson).append(']');
        }
        json.append(",\"footer\":{\"text\":\"").append(escape(msg.getRaw("discord.footer").replace("%server%", server))).append("\"}");
        json.append(",\"timestamp\":\"").append(ISO_FORMAT.format(Instant.now())).append('\"');
        json.append("}]");
        json.append('}');
        return json.toString();
    }

    /** Appends one {"name","value","inline"} object to a fields array builder. */
    private void addField(StringBuilder fields, String name, String value, boolean inline) {
        if (value == null || value.isEmpty()) {
            value = "-";
        }
        if (fields.length() > 0) {
            fields.append(',');
        }
        fields.append("{\"name\":\"").append(escape(name))
                .append("\",\"value\":\"").append(escape(value))
                .append("\",\"inline\":").append(inline).append('}');
    }

    /** Sends the payload asynchronously. The game thread is never blocked. */
    private void dispatch(String payload, TestCallback callback) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .whenComplete((response, throwable) -> {
                        if (throwable != null) {
                            Throwable cause = (throwable instanceof CompletionException && throwable.getCause() != null)
                                    ? throwable.getCause() : throwable;
                            plugin.getLogger().warning("Discord webhook request failed: " + cause.getMessage());
                            if (callback != null) callback.onResult(false);
                            return;
                        }
                        int status = response.statusCode();
                        if (status >= 200 && status < 300) {
                            if (callback != null) callback.onResult(true);
                        } else {
                            plugin.getLogger().warning("Discord webhook returned HTTP " + status
                                    + ". Check that the URL is correct and has not been deleted.");
                            if (callback != null) callback.onResult(false);
                        }
                    });
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Discord webhook URL is invalid: " + e.getMessage());
            if (callback != null) callback.onResult(false);
        }
    }

    /** Formats the duration line: either "Permanent" or date plus remaining time. */
    private String formatDuration(long expiryMillis) {
        if (expiryMillis == -1) {
            return msg.getRaw("discord.value-permanent");
        }
        long remainingMillis = expiryMillis - System.currentTimeMillis();
        StringBuilder sb = new StringBuilder(ISO_FORMAT.format(Instant.ofEpochMilli(expiryMillis)));
        if (remainingMillis > 0) {
            StringBuilder parts = new StringBuilder();
            appendDurationPart(parts, remainingMillis / 86_400_000L, "d");
            appendDurationPart(parts, (remainingMillis / 3_600_000L) % 24, "h");
            appendDurationPart(parts, (remainingMillis / 60_000L) % 60, "m");
            appendDurationPart(parts, (remainingMillis / 1_000L) % 60, "s");
            if (parts.length() == 0) {
                parts.append("0s ");
            }
            parts.setLength(parts.length() - 1); // Trim the trailing space.
            sb.append(" (").append(parts).append(" left)");
        }
        return sb.toString();
    }

    private void appendDurationPart(StringBuilder sb, long amount, String unit) {
        if (amount > 0) {
            sb.append(amount).append(unit).append(' ');
        }
    }

    private boolean isIpType(String type) {
        return "IPBAN".equals(type) || "IPMUTE".equals(type);
    }

    /**
     * Minimal JSON string escaper (quotes, backslashes and control characters).
     * Keeps the plugin free of a shaded JSON library.
     */
    private String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /** Simple callback so the test command can report success/failure in-game. */
    public interface TestCallback {
        void onResult(boolean success);
    }
}

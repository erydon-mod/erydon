package com.oliver.erydon;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Typed ERYDON configuration.
 *
 * <p>Client preferences and server-authoritative gameplay settings deliberately
 * live in separate files. The old {@code erydon.properties} file is read only
 * as a one-time migration source and is left untouched so a failed migration is
 * recoverable.</p>
 */
public final class ErydonConfig {
    public static final int DEFAULT_TOOLTIP_DELAY_MS = 1_000;
    public static final int MIN_TOOLTIP_DELAY_MS = 0;
    public static final int MAX_TOOLTIP_DELAY_MS = 5_000;
    public static final int TOOLTIP_DELAY_STEP_MS = 250;
    public static final int DEFAULT_LIGHT_LEVEL = 15;
    public static final int MIN_LIGHT_LEVEL = 0;
    public static final int MAX_LIGHT_LEVEL = 15;
    public static final int DEFAULT_RECALC_RADIUS = 16;
    public static final int DEFAULT_RECALC_MAX_RADIUS = 32;
    public static final int MIN_RECALC_RADIUS = 1;
    public static final int MAX_RECALC_RADIUS = 128;

    private static final String LEGACY_CONFIG_FILE_NAME = "erydon.properties";
    private static final String CLIENT_CONFIG_FILE_NAME = "erydon-client.properties";
    private static final String SERVER_CONFIG_FILE_NAME = "erydon-server.properties";
    private static final String TOOLTIPS_ENABLED_KEY = "erydon.tooltips.enabled";
    private static final String TOOLTIP_DELAY_KEY = "erydon.tooltip.delay_ms";
    private static final String MODERN_LIGHT_LEVEL_KEY = "erydon.light.modern.level";
    private static final String WALL_LIGHT_LEVEL_KEY = "erydon.light.wall.level";
    private static final String PENDANT_LIGHT_LEVEL_KEY = "erydon.light.pendant.level";
    private static final String BRAZIER_LIGHT_LEVEL_KEY = "erydon.light.brazier.level";
    private static final String OIL_BURNER_LIGHT_LEVEL_KEY = "erydon.light.oil_burner.level";
    private static final String COFFERED_CEILING_LIGHT_LEVEL_KEY = "erydon.light.coffered_ceiling.level";
    private static final String DEBUG_MODEL_RELOAD_KEY = "erydon.debug.model_reload";
    private static final String DEBUG_CTM_KEY = "erydon.debug.ctm";
    private static final String DEBUG_LOAD_PROFILE_KEY = "erydon.debug.load_profile";
    private static final String LOADING_MESSAGE_SOCIAL_DISCORD_KEY = "erydon.loading_message.social.discord";
    private static final String LOADING_MESSAGE_SUGGESTIONS_RECALC_KEY = "erydon.loading_message.suggestions.recalc";
    private static final String RECALC_SHORT_COMMAND_ENABLED_KEY = "erydon.recalc.short_command.enabled";
    private static final String RECALC_DEFAULT_RADIUS_KEY = "erydon.recalc.default_radius";
    private static final String RECALC_MAX_RADIUS_KEY = "erydon.recalc.max_radius";

    private static final Object LOCK = new Object();
    private static final Set<String> WARNED_VALUES = ConcurrentHashMap.newKeySet();
    private static volatile ClientSnapshot clientSettings;
    private static volatile ServerSnapshot serverSettings;
    private static volatile ServerSnapshot clientServerMirror;
    private static volatile boolean clientServerMirrorActive;
    private static volatile boolean clientCanEditServerSettings;
    private static final DebugSnapshot DEBUG_FILE_SETTINGS;
    private static final DebugSnapshot DEBUG_SETTINGS;

    static {
        LoadedProperties legacy = loadProperties(configPath(LEGACY_CONFIG_FILE_NAME));
        LoadedProperties clientFile = loadProperties(configPath(CLIENT_CONFIG_FILE_NAME));
        LoadedProperties serverFile = loadProperties(configPath(SERVER_CONFIG_FILE_NAME));

        boolean migrateClient = !clientFile.exists() && legacy.exists() && legacy.valid();
        boolean migrateServer = !serverFile.exists() && legacy.exists() && legacy.valid();
        Properties clientSource = clientFile.valid() && clientFile.exists()
                ? clientFile.properties()
                : migrateClient ? legacy.properties() : new Properties();
        Properties serverSource = serverFile.valid() && serverFile.exists()
                ? serverFile.properties()
                : migrateServer ? legacy.properties() : new Properties();

        clientSettings = readClientSnapshot(clientSource);
        serverSettings = readServerSnapshot(serverSource);
        clientServerMirror = serverSettings;
        DEBUG_FILE_SETTINGS = readPersistedDebugSnapshot(clientSource);
        DEBUG_SETTINGS = readDebugSnapshot(clientSource);

        if (migrateClient && writeClientSnapshot(clientSettings, DEBUG_FILE_SETTINGS)) {
            Erydon.LOGGER.info("[{}] Migrated client settings to {}.", Erydon.MOD_ID, configPath(CLIENT_CONFIG_FILE_NAME));
        }
        if (migrateServer && writeServerSnapshot(serverSettings)) {
            Erydon.LOGGER.info("[{}] Migrated server settings to {}.", Erydon.MOD_ID, configPath(SERVER_CONFIG_FILE_NAME));
        }
    }

    private ErydonConfig() {
    }

    public static ClientSnapshot clientSettings() {
        return clientSettings;
    }

    /** Returns the server values that should be displayed and rendered on this process. */
    public static ServerSnapshot serverSettings() {
        return clientServerMirrorActive ? clientServerMirror : serverSettings;
    }

    /** Returns the local server's authoritative values, never a multiplayer mirror. */
    public static ServerSnapshot authoritativeServerSettings() {
        return serverSettings;
    }

    public static boolean hasServerSettingsMirror() {
        return clientServerMirrorActive;
    }

    public static boolean canEditServerSettings() {
        return !clientServerMirrorActive || clientCanEditServerSettings;
    }

    public static void installServerSettingsMirror(ServerSnapshot snapshot, boolean canEdit) {
        clientServerMirror = sanitize(snapshot);
        clientCanEditServerSettings = canEdit;
        clientServerMirrorActive = true;
    }

    public static void clearServerSettingsMirror() {
        clientServerMirrorActive = false;
        clientCanEditServerSettings = false;
        clientServerMirror = serverSettings;
    }

    public static boolean tooltipsEnabled() {
        return clientSettings.tooltipsEnabled();
    }

    public static int tooltipDelayMs() {
        return clientSettings.tooltipDelayMs();
    }

    public static int modernLightLevel() {
        return serverSettings().modernLightLevel();
    }

    public static int wallLightLevel() {
        return serverSettings().wallLightLevel();
    }

    public static int pendantLightLevel() {
        return serverSettings().pendantLightLevel();
    }

    public static int brazierLightLevel() {
        return serverSettings().brazierLightLevel();
    }

    public static int oilBurnerLightLevel() {
        return serverSettings().oilBurnerLightLevel();
    }

    public static int cofferedCeilingLightLevel() {
        return serverSettings().cofferedCeilingLightLevel();
    }

    public static boolean debugModelReload() {
        return DEBUG_SETTINGS.modelReload();
    }

    public static boolean debugCtm() {
        return DEBUG_SETTINGS.ctm();
    }

    public static boolean debugLoadProfile() {
        return DEBUG_SETTINGS.loadProfile();
    }

    public static boolean loadingMessageSocialDiscord() {
        return serverSettings().loadingMessageSocialDiscord();
    }

    public static boolean loadingMessageSuggestionsRecalc() {
        return serverSettings().loadingMessageSuggestionsRecalc();
    }

    public static boolean recalcShortCommandEnabled() {
        return serverSettings().recalcShortCommandEnabled();
    }

    public static int recalcDefaultRadius() {
        return serverSettings().recalcDefaultRadius();
    }

    public static int recalcMaxRadius() {
        return serverSettings().recalcMaxRadius();
    }

    public static void setTooltipsEnabled(boolean enabled) {
        synchronized (LOCK) {
            clientSettings = new ClientSnapshot(enabled, clientSettings.tooltipDelayMs());
        }
    }

    public static void setTooltipDelayMs(int delayMs) {
        synchronized (LOCK) {
            clientSettings = new ClientSnapshot(clientSettings.tooltipsEnabled(), clampTooltipDelay(delayMs));
        }
    }

    public static void setModernLightLevel(int lightLevel) {
        mutateServer(serverSettings.withModernLightLevel(lightLevel));
    }

    public static void setWallLightLevel(int lightLevel) {
        mutateServer(serverSettings.withWallLightLevel(lightLevel));
    }

    public static void setPendantLightLevel(int lightLevel) {
        mutateServer(serverSettings.withPendantLightLevel(lightLevel));
    }

    public static void setBrazierLightLevel(int lightLevel) {
        mutateServer(serverSettings.withBrazierLightLevel(lightLevel));
    }

    public static void setOilBurnerLightLevel(int lightLevel) {
        mutateServer(serverSettings.withOilBurnerLightLevel(lightLevel));
    }

    public static void setCofferedCeilingLightLevel(int lightLevel) {
        mutateServer(serverSettings.withCofferedCeilingLightLevel(lightLevel));
    }

    public static void setLoadingMessageSocialDiscord(boolean enabled) {
        mutateServer(serverSettings.withLoadingMessageSocialDiscord(enabled));
    }

    public static void setLoadingMessageSuggestionsRecalc(boolean enabled) {
        mutateServer(serverSettings.withLoadingMessageSuggestionsRecalc(enabled));
    }

    public static void setRecalcShortCommandEnabled(boolean enabled) {
        mutateServer(serverSettings.withRecalcShortCommandEnabled(enabled));
    }

    public static void setRecalcDefaultRadius(int radius) {
        mutateServer(serverSettings.withRecalcDefaultRadius(radius));
    }

    public static void setRecalcMaxRadius(int radius) {
        mutateServer(serverSettings.withRecalcMaxRadius(radius));
    }

    /**
     * Persists both local snapshots. Each target is written by replacement of a
     * completed temporary file, so a crash cannot leave a half-written file.
     */
    public static boolean save() {
        boolean clientSaved = saveClient();
        // A connected client must never rewrite this machine's unrelated local
        // server file. Remote settings use the permission-checked packet path.
        boolean serverSaved = clientServerMirrorActive || saveServer();
        return clientSaved && serverSaved;
    }

    public static boolean saveClient() {
        synchronized (LOCK) {
            return writeClientSnapshot(clientSettings, DEBUG_FILE_SETTINGS);
        }
    }

    /** Atomically persists and then publishes a complete client snapshot. */
    public static boolean replaceClientSettings(ClientSnapshot requested) {
        ClientSnapshot sanitized = new ClientSnapshot(
                requested.tooltipsEnabled(),
                requested.tooltipDelayMs()
        );
        synchronized (LOCK) {
            if (!writeClientSnapshot(sanitized, DEBUG_FILE_SETTINGS)) {
                return false;
            }
            clientSettings = sanitized;
            return true;
        }
    }

    public static boolean saveServer() {
        synchronized (LOCK) {
            return writeServerSnapshot(serverSettings);
        }
    }

    /** Saves and publishes a validated authoritative server snapshot. */
    public static boolean replaceServerSettings(ServerSnapshot requested) {
        ServerSnapshot sanitized = sanitize(requested);
        synchronized (LOCK) {
            if (!writeServerSnapshot(sanitized)) {
                return false;
            }
            serverSettings = sanitized;
            if (clientServerMirrorActive) {
                clientServerMirror = sanitized;
            }
            return true;
        }
    }

    public static int clampTooltipDelay(int delayMs) {
        return Math.max(MIN_TOOLTIP_DELAY_MS, Math.min(MAX_TOOLTIP_DELAY_MS, delayMs));
    }

    public static int clampLightLevel(int lightLevel) {
        return Math.max(MIN_LIGHT_LEVEL, Math.min(MAX_LIGHT_LEVEL, lightLevel));
    }

    public static int clampRecalcRadius(int radius) {
        return Math.max(MIN_RECALC_RADIUS, Math.min(MAX_RECALC_RADIUS, radius));
    }

    private static void mutateServer(ServerSnapshot snapshot) {
        synchronized (LOCK) {
            if (clientServerMirrorActive) {
                // Compatibility setters are intentionally local-server only.
                // Connected screens submit a category-scoped snapshot through
                // ErydonConfigNetworkingClient.save(snapshot, updateFields).
                return;
            }
            serverSettings = sanitize(snapshot);
            clientServerMirror = serverSettings;
        }
    }

    private static ServerSnapshot sanitize(ServerSnapshot snapshot) {
        int maxRadius = clampRecalcRadius(snapshot.recalcMaxRadius());
        int defaultRadius = Math.min(clampRecalcRadius(snapshot.recalcDefaultRadius()), maxRadius);
        return new ServerSnapshot(
                clampLightLevel(snapshot.modernLightLevel()),
                clampLightLevel(snapshot.wallLightLevel()),
                clampLightLevel(snapshot.pendantLightLevel()),
                clampLightLevel(snapshot.brazierLightLevel()),
                clampLightLevel(snapshot.oilBurnerLightLevel()),
                clampLightLevel(snapshot.cofferedCeilingLightLevel()),
                snapshot.loadingMessageSocialDiscord(),
                snapshot.loadingMessageSuggestionsRecalc(),
                snapshot.recalcShortCommandEnabled(),
                defaultRadius,
                maxRadius
        );
    }

    private static ClientSnapshot readClientSnapshot(Properties properties) {
        return new ClientSnapshot(
                readBoolean(properties, TOOLTIPS_ENABLED_KEY, true),
                readInt(properties, TOOLTIP_DELAY_KEY, DEFAULT_TOOLTIP_DELAY_MS,
                        MIN_TOOLTIP_DELAY_MS, MAX_TOOLTIP_DELAY_MS)
        );
    }

    private static ServerSnapshot readServerSnapshot(Properties properties) {
        int maxRadius = readInt(properties, RECALC_MAX_RADIUS_KEY, DEFAULT_RECALC_MAX_RADIUS,
                MIN_RECALC_RADIUS, MAX_RECALC_RADIUS);
        int defaultRadius = Math.min(
                readInt(properties, RECALC_DEFAULT_RADIUS_KEY, DEFAULT_RECALC_RADIUS,
                        MIN_RECALC_RADIUS, MAX_RECALC_RADIUS),
                maxRadius
        );
        return new ServerSnapshot(
                readInt(properties, MODERN_LIGHT_LEVEL_KEY, DEFAULT_LIGHT_LEVEL, MIN_LIGHT_LEVEL, MAX_LIGHT_LEVEL),
                readInt(properties, WALL_LIGHT_LEVEL_KEY, DEFAULT_LIGHT_LEVEL, MIN_LIGHT_LEVEL, MAX_LIGHT_LEVEL),
                readInt(properties, PENDANT_LIGHT_LEVEL_KEY, DEFAULT_LIGHT_LEVEL, MIN_LIGHT_LEVEL, MAX_LIGHT_LEVEL),
                readInt(properties, BRAZIER_LIGHT_LEVEL_KEY, DEFAULT_LIGHT_LEVEL, MIN_LIGHT_LEVEL, MAX_LIGHT_LEVEL),
                readInt(properties, OIL_BURNER_LIGHT_LEVEL_KEY, DEFAULT_LIGHT_LEVEL, MIN_LIGHT_LEVEL, MAX_LIGHT_LEVEL),
                readInt(properties, COFFERED_CEILING_LIGHT_LEVEL_KEY, DEFAULT_LIGHT_LEVEL, MIN_LIGHT_LEVEL, MAX_LIGHT_LEVEL),
                readBoolean(properties, LOADING_MESSAGE_SOCIAL_DISCORD_KEY, true),
                readBoolean(properties, LOADING_MESSAGE_SUGGESTIONS_RECALC_KEY, true),
                readBoolean(properties, RECALC_SHORT_COMMAND_ENABLED_KEY, true),
                defaultRadius,
                maxRadius
        );
    }

    private static DebugSnapshot readDebugSnapshot(Properties properties) {
        return new DebugSnapshot(
                readDebugBoolean(properties, DEBUG_MODEL_RELOAD_KEY, false),
                readDebugBoolean(properties, DEBUG_CTM_KEY, false),
                readDebugBoolean(properties, DEBUG_LOAD_PROFILE_KEY, false)
        );
    }

    private static DebugSnapshot readPersistedDebugSnapshot(Properties properties) {
        return new DebugSnapshot(
                readBoolean(properties, DEBUG_MODEL_RELOAD_KEY, false),
                readBoolean(properties, DEBUG_CTM_KEY, false),
                readBoolean(properties, DEBUG_LOAD_PROFILE_KEY, false)
        );
    }

    private static boolean writeClientSnapshot(ClientSnapshot snapshot, DebugSnapshot debug) {
        Properties properties = new Properties();
        properties.setProperty(TOOLTIPS_ENABLED_KEY, Boolean.toString(snapshot.tooltipsEnabled()));
        properties.setProperty(TOOLTIP_DELAY_KEY, Integer.toString(snapshot.tooltipDelayMs()));
        properties.setProperty(DEBUG_MODEL_RELOAD_KEY, Boolean.toString(debug.modelReload()));
        properties.setProperty(DEBUG_CTM_KEY, Boolean.toString(debug.ctm()));
        properties.setProperty(DEBUG_LOAD_PROFILE_KEY, Boolean.toString(debug.loadProfile()));
        return writePropertiesAtomically(configPath(CLIENT_CONFIG_FILE_NAME), properties, "ERYDON client settings");
    }

    private static boolean writeServerSnapshot(ServerSnapshot snapshot) {
        Properties properties = new Properties();
        properties.setProperty(MODERN_LIGHT_LEVEL_KEY, Integer.toString(snapshot.modernLightLevel()));
        properties.setProperty(WALL_LIGHT_LEVEL_KEY, Integer.toString(snapshot.wallLightLevel()));
        properties.setProperty(PENDANT_LIGHT_LEVEL_KEY, Integer.toString(snapshot.pendantLightLevel()));
        properties.setProperty(BRAZIER_LIGHT_LEVEL_KEY, Integer.toString(snapshot.brazierLightLevel()));
        properties.setProperty(OIL_BURNER_LIGHT_LEVEL_KEY, Integer.toString(snapshot.oilBurnerLightLevel()));
        properties.setProperty(COFFERED_CEILING_LIGHT_LEVEL_KEY, Integer.toString(snapshot.cofferedCeilingLightLevel()));
        properties.setProperty(LOADING_MESSAGE_SOCIAL_DISCORD_KEY, Boolean.toString(snapshot.loadingMessageSocialDiscord()));
        properties.setProperty(LOADING_MESSAGE_SUGGESTIONS_RECALC_KEY, Boolean.toString(snapshot.loadingMessageSuggestionsRecalc()));
        properties.setProperty(RECALC_SHORT_COMMAND_ENABLED_KEY, Boolean.toString(snapshot.recalcShortCommandEnabled()));
        properties.setProperty(RECALC_DEFAULT_RADIUS_KEY, Integer.toString(snapshot.recalcDefaultRadius()));
        properties.setProperty(RECALC_MAX_RADIUS_KEY, Integer.toString(snapshot.recalcMaxRadius()));
        return writePropertiesAtomically(configPath(SERVER_CONFIG_FILE_NAME), properties, "ERYDON server settings");
    }

    private static LoadedProperties loadProperties(Path path) {
        if (!Files.isRegularFile(path)) {
            return new LoadedProperties(new Properties(), false, true);
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
            return new LoadedProperties(properties, true, true);
        } catch (IOException | IllegalArgumentException exception) {
            Erydon.LOGGER.warn("[{}] Could not read {}. Safe defaults will be used.", Erydon.MOD_ID, path, exception);
            return new LoadedProperties(new Properties(), true, false);
        }
    }

    private static boolean writePropertiesAtomically(Path path, Properties properties, String comment) {
        Path temporary = null;
        try {
            Files.createDirectories(path.getParent());
            temporary = Files.createTempFile(path.getParent(), path.getFileName().toString() + ".", ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, comment);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException exception) {
            Erydon.LOGGER.warn("[{}] Failed to save {}.", Erydon.MOD_ID, path, exception);
            return false;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Best-effort cleanup of an incomplete temporary file.
                }
            }
        }
    }

    private static int readInt(Properties properties, String key, int defaultValue, int min, int max) {
        String raw = trimmed(properties.getProperty(key));
        if (raw == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed < min || parsed > max) {
                warnValueOnce(key, raw, Integer.toString(Math.max(min, Math.min(max, parsed))));
            }
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException exception) {
            warnValueOnce(key, raw, Integer.toString(defaultValue));
            return defaultValue;
        }
    }

    private static boolean readBoolean(Properties properties, String key, boolean defaultValue) {
        return parseBoolean(key, trimmed(properties.getProperty(key)), defaultValue);
    }

    /** Only diagnostic keys intentionally honour JVM -D overrides. */
    private static boolean readDebugBoolean(Properties properties, String key, boolean defaultValue) {
        String systemValue = trimmed(System.getProperty(key));
        return parseBoolean(key, systemValue != null ? systemValue : trimmed(properties.getProperty(key)), defaultValue);
    }

    private static boolean parseBoolean(String key, String raw, boolean defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "1", "on" -> true;
            case "false", "no", "0", "off" -> false;
            default -> {
                warnValueOnce(key, raw, Boolean.toString(defaultValue));
                yield defaultValue;
            }
        };
    }

    private static void warnValueOnce(String key, String value, String fallback) {
        if (WARNED_VALUES.add(key)) {
            Erydon.LOGGER.warn("[{}] Invalid value for {}: {}. Using {}.", Erydon.MOD_ID, key, value, fallback);
        }
    }

    private static String trimmed(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static Path configPath(String fileName) {
        return FabricLoader.getInstance().getConfigDir().resolve(fileName);
    }

    public record ClientSnapshot(boolean tooltipsEnabled, int tooltipDelayMs) {
        public ClientSnapshot {
            tooltipDelayMs = clampTooltipDelay(tooltipDelayMs);
        }
    }

    public record ServerSnapshot(
            int modernLightLevel,
            int wallLightLevel,
            int pendantLightLevel,
            int brazierLightLevel,
            int oilBurnerLightLevel,
            int cofferedCeilingLightLevel,
            boolean loadingMessageSocialDiscord,
            boolean loadingMessageSuggestionsRecalc,
            boolean recalcShortCommandEnabled,
            int recalcDefaultRadius,
            int recalcMaxRadius
    ) {
        public ServerSnapshot withModernLightLevel(int value) {
            return copy(value, wallLightLevel, pendantLightLevel, brazierLightLevel, oilBurnerLightLevel,
                    cofferedCeilingLightLevel, loadingMessageSocialDiscord, loadingMessageSuggestionsRecalc,
                    recalcShortCommandEnabled, recalcDefaultRadius, recalcMaxRadius);
        }

        public ServerSnapshot withWallLightLevel(int value) {
            return copy(modernLightLevel, value, pendantLightLevel, brazierLightLevel, oilBurnerLightLevel,
                    cofferedCeilingLightLevel, loadingMessageSocialDiscord, loadingMessageSuggestionsRecalc,
                    recalcShortCommandEnabled, recalcDefaultRadius, recalcMaxRadius);
        }

        public ServerSnapshot withPendantLightLevel(int value) {
            return copy(modernLightLevel, wallLightLevel, value, brazierLightLevel, oilBurnerLightLevel,
                    cofferedCeilingLightLevel, loadingMessageSocialDiscord, loadingMessageSuggestionsRecalc,
                    recalcShortCommandEnabled, recalcDefaultRadius, recalcMaxRadius);
        }

        public ServerSnapshot withBrazierLightLevel(int value) {
            return copy(modernLightLevel, wallLightLevel, pendantLightLevel, value, oilBurnerLightLevel,
                    cofferedCeilingLightLevel, loadingMessageSocialDiscord, loadingMessageSuggestionsRecalc,
                    recalcShortCommandEnabled, recalcDefaultRadius, recalcMaxRadius);
        }

        public ServerSnapshot withOilBurnerLightLevel(int value) {
            return copy(modernLightLevel, wallLightLevel, pendantLightLevel, brazierLightLevel, value,
                    cofferedCeilingLightLevel, loadingMessageSocialDiscord, loadingMessageSuggestionsRecalc,
                    recalcShortCommandEnabled, recalcDefaultRadius, recalcMaxRadius);
        }

        public ServerSnapshot withCofferedCeilingLightLevel(int value) {
            return copy(modernLightLevel, wallLightLevel, pendantLightLevel, brazierLightLevel, oilBurnerLightLevel,
                    value, loadingMessageSocialDiscord, loadingMessageSuggestionsRecalc,
                    recalcShortCommandEnabled, recalcDefaultRadius, recalcMaxRadius);
        }

        public ServerSnapshot withLoadingMessageSocialDiscord(boolean value) {
            return copy(modernLightLevel, wallLightLevel, pendantLightLevel, brazierLightLevel, oilBurnerLightLevel,
                    cofferedCeilingLightLevel, value, loadingMessageSuggestionsRecalc,
                    recalcShortCommandEnabled, recalcDefaultRadius, recalcMaxRadius);
        }

        public ServerSnapshot withLoadingMessageSuggestionsRecalc(boolean value) {
            return copy(modernLightLevel, wallLightLevel, pendantLightLevel, brazierLightLevel, oilBurnerLightLevel,
                    cofferedCeilingLightLevel, loadingMessageSocialDiscord, value,
                    recalcShortCommandEnabled, recalcDefaultRadius, recalcMaxRadius);
        }

        public ServerSnapshot withRecalcShortCommandEnabled(boolean value) {
            return copy(modernLightLevel, wallLightLevel, pendantLightLevel, brazierLightLevel, oilBurnerLightLevel,
                    cofferedCeilingLightLevel, loadingMessageSocialDiscord, loadingMessageSuggestionsRecalc,
                    value, recalcDefaultRadius, recalcMaxRadius);
        }

        public ServerSnapshot withRecalcDefaultRadius(int value) {
            return copy(modernLightLevel, wallLightLevel, pendantLightLevel, brazierLightLevel, oilBurnerLightLevel,
                    cofferedCeilingLightLevel, loadingMessageSocialDiscord, loadingMessageSuggestionsRecalc,
                    recalcShortCommandEnabled, value, recalcMaxRadius);
        }

        public ServerSnapshot withRecalcMaxRadius(int value) {
            return copy(modernLightLevel, wallLightLevel, pendantLightLevel, brazierLightLevel, oilBurnerLightLevel,
                    cofferedCeilingLightLevel, loadingMessageSocialDiscord, loadingMessageSuggestionsRecalc,
                    recalcShortCommandEnabled, recalcDefaultRadius, value);
        }

        public boolean sameLightLevels(ServerSnapshot other) {
            return modernLightLevel == other.modernLightLevel
                    && wallLightLevel == other.wallLightLevel
                    && pendantLightLevel == other.pendantLightLevel
                    && brazierLightLevel == other.brazierLightLevel
                    && oilBurnerLightLevel == other.oilBurnerLightLevel
                    && cofferedCeilingLightLevel == other.cofferedCeilingLightLevel;
        }

        private static ServerSnapshot copy(int modern, int wall, int pendant, int brazier, int oilBurner,
                                           int coffered, boolean social, boolean suggestions, boolean alias,
                                           int defaultRadius, int maxRadius) {
            return sanitize(new ServerSnapshot(modern, wall, pendant, brazier, oilBurner, coffered,
                    social, suggestions, alias, defaultRadius, maxRadius));
        }
    }

    private record DebugSnapshot(boolean modelReload, boolean ctm, boolean loadProfile) {
    }

    private record LoadedProperties(Properties properties, boolean exists, boolean valid) {
    }
}

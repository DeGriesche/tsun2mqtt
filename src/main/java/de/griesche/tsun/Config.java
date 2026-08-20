package de.griesche.tsun;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Runtime configuration, read from environment variables so the image can be configured
 * purely through {@code docker run -e ...} / compose.
 */
public record Config(
        String talentBaseUrl,
        String talentUsername,
        String talentPassword,
        String talentTimezone,
        Duration pollInterval,
        Duration httpTimeout,
        String mqttUrl,
        Optional<String> mqttUsername,
        Optional<String> mqttPassword,
        String mqttClientId,
        String mqttBaseTopic,
        int mqttQos,
        boolean mqttRetain,
        boolean haDiscoveryEnabled,
        String haDiscoveryPrefix,
        String energyUnit,
        boolean publishRaw,
        String healthFile,
        String logLevel) {

    public static final String DEFAULT_BASE_URL = "https://pro.talent-monitoring.com";

    public static Config fromEnv() {
        return fromEnv(System.getenv());
    }

    public static Config fromEnv(Map<String, String> env) {
        var username = required(env, "TALENT_USERNAME");
        var password = required(env, "TALENT_PASSWORD");

        return new Config(
                stripTrailingSlash(get(env, "TALENT_BASE_URL", DEFAULT_BASE_URL)),
                username,
                password,
                get(env, "TALENT_TIMEZONE", "+02:00"),
                Duration.ofSeconds(positiveLong(env, "POLL_INTERVAL_SECONDS", 20)),
                Duration.ofSeconds(positiveLong(env, "HTTP_TIMEOUT_SECONDS", 30)),
                get(env, "MQTT_URL", "tcp://omv2:1883"),
                optional(env, "MQTT_USERNAME"),
                optional(env, "MQTT_PASSWORD"),
                get(env, "MQTT_CLIENT_ID", "tsun2mqtt"),
                stripSlashes(get(env, "MQTT_BASE_TOPIC", "tsun")),
                qos(env),
                bool(env, "MQTT_RETAIN", true),
                bool(env, "HA_DISCOVERY_ENABLED", true),
                stripSlashes(get(env, "HA_DISCOVERY_PREFIX", "homeassistant")),
                get(env, "ENERGY_UNIT", "kWh"),
                bool(env, "PUBLISH_RAW", false),
                get(env, "HEALTH_FILE", "/tmp/tsun2mqtt-healthy"),
                get(env, "LOG_LEVEL", "info").toLowerCase());
    }

    private static String get(Map<String, String> env, String key, String fallback) {
        var value = env.get(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static Optional<String> optional(Map<String, String> env, String key) {
        var value = env.get(key);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static String required(Map<String, String> env, String key) {
        var value = env.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable " + key);
        }
        return value.trim();
    }

    private static boolean bool(Map<String, String> env, String key, boolean fallback) {
        var value = get(env, key, String.valueOf(fallback));
        return switch (value.toLowerCase()) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default -> throw new IllegalStateException(key + " must be a boolean, got: " + value);
        };
    }

    private static long positiveLong(Map<String, String> env, String key, long fallback) {
        var value = get(env, key, String.valueOf(fallback));
        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(key + " must be a number, got: " + value);
        }
        if (parsed <= 0) {
            throw new IllegalStateException(key + " must be > 0, got: " + parsed);
        }
        return parsed;
    }

    private static int qos(Map<String, String> env) {
        var value = get(env, "MQTT_QOS", "0");
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("MQTT_QOS must be 0, 1 or 2, got: " + value);
        }
        if (parsed < 0 || parsed > 2) {
            throw new IllegalStateException("MQTT_QOS must be 0, 1 or 2, got: " + parsed);
        }
        return parsed;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String stripSlashes(String topic) {
        var trimmed = topic;
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isBlank()) {
            throw new IllegalStateException("Topic must not be empty: " + topic);
        }
        return trimmed;
    }
}

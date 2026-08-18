package com.deutscheleasing.swfactory.tsun;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {

    private static Map<String, String> credentials() {
        var env = new HashMap<String, String>();
        env.put("TALENT_USERNAME", "user@example.com");
        env.put("TALENT_PASSWORD", "secret");
        return env;
    }

    @Test
    void appliesDefaults() {
        var config = Config.fromEnv(credentials());

        assertEquals(Config.DEFAULT_BASE_URL, config.talentBaseUrl());
        assertEquals("tcp://localhost:1883", config.mqttUrl());
        assertEquals("tsun", config.mqttBaseTopic());
        assertEquals(300, config.pollInterval().toSeconds());
        assertEquals(0, config.mqttQos());
        assertTrue(config.mqttRetain());
        assertTrue(config.haDiscoveryEnabled());
        assertEquals("homeassistant", config.haDiscoveryPrefix());
        assertEquals("kWh", config.energyUnit());
        assertFalse(config.publishRaw());
        assertTrue(config.mqttUsername().isEmpty());
    }

    @Test
    void failsWithoutCredentials() {
        var e = assertThrows(IllegalStateException.class, () -> Config.fromEnv(Map.of()));
        assertTrue(e.getMessage().contains("TALENT_USERNAME"));
    }

    @Test
    void normalisesUrlsAndTopics() {
        var env = credentials();
        env.put("TALENT_BASE_URL", "https://www.talent-monitoring.com/prod-api/");
        env.put("MQTT_BASE_TOPIC", "/solar/tsun/");
        env.put("HA_DISCOVERY_PREFIX", "ha/");

        var config = Config.fromEnv(env);

        assertEquals("https://www.talent-monitoring.com/prod-api", config.talentBaseUrl());
        assertEquals("solar/tsun", config.mqttBaseTopic());
        assertEquals("ha", config.haDiscoveryPrefix());
    }

    @Test
    void parsesFlagsAndNumbers() {
        var env = credentials();
        env.put("HA_DISCOVERY_ENABLED", "false");
        env.put("MQTT_RETAIN", "no");
        env.put("PUBLISH_RAW", "1");
        env.put("MQTT_QOS", "1");
        env.put("POLL_INTERVAL_SECONDS", "60");

        var config = Config.fromEnv(env);

        assertFalse(config.haDiscoveryEnabled());
        assertFalse(config.mqttRetain());
        assertTrue(config.publishRaw());
        assertEquals(1, config.mqttQos());
        assertEquals(60, config.pollInterval().toSeconds());
    }

    @Test
    void rejectsInvalidValues() {
        var badQos = credentials();
        badQos.put("MQTT_QOS", "3");
        assertThrows(IllegalStateException.class, () -> Config.fromEnv(badQos));

        var badInterval = credentials();
        badInterval.put("POLL_INTERVAL_SECONDS", "0");
        assertThrows(IllegalStateException.class, () -> Config.fromEnv(badInterval));

        var badFlag = credentials();
        badFlag.put("PUBLISH_RAW", "maybe");
        assertThrows(IllegalStateException.class, () -> Config.fromEnv(badFlag));
    }
}

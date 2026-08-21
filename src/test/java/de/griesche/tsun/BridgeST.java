package de.griesche.tsun;

import de.griesche.tsun.mqtt.HomeAssistantDiscovery;
import de.griesche.tsun.mqtt.MqttPublisher;
import de.griesche.tsun.talent.Model;
import de.griesche.tsun.talent.TalentClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives a single poll cycle against a stubbed API and inspects what lands on MQTT. */
class BridgeST {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Captures publishes instead of talking to a broker. */
    private static final class RecordingPublisher extends MqttPublisher {

        private final Map<String, String> messages = new LinkedHashMap<>();

        RecordingPublisher(Config config) {
            super(config);
        }

        @Override
        public void publish(String topic, String payload) {
            messages.put(topic, payload);
        }

        @Override
        public void publish(String topic, String payload, boolean retain) {
            messages.put(topic, payload);
        }
    }

    private static final class StubClient extends TalentClient {

        StubClient(Config config) {
            super(config, null, MAPPER);
        }

        @Override
        public List<Model.Station> stations() {
            return List.of(new Model.Station("guid-1", "Balcony", Optional.of("1"), MAPPER.createObjectNode()));
        }

    }

    private static Config config(Map<String, String> overrides) {
        var env = new HashMap<String, String>();
        env.put("TALENT_USERNAME", "user");
        env.put("TALENT_PASSWORD", "secret");
        env.putAll(overrides);
        return Config.fromEnv(env);
    }

    private static RecordingPublisher poll(Config config) {
        var publisher = new RecordingPublisher(config);
        var discovery = new HomeAssistantDiscovery(config, MAPPER);
        try (publisher) {
            new Bridge(config, new StubClient(config), publisher, discovery, MAPPER).pollOnce();
        }
        return publisher;
    }

    private static JsonNode parse(String payload) {
        try {
            return MAPPER.readTree(payload);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void publishesStationCollectorAndInverterState() {
        var messages = poll(config(Map.of())).messages;

        var station = parse(messages.get("tsun/station/balcony/state"));
        assertEquals("Balcony", station.get("station_name").asText());
        assertEquals(612.5, station.get("total_active_power").asDouble());
        assertEquals(3.42, station.get("day_energy").asDouble());
        assertFalse(station.has("total_energy"), "absent API values must not be published as zero");

        var collector = parse(messages.get("tsun/collector/c1/state"));
        assertEquals(78, collector.get("signal_strength").asInt());

        var inverter = parse(messages.get("tsun/inverter/y1234567/state"));
        assertEquals(41.3, inverter.get("temperature").asDouble());
        assertEquals(75.0, inverter.get("pv1_power").asDouble());
        assertEquals(71.0, inverter.get("pv2_power").asDouble());
        assertEquals(146.0, inverter.get("pv_total_power").asDouble());
        assertEquals(50.01, inverter.get("grid1_frequency").asDouble());
        assertTrue(inverter.has("last_update"));
    }

    @Test
    void publishesHomeAssistantDiscoveryOnce() {
        var messages = poll(config(Map.of())).messages;

        var topic = "homeassistant/sensor/tsun_inverter_y1234567/pv1_power/config";
        var discovery = parse(messages.get(topic));
        assertEquals("tsun_inverter_y1234567_pv1_power", discovery.get("unique_id").asText());
        assertEquals("tsun/inverter/y1234567/state", discovery.get("state_topic").asText());
        assertEquals("W", discovery.get("unit_of_measurement").asText());
        assertEquals("power", discovery.get("device_class").asText());
        assertEquals("tsun/status", discovery.get("availability_topic").asText());
        assertEquals("tsun_station_guid-1", discovery.get("device").get("via_device").asText());

        var energy = parse(messages.get("homeassistant/sensor/tsun_station_guid-1/day_energy/config"));
        assertEquals("kWh", energy.get("unit_of_measurement").asText());
        assertEquals("total_increasing", energy.get("state_class").asText());
    }

    @Test
    void discoveryCanBeDisabledAndTopicPrefixChanged() {
        var messages = poll(config(Map.of(
                "HA_DISCOVERY_ENABLED", "false",
                "MQTT_BASE_TOPIC", "solar/tsun"))).messages;

        assertTrue(messages.keySet().stream().noneMatch(t -> t.startsWith("homeassistant/")));
        assertTrue(messages.containsKey("solar/tsun/inverter/y1234567/state"));
    }

    @Test
    void publishesRawPayloadsOnDemand() {
        var messages = poll(config(Map.of("PUBLISH_RAW", "true"))).messages;

        assertTrue(messages.containsKey("tsun/station/balcony/raw"));
        assertTrue(messages.containsKey("tsun/inverter/y1234567/raw"));
    }
}

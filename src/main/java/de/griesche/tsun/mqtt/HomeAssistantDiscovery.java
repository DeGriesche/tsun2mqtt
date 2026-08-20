package de.griesche.tsun.mqtt;

import de.griesche.tsun.Config;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Publishes Home Assistant MQTT discovery configs so every value shows up as a sensor entity
 * without manual YAML. Each config is retained and published once per connection.
 *
 * <p>See https://www.home-assistant.io/integrations/mqtt/#mqtt-discovery
 */
public class HomeAssistantDiscovery {

    private static final Logger LOG = LoggerFactory.getLogger(HomeAssistantDiscovery.class);
    private static final String MANUFACTURER = "TSUN";

    private final Config config;
    private final ObjectMapper mapper;
    private final Set<String> published = ConcurrentHashMap.newKeySet();

    public HomeAssistantDiscovery(Config config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
    }

    /** One sensor entity, reading {@code key} out of the JSON state payload. */
    public record Sensor(
            String key,
            String name,
            Optional<String> unit,
            Optional<String> deviceClass,
            Optional<String> stateClass,
            boolean diagnostic) {

        public static Sensor measurement(String key, String name, String unit, String deviceClass) {
            return new Sensor(key, name, Optional.ofNullable(unit), Optional.of(deviceClass),
                    Optional.of("measurement"), false);
        }

        public static Sensor totalIncreasing(String key, String name, String unit, String deviceClass) {
            return new Sensor(key, name, Optional.of(unit), Optional.of(deviceClass),
                    Optional.of("total_increasing"), false);
        }

        public static Sensor diagnostic(String key, String name, String unit) {
            return new Sensor(key, name, Optional.ofNullable(unit), Optional.empty(),
                    Optional.empty(), true);
        }
    }

    /** A Home Assistant device grouping several sensors. */
    public record Device(String kind, String id, String name, String model, Optional<String> viaDevice) {

        public String identifier() {
            return "tsun_" + Topics.slug(kind) + "_" + Topics.slug(id);
        }
    }

    /** Forgets what was already sent, so the next call republishes everything (after reconnect). */
    public void reset() {
        published.clear();
    }

    public void publish(MqttPublisher publisher, Device device, String stateTopic, List<Sensor> sensors) {
        if (!config.haDiscoveryEnabled()) {
            return;
        }
        for (var sensor : sensors) {
            var uniqueId = device.identifier() + "_" + Topics.slug(sensor.key());
            if (!published.add(uniqueId)) {
                continue;
            }
            var topic = config.haDiscoveryPrefix() + "/sensor/" + device.identifier()
                    + "/" + Topics.slug(sensor.key()) + "/config";
            publisher.publish(topic, payload(device, stateTopic, sensor, uniqueId, publisher), true);
            LOG.debug("Published discovery config for {}", uniqueId);
        }
    }

    private String payload(
            Device device, String stateTopic, Sensor sensor, String uniqueId, MqttPublisher publisher) {

        var root = mapper.createObjectNode();
        root.put("name", sensor.name());
        root.put("unique_id", uniqueId);
        root.put("object_id", uniqueId);
        root.put("state_topic", stateTopic);
        root.put("value_template", "{{ value_json." + sensor.key() + " | default('') }}");
        root.put("availability_topic", publisher.availabilityTopic());
        root.put("payload_available", MqttPublisher.PAYLOAD_ONLINE);
        root.put("payload_not_available", MqttPublisher.PAYLOAD_OFFLINE);
        sensor.unit().ifPresent(unit -> root.put("unit_of_measurement", unit));
        sensor.deviceClass().ifPresent(deviceClass -> root.put("device_class", deviceClass));
        sensor.stateClass().ifPresent(stateClass -> root.put("state_class", stateClass));
        if (sensor.diagnostic()) {
            root.put("entity_category", "diagnostic");
        }

        var deviceNode = root.putObject("device");
        deviceNode.putArray("identifiers").add(device.identifier());
        deviceNode.put("name", device.name());
        deviceNode.put("manufacturer", MANUFACTURER);
        deviceNode.put("model", device.model());
        device.viaDevice().ifPresent(via -> deviceNode.put("via_device", via));

        root.putObject("origin")
                .put("name", "tsun2mqtt")
                .put("support_url", "https://pro.talent-monitoring.com/");

        return root.toString();
    }
}

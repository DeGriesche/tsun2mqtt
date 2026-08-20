package de.griesche.tsun;

import de.griesche.tsun.mqtt.HomeAssistantDiscovery;
import de.griesche.tsun.mqtt.HomeAssistantDiscovery.Device;
import de.griesche.tsun.mqtt.HomeAssistantDiscovery.Sensor;
import de.griesche.tsun.mqtt.MqttPublisher;
import de.griesche.tsun.mqtt.Topics;
import de.griesche.tsun.talent.Model;
import de.griesche.tsun.talent.TalentClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Polls every station and every inverter of the account and publishes their readings to MQTT.
 *
 * <p>One cycle: stations -> station production -> collectors -> inverters -> inverter details.
 * Failures of a single station or device are logged and skipped so the rest of the account still
 * gets updated; the loop itself only stops on shutdown.
 */
public class Bridge {

    private static final Logger LOG = LoggerFactory.getLogger(Bridge.class);
    public static final String BATTERY_SOC = "battery_soc";
    public static final String BATTERY_STATUS = "battery_status";
    public static final String BATTERY_POWER = "battery_power";
    public static final String TOTAL_GENERATION_POWER = "total_generation_power";
    public static final String USE_POWER = "use_power";
    public static final String GENERATION_POWER = "generation_power";
    public static final String BATTERY_REMAINING_CAPACITY = "battery_remaining_capacity";
    public static final String LAST_UPDATE = "last_update";
    public static final String STATION_GUID = "station_guid";
    public static final String STATION_NAME = "station_name";
    public static final String STATION = "station";
    public static final String CHARGE_VALUE_DAY = "charge_value_day";
    public static final String DISCHARGE_VALUE_DAY = "discharge_value_day";
    public static final String MODEL = "TSUN DCU2000Lite";

    private final Config config;
    private final TalentClient client;
    private final MqttPublisher publisher;
    private final HomeAssistantDiscovery discovery;
    private final ObjectMapper mapper;

    /** Stable topic segment per station, so renaming collisions do not move topics around. */
    private final Map<String, String> stationTopicIds = new ConcurrentHashMap<>();

    private final CountDownLatch stop = new CountDownLatch(1);

    public Bridge(Config config, TalentClient client, MqttPublisher publisher,
                  HomeAssistantDiscovery discovery, ObjectMapper mapper) {
        this.config = config;
        this.client = client;
        this.publisher = publisher;
        this.discovery = discovery;
        this.mapper = mapper;
    }

    /** Polls until {@link #shutdown()} is called. */
    public void run() throws InterruptedException {
        LOG.info("Polling {} every {}s, publishing to {} under {}/",
                config.talentBaseUrl(), config.pollInterval().toSeconds(),
                config.mqttUrl(), config.mqttBaseTopic());

        while (stop.getCount() > 0) {
            try {
                pollOnce();
                touchHealthFile();
            } catch (RuntimeException e) {
                LOG.error("Poll cycle failed: {}", e.getMessage(), e);
            }
            if (stop.await(config.pollInterval().toSeconds(), TimeUnit.SECONDS)) {
                break;
            }
        }
        LOG.info("Poll loop stopped");
    }

    public void shutdown() {
        stop.countDown();
    }

    /** A single poll of the whole account. Package private so tests can drive one cycle. */
    void pollOnce() {
        var stations = client.stations();
        if (stations.isEmpty()) {
            LOG.warn("Account has no power stations");
            return;
        }
        LOG.debug("Found {} station(s)", stations.size());
        for (var station : stations) {
            try {
                publishStation(station);
            } catch (RuntimeException e) {
                LOG.warn("Skipping station {}: {}", station.name(), e.getMessage(), e);
            }
        }
    }

    private void publishStation(Model.Station station) {
        var topicId = stationTopicIds.computeIfAbsent(station.guid(), guid -> uniqueTopicId(station));
        var stationDetails = client.station(station.guid());

        var payload = mapper.createObjectNode();
        payload.put(STATION_NAME, station.name());
        payload.put(STATION_GUID, station.guid());
        station.status().ifPresent(status -> payload.put("status", status));
        putNumber(payload, GENERATION_POWER, stationDetails.generationPower());
        putNumber(payload, USE_POWER, stationDetails.usePower());
        putNumber(payload, TOTAL_GENERATION_POWER, stationDetails.generationValue());
        putNumber(payload, BATTERY_POWER, stationDetails.batteryPower());
        putString(payload, BATTERY_STATUS, stationDetails.batteryStatus().map(Model.BatteryStatus::name));
        putNumber(payload, BATTERY_SOC, stationDetails.batterySoc());
        putNumber(payload, BATTERY_REMAINING_CAPACITY, stationDetails.batteryRemainingCapacity());
        putNumber(payload, CHARGE_VALUE_DAY, stationDetails.chargeValueDay());
        putNumber(payload, DISCHARGE_VALUE_DAY, stationDetails.dischargeValueDay());
        payload.put(LAST_UPDATE, Instant.now().toString());

        var stateTopic = Topics.state(config.mqttBaseTopic(), STATION, topicId);
        var device = new Device(STATION, station.guid(), station.name(), MODEL, Optional.empty());

        var sensors = new ArrayList<Sensor>();
        if (payload.has(BATTERY_STATUS)) {
            sensors.add(Sensor.diagnostic(BATTERY_STATUS, station.name() + " status", null));
        }
        addIfPresent(sensors, payload, BATTERY_SOC,
                () -> Sensor.measurement(BATTERY_SOC, "Batterie Restkapazität (%)", "%", "Battery"));
        addIfPresent(sensors, payload, GENERATION_POWER,
                () -> Sensor.measurement(GENERATION_POWER, "Batterie Eingangsleistung", "W","Power"));
        addIfPresent(sensors, payload, USE_POWER,
                () -> Sensor.measurement(USE_POWER, "Batterie Ausgangsleistung", "W","Power"));
        addIfPresent(sensors, payload, TOTAL_GENERATION_POWER,
                () -> Sensor.totalIncreasing(TOTAL_GENERATION_POWER, "Stromerzeugung gesamt", config.energyUnit(),"Energy"));
        addIfPresent(sensors, payload, BATTERY_POWER,
                () -> Sensor.measurement(BATTERY_POWER, "Batterie Ladeleistung", "W","Power"));
        addIfPresent(sensors, payload, BATTERY_REMAINING_CAPACITY,
                () -> Sensor.measurement(BATTERY_REMAINING_CAPACITY, "Batterie Restkapazität", config.energyUnit(),"Energy"));
        addIfPresent(sensors, payload, CHARGE_VALUE_DAY,
                () -> Sensor.totalIncreasing(CHARGE_VALUE_DAY, "Batterie Ladekapazität heute", config.energyUnit(),"Energy"));
        addIfPresent(sensors, payload, DISCHARGE_VALUE_DAY,
                () -> Sensor.totalIncreasing(DISCHARGE_VALUE_DAY, "Batterie Entladekapazität heute", config.energyUnit(),"Energy"));

        discovery.publish(publisher, device, stateTopic, sensors);
        publisher.publish(stateTopic, payload.toString());
        if (config.publishRaw()) {
            publisher.publish(Topics.raw(config.mqttBaseTopic(), STATION, topicId), stationDetails.raw().toString());
        }
    }

    /**
     * Records the time of the last completed cycle. The container HEALTHCHECK reads the file
     * timestamp, so a bridge that keeps running but stops receiving data is reported unhealthy.
     */
    private void touchHealthFile() {
        var path = java.nio.file.Path.of(config.healthFile());
        try {
            java.nio.file.Files.writeString(path, Instant.now() + System.lineSeparator());
        } catch (java.io.IOException e) {
            LOG.debug("Cannot write health file {}: {}", path, e.getMessage());
        }
    }

    /** Keeps station topics distinct when two stations share a name. */
    private String uniqueTopicId(Model.Station station) {
        var candidate = Topics.slug(station.name());
        if (!stationTopicIds.containsValue(candidate)) {
            return candidate;
        }
        return candidate + "_" + Topics.slug(station.guid());
    }

    private static void putNumber(ObjectNode payload, String key, OptionalDouble value) {
        value.ifPresent(number -> payload.put(key, round(number)));
    }


    private static void putString(ObjectNode payload, String key, Optional<String> value) {
        value.ifPresent(str -> payload.put(key, str));
    }

    private static void addIfPresent(
            List<Sensor> sensors, ObjectNode payload, String key, java.util.function.Supplier<Sensor> sensor) {
        if (payload.has(key)) {
            sensors.add(sensor.get());
        }
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}

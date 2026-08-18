package com.deutscheleasing.swfactory.tsun;

import com.deutscheleasing.swfactory.tsun.mqtt.HomeAssistantDiscovery;
import com.deutscheleasing.swfactory.tsun.mqtt.HomeAssistantDiscovery.Device;
import com.deutscheleasing.swfactory.tsun.mqtt.HomeAssistantDiscovery.Sensor;
import com.deutscheleasing.swfactory.tsun.mqtt.MqttPublisher;
import com.deutscheleasing.swfactory.tsun.mqtt.Topics;
import com.deutscheleasing.swfactory.tsun.talent.Model;
import com.deutscheleasing.swfactory.tsun.talent.TalentClient;
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
                LOG.warn("Skipping station {}: {}", station.name(), e.getMessage());
            }
        }
    }

    private void publishStation(Model.Station station) {
        var topicId = stationTopicIds.computeIfAbsent(station.guid(), guid -> uniqueTopicId(station));
        var power = client.stationPower(station.guid());

        var payload = mapper.createObjectNode();
        payload.put("station_name", station.name());
        payload.put("station_guid", station.guid());
        station.status().ifPresent(status -> payload.put("status", status));
        putNumber(payload, "total_active_power", power.totalActivePower());
        putNumber(payload, "day_energy", power.dayEnergy());
        putNumber(payload, "month_energy", power.monthEnergy());
        putNumber(payload, "year_energy", power.yearEnergy());
        putNumber(payload, "total_energy", power.totalEnergy());
        payload.put("last_update", Instant.now().toString());

        var stateTopic = Topics.state(config.mqttBaseTopic(), "station", topicId);
        var device = new Device("station", station.guid(), station.name(), "Power station", Optional.empty());

        var sensors = new ArrayList<Sensor>();
        if (payload.has("status")) {
            sensors.add(Sensor.diagnostic("status", station.name() + " status", null));
        }
        addIfPresent(sensors, payload, "total_active_power",
                () -> Sensor.measurement("total_active_power", "Active power", "W", "power"));
        addIfPresent(sensors, payload, "day_energy",
                () -> Sensor.totalIncreasing("day_energy", "Energy today", config.energyUnit()));
        addIfPresent(sensors, payload, "month_energy",
                () -> Sensor.totalIncreasing("month_energy", "Energy this month", config.energyUnit()));
        addIfPresent(sensors, payload, "year_energy",
                () -> Sensor.totalIncreasing("year_energy", "Energy this year", config.energyUnit()));
        addIfPresent(sensors, payload, "total_energy",
                () -> Sensor.totalIncreasing("total_energy", "Energy total", config.energyUnit()));

        discovery.publish(publisher, device, stateTopic, sensors);
        publisher.publish(stateTopic, payload.toString());
        if (config.publishRaw()) {
            publisher.publish(Topics.raw(config.mqttBaseTopic(), "station", topicId), power.raw().toString());
        }

        publishCollectors(station, device.identifier());
        publishInverters(station, device.identifier());
    }

    private void publishCollectors(Model.Station station, String viaDevice) {
        List<Model.Device> collectors;
        try {
            collectors = client.collectors(station.guid());
        } catch (RuntimeException e) {
            LOG.warn("Cannot list collectors of {}: {}", station.name(), e.getMessage());
            return;
        }

        for (var collector : collectors) {
            var payload = mapper.createObjectNode();
            payload.put("device_id", collector.id());
            collector.serialNumber().ifPresent(sn -> payload.put("serial_number", sn));
            collector.name().ifPresent(name -> payload.put("device_name", name));
            payload.put("station_name", station.name());
            putNumber(payload, "signal_strength", collector.signalStrength());
            payload.put("last_update", Instant.now().toString());

            var stateTopic = Topics.state(config.mqttBaseTopic(), "collector", collector.id());
            var device = new Device("collector", collector.id(),
                    collector.name().orElse("Collector " + collector.id()), "Collector",
                    Optional.of(viaDevice));

            var sensors = new ArrayList<Sensor>();
            addIfPresent(sensors, payload, "signal_strength",
                    () -> Sensor.diagnostic("signal_strength", "Signal strength", "%"));

            discovery.publish(publisher, device, stateTopic, sensors);
            publisher.publish(stateTopic, payload.toString());
            if (config.publishRaw()) {
                publisher.publish(Topics.raw(config.mqttBaseTopic(), "collector", collector.id()),
                        collector.raw().toString());
            }
        }
    }

    private void publishInverters(Model.Station station, String viaDevice) {
        List<Model.Device> inverters;
        try {
            inverters = client.inverters(station.guid());
        } catch (RuntimeException e) {
            LOG.warn("Cannot list inverters of {}: {}", station.name(), e.getMessage());
            return;
        }

        for (var inverter : inverters) {
            try {
                publishInverter(station, inverter, viaDevice);
            } catch (RuntimeException e) {
                LOG.warn("Skipping inverter {}: {}", inverter.id(), e.getMessage());
            }
        }
    }

    private void publishInverter(Model.Station station, Model.Device inverter, String viaDevice) {
        var info = client.inverterInfo(inverter.guid());

        var payload = mapper.createObjectNode();
        payload.put("device_id", inverter.id());
        inverter.serialNumber().ifPresent(sn -> payload.put("serial_number", sn));
        inverter.name().ifPresent(name -> payload.put("device_name", name));
        payload.put("station_name", station.name());
        putNumber(payload, "temperature", info.temperature());

        var sensors = new ArrayList<Sensor>();
        addIfPresent(sensors, payload, "temperature",
                () -> Sensor.measurement("temperature", "Inverter temperature", "°C", "temperature"));

        var pvTotal = 0.0;
        var pvTotalKnown = false;
        for (var pv : info.pvStrings()) {
            var prefix = "pv" + pv.index() + "_";
            putNumber(payload, prefix + "voltage", pv.voltage());
            putNumber(payload, prefix + "current", pv.current());
            putNumber(payload, prefix + "power", pv.power());
            if (pv.power().isPresent()) {
                pvTotal += pv.power().getAsDouble();
                pvTotalKnown = true;
            }
            var label = "PV" + pv.index();
            addIfPresent(sensors, payload, prefix + "voltage",
                    () -> Sensor.measurement(prefix + "voltage", label + " voltage", "V", "voltage"));
            addIfPresent(sensors, payload, prefix + "current",
                    () -> Sensor.measurement(prefix + "current", label + " current", "A", "current"));
            addIfPresent(sensors, payload, prefix + "power",
                    () -> Sensor.measurement(prefix + "power", label + " power", "W", "power"));
        }
        if (pvTotalKnown) {
            payload.put("pv_total_power", round(pvTotal));
            sensors.add(Sensor.measurement("pv_total_power", "PV power total", "W", "power"));
        }

        for (var phase : info.phases()) {
            var prefix = "grid" + phase.index() + "_";
            putNumber(payload, prefix + "voltage", phase.voltage());
            putNumber(payload, prefix + "current", phase.current());
            putNumber(payload, prefix + "frequency", phase.frequency());
            var label = info.phases().size() > 1 ? "Grid L" + phase.index() : "Grid";
            addIfPresent(sensors, payload, prefix + "voltage",
                    () -> Sensor.measurement(prefix + "voltage", label + " voltage", "V", "voltage"));
            addIfPresent(sensors, payload, prefix + "current",
                    () -> Sensor.measurement(prefix + "current", label + " current", "A", "current"));
            addIfPresent(sensors, payload, prefix + "frequency",
                    () -> Sensor.measurement(prefix + "frequency", label + " frequency", "Hz", "frequency"));
        }

        payload.put("last_update", Instant.now().toString());

        var stateTopic = Topics.state(config.mqttBaseTopic(), "inverter", inverter.id());
        var device = new Device("inverter", inverter.id(),
                inverter.name().orElse("Inverter " + inverter.id()), "Inverter", Optional.of(viaDevice));

        discovery.publish(publisher, device, stateTopic, sensors);
        publisher.publish(stateTopic, payload.toString());
        if (config.publishRaw()) {
            publisher.publish(Topics.raw(config.mqttBaseTopic(), "inverter", inverter.id()),
                    info.raw().toString());
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

package com.deutscheleasing.swfactory.tsun;

import com.deutscheleasing.swfactory.tsun.mqtt.HomeAssistantDiscovery;
import com.deutscheleasing.swfactory.tsun.mqtt.MqttPublisher;
import com.deutscheleasing.swfactory.tsun.talent.TalentClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/** Entry point: wires the TALENT client, the MQTT publisher and the poll loop together. */
public final class Main {

    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

    private Main() {
    }

    public static void main(String[] args) {
        // Must happen before the first logger is created, hence no static logger in this class.
        configureLogging(System.getenv().getOrDefault("LOG_LEVEL", "info"));
        var log = LoggerFactory.getLogger(Main.class);

        Config config;
        try {
            config = Config.fromEnv();
        } catch (IllegalStateException e) {
            log.error("Invalid configuration: {}", e.getMessage());
            System.exit(2);
            return;
        }

        var mapper = new ObjectMapper();
        var client = TalentClient.create(config, mapper);
        var discovery = new HomeAssistantDiscovery(config, mapper);
        var publisher = new MqttPublisher(config);
        // Retained discovery configs are lost on a clean session, so resend them after every connect.
        publisher.onConnected(p -> discovery.reset());
        var bridge = new Bridge(config, client, publisher, discovery, mapper);

        var main = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            bridge.shutdown();
            try {
                main.join(SHUTDOWN_GRACE.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "tsun2mqtt-shutdown"));

        try (publisher) {
            publisher.connect();
            bridge.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.error("Fatal error: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    private static void configureLogging(String level) {
        setIfAbsent("org.slf4j.simpleLogger.defaultLogLevel", level.toLowerCase());
        setIfAbsent("org.slf4j.simpleLogger.showDateTime", "true");
        setIfAbsent("org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd HH:mm:ss");
        setIfAbsent("org.slf4j.simpleLogger.showShortLogName", "true");
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }
}

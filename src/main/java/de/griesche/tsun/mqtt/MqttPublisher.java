package de.griesche.tsun.mqtt;

import de.griesche.tsun.Config;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Thin MQTT v5 publisher with a retained availability topic and automatic reconnect. */
public class MqttPublisher implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MqttPublisher.class);

    public static final String PAYLOAD_ONLINE = "online";
    public static final String PAYLOAD_OFFLINE = "offline";

    private static final int CONNECT_ATTEMPTS = 10;
    private static final java.time.Duration CONNECT_RETRY_DELAY = java.time.Duration.ofSeconds(10);

    private final Config config;
    private final MqttClient client;
    private final String availabilityTopic;

    /**
     * Paho delivers token completions on its own callback thread, so a blocking publish issued
     * from inside a callback would wait for that very thread. All callback follow-up work is
     * therefore handed to this worker.
     */
    private final ExecutorService callbackWorker = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "tsun2mqtt-mqtt-callback");
        thread.setDaemon(true);
        return thread;
    });

    /** Invoked after every (re)connect, so retained discovery messages can be republished. */
    private volatile Consumer<MqttPublisher> onConnected = publisher -> { };

    public MqttPublisher(Config config) {
        this.config = config;
        this.availabilityTopic = config.mqttBaseTopic() + "/status";
        var clientId = config.mqttClientId() + "-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            this.client = new MqttClient(config.mqttUrl(), clientId, new MemoryPersistence());
        } catch (MqttException e) {
            throw new IllegalStateException("Cannot create MQTT client for " + config.mqttUrl(), e);
        }
    }

    public void onConnected(Consumer<MqttPublisher> callback) {
        this.onConnected = callback;
    }

    public String availabilityTopic() {
        return availabilityTopic;
    }

    public void connect() {
        var options = new MqttConnectionOptions();
        options.setCleanStart(true);
        options.setAutomaticReconnect(true);
        options.setKeepAliveInterval(60);
        options.setConnectionTimeout((int) config.httpTimeout().toSeconds());
        config.mqttUsername().ifPresent(options::setUserName);
        config.mqttPassword().ifPresent(p -> options.setPassword(p.getBytes(StandardCharsets.UTF_8)));
        options.setWill(availabilityTopic, retained(PAYLOAD_OFFLINE));

        client.setCallback(new MqttCallback() {
            @Override
            public void connectComplete(boolean reconnect, String serverUri) {
                LOG.info("MQTT {} to {}", reconnect ? "reconnected" : "connected", serverUri);
                // Never publish on the callback thread, see callbackWorker.
                callbackWorker.execute(() -> {
                    publish(availabilityTopic, PAYLOAD_ONLINE, true);
                    onConnected.accept(MqttPublisher.this);
                });
            }

            @Override
            public void disconnected(MqttDisconnectResponse response) {
                LOG.warn("MQTT disconnected: {}", response.getReasonString());
            }

            @Override
            public void mqttErrorOccurred(MqttException exception) {
                LOG.warn("MQTT error: {}", exception.getMessage());
            }

            @Override
            public void deliveryComplete(IMqttToken token) {
                // nothing to do, publishes are fire and forget
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                // this bridge is publish only
            }

            @Override
            public void authPacketArrived(int reasonCode, MqttProperties properties) {
                // no enhanced authentication in use
            }
        });

        connectWithRetry(options);
    }

    /**
     * Paho only reconnects automatically once it has been connected, so the initial connect is
     * retried here: broker and bridge are usually started together. After that the container
     * restart policy takes over.
     */
    private void connectWithRetry(MqttConnectionOptions options) {
        for (var attempt = 1; ; attempt++) {
            try {
                client.connect(options);
                return;
            } catch (MqttException e) {
                if (attempt >= CONNECT_ATTEMPTS) {
                    throw new IllegalStateException("Cannot connect to MQTT broker " + config.mqttUrl()
                            + " after " + attempt + " attempts", e);
                }
                LOG.warn("MQTT connect to {} failed ({}), retrying in {}s [{}/{}]",
                        config.mqttUrl(), e.getMessage(), CONNECT_RETRY_DELAY.toSeconds(),
                        attempt, CONNECT_ATTEMPTS);
                try {
                    Thread.sleep(CONNECT_RETRY_DELAY);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while connecting to MQTT broker", e);
                }
            }
        }
    }

    /** Publishes with the configured QoS and retain flag. */
    public void publish(String topic, String payload) {
        publish(topic, payload, config.mqttRetain());
    }

    public void publish(String topic, String payload, boolean retain) {
        var message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
        message.setQos(config.mqttQos());
        message.setRetained(retain);
        try {
            client.publish(topic, message);
            LOG.debug("-> {} {}", topic, payload);
        } catch (MqttException e) {
            LOG.warn("Publish to {} failed: {}", topic, e.getMessage());
        }
    }

    private MqttMessage retained(String payload) {
        var message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
        message.setQos(config.mqttQos());
        message.setRetained(true);
        return message;
    }

    @Override
    public void close() {
        LOG.info("Closing MQTT Publisher.");
        callbackWorker.shutdownNow();
        try {
            if (client.isConnected()) {
                publish(availabilityTopic, PAYLOAD_OFFLINE, true);
                client.disconnect();
            }
        } catch (MqttException e) {
            LOG.debug("Ignoring error while disconnecting: {}", e.getMessage());
        } finally {
            try {
                client.close();
            } catch (MqttException e) {
                LOG.debug("Ignoring error while closing client: {}", e.getMessage());
            }
        }
    }
}

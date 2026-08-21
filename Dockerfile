# syntax=docker/dockerfile:1

# ---------- build ----------
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

# Dependencies first, so code changes do not invalidate the dependency layer.
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
#RUN mvn -B -ntp clean package -DskipTests
RUN mvn -B -ntp clean package

# ---------- runtime ----------
FROM eclipse-temurin:25-jre-alpine

LABEL org.opencontainers.image.title="tsun2mqtt" \
      org.opencontainers.image.description="Publishes TALENT Monitoring (TSUN) inverter data to MQTT" \
      org.opencontainers.image.source="https://pro.talent-monitoring.com/"

# tzdata so a TZ setting actually applies to log timestamps.
RUN apk add --no-cache tzdata \
    && addgroup -S -g 10001 app \
    && adduser -S -u 10001 -G app app

WORKDIR /app
COPY --from=build /build/target/tsun2mqtt.jar /app/tsun2mqtt.jar
USER app

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC" \
    TALENT_BASE_URL="https://pro.talent-monitoring.com" \
    POLL_INTERVAL_SECONDS="30" \
    MQTT_URL="tcp://localhost:1883" \
    MQTT_BASE_TOPIC="tsun" \
    HA_DISCOVERY_ENABLED="true" \
    HEALTH_FILE="/tmp/tsun2mqtt-healthy" \
    HEALTH_MAX_AGE="900"

# Unhealthy as soon as no poll cycle completed within HEALTH_MAX_AGE seconds.
HEALTHCHECK --interval=60s --timeout=5s --start-period=90s --retries=3 \
    CMD [ -f "$HEALTH_FILE" ] \
        && [ $(( $(date +%s) - $(stat -c %Y "$HEALTH_FILE") )) -lt "$HEALTH_MAX_AGE" ]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/tsun2mqtt.jar"]

# tsun2mqtt

Docker image that polls the **TALENT Monitoring** cloud API (`pro.talent-monitoring.com`, the portal
behind TSUN inverters / Talent Pro app) and publishes every reading to **MQTT**, including
Home Assistant auto-discovery.

No inverter firmware changes, no local proxy: it logs in with the same account you use in the
portal, walks every power station and every device of that account, and republishes the values.

## Quick start

```bash
cp .env.example .env      # fill in TALENT_USERNAME / TALENT_PASSWORD / MQTT_URL
docker compose up -d      # includes a throwaway Mosquitto broker; delete that service if you have one
docker compose logs -f tsun2mqtt
```

Or standalone against an existing broker:

```bash
docker build -t tsun2mqtt .
docker run -d --name tsun2mqtt --restart unless-stopped \
  -e TALENT_USERNAME='you@example.com' \
  -e TALENT_PASSWORD='...' \
  -e MQTT_URL='tcp://192.168.1.10:1883' \
  tsun2mqtt
```

Watch what it produces:

```bash
mosquitto_sub -h localhost -t 'tsun/#' -v
```

## What gets published

One JSON message per device per poll cycle (retained by default), plus a retained availability topic
with a last-will so consumers notice a dead bridge.

| Topic | Content |
| --- | --- |
| `tsun/status` | `online` / `offline` |
| `tsun/station/<station-name>/state` | station totals |
| `tsun/inverter/<serial>/state` | per-inverter live values |
| `tsun/collector/<serial>/state` | WiFi stick / data logger diagnostics |
| `tsun/<kind>/<id>/raw` | untouched API payload, only when `PUBLISH_RAW=true` |

Station payloads carry `station_name`, `status`, `total_active_power`, `day_energy`,
`month_energy`, `year_energy` and `total_energy`. The number of PV strings and AC phases follows
whatever the API reports, so a three-string or three-phase inverter gets `pv3_*` / `grid3_*` keys
without a code change. **Values the API does not report are omitted rather than published as `0`.**

### Home Assistant

With `HA_DISCOVERY_ENABLED=true` (the default) a retained config message is published per value
under `homeassistant/sensor/...`, so entities appear on their own. Stations, inverters and
collectors become separate devices, with inverters/collectors linked to their station via
`via_device`. Energy counters use `state_class: total_increasing` so they feed the Energy dashboard;
signal strength and status are marked as diagnostic entities.

Discovery configs are re-sent after every broker reconnect. To clean up the entities after removing
the bridge, clear its retained configs — MQTT wildcards cannot match a partial topic level, so
filter on the `tsun_` node prefix instead of wildcarding it:

```bash
mosquitto_sub -h localhost -t 'homeassistant/sensor/#' -v --retained-only -W 3 \
  | awk '$1 ~ /\/tsun_/ {print $1}' \
  | while read -r topic; do mosquitto_pub -h localhost -t "$topic" -r -n; done
```

## Configuration

All configuration is environment variables (see `.env.example`).

| Variable | Default | Meaning |
| --- | --- | --- |
| `TALENT_USERNAME` | – | **required**, portal login |
| `TALENT_PASSWORD` | – | **required**, portal password |
| `TALENT_BASE_URL` | `https://pro.talent-monitoring.com` | use `https://www.talent-monitoring.com` for the non-pro portal |
| `TALENT_TIMEZONE` | `+02:00` | UTC offset sent to the station endpoint |
| `POLL_INTERVAL_SECONDS` | `300` | poll cycle length |
| `HTTP_TIMEOUT_SECONDS` | `30` | per-request timeout |
| `MQTT_URL` | `tcp://localhost:1883` | `tcp://`, `ssl://` or `ws://` |
| `MQTT_USERNAME` / `MQTT_PASSWORD` | – | optional broker credentials |
| `MQTT_CLIENT_ID` | `tsun2mqtt` | a random suffix is appended |
| `MQTT_BASE_TOPIC` | `tsun` | root of all state topics |
| `MQTT_QOS` | `0` | 0, 1 or 2 |
| `MQTT_RETAIN` | `true` | retain state messages |
| `HA_DISCOVERY_ENABLED` | `true` | publish Home Assistant discovery configs |
| `HA_DISCOVERY_PREFIX` | `homeassistant` | discovery topic prefix |
| `ENERGY_UNIT` | `kWh` | unit advertised for energy counters |
| `PUBLISH_RAW` | `false` | also publish raw API payloads |
| `LOG_LEVEL` | `info` | `trace`/`debug`/`info`/`warn`/`error` |
| `HEALTH_FILE` | `/tmp/tsun2mqtt-healthy` | touched after each successful cycle |
| `HEALTH_MAX_AGE` | `900` | seconds before the container is reported unhealthy |

Be gentle with `POLL_INTERVAL_SECONDS`: this is someone else's cloud, and the portal itself only
refreshes inverter data every few minutes. Below ~60s you gain nothing but rate-limit risk.

## Operating notes

- **Resilience.** A failing station or device is logged and skipped; the loop keeps running. Expired
  tokens trigger a transparent re-login (the API signals this as HTTP 401 or `code: 401`).
- **Broker startup.** The first connect is retried 10 times, 10s apart, so the bridge survives a
  broker that is not up yet; later drops are handled by the Paho auto-reconnect. If the broker is
  still unreachable after that the process exits non-zero and the restart policy takes over.
- **Health.** `docker inspect --format '{{.State.Health.Status}}' tsun2mqtt` turns `unhealthy` when
  no poll cycle completed within `HEALTH_MAX_AGE`, which also covers "process alive, cloud dead".
- **Nights.** The portal reports zeros or placeholders (`"--"`) when the inverters are asleep;
  placeholders are treated as "no value" and omitted.
- **Credentials** are only read from the environment and never logged. Prefer an `.env` file or
  Docker secrets over inline `-e` flags in shell history.

## Development

Requires JDK 25. There is no Maven wrapper in the repo, so use a local Maven or the build image:

```bash
mvn clean package                  # produces target/tsun2mqtt.jar (shaded, runnable)
mvn test                           # unit tests only, no network needed

# without a local Maven installation:
docker build -t tsun2mqtt .        # compiles and runs the tests inside the build stage
```



package de.griesche.tsun.talent;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Value types extracted from the TALENT API responses. Each keeps its raw node for pass-through.
 */
public final class Model {

    private Model() {
    }

    /**
     * A power station ("plant") of the account.
     */
    public record Station(String guid, String name, Optional<String> status, JsonNode raw) {

        static Station of(final JsonNode row) {
            final JsonNode station = row.get("station");
            return new Station(
                    Json.text(station, "id").orElseThrow(
                            () -> new TalentApiException("Station row without id: " + station)),
                    Json.text(station, "stationName").orElse("station"),
                    Json.text(station, "batteryStatus"),
                    station);
        }
    }

    public enum BatteryStatus {
        CHARGE,
        DISCHARGE,
        STATIC
    }

    /**
     * Aggregated production figures of a station.
     */
    public record StationDetails(
            OptionalDouble batterySoc,
            OptionalDouble generationPower,
            OptionalDouble usePower,
            OptionalDouble generationValue,
            OptionalDouble batteryPower,
            OptionalDouble chargeValueDay,
            OptionalDouble dischargeValueDay,
            Optional<BatteryStatus> batteryStatus,
            OptionalDouble batteryRemainingCapacity,
            JsonNode raw) {

        static StationDetails of(final JsonNode data) {
            return new StationDetails(
                    Json.number(data, "batterySoc"),
                    Json.number(data, "generationPower"),
                    Json.number(data, "usePower"),
                    Json.number(data, "generationUploadTotal"),
                    Json.number(data, "batteryPower"),
                    Json.number(data, "chargeValue"),
                    Json.number(data, "dischargeValue"),
                    Json.text(data, "batteryStatus").map(BatteryStatus::valueOf),
                    Json.number(data, "remainingCapacity"),
                    data);
        }
    }

}

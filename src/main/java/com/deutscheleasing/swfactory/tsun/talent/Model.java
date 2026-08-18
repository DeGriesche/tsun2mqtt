package com.deutscheleasing.swfactory.tsun.talent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/** Value types extracted from the TALENT API responses. Each keeps its raw node for pass-through. */
public final class Model {

    private Model() {
    }

    /** A power station ("plant") of the account. */
    public record Station(String guid, String name, Optional<String> status, JsonNode raw) {

        static Station of(JsonNode row) {
            JsonNode station = row.get("station");
            return new Station(
                    Json.text(station, "id").orElseThrow(
                            () -> new TalentApiException("Station row without powerStationGuid: " + station)),
                    Json.text(station,  "name").orElse("station"),
                    Json.text(station, "batteryStatus"),
                    station);
        }
    }

    /** An inverter or a collector (data logger / WiFi stick). */
    public record Device(
            String guid,
            Optional<String> serialNumber,
            Optional<String> name,
            Optional<String> stationGuid,
            OptionalDouble signalStrength,
            JsonNode raw) {

        static Device of(JsonNode row) {
            return new Device(
                    Json.text(row, "deviceGuid", "guid", "deviceId").orElseThrow(
                            () -> new TalentApiException("Device row without deviceGuid: " + row)),
                    Json.text(row, "deviceSn", "sn", "deviceSN", "serialNumber"),
                    Json.text(row, "deviceName", "name", "alias"),
                    Json.text(row, "powerStationGuid", "stationGuid"),
                    Json.number(row, "signalStrength", "signal", "rssi"),
                    row);
        }

        /** Stable, human-recognisable id used in topics: serial number if known, else the GUID. */
        public String id() {
            return serialNumber().orElse(guid());
        }
    }

    /** Aggregated production figures of a station. */
    public record StationDetails(
            OptionalDouble soc,
            OptionalDouble currentGenerationPower,
            OptionalDouble totalGenerationPower,
            JsonNode raw) {

        static StationDetails of(JsonNode data) {
            return new StationDetails(
                    Json.number(data, "batterySoc"),
                    Json.number(data, "generationPower"),
                    Json.number(data, "generationValue"),
                    data);
        }
    }

    /** Aggregated production figures of a station. */
    public record StationPower(
            OptionalDouble totalActivePower,
            OptionalDouble dayEnergy,
            OptionalDouble monthEnergy,
            OptionalDouble yearEnergy,
            OptionalDouble totalEnergy,
            JsonNode raw) {

        static StationPower of(JsonNode data) {
            return new StationPower(
                    Json.number(data, "totalActivePower", "activePower", "power"),
                    Json.number(data, "dayEnergy", "todayEnergy", "dailyEnergy"),
                    Json.number(data, "monthEnergy", "monthlyEnergy"),
                    Json.number(data, "yearEnergy", "yearlyEnergy"),
                    Json.number(data, "totalEnergy", "cumulativeEnergy", "allEnergy"),
                    data);
        }
    }

    /** One PV input (string) of an inverter. */
    public record PvString(int index, OptionalDouble voltage, OptionalDouble current, OptionalDouble power) {
    }

    /** One AC phase of an inverter. */
    public record Phase(int index, OptionalDouble voltage, OptionalDouble current, OptionalDouble frequency) {
    }

    /** Live readings of a single inverter. */
    public record InverterInfo(
            OptionalDouble temperature,
            List<PvString> pvStrings,
            List<Phase> phases,
            JsonNode raw) {

        static InverterInfo of(JsonNode data) {
            var pv = Json.array(data, "pv", "pvList");
            var pvStrings = new java.util.ArrayList<PvString>(pv.size());
            for (var i = 0; i < pv.size(); i++) {
                var node = pv.get(i);
                pvStrings.add(new PvString(
                        i + 1,
                        Json.number(node, "voltage", "vol", "u"),
                        Json.number(node, "current", "cur", "i"),
                        Json.number(node, "power", "p")));
            }

            var ac = Json.array(data, "phase", "phaseList", "ac");
            var phases = new java.util.ArrayList<Phase>(ac.size());
            for (var i = 0; i < ac.size(); i++) {
                var node = ac.get(i);
                phases.add(new Phase(
                        i + 1,
                        Json.number(node, "voltage", "vol", "u"),
                        Json.number(node, "current", "cur", "i"),
                        Json.number(node, "frequency", "freq", "f")));
            }

            return new InverterInfo(
                    Json.number(data, "inverterTemp", "temperature", "temp"),
                    List.copyOf(pvStrings),
                    List.copyOf(phases),
                    data);
        }
    }
}

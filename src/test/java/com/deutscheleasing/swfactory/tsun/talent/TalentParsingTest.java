package com.deutscheleasing.swfactory.tsun.talent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing of the response shapes the pro portal returns. */
class TalentParsingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static com.fasterxml.jackson.databind.JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void readsStationRows() {
        var body = json("""
                {"total":1,"rows":[{"powerStationGuid":"abc-123","stationName":"Balcony","status":"1"}],"code":200}
                """);

        var rows = Json.rows(body);
        assertEquals(1, rows.size());

        var station = Model.Station.of(rows.getFirst());
        assertEquals("abc-123", station.guid());
        assertEquals("Balcony", station.name());
        assertEquals("1", station.status().orElseThrow());
    }

    @Test
    void readsStationPowerWithStringNumbers() {
        var body = json("""
                {"code":200,"data":{"totalActivePower":"612.5","dayEnergy":3.42,"monthEnergy":88,"yearEnergy":"--"}}
                """);

        var power = Model.StationPower.of(Json.at(body, "data"));
        assertEquals(612.5, power.totalActivePower().orElseThrow());
        assertEquals(3.42, power.dayEnergy().orElseThrow());
        assertEquals(88.0, power.monthEnergy().orElseThrow());
        // "--" is the portal placeholder for "no value", it must not become 0.
        assertTrue(power.yearEnergy().isEmpty());
        assertTrue(power.totalEnergy().isEmpty());
    }

    @Test
    void readsInverterInfoWithVariableStringCount() {
        var body = json("""
                {"code":200,"data":{
                  "inverterTemp":41.3,
                  "pv":[{"voltage":34.1,"current":2.2,"power":75.0},
                        {"voltage":33.8,"current":2.1,"power":71.0},
                        {"voltage":0,"current":0,"power":0}],
                  "phase":[{"voltage":229.7,"current":0.63,"frequency":50.01}]}}
                """);

        var info = Model.InverterInfo.of(Json.at(body, "data"));
        assertEquals(41.3, info.temperature().orElseThrow());
        assertEquals(3, info.pvStrings().size());
        assertEquals(1, info.pvStrings().getFirst().index());
        assertEquals(75.0, info.pvStrings().getFirst().power().orElseThrow());
        assertEquals(50.01, info.phases().getFirst().frequency().orElseThrow());
    }

    @Test
    void toleratesMissingSections() {
        var info = Model.InverterInfo.of(Json.at(json("{\"data\":{}}"), "data"));
        assertTrue(info.pvStrings().isEmpty());
        assertTrue(info.phases().isEmpty());
        assertTrue(info.temperature().isEmpty());
    }

    @Test
    void readsDeviceRowsFromNestedData() {
        var body = json("""
                {"code":200,"data":{"rows":[{"deviceGuid":"dev-1","deviceSn":"Y1234567","signalStrength":"78"}]}}
                """);

        var device = Model.Device.of(Json.rows(body).getFirst());
        assertEquals("dev-1", device.guid());
        assertEquals("Y1234567", device.serialNumber().orElseThrow());
        assertEquals("Y1234567", device.id());
        assertEquals(78.0, device.signalStrength().orElseThrow());
        assertFalse(device.stationGuid().isPresent());
    }
}

package de.griesche.tsun.talent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Parsing of the response shapes the pro portal returns.
 */
class TalentParsingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(final String text) {
        try {
            return MAPPER.readTree(text);
        } catch (final Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void readsStationRows() {
        final var body = json("""
                              {"station":{"id":"abc-123","stationName":"Balcony","batteryStatus":"1"}}
                              """);

        final var station = Model.Station.of(body);
        assertEquals("abc-123", station.guid());
        assertEquals("Balcony", station.name());
        assertEquals("1", station.status().orElseThrow());
    }

}

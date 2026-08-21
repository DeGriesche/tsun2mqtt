package de.griesche.tsun.talent;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.griesche.tsun.Config;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TalentClientST {
    static TalentClient talentClient;

    @BeforeAll
    public static void init() {
        final Config config = Config.fromEnv();
        talentClient = TalentClient.create(config, new ObjectMapper());
    }

    @Test
    public void login() {
        talentClient.login();
    }

    @Test
    public void stations() {
        final List<Model.Station> stationList = talentClient.stations();
        assertEquals(1, stationList.size());
    }

    @Test
    public void station() {
        final Model.StationDetails station = talentClient.station("168510");
        assertNotNull(station.batterySoc());
    }
}

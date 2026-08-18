package com.deutscheleasing.swfactory.tsun.talent;

import com.deutscheleasing.swfactory.tsun.Config;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TalentClientTest {
    static TalentClient talentClient;
    @BeforeAll
    public static void init() {
        Config config = Config.fromEnv();
         talentClient = TalentClient.create(config, new ObjectMapper());
    }

    @Test
    public void login() {
        talentClient.login();
    }

    @Test
    public void stations() {
        List<Model.Station> stationList=  talentClient.stations();
        assertEquals(1, stationList.size());
    }

    @Test
    public void station() {
        Model.StationDetails station=  talentClient.station("168510");
        assertNotNull(station.soc());
    }
}

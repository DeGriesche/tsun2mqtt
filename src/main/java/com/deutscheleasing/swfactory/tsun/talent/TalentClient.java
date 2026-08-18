package com.deutscheleasing.swfactory.tsun.talent;

import com.deutscheleasing.swfactory.tsun.Config;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Client for the (undocumented) TALENT Monitoring REST API, as used by the pro portal and the
 * TSUN mobile apps. Endpoints were derived from the community integrations listed in the README.
 *
 * <p>Auth is a bearer token obtained from {@code POST /login}. The token is refreshed
 * transparently when the API answers 401, either as HTTP status or as {@code code} in the body.
 */
public class TalentClient {

    private static final Logger LOG = LoggerFactory.getLogger(TalentClient.class);
    private static final int PAGE_SIZE = 100;

    private final Config config;
    private final HttpClient http;
    private final OkHttpClient client = new OkHttpClient();
    private final ObjectMapper mapper;

    private volatile String token;

    public TalentClient(Config config, HttpClient http, ObjectMapper mapper) {
        this.config = config;
        this.http = http;
        this.mapper = mapper;
    }

    public static TalentClient create(Config config, ObjectMapper mapper) {
        var http = HttpClient.newBuilder()
                .connectTimeout(config.httpTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return new TalentClient(config, http, mapper);
    }

    /** Authenticates and caches the bearer token. */
    public void login() {
        var payload = String.format("grant_type=password&username=%s&password=%S&client_id=sdl_client&identity_type=2",
                 config.talentUsername(),config.talentPassword());

        RequestBody formBody = new FormBody.Builder()
                .add("grant_type","password")
                .add("username", config.talentUsername())
                .add("password", config.talentPassword())
                .add("client_id","sdl_client")
                .add("identity_type","2")
                .build();
        Request request = new Request.Builder()
                .url(config.talentBaseUrl() + "/oauth2-s/oauth/token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(formBody)
                .build();

        var body = send(request);
        token = Json.text(body, "access_token")
                .or(() -> Json.text(Json.at(body, "data"), "token", "access_token"))
                .orElseThrow(() -> new TalentApiException(
                        "Login failed: no token in response (" + describe(body) + ")", true, null));
        LOG.info("Logged in to {} as {}", config.talentBaseUrl(), config.talentUsername());
    }

    public List<Model.Station> stations() {
        return Json.rows(post("/station-s/station/query/list","{\"returnTag\": true}\n")).stream()
                .map(Model.Station::of)
                .toList();
    }

    public Model.StationDetails station(String stationGuid) {
        var body = get("/station-s/station/statistic/current/flow/" + stationGuid);
        return Model.StationDetails.of(Json.at(body, "stationCurrentDto"));
    }

    public Model.StationPower stationPower(String stationGuid) {
        var body = get("/system/station/getPowerStationByGuid"
                + "?powerStationGuid=" + encode(stationGuid)
                + "&timezone=" + encode(config.talentTimezone()));
        return Model.StationPower.of(Json.at(body, "data"));
    }

    /** Inverters of a station. The list endpoint is account wide, so rows are filtered by station. */
    public List<Model.Device> inverters(String stationGuid) {
        return devices("/tools/device/selectDeviceInverter", stationGuid);
    }

    /** Collectors (WiFi sticks, data loggers) of a station. */
    public List<Model.Device> collectors(String stationGuid) {
        return devices("/tools/device/selectDeviceCollector", stationGuid);
    }

    public Model.InverterInfo inverterInfo(String deviceGuid) {
        var body = get("/tools/device/selectDeviceInverterInfo?deviceGuid=" + encode(deviceGuid));
        return Model.InverterInfo.of(Json.at(body, "data"));
    }

    private List<Model.Device> devices(String path, String stationGuid) {
        var body = get(path
                + "?powerStationGuid=" + encode(stationGuid)
                + "&pageNum=1&pageSize=" + PAGE_SIZE);
        return Json.rows(body).stream()
                .map(Model.Device::of)
                // The filter parameter is ignored by some portal versions, so keep only the rows of
                // this station whenever a row carries a station reference at all.
                .filter(d -> d.stationGuid().map(stationGuid::equals).orElse(true))
                .toList();
    }

    /** GET a path relative to the API base, logging in or re-logging in as needed. */
    public JsonNode get(String pathAndQuery) {
        if (token == null) {
            login();
        }
        try {
            return send(authorizedGet(pathAndQuery));
        } catch (TalentApiException e) {
            if (!e.isUnauthorized()) {
                throw e;
            }
            LOG.debug("Token rejected for {}, re-authenticating", pathAndQuery);
            login();
            return send(authorizedGet(pathAndQuery));
        }
    }

    /** POST a path relative to the API base, logging in or re-logging in as needed. */
    public JsonNode post(String pathAndQuery, String body) {
        if (token == null) {
            login();
        }
        try {
            return send(authorizedPost(pathAndQuery,body));
        } catch (TalentApiException e) {
            if (!e.isUnauthorized()) {
                throw e;
            }
            LOG.debug("Token rejected for {}, re-authenticating", pathAndQuery);
            login();
            return send(authorizedPost(pathAndQuery,body));
        }
    }

    private Request authorizedGet(String pathAndQuery) {
        return new Request.Builder()
                .url(config.talentBaseUrl() + pathAndQuery)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .get()
                .build();
    }

    private Request authorizedPost(String pathAndQuery, String body) {
        return new Request.Builder()
                .url(config.talentBaseUrl() + pathAndQuery)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.getBytes(StandardCharsets.UTF_8)))
                .build();
    }

    private JsonNode send(Request request) {
        try (Response response = client.newCall(request).execute()){
            var status = response.code();
            if (status == 401 || status == 403) {
                throw new TalentApiException("API returned HTTP " + status + " for " + request.url(), true, null);
            }
            if (status / 100 != 2) {
                throw new TalentApiException("API returned HTTP " + status + " for " + request.url()
                        + ": " + abbreviate(response.body().string()));
            }

            JsonNode body;
            try {
                body = mapper.readTree(response.body().string());
            } catch (IOException e) {
                throw new TalentApiException("Malformed JSON from " + request.url(), false, e);
            }

            // The portal runs a RuoYi backend: business errors come back as HTTP 200 with a code field.
            var code = Json.number(body, "code");
            if (code.isPresent() && code.getAsDouble() != 200) {
                var value = (int) code.getAsDouble();
                var unauthorized = value == 401 || value == 403;
                throw new TalentApiException("API error " + value + " for " + request.url()
                        + ": " + describe(body), unauthorized, null);
            }
            return body;

        } catch (IOException e) {
            throw new TalentApiException(request.method() + " " + request.url() + " failed", false, e);
        }
    }

    private JsonNode send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new TalentApiException(request.method() + " " + request.uri().getPath() + " failed", false, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TalentApiException("Interrupted while calling " + request.uri().getPath(), false, e);
        }

        var status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new TalentApiException(
                    "API returned HTTP " + status + " for " + request.uri().getPath(), true, null);
        }
        if (status / 100 != 2) {
            throw new TalentApiException("API returned HTTP " + status + " for " + request.uri().getPath()
                    + ": " + abbreviate(response.body()));
        }

        JsonNode body;
        try {
            body = mapper.readTree(response.body());
        } catch (IOException e) {
            throw new TalentApiException("Malformed JSON from " + request.uri().getPath(), false, e);
        }

        // The portal runs a RuoYi backend: business errors come back as HTTP 200 with a code field.
        var code = Json.number(body, "code");
        if (code.isPresent() && code.getAsDouble() != 200) {
            var value = (int) code.getAsDouble();
            var unauthorized = value == 401 || value == 403;
            throw new TalentApiException("API error " + value + " for " + request.uri().getPath()
                    + ": " + describe(body), unauthorized, null);
        }
        return body;
    }

    private static String describe(JsonNode body) {
        return Json.text(body, "msg", "message", "error").orElseGet(() -> abbreviate(body.toString()));
    }

    private static String abbreviate(String text) {
        var flat = Optional.ofNullable(text).orElse("").replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "...";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

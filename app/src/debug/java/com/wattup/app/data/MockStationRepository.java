package com.wattup.app.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Fixed Daegu/DC_COMBO request, for debug builds only. */
public final class MockStationRepository {
    public interface Assets {
        InputStream open(String path) throws IOException;
    }

    public enum Scenario { NORMAL, PREDICTION_MISSING, STALE, EMPTY, NETWORK_FAILURE }

    private final Assets assets;
    private final Scenario scenario;

    public MockStationRepository(Assets assets, Scenario scenario) {
        this.assets = assets;
        this.scenario = scenario;
    }

    private JsonObject load(String name) throws IOException {
        if (scenario == Scenario.NETWORK_FAILURE) {
            throw new IOException("Simulated station API connection failure");
        }
        try (InputStream stream = assets.open("mock/" + name + ".json");
             InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    public JsonObject nearby() throws IOException {
        JsonObject result = load("nearby");
        applyListScenario(result, "stations");
        return result;
    }

    /** Simulates the server's radius query against the two bundled station coordinates. */
    public JsonObject nearby(double latitude, double longitude, int radiusM) throws IOException {
        JsonObject result = nearby();
        JsonArray matches = new JsonArray();
        for (com.google.gson.JsonElement item : result.getAsJsonArray("stations")) {
            JsonObject station = item.getAsJsonObject();
            double stationLat = station.get("latitude").getAsDouble();
            double stationLon = station.get("longitude").getAsDouble();
            double a = Math.pow(Math.sin(Math.toRadians(stationLat - latitude) / 2), 2)
                    + Math.cos(Math.toRadians(latitude)) * Math.cos(Math.toRadians(stationLat))
                    * Math.pow(Math.sin(Math.toRadians(stationLon - longitude) / 2), 2);
            double meters = 12742000 * Math.asin(Math.min(1, Math.sqrt(a)));
            if (meters <= radiusM) {
                station.addProperty("distanceM", Math.round(meters));
                matches.add(station);
            }
        }
        result.add("stations", matches);
        return result;
    }

    public JsonObject detail(String stationId) throws IOException {
        String fixture;
        switch (stationId) {
            case "KECO:0001": fixture = "detail_1"; break;
            case "KECO:0002": fixture = "detail_2"; break;
            default: throw new IllegalArgumentException("Unknown fixture station: " + stationId);
        }
        JsonObject station = load(fixture);
        applyStationScenario(station);
        return station;
    }

    public JsonObject recommendations() throws IOException {
        JsonObject result = load("recommendations");
        applyListScenario(result, "items");
        if (scenario == Scenario.PREDICTION_MISSING || scenario == Scenario.STALE) {
            result.addProperty("method", "CURRENT_AVAILABILITY");
            for (com.google.gson.JsonElement item : result.getAsJsonArray("items")) {
                item.getAsJsonObject().addProperty("reason", "최근 확인된 가용 대수 기준");
            }
        }
        return result;
    }

    private void applyListScenario(JsonObject result, String key) {
        if (scenario == Scenario.EMPTY) {
            result.add(key, new JsonArray());
            return;
        }
        for (com.google.gson.JsonElement item : result.getAsJsonArray(key)) {
            applyStationScenario(item.getAsJsonObject());
        }
    }

    private void applyStationScenario(JsonObject station) {
        if (scenario == Scenario.STALE) {
            station.addProperty("freshness", "STALE");
            station.addProperty("asOf", "2026-11-12T13:45:00+09:00");
        }
        if (station.has("prediction") &&
                (scenario == Scenario.PREDICTION_MISSING || scenario == Scenario.STALE)) {
            JsonObject prediction = station.getAsJsonObject("prediction");
            prediction.add("probability", JsonNull.INSTANCE);
            if (scenario == Scenario.STALE) {
                prediction.add("asOf", station.get("asOf"));
                prediction.addProperty("targetAt", "2026-11-12T14:15:00+09:00");
            }
        }
    }
}

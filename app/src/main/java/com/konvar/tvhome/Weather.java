package com.konvar.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Current weather and a 7-day forecast from Open-Meteo (free, no key), for the place set in config.xml. */
final class Weather {

    static final class Day {
        String date;
        int code;
        int max;
        int min;
    }

    static final class Report {
        int temperature;
        int code;
        final List<Day> days = new ArrayList<>();
    }

    private Weather() {
    }

    static Report fetch(String latitude, String longitude, String timezone) throws Exception {
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + latitude + "&longitude=" + longitude
                + "&current=temperature_2m,weather_code"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                + "&timezone=" + android.net.Uri.encode(timezone) + "&forecast_days=7";
        String body = new String(Net.get(url), StandardCharsets.UTF_8);
        Report r = parse(body);
        lastBody = body;
        return r;
    }

    /** The raw answer of the last fetch, kept so the next start can show it at once. */
    static volatile String lastBody;

    static Report parse(String body) throws Exception {
        JSONObject j = new JSONObject(body);
        Report r = new Report();
        JSONObject current = j.getJSONObject("current");
        r.temperature = (int) Math.round(current.getDouble("temperature_2m"));
        r.code = current.getInt("weather_code");
        JSONObject daily = j.getJSONObject("daily");
        JSONArray dates = daily.getJSONArray("time");
        for (int i = 0; i < dates.length(); i++) {
            Day d = new Day();
            d.date = dates.getString(i);
            d.code = daily.getJSONArray("weather_code").getInt(i);
            d.max = (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(i));
            d.min = (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(i));
            r.days.add(d);
        }
        return r;
    }

    /** WMO weather code as a symbol. */
    static String icon(int code) {
        if (code == 0) return "☀️";
        if (code <= 2) return "🌤️";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "☁️"; // fog: the TV's emoji font has no fog symbol
        if (code >= 51 && code <= 67) return "🌧️";
        if (code >= 71 && code <= 77) return "❄️";
        if (code >= 80 && code <= 82) return "🌦️";
        if (code == 85 || code == 86) return "🌨️";
        if (code >= 95) return "⛈️";
        return "🌡️";
    }
}

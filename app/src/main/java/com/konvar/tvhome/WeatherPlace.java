package com.konvar.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Where the weather is for: found from the TV's internet connection (default), or a town chosen in the
 * settings (Open-Meteo's place search). Falls back to the place in config.xml when neither answers.
 */
final class WeatherPlace {

    static final class Place {
        String name, lat, lon, timezone;
    }

    private WeatherPlace() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("weather", Context.MODE_PRIVATE);
    }

    static boolean automatic(Context c) {
        return prefs(c).getString("lat", null) == null;
    }

    static String chosenName(Context c) {
        return prefs(c).getString("name", null);
    }

    static void setAutomatic(Context c) {
        prefs(c).edit().remove("name").remove("lat").remove("lon").remove("tz").apply();
    }

    static void choose(Context c, Place p) {
        prefs(c).edit().putString("name", p.name).putString("lat", p.lat).putString("lon", p.lon)
                .putString("tz", p.timezone).apply();
    }

    static Place resolve(Context c) {
        SharedPreferences p = prefs(c);
        Place out = new Place();
        if (p.getString("lat", null) != null) {
            out.name = p.getString("name", "");
            out.lat = p.getString("lat", "");
            out.lon = p.getString("lon", "");
            out.timezone = p.getString("tz", "auto");
            return out;
        }
        // The location found from the connection is kept for a day: one request less on every start.
        if (System.currentTimeMillis() - p.getLong("auto_at", 0) < 24 * 3600 * 1000L && p.getString("auto_lat", null) != null) {
            out.name = p.getString("auto_name", "");
            out.lat = p.getString("auto_lat", "");
            out.lon = p.getString("auto_lon", "");
            out.timezone = p.getString("auto_tz", "auto");
            return out;
        }
        try {
            // The location of this connection (city level), no key needed.
            Net.Response r = Net.json("GET", "https://ipwho.is/?fields=success,city,country,latitude,longitude,timezone",
                    new HashMap<>(), null);
            JSONObject o = new JSONObject(r.body);
            if (o.optBoolean("success")) {
                out.name = o.optString("city");
                out.lat = String.valueOf(o.optDouble("latitude"));
                out.lon = String.valueOf(o.optDouble("longitude"));
                JSONObject tz = o.optJSONObject("timezone");
                out.timezone = tz != null ? tz.optString("id", "auto") : "auto";
                p.edit().putString("auto_name", out.name).putString("auto_lat", out.lat).putString("auto_lon", out.lon)
                        .putString("auto_tz", out.timezone).putLong("auto_at", System.currentTimeMillis()).apply();
                return out;
            }
        } catch (Exception e) {
            android.util.Log.w("TvHomeWeather", "location by IP failed: " + e);
        }
        out.name = c.getString(R.string.weather_place);
        out.lat = c.getString(R.string.weather_latitude);
        out.lon = c.getString(R.string.weather_longitude);
        out.timezone = c.getString(R.string.weather_timezone);
        return out;
    }

    /** Towns matching a name, with their region and country so similar names can be told apart. */
    static List<Place> search(String query) throws Exception {
        Net.Response r = Net.json("GET", "https://geocoding-api.open-meteo.com/v1/search?count=10&language=en&name="
                + URLEncoder.encode(query, "UTF-8"), new HashMap<>(), null);
        List<Place> out = new ArrayList<>();
        JSONArray a = new JSONObject(r.body).optJSONArray("results");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Place p = new Place();
            StringBuilder name = new StringBuilder(o.optString("name"));
            String region = o.optString("admin1", "");
            if (!region.isEmpty() && !region.equals(o.optString("name"))) name.append(", ").append(region);
            if (!o.optString("country").isEmpty()) name.append(", ").append(o.optString("country"));
            p.name = name.toString();
            p.lat = String.valueOf(o.optDouble("latitude"));
            p.lon = String.valueOf(o.optDouble("longitude"));
            p.timezone = o.optString("timezone", "auto");
            out.add(p);
        }
        return out;
    }
}

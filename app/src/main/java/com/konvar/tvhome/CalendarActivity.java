package com.konvar.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The next month from Trakt's calendar, one row per day: episodes of shows you watch and movie releases. */
public class CalendarActivity extends Activity implements Rows.Listener {

    private Rows.RowAdapter rows;
    private Ambient ambient;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Display.fastest(this);
        getWindow().setFormat(PixelFormat.RGB_565);
        setContentView(R.layout.activity_calendar);
        ambient = new Ambient(findViewById(R.id.pageRoot), getColor(R.color.bg));
        status = findViewById(R.id.status);
        RecyclerView list = findViewById(R.id.rows);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setItemAnimator(null);
        rows = new Rows.RowAdapter(this);
        list.setAdapter(rows);
        load();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) ImageLoader.trim();
    }

    private void load() {
        Net.API.execute(() -> {
            List<Catalog.Item> items = null;
            try {
                items = TraktActions.calendar(getApplicationContext(), 30);
            } catch (Exception e) {
                android.util.Log.w("TvHomeCalendar", "calendar failed", e);
            }
            List<Catalog.Item> result = items;
            Net.MAIN.post(() -> {
                if (result == null || result.isEmpty()) {
                    status.setText(R.string.calendar_empty);
                    return;
                }
                status.setText(null);
                List<Rows.Row> days = byDay(result);
                rows.setRows(days);
                RecyclerView list = findViewById(R.id.rows);
                list.post(() -> Rows.focusRow(list, firstUpcoming(days), 0));
            });
        });
    }

    /** One row per day: "Yesterday", "Today", "Tomorrow", then "Fri 24 Oct". */
    private List<Rows.Row> byDay(List<Catalog.Item> items) {
        Map<String, List<Catalog.Item>> days = new LinkedHashMap<>();
        Map<String, String> titles = new LinkedHashMap<>();
        SimpleDateFormat key = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        SimpleDateFormat label = new SimpleDateFormat("EEEE d MMMM", Locale.getDefault());
        String today = key.format(new java.util.Date());
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, 1);
        String tomorrow = key.format(cal.getTime());
        cal.add(Calendar.DAY_OF_YEAR, -2);
        String yesterday = key.format(cal.getTime());
        for (Catalog.Item it : items) {
            java.util.Date when = new java.util.Date(it.order);
            String k = key.format(when);
            if (!days.containsKey(k)) {
                days.put(k, new ArrayList<>());
                String t = label.format(when);
                if (k.equals(today)) t = getString(R.string.today) + "  ·  " + t;
                else if (k.equals(tomorrow)) t = getString(R.string.tomorrow) + "  ·  " + t;
                else if (k.equals(yesterday)) t = getString(R.string.yesterday) + "  ·  " + t;
                titles.put(k, t);
            }
            days.get(k).add(it);
        }
        List<Rows.Row> out = new ArrayList<>();
        for (Map.Entry<String, List<Catalog.Item>> e : days.entrySet()) out.add(new Rows.Row(titles.get(e.getKey()), e.getValue()));
        return out;
    }

    private int firstUpcoming(List<Rows.Row> days) {
        long now = System.currentTimeMillis() - 12 * 3600 * 1000L;
        for (int i = 0; i < days.size(); i++) {
            Object o = days.get(i).items.get(0);
            if (o instanceof Catalog.Item && ((Catalog.Item) o).order >= now) return i;
        }
        return 0;
    }

    @Override
    public void onItemFocused(Object item, int row) {
        if (item instanceof Catalog.Item) {
            Catalog.Item it = (Catalog.Item) item;
            ambient.fromCache((it.thumb != null ? it.thumb : it.background) + '@' + Rows.px(this, 208));
        }
        if (row >= 0) Rows.scrollRowToTop(findViewById(R.id.rows), row);
    }

    @Override
    public void onItemClicked(Object item) {
        if (!(item instanceof Catalog.Item)) return;
        try {
            startActivity(Catalog.openIntent(this, (Catalog.Item) item));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }
}

package com.konvar.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Search movies and shows by keyboard or voice; every result opens in the content app. */
public class SearchActivity extends Activity implements Rows.Listener {

    /**
     * Search has its own threads: the shared queues can be busy for minutes with Home's Trakt and catalog loading,
     * and a search must answer at once.
     */
    private static final java.util.concurrent.ExecutorService SEARCH = java.util.concurrent.Executors.newFixedThreadPool(3);

    private static final int REQUEST_VOICE = 1;
    private static final long DEBOUNCE_MS = 450;

    private EditText query;
    private TextView status;
    private RecyclerView results;
    private Rows.RowAdapter adapter;
    private int generation;
    private Ambient ambient;
    private String lastQuery = "";

    private final Runnable runSearch = () -> search(query.getText().toString());

    /** The visible search page, if any: Assistant voice input is typed into it instead of being answered. */
    static SearchActivity visible;

    // Start/stop rather than resume/pause: Assistant's overlay pauses this page while it listens.
    @Override
    protected void onStart() {
        super.onStart();
        visible = this;
    }

    @Override
    protected void onStop() {
        if (visible == this) visible = null;
        super.onStop();
    }

    /** Spoken text from the remote's mic (via Assistant): a play request starts, anything else is searched. */
    void onSpoken(String text) {
        if (VoicePlay.handle(this, text)) {
            finish();
            return;
        }
        query.setText(text);
        query.setSelection(text.length());
        query.removeCallbacks(runSearch);
        search(text);
        results.postDelayed(() -> results.requestFocus(), 400);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Display.fastest(this);
        getWindow().setFormat(PixelFormat.RGB_565);
        setContentView(R.layout.activity_search);
        query = findViewById(R.id.query);
        status = findViewById(R.id.status);
        results = findViewById(R.id.results);
        ambient = new Ambient(findViewById(R.id.pageRoot), getColor(R.color.bg));
        results.setLayoutManager(new LinearLayoutManager(this));
        results.setItemAnimator(null);
        adapter = new Rows.RowAdapter(this);
        results.setAdapter(adapter);

        query.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query.removeCallbacks(runSearch);
                query.postDelayed(runSearch, DEBOUNCE_MS);
            }
        });
        query.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                query.removeCallbacks(runSearch);
                if (VoicePlay.handle(this, query.getText().toString())) {
                    finish();
                    return true;
                }
                search(query.getText().toString());
                hideKeyboard();
                results.post(() -> results.requestFocus());
                return true;
            }
            return false;
        });
        // The remote only sends audio while its mic button is held, and that opens Assistant; the launcher
        // catches what Assistant heard (InputKeyService) and types it here. The on-screen button explains that.
        findViewById(R.id.micButton).setOnClickListener(v ->
                Toast.makeText(this, R.string.voice_hold_hint, Toast.LENGTH_LONG).show());

        String initial = getIntent().getStringExtra("query");
        if (initial != null && VoicePlay.handle(this, initial)) {
            finish();
            return;
        }
        if (initial != null) {
            query.setText(initial);
            query.setSelection(initial.length());
        } else {
            query.requestFocus();
            query.postDelayed(this::showKeyboard, 250);
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) ImageLoader.trim();
    }

    private void showKeyboard() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.showSoftInput(query, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(query.getWindowToken(), 0);
    }

    private void startVoice() {
        Intent voice = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .setPackage("com.google.android.katniss")
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.search_hint));
        try {
            startActivityForResult(voice, REQUEST_VOICE);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.voice_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_VOICE || resultCode != RESULT_OK || data == null) return;
        ArrayList<String> spoken = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (spoken == null || spoken.isEmpty()) return;
        if (VoicePlay.handle(this, spoken.get(0))) {
            finish();
            return;
        }
        query.setText(spoken.get(0));
        query.removeCallbacks(runSearch);
        search(spoken.get(0));
        results.postDelayed(() -> results.requestFocus(), 300);
    }

    private void search(String raw) {
        String q = raw.trim();
        if (q.equals(lastQuery)) return;
        lastQuery = q;
        int gen = ++generation;
        if (q.length() < 2) {
            adapter.setRows(new ArrayList<>());
            status.setText(R.string.search_prompt);
            return;
        }
        status.setText(R.string.searching);
        String addon = getString(R.string.addon_url);
        Rows.Row[] found = new Rows.Row[3];
        int[] pending = {3};
        boolean[] failed = {false};
        String[] types = {"movie", "series"};
        int[] titles = {R.string.movies, R.string.shows};
        // Third: user-made Trakt lists matching the query.
        SEARCH.execute(() -> {
            List<Catalog.Item> lists = null;
            try {
                if (Trakt.configured()) lists = Trakt.searchLists(q, 30);
            } catch (Exception ignored) {
            }
            List<Catalog.Item> result = lists;
            Net.MAIN.post(() -> {
                if (gen != generation) return;
                if (result != null && !result.isEmpty()) found[2] = new Rows.Row(getString(R.string.lists), result);
                pending[0]--;
                List<Rows.Row> rows = new ArrayList<>();
                for (Rows.Row r : found) if (r != null) rows.add(r);
                adapter.setRows(rows);
                if (pending[0] > 0) return;
                status.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                if (rows.isEmpty()) status.setText(getString(R.string.no_results, q));
            });
        });
        for (int i = 0; i < 2; i++) {
            int index = i;
            SEARCH.execute(() -> {
                List<Catalog.Item> items = null;
                try {
                    items = Catalog.search(addon, types[index], q);
                } catch (Exception e) {
                    failed[0] = true;
                }
                List<Catalog.Item> result = items;
                Net.MAIN.post(() -> {
                    if (gen != generation) return;
                    if (result != null && !result.isEmpty()) found[index] = new Rows.Row(getString(titles[index]), result);
                    pending[0]--;
                    List<Rows.Row> rows = new ArrayList<>();
                    for (Rows.Row r : found) if (r != null) rows.add(r);
                    adapter.setRows(rows);
                    if (pending[0] > 0) return;
                    if (!rows.isEmpty()) status.setText(null);
                    else status.setText(failed[0] ? getString(R.string.search_error) : getString(R.string.no_results, q));
                    status.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                });
            });
        }
        status.setVisibility(View.VISIBLE);
    }

    @Override
    public void onItemFocused(Object item, int row) {
        if (item instanceof Catalog.Item && !"list".equals(((Catalog.Item) item).type)) {
            ambient.fromCache(((Catalog.Item) item).poster + '@' + Rows.px(this, 100));
        }
        if (row >= 0) Rows.scrollRowToTop(results, row);
    }

    @Override
    public void onItemClicked(Object item) {
        if (!(item instanceof Catalog.Item)) return;
        if (GridActivity.open(this, (Catalog.Item) item)) return;
        try {
            startActivity(Catalog.openIntent(this, (Catalog.Item) item));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }
}

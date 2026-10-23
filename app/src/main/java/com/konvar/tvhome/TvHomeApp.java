package com.konvar.tvhome;

import android.app.Application;

/** Gives the key store its context before any screen or background job reads a key. */
public class TvHomeApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Keys.app = this;
        Keys.tidy();
        Trakt.watchedIds = RowCache.loadIds(this, "watched");
    }
}

package com.konvar.tvhome;

import android.app.Application;

/** Gives the key store its context before any screen or background job reads a key. */
public class TvHomeApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Every screen's layouts get their font weights from the shared, cached faces (see Fonts). This runs
        // inside each activity's super.onCreate(), before it inflates anything.
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(android.app.Activity a, android.os.Bundle b) {
                Fonts.install(a.getLayoutInflater());
            }

            @Override
            public void onActivityStarted(android.app.Activity a) {
            }

            @Override
            public void onActivityResumed(android.app.Activity a) {
            }

            @Override
            public void onActivityPaused(android.app.Activity a) {
            }

            @Override
            public void onActivityStopped(android.app.Activity a) {
            }

            @Override
            public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) {
            }

            @Override
            public void onActivityDestroyed(android.app.Activity a) {
            }
        });
        Keys.app = this;
        Keys.tidy();
        Trakt.watchedIds = RowCache.loadIds(this, "watched");
        Trakt.loadSavedRatings(this);
    }
}

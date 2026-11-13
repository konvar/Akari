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
                // Every screen's root paints its own opaque background, so the window's background under it is a
                // wasted full-screen fill on every frame (the TV's GPU is the slow part). It stays for the instant
                // before the first frame, then goes, only when the root really is opaque.
                a.getWindow().getDecorView().post(() -> {
                    android.view.ViewGroup content = a.findViewById(android.R.id.content);
                    android.view.View root = content != null && content.getChildCount() > 0 ? content.getChildAt(0) : null;
                    android.graphics.drawable.Drawable bg = root != null ? root.getBackground() : null;
                    if (bg instanceof android.graphics.drawable.ColorDrawable
                            && ((android.graphics.drawable.ColorDrawable) bg).getAlpha() == 255
                            && root.getWidth() >= content.getWidth() && root.getHeight() >= content.getHeight()) {
                        a.getWindow().setBackgroundDrawable(null);
                    }
                });
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
        // Home's saved rows and the watched list are read off the main thread while Home is being created.
        Startup.begin(this);
        Trakt.loadSavedRatings(this);
    }
}

package com.konvar.tvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Every 30 minutes: episodes of shows you watch that aired since the last look. Each gets one notification
 * that opens it in the content app, and the show's cached Trakt progress is dropped so the episode shows up
 * in Continue Watching on the next refresh.
 */
public class EpisodeJob extends JobService {

    private static final String TAG = "TvHomeEpisodes";
    private static final int JOB_ID = 4711;
    static final String CHANNEL = "new_episodes";

    static void schedule(Context c) {
        // Never allowed to take Home down with it.
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js == null || js.getPendingJob(JOB_ID) != null) return;
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, EpisodeJob.class))
                    .setPeriodic(30 * 60 * 1000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build());
        } catch (RuntimeException e) {
            Log.w(TAG, "could not schedule the new-episode check", e);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Net.API.execute(() -> {
            try {
                check(getApplicationContext());
            } catch (Exception e) {
                Log.w(TAG, "check failed", e);
            }
            jobFinished(params, false);
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    static void check(Context c) throws Exception {
        if (!Trakt.configured() || !Trakt.signedIn(c)) return;
        List<Catalog.Item> aired = Trakt.recentlyAired(c, Player.pkg(c));
        SharedPreferences prefs = c.getSharedPreferences("episode_notices", Context.MODE_PRIVATE);
        Set<String> told = new HashSet<>(prefs.getStringSet("told", new HashSet<>()));
        boolean first = !prefs.contains("told");
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                c.getString(R.string.new_episodes), NotificationManager.IMPORTANCE_HIGH));
        for (Catalog.Item it : aired) {
            String key = it.id + ":" + it.season + ":" + it.episode;
            if (!told.add(key)) continue;
            Trakt.forgetProgress(c, it.id);
            // The first run only learns what already aired, so installing does not fire a burst of notices.
            if (first) continue;
            Log.i(TAG, "new episode " + key);
            PendingIntent open = PendingIntent.getActivity(c, key.hashCode(),
                    it.launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.ic_play)
                    .setContentTitle(c.getString(R.string.new_episode_title, it.name))
                    .setContentText(it.subtitle)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build();
            nm.notify(key.hashCode(), n);
        }
        prefs.edit().putStringSet("told", told).apply();
    }
}

package com.konvar.tvhome;

import android.os.SystemClock;

/**
 * How fast focus is moving (idea from Nuvio's FocusStepPace). Holding a D-pad key moves focus every ~50–150 ms; at
 * that pace every card passed would still start its hero, trailer, details and neighbour lookups, whose results can
 * never be shown before focus has moved on, and they queue in front of the card the viewer stops on. While stepping
 * quickly the hero and lookups wait a little longer, so only the card focus settles on starts them.
 */
final class FocusPace {

    /** Two focus moves closer than this are "quick" (key repeat is well under it, deliberate presses are over it). */
    private static final long QUICK_MS = 260;
    /** Extra wait while stepping quickly. */
    private static final long EXTRA_MS = 280;

    private static long last;
    private static int streak;

    private FocusPace() {
    }

    /** Call on every focus move (main thread); returns the extra wait for this move's lookups. */
    static long step() {
        long now = SystemClock.uptimeMillis();
        streak = now - last < QUICK_MS ? Math.min(streak + 1, 10) : 0;
        last = now;
        return extra();
    }

    /** The extra wait now: none for a single press, more once the presses come quickly one after another. */
    static long extra() {
        return streak >= 2 ? EXTRA_MS : 0;
    }

    /** True while focus is moving quickly (the last move was recent and part of a quick streak). */
    static boolean quick() {
        return streak >= 2 && SystemClock.uptimeMillis() - last < QUICK_MS;
    }
}

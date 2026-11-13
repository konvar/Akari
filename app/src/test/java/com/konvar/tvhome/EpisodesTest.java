package com.konvar.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The shared episode rules: what counts as aired, "N left", next up and finished (incl. Nuvio's air-day rule). */
public class EpisodesTest {

    private static final long DAY = 24 * 3600 * 1000L;
    private static final long NOW = 1_800_000_000_000L;

    private static Episodes.Ep ep(int s, int e, long released) {
        return new Episodes.Ep(s, e, released, "");
    }

    /** S1 E1–E3 aired, S1 E4 airs tomorrow, S1 E5 has no date yet. */
    private static List<Episodes.Ep> airing() {
        return Arrays.asList(ep(1, 1, NOW - 20 * DAY), ep(1, 2, NOW - 13 * DAY), ep(1, 3, NOW - 6 * DAY),
                ep(1, 4, NOW + DAY), ep(1, 5, 0));
    }

    private static Set<String> watched(String... keys) {
        return new HashSet<>(Arrays.asList(keys));
    }

    @Test
    public void datedEpisodesAirOnTheirDate() {
        List<Episodes.Ep> l = airing();
        assertTrue(Episodes.aired(l, l.get(2), NOW));
        assertFalse(Episodes.aired(l, l.get(3), NOW));
    }

    @Test
    public void undatedEpisodeAfterTheLastAiredOneIsNotOut() {
        List<Episodes.Ep> l = airing();
        assertFalse(Episodes.aired(l, l.get(4), NOW));
    }

    @Test
    public void undatedEpisodeBeforeAnAiredOneIsOut() {
        List<Episodes.Ep> l = Arrays.asList(ep(1, 1, 0), ep(1, 2, NOW - DAY));
        assertTrue(Episodes.aired(l, l.get(0), NOW));
    }

    @Test
    public void showWithoutAnyDatesCountsInFull() {
        List<Episodes.Ep> l = Arrays.asList(ep(1, 1, 0), ep(1, 2, 0));
        assertEquals(2, Episodes.leftFrom(l, 1, 1, NOW));
    }

    @Test
    public void leftCountsAiredEpisodesFromThisOne() {
        assertEquals(2, Episodes.leftFrom(airing(), 1, 2, NOW));
        assertEquals(0, Episodes.leftFrom(airing(), 1, 4, NOW));
    }

    @Test
    public void nextUpFollowsTheFurthestWatched() {
        List<Episodes.Ep> l = airing();
        Episodes.Ep last = Episodes.furthest(l, watched("1:1", "1:2"));
        Episodes.Ep next = Episodes.after(l, last.season, last.episode);
        assertEquals(3, next.episode);
        assertNull(Episodes.furthest(l, watched()));
    }

    @Test
    public void caughtUpShowIsFinishedUntilTheNextEpisodeAirs() {
        List<Episodes.Ep> l = airing();
        Set<String> w = watched("1:1", "1:2", "1:3");
        assertTrue(Episodes.finished(l, w, NOW));
        // Air day of S1 E4: the show is no longer finished, so it returns to Continue Watching instead of dropping.
        assertFalse(Episodes.finished(l, w, NOW + 2 * DAY));
    }

    @Test
    public void nothingAiredIsNeverFinished() {
        List<Episodes.Ep> l = Arrays.asList(ep(1, 1, NOW + DAY));
        assertFalse(Episodes.finished(l, watched(), NOW));
    }
}

package com.konvar.tvhome;

import android.app.Activity;
import android.view.Window;
import android.view.WindowManager;

/**
 * Every screen runs at the panel's fastest refresh rate at its current resolution (60 Hz here, 120/144 Hz on
 * TVs that offer it), without asking for a resolution change, and keeps the GPU clocks steady where supported.
 */
final class Display {

    private Display() {
    }

    static void fastest(Activity a) {
        Window w = a.getWindow();
        android.view.Display d = a.getWindowManager().getDefaultDisplay();
        android.view.Display.Mode current = d.getMode();
        android.view.Display.Mode best = current;
        for (android.view.Display.Mode m : d.getSupportedModes()) {
            if (m.getPhysicalWidth() == current.getPhysicalWidth() && m.getPhysicalHeight() == current.getPhysicalHeight()
                    && m.getRefreshRate() > best.getRefreshRate() + 0.5f) best = m;
        }
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.preferredDisplayModeId = best.getModeId();
        w.setAttributes(lp);
        android.os.PowerManager pm = (android.os.PowerManager) a.getSystemService(android.content.Context.POWER_SERVICE);
        if (pm != null && pm.isSustainedPerformanceModeSupported()) w.setSustainedPerformanceMode(true);
        android.util.Log.i("TvHomeDisplay", a.getClass().getSimpleName() + " at " + best.getRefreshRate() + " Hz ("
                + best.getPhysicalWidth() + "x" + best.getPhysicalHeight() + ")");
    }
}

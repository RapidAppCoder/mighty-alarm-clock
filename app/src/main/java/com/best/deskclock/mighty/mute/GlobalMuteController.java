// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.mute;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;

/**
 * Stores and evaluates a "global mute until time X" setting that temporarily silences (or forces
 * vibrate-only) every alarm that fires while it is active, regardless of the individual alarm's
 * own ringtone/vibrate settings.
 */
public final class GlobalMuteController {

    /**
     * The behavior applied to alarms while global mute is active.
     */
    public enum Mode {
        /** No ringtone and no vibration at all. */
        SILENT,
        /** No ringtone, but force vibration on even if the alarm itself has vibration disabled. */
        VIBRATE
    }

    private static final String PREFS_NAME = "global_mute_prefs";
    private static final String KEY_END_TIME = "mute_end_time_millis";
    private static final String KEY_MODE = "mute_mode";

    private GlobalMuteController() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Enables global mute until the given time, using the given mode.
     */
    public static void enableUntil(Context context, long endTimeMillis, Mode mode) {
        prefs(context).edit()
            .putLong(KEY_END_TIME, endTimeMillis)
            .putString(KEY_MODE, mode.name())
            .apply();
    }

    /**
     * Disables global mute immediately.
     */
    public static void disable(Context context) {
        prefs(context).edit().remove(KEY_END_TIME).remove(KEY_MODE).apply();
    }

    /**
     * @return {@code true} if global mute is currently active (end time in the future).
     */
    public static boolean isActive(Context context) {
        final long end = prefs(context).getLong(KEY_END_TIME, 0L);
        if (end <= 0L) {
            return false;
        }
        if (System.currentTimeMillis() >= end) {
            // Mute period has elapsed; clean it up lazily.
            disable(context);
            return false;
        }
        return true;
    }

    /**
     * @return the timestamp (ms since epoch) at which global mute will end, or 0 if not set.
     */
    public static long getEndTime(Context context) {
        return prefs(context).getLong(KEY_END_TIME, 0L);
    }

    /**
     * @return the currently configured mute {@link Mode}, defaulting to {@link Mode#SILENT}.
     */
    public static Mode getMode(Context context) {
        final String value = prefs(context).getString(KEY_MODE, Mode.SILENT.name());
        try {
            return Mode.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            return Mode.SILENT;
        }
    }

    // Convenience presets used by the UI.

    public static void muteForOneHour(Context context, Mode mode) {
        enableUntil(context, System.currentTimeMillis() + 60L * 60L * 1000L, mode);
    }

    public static void muteForTwoHours(Context context, Mode mode) {
        enableUntil(context, System.currentTimeMillis() + 2L * 60L * 60L * 1000L, mode);
    }

    public static void muteUntilTomorrowMorning(Context context, Mode mode) {
        final Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_YEAR, 1);
        calendar.set(Calendar.HOUR_OF_DAY, 8);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        enableUntil(context, calendar.getTimeInMillis(), mode);
    }
}

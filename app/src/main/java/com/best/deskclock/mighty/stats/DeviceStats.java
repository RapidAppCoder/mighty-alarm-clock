// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.stats;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Tracks simple lifetime device statistics (total number of times any alarm has rung, total
 * number of times any alarm has been snoozed) since a given "stats since" timestamp.
 */
public final class DeviceStats {

    private static final String PREFS_NAME = "device_stats";
    private static final String KEY_TOTAL_RING_COUNT = "total_ring_count";
    private static final String KEY_TOTAL_SNOOZE_COUNT = "total_snooze_count";
    private static final String KEY_STATS_SINCE = "stats_since";

    private DeviceStats() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static void ensureStatsSince(SharedPreferences prefs) {
        if (!prefs.contains(KEY_STATS_SINCE)) {
            prefs.edit().putLong(KEY_STATS_SINCE, System.currentTimeMillis()).apply();
        }
    }

    public static void onAlarmFired(Context context) {
        final SharedPreferences prefs = prefs(context);
        ensureStatsSince(prefs);
        prefs.edit().putInt(KEY_TOTAL_RING_COUNT, prefs.getInt(KEY_TOTAL_RING_COUNT, 0) + 1).apply();
    }

    public static void onAlarmSnoozed(Context context) {
        final SharedPreferences prefs = prefs(context);
        ensureStatsSince(prefs);
        prefs.edit().putInt(KEY_TOTAL_SNOOZE_COUNT, prefs.getInt(KEY_TOTAL_SNOOZE_COUNT, 0) + 1).apply();
    }

    public static int getTotalRingCount(Context context) {
        return prefs(context).getInt(KEY_TOTAL_RING_COUNT, 0);
    }

    public static int getTotalSnoozeCount(Context context) {
        return prefs(context).getInt(KEY_TOTAL_SNOOZE_COUNT, 0);
    }

    public static long getStatsSince(Context context) {
        final SharedPreferences prefs = prefs(context);
        ensureStatsSince(prefs);
        return prefs.getLong(KEY_STATS_SINCE, System.currentTimeMillis());
    }

    /**
     * Resets every counter and restarts the "stats since" timestamp at the current time.
     */
    public static void reset(Context context) {
        prefs(context).edit().clear().putLong(KEY_STATS_SINCE, System.currentTimeMillis()).apply();
    }
}

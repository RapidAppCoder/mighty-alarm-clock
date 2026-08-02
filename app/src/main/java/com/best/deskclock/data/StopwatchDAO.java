/*
 * Copyright (C) 2015 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package com.best.deskclock.data;

import static com.best.deskclock.data.Stopwatch.State.RESET;

import android.content.SharedPreferences;
import android.text.TextUtils;

import com.best.deskclock.data.Stopwatch.State;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * This class encapsulates the transfer of data between {@link Stopwatch} and {@link Lap} domain
 * objects and their permanent storage in {@link SharedPreferences}.
 */
final class StopwatchDAO {

    /**
     * Key to a preference that stores the set of stopwatch ids.
     */
    private static final String STOPWATCH_IDS = "stopwatches_list";

    /**
     * Key to a preference that stores the id to assign to the next stopwatch.
     */
    private static final String NEXT_STOPWATCH_ID = "next_stopwatch_id";

    /**
     * Key to a preference that stores the id of the selected stopwatch.
     */
    private static final String SELECTED_STOPWATCH_ID = "selected_stopwatch_id";

    /**
     * Prefix for a key to a preference that stores the state of a stopwatch.
     */
    private static final String STATE = "sw_state_";

    /**
     * Prefix for a key to a preference that stores the last start time of a stopwatch.
     */
    private static final String LAST_START_TIME = "sw_start_time_";

    /**
     * Prefix for a key to a preference that stores the epoch time when a stopwatch last started.
     */
    private static final String LAST_WALL_CLOCK_TIME = "sw_wall_clock_time_";

    /**
     * Prefix for a key to a preference that stores the accumulated elapsed time of a stopwatch.
     */
    private static final String ACCUMULATED_TIME = "sw_accum_time_";

    /**
     * Prefix for a key to a preference that stores the label of a stopwatch.
     */
    private static final String LABEL = "sw_label_";

    /**
     * Prefix for a key to a preference that stores the number of recorded laps for a stopwatch.
     */
    private static final String LAP_COUNT = "sw_lap_num_";

    /**
     * Prefix for a key to a preference that stores accumulated time at the end of a lap.
     */
    private static final String LAP_ACCUMULATED_TIME = "sw_lap_time_";

    // Legacy single-stopwatch keys (migrated on first multi-stopwatch read).
    private static final String LEGACY_STATE = "sw_state";
    private static final String LEGACY_LAST_START_TIME = "sw_start_time";
    private static final String LEGACY_LAST_WALL_CLOCK_TIME = "sw_wall_clock_time";
    private static final String LEGACY_ACCUMULATED_TIME = "sw_accum_time";
    private static final String LEGACY_LAP_COUNT = "sw_lap_num";
    private static final String LEGACY_LAP_ACCUMULATED_TIME = "sw_lap_time_";

    private StopwatchDAO() {
    }

    /**
     * @return the stopwatches from permanent storage; migrates legacy single-stopwatch data
     */
    static List<Stopwatch> getStopwatches(SharedPreferences prefs) {
        migrateLegacyStopwatchIfNeeded(prefs);

        final Set<String> stopwatchIds = prefs.getStringSet(STOPWATCH_IDS, Collections.emptySet());
        if (stopwatchIds.isEmpty()) {
            // Ensure at least one stopwatch always exists.
            final Stopwatch created = addStopwatch(prefs, new Stopwatch(
                Stopwatch.UNUSED_ID, RESET, Stopwatch.UNUSED, Stopwatch.UNUSED, 0, null));
            return Collections.singletonList(created);
        }

        final List<Stopwatch> stopwatches = new ArrayList<>(stopwatchIds.size());
        for (String stopwatchId : stopwatchIds) {
            final int id = Integer.parseInt(stopwatchId);
            final int stateIndex = prefs.getInt(STATE + id, RESET.ordinal());
            final State state = State.values()[stateIndex];
            final long lastStartTime = prefs.getLong(LAST_START_TIME + id, Stopwatch.UNUSED);
            final long lastWallClockTime = prefs.getLong(LAST_WALL_CLOCK_TIME + id, Stopwatch.UNUSED);
            final long accumulatedTime = prefs.getLong(ACCUMULATED_TIME + id, 0);
            final String label = prefs.getString(LABEL + id, null);

            Stopwatch stopwatch = new Stopwatch(id, state, lastStartTime, lastWallClockTime,
                accumulatedTime, label);

            // If the stopwatch reports an illegal (negative) amount of time, remove the bad data.
            if (stopwatch.getTotalTime() < 0) {
                stopwatch = stopwatch.reset();
                updateStopwatch(prefs, stopwatch);
            }
            stopwatches.add(stopwatch);
        }

        // Stable order by id so the UI does not reshuffle after restarts.
        Collections.sort(stopwatches, (a, b) -> Integer.compare(a.getId(), b.getId()));
        return stopwatches;
    }

    /**
     * @param stopwatch the stopwatch to be added (id is assigned)
     * @return the persisted stopwatch with its generated id
     */
    static Stopwatch addStopwatch(SharedPreferences prefs, Stopwatch stopwatch) {
        final SharedPreferences.Editor editor = prefs.edit();

        final int id = prefs.getInt(NEXT_STOPWATCH_ID, 0);
        editor.putInt(NEXT_STOPWATCH_ID, id + 1);

        final Set<String> stopwatchIds = new HashSet<>(getStopwatchIds(prefs));
        stopwatchIds.add(String.valueOf(id));
        editor.putStringSet(STOPWATCH_IDS, stopwatchIds);

        writeStopwatchFields(editor, id, stopwatch);
        editor.apply();

        return new Stopwatch(id, stopwatch.getState(), stopwatch.getLastStartTime(),
            stopwatch.getLastWallClockTime(), stopwatch.getAccumulatedTime(), stopwatch.getLabel());
    }

    /**
     * @param stopwatch the stopwatch to be updated
     */
    static void updateStopwatch(SharedPreferences prefs, Stopwatch stopwatch) {
        final SharedPreferences.Editor editor = prefs.edit();
        writeStopwatchFields(editor, stopwatch.getId(), stopwatch);
        editor.apply();
    }

    /**
     * @param stopwatch the stopwatch to be removed
     */
    static void removeStopwatch(SharedPreferences prefs, Stopwatch stopwatch) {
        final SharedPreferences.Editor editor = prefs.edit();
        final int id = stopwatch.getId();

        final Set<String> stopwatchIds = new HashSet<>(getStopwatchIds(prefs));
        stopwatchIds.remove(String.valueOf(id));
        if (stopwatchIds.isEmpty()) {
            editor.remove(STOPWATCH_IDS);
            editor.remove(NEXT_STOPWATCH_ID);
            editor.remove(SELECTED_STOPWATCH_ID);
        } else {
            editor.putStringSet(STOPWATCH_IDS, stopwatchIds);
            if (getSelectedStopwatchId(prefs) == id) {
                editor.putInt(SELECTED_STOPWATCH_ID, Integer.parseInt(stopwatchIds.iterator().next()));
            }
        }

        editor.remove(STATE + id);
        editor.remove(LAST_START_TIME + id);
        editor.remove(LAST_WALL_CLOCK_TIME + id);
        editor.remove(ACCUMULATED_TIME + id);
        editor.remove(LABEL + id);
        clearLaps(editor, prefs, id);
        editor.apply();
    }

    static int getSelectedStopwatchId(SharedPreferences prefs) {
        return prefs.getInt(SELECTED_STOPWATCH_ID, 0);
    }

    static void setSelectedStopwatchId(SharedPreferences prefs, int id) {
        prefs.edit().putInt(SELECTED_STOPWATCH_ID, id).apply();
    }

    /**
     * @return a list of recorded laps for the given stopwatch
     */
    static List<Lap> getLaps(SharedPreferences prefs, int stopwatchId) {
        final int lapCount = prefs.getInt(LAP_COUNT + stopwatchId, 0);
        final List<Lap> laps = new ArrayList<>(lapCount);

        long prevAccumulatedTime = 0;

        for (int lapNumber = 1; lapNumber <= lapCount; lapNumber++) {
            final String lapAccumulatedTimeKey = lapKey(stopwatchId, lapNumber);
            final long accumulatedTime = prefs.getLong(lapAccumulatedTimeKey, 0);
            final long lapTime = accumulatedTime - prevAccumulatedTime;
            laps.add(new Lap(lapNumber, lapTime, accumulatedTime));
            prevAccumulatedTime = accumulatedTime;
        }

        Collections.reverse(laps);
        return laps;
    }

    /**
     * @param newLapCount     the number of laps including the new lap
     * @param accumulatedTime the amount of time accumulate by the stopwatch at the end of the lap
     */
    static void addLap(SharedPreferences prefs, int stopwatchId, int newLapCount, long accumulatedTime) {
        prefs.edit()
            .putInt(LAP_COUNT + stopwatchId, newLapCount)
            .putLong(lapKey(stopwatchId, newLapCount), accumulatedTime)
            .apply();
    }

    /**
     * Remove the recorded laps for the given stopwatch.
     */
    static void clearLaps(SharedPreferences prefs, int stopwatchId) {
        final SharedPreferences.Editor editor = prefs.edit();
        clearLaps(editor, prefs, stopwatchId);
        editor.apply();
    }

    private static void clearLaps(SharedPreferences.Editor editor, SharedPreferences prefs, int stopwatchId) {
        final int lapCount = prefs.getInt(LAP_COUNT + stopwatchId, 0);
        for (int lapNumber = 1; lapNumber <= lapCount; lapNumber++) {
            editor.remove(lapKey(stopwatchId, lapNumber));
        }
        editor.remove(LAP_COUNT + stopwatchId);
    }

    private static void writeStopwatchFields(SharedPreferences.Editor editor, int id, Stopwatch stopwatch) {
        if (stopwatch.isReset()) {
            editor.remove(STATE + id)
                .remove(LAST_START_TIME + id)
                .remove(LAST_WALL_CLOCK_TIME + id)
                .remove(ACCUMULATED_TIME + id);
        } else {
            editor.putInt(STATE + id, stopwatch.getState().ordinal())
                .putLong(LAST_START_TIME + id, stopwatch.getLastStartTime())
                .putLong(LAST_WALL_CLOCK_TIME + id, stopwatch.getLastWallClockTime())
                .putLong(ACCUMULATED_TIME + id, stopwatch.getAccumulatedTime());
        }

        if (TextUtils.isEmpty(stopwatch.getLabel())) {
            editor.remove(LABEL + id);
        } else {
            editor.putString(LABEL + id, stopwatch.getLabel());
        }
    }

    private static String lapKey(int stopwatchId, int lapNumber) {
        return LAP_ACCUMULATED_TIME + stopwatchId + "_" + lapNumber;
    }

    private static Set<String> getStopwatchIds(SharedPreferences prefs) {
        return prefs.getStringSet(STOPWATCH_IDS, Collections.emptySet());
    }

    /**
     * Migrates the pre-multi-stopwatch SharedPreferences schema into the multi-instance format.
     */
    private static void migrateLegacyStopwatchIfNeeded(SharedPreferences prefs) {
        if (prefs.contains(STOPWATCH_IDS) || !prefs.contains(LEGACY_STATE)) {
            return;
        }

        final SharedPreferences.Editor editor = prefs.edit();
        final int id = 0;

        final int stateIndex = prefs.getInt(LEGACY_STATE, RESET.ordinal());
        final long lastStartTime = prefs.getLong(LEGACY_LAST_START_TIME, Stopwatch.UNUSED);
        final long lastWallClockTime = prefs.getLong(LEGACY_LAST_WALL_CLOCK_TIME, Stopwatch.UNUSED);
        final long accumulatedTime = prefs.getLong(LEGACY_ACCUMULATED_TIME, 0);

        final Set<String> stopwatchIds = new HashSet<>();
        stopwatchIds.add(String.valueOf(id));
        editor.putStringSet(STOPWATCH_IDS, stopwatchIds);
        editor.putInt(NEXT_STOPWATCH_ID, id + 1);
        editor.putInt(SELECTED_STOPWATCH_ID, id);

        if (stateIndex != RESET.ordinal()) {
            editor.putInt(STATE + id, stateIndex)
                .putLong(LAST_START_TIME + id, lastStartTime)
                .putLong(LAST_WALL_CLOCK_TIME + id, lastWallClockTime)
                .putLong(ACCUMULATED_TIME + id, accumulatedTime);
        }

        final int lapCount = prefs.getInt(LEGACY_LAP_COUNT, 0);
        if (lapCount > 0) {
            editor.putInt(LAP_COUNT + id, lapCount);
            for (int lapNumber = 1; lapNumber <= lapCount; lapNumber++) {
                final long lapTime = prefs.getLong(LEGACY_LAP_ACCUMULATED_TIME + lapNumber, 0);
                editor.putLong(lapKey(id, lapNumber), lapTime);
                editor.remove(LEGACY_LAP_ACCUMULATED_TIME + lapNumber);
            }
        }

        editor.remove(LEGACY_STATE)
            .remove(LEGACY_LAST_START_TIME)
            .remove(LEGACY_LAST_WALL_CLOCK_TIME)
            .remove(LEGACY_ACCUMULATED_TIME)
            .remove(LEGACY_LAP_COUNT);

        editor.apply();
    }
}

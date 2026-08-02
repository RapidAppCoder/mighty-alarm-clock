/*
 * Copyright (C) 2016 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package com.best.deskclock.data;

/**
 * The interface through which interested parties are notified of changes to stopwatches or laps.
 */
public interface StopwatchListener {

    /**
     * @param stopwatch the newly added stopwatch
     */
    default void stopwatchAdded(Stopwatch stopwatch) {
    }

    /**
     * @param before the stopwatch state before the update
     * @param after  the stopwatch state after the update
     */
    default void stopwatchUpdated(Stopwatch before, Stopwatch after) {
        stopwatchUpdated(after);
    }

    /**
     * @param after the stopwatch state after the update
     * @deprecated Prefer {@link #stopwatchUpdated(Stopwatch, Stopwatch)}
     */
    @Deprecated
    default void stopwatchUpdated(Stopwatch after) {
    }

    /**
     * @param stopwatch the removed stopwatch
     */
    default void stopwatchRemoved(Stopwatch stopwatch) {
    }
}

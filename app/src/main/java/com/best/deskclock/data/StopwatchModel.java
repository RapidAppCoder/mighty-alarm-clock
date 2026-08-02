/*
 * Copyright (C) 2015 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package com.best.deskclock.data;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.service.quicksettings.TileService;
import android.text.TextUtils;
import android.text.format.DateUtils;

import androidx.annotation.VisibleForTesting;
import androidx.core.content.ContextCompat;

import com.best.deskclock.provider.EventLogStore;
import com.best.deskclock.tiles.StopwatchTileService;
import com.best.deskclock.utils.SdkUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * All {@link Stopwatch} data is accessed via this model.
 */
final class StopwatchModel {

    private final Context mContext;

    private final SharedPreferences mPrefs;

    /**
     * The model from which notification data are fetched.
     */
    private final NotificationModel mNotificationModel;

    /**
     * Used to create and destroy system notifications related to the stopwatch.
     */
    private final NotificationManager mNotificationManager;

    /**
     * Update stopwatch notification when locale changes.
     */
    @SuppressWarnings("FieldCanBeLocal")
    private final BroadcastReceiver mLocaleChangedReceiver = new LocaleChangedReceiver();

    /**
     * The listeners to notify when stopwatches or their laps change.
     */
    private final List<StopwatchListener> mStopwatchListeners = new ArrayList<>();

    /**
     * Delegate that builds platform-specific stopwatch notifications.
     */
    private final StopwatchNotificationBuilder mNotificationBuilder = new StopwatchNotificationBuilder();

    /**
     * The current stopwatches, loaded lazily.
     */
    private List<Stopwatch> mStopwatches;

    /**
     * Laps recorded for each stopwatch, keyed by stopwatch id.
     */
    private Map<Integer, List<Lap>> mLapsByStopwatchId;

    /**
     * The id of the stopwatch currently selected in the UI.
     */
    private Integer mSelectedStopwatchId;

    StopwatchModel(Context context, SharedPreferences prefs, NotificationModel notificationModel) {
        mContext = context.getApplicationContext();
        mPrefs = prefs;
        mNotificationModel = notificationModel;
        mNotificationManager = mContext.getSystemService(NotificationManager.class);

        // Update stopwatch notification when locale changes.
        final IntentFilter localeBroadcastFilter = new IntentFilter();
        localeBroadcastFilter.addAction(Intent.ACTION_LOCALE_CHANGED);

        if (SdkUtils.isAtLeastAndroid13()) {
            localeBroadcastFilter.addAction(Intent.ACTION_APPLICATION_LOCALE_CHANGED);
        }

        if (SdkUtils.isAtLeastAndroid13()) {
            mContext.registerReceiver(mLocaleChangedReceiver, localeBroadcastFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            mContext.registerReceiver(mLocaleChangedReceiver, localeBroadcastFilter);
        }
    }

    /**
     * @param stopwatchListener to be notified when stopwatches change or laps are added
     */
    void addStopwatchListener(StopwatchListener stopwatchListener) {
        mStopwatchListeners.add(stopwatchListener);
    }

    /**
     * @param stopwatchListener to no longer be notified when stopwatches change or laps are added
     */
    void removeStopwatchListener(StopwatchListener stopwatchListener) {
        mStopwatchListeners.remove(stopwatchListener);
    }

    /**
     * @return an unmodifiable list of all stopwatches
     */
    List<Stopwatch> getStopwatches() {
        return Collections.unmodifiableList(getMutableStopwatches());
    }

    /**
     * @return the currently selected stopwatch
     */
    Stopwatch getStopwatch() {
        return getStopwatch(getSelectedStopwatchId());
    }

    /**
     * @return the stopwatch with the given id, or the selected stopwatch if not found
     */
    Stopwatch getStopwatch(int stopwatchId) {
        for (Stopwatch stopwatch : getMutableStopwatches()) {
            if (stopwatch.getId() == stopwatchId) {
                return stopwatch;
            }
        }
        return getMutableStopwatches().get(0);
    }

    int getSelectedStopwatchId() {
        if (mSelectedStopwatchId == null) {
            mSelectedStopwatchId = StopwatchDAO.getSelectedStopwatchId(mPrefs);
            // Ensure the selected id refers to an existing stopwatch.
            boolean found = false;
            for (Stopwatch stopwatch : getMutableStopwatches()) {
                if (stopwatch.getId() == mSelectedStopwatchId) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                mSelectedStopwatchId = getMutableStopwatches().get(0).getId();
                StopwatchDAO.setSelectedStopwatchId(mPrefs, mSelectedStopwatchId);
            }
        }
        return mSelectedStopwatchId;
    }

    void setSelectedStopwatchId(int stopwatchId) {
        if (mSelectedStopwatchId != null && mSelectedStopwatchId == stopwatchId) {
            return;
        }
        mSelectedStopwatchId = stopwatchId;
        StopwatchDAO.setSelectedStopwatchId(mPrefs, stopwatchId);

        if (!mNotificationModel.isApplicationInForeground()) {
            updateNotification();
        }
    }

    /**
     * Creates and persists a new stopwatch, selects it, and notifies listeners.
     */
    Stopwatch addStopwatch(String label) {
        final Stopwatch stopwatch = StopwatchDAO.addStopwatch(mPrefs,
            new Stopwatch(Stopwatch.UNUSED_ID, Stopwatch.State.RESET, Stopwatch.UNUSED, Stopwatch.UNUSED, 0, label));
        getMutableStopwatches().add(stopwatch);
        getMutableLapsMap().put(stopwatch.getId(), new ArrayList<>());
        setSelectedStopwatchId(stopwatch.getId());

        for (StopwatchListener listener : mStopwatchListeners) {
            listener.stopwatchAdded(stopwatch);
        }
        return stopwatch;
    }

    /**
     * Removes the stopwatch. If it is the last one, it is reset instead of removed.
     */
    void removeStopwatch(Stopwatch stopwatch) {
        if (getMutableStopwatches().size() <= 1) {
            setStopwatch(stopwatch.reset());
            return;
        }

        // Log any unfinished running segment before removing.
        if (stopwatch.isRunning()) {
            final long segmentDuration = Math.max(0, stopwatch.getTotalTime() - stopwatch.getAccumulatedTime());
            if (segmentDuration > 0) {
                logStopwatchStopped(stopwatch, segmentDuration);
            }
        }

        StopwatchDAO.removeStopwatch(mPrefs, stopwatch);
        getMutableStopwatches().remove(stopwatch);
        getMutableLapsMap().remove(stopwatch.getId());

        if (getSelectedStopwatchId() == stopwatch.getId()) {
            mSelectedStopwatchId = getMutableStopwatches().get(0).getId();
            StopwatchDAO.setSelectedStopwatchId(mPrefs, mSelectedStopwatchId);
        }

        if (!mNotificationModel.isApplicationInForeground()) {
            updateNotification();
        }

        if (SdkUtils.isAtLeastAndroid7()) {
            TileService.requestListeningState(mContext, new ComponentName(mContext, StopwatchTileService.class));
        }

        for (StopwatchListener listener : mStopwatchListeners) {
            listener.stopwatchRemoved(stopwatch);
        }
    }

    /**
     * @param stopwatch the new state of a stopwatch
     */
    void setStopwatch(Stopwatch stopwatch) {
        final Stopwatch before = getStopwatch(stopwatch.getId());
        if (before == stopwatch) {
            return;
        }

        // Log a completed running segment when pausing.
        if (before.isRunning() && stopwatch.isPaused()) {
            final long segmentDuration = Math.max(0, stopwatch.getTotalTime() - before.getAccumulatedTime());
            if (segmentDuration > 0) {
                logStopwatchStopped(stopwatch, segmentDuration);
            }
        }

        // Log remaining time when resetting a non-reset stopwatch that was not just logged via pause.
        // If resetting while running, log the current unfinished segment.
        if (!before.isReset() && stopwatch.isReset()) {
            if (before.isRunning()) {
                final long segmentDuration = Math.max(0, before.getTotalTime() - before.getAccumulatedTime());
                if (segmentDuration > 0) {
                    logStopwatchStopped(before, segmentDuration);
                }
            }
            // Paused time was already logged on each pause; nothing more to log on reset.
        }

        StopwatchDAO.updateStopwatch(mPrefs, stopwatch);
        final List<Stopwatch> stopwatches = getMutableStopwatches();
        final int index = indexOf(stopwatches, stopwatch.getId());
        if (index >= 0) {
            stopwatches.set(index, stopwatch);
        }

        // Refresh the stopwatch notification to reflect the latest stopwatch state.
        if (!mNotificationModel.isApplicationInForeground()) {
            updateNotification();
        }

        if (SdkUtils.isAtLeastAndroid7()) {
            TileService.requestListeningState(mContext, new ComponentName(mContext, StopwatchTileService.class));
        }

        // Resetting the stopwatch implicitly clears the recorded laps.
        if (stopwatch.isReset()) {
            clearLaps(stopwatch.getId());
        }

        // Notify listeners of the stopwatch change.
        for (StopwatchListener stopwatchListener : mStopwatchListeners) {
            stopwatchListener.stopwatchUpdated(before, stopwatch);
        }
    }

    void updateStopwatchesAfterReboot() {
        final List<Stopwatch> stopwatches = new ArrayList<>(getMutableStopwatches());
        for (Stopwatch stopwatch : stopwatches) {
            final Stopwatch updated = stopwatch.updateAfterReboot();
            if (updated != stopwatch) {
                setStopwatch(updated);
            }
        }
    }

    void updateStopwatchesAfterTimeSet() {
        final List<Stopwatch> stopwatches = new ArrayList<>(getMutableStopwatches());
        for (Stopwatch stopwatch : stopwatches) {
            final Stopwatch updated = stopwatch.updateAfterTimeSet();
            if (updated != stopwatch) {
                setStopwatch(updated);
            }
        }
    }

    /**
     * @return the laps recorded for the selected stopwatch
     */
    List<Lap> getLaps() {
        return getLaps(getSelectedStopwatchId());
    }

    /**
     * @return the laps recorded for the given stopwatch
     */
    List<Lap> getLaps(int stopwatchId) {
        return Collections.unmodifiableList(getMutableLaps(stopwatchId));
    }

    /**
     * @return a newly recorded lap completed now on the selected stopwatch; {@code null} if no more laps can be added
     */
    Lap addLap() {
        return addLap(getSelectedStopwatchId());
    }

    Lap addLap(int stopwatchId) {
        final Stopwatch stopwatch = getStopwatch(stopwatchId);
        if (!stopwatch.isRunning() || !canAddMoreLaps(stopwatchId)) {
            return null;
        }

        final long totalTime = stopwatch.getTotalTime();
        final List<Lap> laps = getMutableLaps(stopwatchId);

        final int lapNumber = laps.size() + 1;
        StopwatchDAO.addLap(mPrefs, stopwatchId, lapNumber, totalTime);

        final long prevAccumulatedTime = laps.isEmpty() ? 0 : laps.get(0).getAccumulatedTime();
        final long lapTime = totalTime - prevAccumulatedTime;

        final Lap lap = new Lap(lapNumber, lapTime, totalTime);
        laps.add(0, lap);

        // Refresh the stopwatch notification to reflect the latest stopwatch state.
        if (!mNotificationModel.isApplicationInForeground()) {
            updateNotification();
        }

        return lap;
    }

    /**
     * Clears the laps recorded for the given stopwatch.
     */
    @VisibleForTesting
    void clearLaps(int stopwatchId) {
        StopwatchDAO.clearLaps(mPrefs, stopwatchId);
        getMutableLaps(stopwatchId).clear();
    }

    /**
     * @return {@code true} iff more laps can be recorded on the selected stopwatch
     */
    boolean canAddMoreLaps() {
        return canAddMoreLaps(getSelectedStopwatchId());
    }

    boolean canAddMoreLaps(int stopwatchId) {
        return getLaps(stopwatchId).size() < 98;
    }

    /**
     * @return the longest lap time of all recorded laps and the current lap for the selected stopwatch
     */
    long getLongestLapTime() {
        return getLongestLapTime(getSelectedStopwatchId());
    }

    long getLongestLapTime(int stopwatchId) {
        long maxLapTime = 0;

        final List<Lap> laps = getLaps(stopwatchId);
        if (!laps.isEmpty()) {
            for (Lap lap : laps) {
                maxLapTime = Math.max(maxLapTime, lap.getLapTime());
            }

            final Stopwatch stopwatch = getStopwatch(stopwatchId);
            final long currentLapTime = stopwatch.getTotalTime() - laps.get(0).getAccumulatedTime();
            maxLapTime = Math.max(maxLapTime, currentLapTime);
        }

        return maxLapTime;
    }

    /**
     * In practice, {@code time} can be any value due to device reboots. When the real-time clock is
     * reset, there is no more guarantee that this time falls after the last recorded lap.
     *
     * @param time a point in time expected, but not required, to be after the end of the prior lap
     * @return the elapsed time between the given {@code time} and the end of the prior lap;
     * negative elapsed times are normalized to {@code 0}
     */
    long getCurrentLapTime(long time) {
        return getCurrentLapTime(getSelectedStopwatchId(), time);
    }

    long getCurrentLapTime(int stopwatchId, long time) {
        final Lap previousLap = getLaps(stopwatchId).get(0);
        final long currentLapTime = time - previousLap.getAccumulatedTime();
        return Math.max(0, currentLapTime);
    }

    /**
     * @return {@code true} if any stopwatch is currently running
     */
    boolean isAnyStopwatchRunning() {
        for (Stopwatch stopwatch : getMutableStopwatches()) {
            if (stopwatch.isRunning()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Updates the notification to reflect the latest state of stopwatches and recorded laps.
     * The notification follows the selected stopwatch when it is active; otherwise the first
     * running or paused stopwatch.
     */
    void updateNotification() {
        final Stopwatch stopwatch = getStopwatchForNotification();

        // Notification should be hidden if no stopwatch has time or the app is open.
        if (stopwatch == null || stopwatch.isReset() || mNotificationModel.isApplicationInForeground()) {
            mNotificationManager.cancel(mNotificationModel.getStopwatchNotificationId());
            return;
        }

        if (ContextCompat.checkSelfPermission(mContext, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            // Always false, because notification activation is always checked when the application is started.
            return;
        }

        // Otherwise build and post a notification reflecting the latest stopwatch state.
        final Notification notification = mNotificationBuilder.build(mContext, mNotificationModel, stopwatch);
        mNotificationManager.notify(mNotificationModel.getStopwatchNotificationId(), notification);
    }

    private Stopwatch getStopwatchForNotification() {
        final Stopwatch selected = getStopwatch();
        if (!selected.isReset()) {
            return selected;
        }
        for (Stopwatch stopwatch : getMutableStopwatches()) {
            if (stopwatch.isRunning()) {
                return stopwatch;
            }
        }
        for (Stopwatch stopwatch : getMutableStopwatches()) {
            if (stopwatch.isPaused()) {
                return stopwatch;
            }
        }
        return null;
    }

    private void logStopwatchStopped(Stopwatch stopwatch, long durationMs) {
        final String uuid = "stopwatch:" + stopwatch.getId();
        final String label = TextUtils.isEmpty(stopwatch.getLabel()) ? null : stopwatch.getLabel();
        final String duration = DateUtils.formatElapsedTime(durationMs / 1000);
        final String details = "duration=" + duration + " (" + durationMs + " ms)";
        EventLogStore.logEvent(mContext.getContentResolver(), EventLogStore.EVENT_STOPWATCH_STOPPED,
            uuid, label, details);
    }

    private List<Stopwatch> getMutableStopwatches() {
        if (mStopwatches == null) {
            mStopwatches = new ArrayList<>(StopwatchDAO.getStopwatches(mPrefs));
        }
        return mStopwatches;
    }

    private Map<Integer, List<Lap>> getMutableLapsMap() {
        if (mLapsByStopwatchId == null) {
            mLapsByStopwatchId = new HashMap<>();
        }
        return mLapsByStopwatchId;
    }

    private List<Lap> getMutableLaps(int stopwatchId) {
        List<Lap> laps = getMutableLapsMap().get(stopwatchId);
        if (laps == null) {
            laps = StopwatchDAO.getLaps(mPrefs, stopwatchId);
            getMutableLapsMap().put(stopwatchId, laps);
        }
        return laps;
    }

    private static int indexOf(List<Stopwatch> stopwatches, int id) {
        for (int i = 0; i < stopwatches.size(); i++) {
            if (stopwatches.get(i).getId() == id) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Update the stopwatch notification in response to a locale change.
     */
    private final class LocaleChangedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateNotification();
        }
    }
}

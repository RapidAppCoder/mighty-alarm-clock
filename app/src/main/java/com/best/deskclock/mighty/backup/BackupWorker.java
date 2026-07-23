// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.backup;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.best.deskclock.utils.LogUtils;

import java.util.concurrent.TimeUnit;

/**
 * WorkManager worker that performs a periodic JSON backup export via {@link JsonBackupManager}.
 */
public final class BackupWorker extends Worker {

    private static final String WORK_NAME = "mighty_json_backup";
    private static final int MIN_INTERVAL_HOURS = 1;

    public BackupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        LogUtils.i("BackupWorker: running scheduled JSON backup export");
        final boolean success = JsonBackupManager.exportNow(getApplicationContext());
        return success ? Result.success() : Result.failure();
    }

    /**
     * Schedules (or replaces) a periodic backup every {@code intervalHours}.
     */
    public static void schedulePeriodic(Context context, int intervalHours) {
        final int hours = Math.max(MIN_INTERVAL_HOURS, intervalHours);
        final PeriodicWorkRequest request =
            new PeriodicWorkRequest.Builder(BackupWorker.class, hours, TimeUnit.HOURS).build();

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request);
    }

    /**
     * Cancels the scheduled periodic backup, if any.
     */
    public static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
    }
}

// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.exchange;

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
 * WorkManager worker that periodically scans every configured {@link ExchangeManager.ExchangeFolder}
 * for new handoff files.
 */
public final class ExchangeScanWorker extends Worker {

    private static final String WORK_NAME = "mighty_exchange_scan";
    private static final long INTERVAL_MINUTES = 30;

    public ExchangeScanWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        LogUtils.i("ExchangeScanWorker: scanning exchange folders");
        try {
            ExchangeManager.scanAll(getApplicationContext());
            return Result.success();
        } catch (Exception e) {
            LogUtils.e("ExchangeScanWorker: scan failed", e);
            return Result.failure();
        }
    }

    public static void schedulePeriodic(Context context) {
        final PeriodicWorkRequest request =
            new PeriodicWorkRequest.Builder(ExchangeScanWorker.class, INTERVAL_MINUTES, TimeUnit.MINUTES).build();

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    public static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
    }
}

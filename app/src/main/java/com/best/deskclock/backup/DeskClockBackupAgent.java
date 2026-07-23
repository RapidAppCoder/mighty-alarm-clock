// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.backup;

import android.app.backup.BackupAgent;
import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;

import com.best.deskclock.alarms.AlarmStateManager;
import com.best.deskclock.base.AlarmAlertWakeLock;
import com.best.deskclock.mighty.backup.BackupWorker;
import com.best.deskclock.mighty.backup.JsonBackupManager;
import com.best.deskclock.mighty.exchange.ExchangeManager;
import com.best.deskclock.mighty.exchange.ExchangeScanWorker;
import com.best.deskclock.utils.LogUtils;

/**
 * Full-backup agent used with SeedVault / Android Auto Backup.
 * <p>
 * File inclusion is controlled by {@code backup_rules.xml} /
 * {@code data_extraction_rules.xml}. This agent only runs post-restore setup:
 * AlarmManager PendingIntents are not part of the backup payload and must be
 * re-registered, and Mighty WorkManager schedules must be recreated.
 */
public class DeskClockBackupAgent extends BackupAgent {

    @Override
    public void onBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
                         ParcelFileDescriptor newState) {
        // fullBackupOnly: key-value backup is unused; the platform streams files.
    }

    @Override
    public void onRestore(BackupDataInput data, int appVersionCode,
                          ParcelFileDescriptor newState) {
        // fullBackupOnly: key-value restore is unused; the platform restores files.
    }

    @Override
    public void onRestoreFinished() {
        LogUtils.i("DeskClockBackupAgent: restore finished, re-registering alarms");

        final PowerManager.WakeLock wl = AlarmAlertWakeLock.createPartialWakeLock(this);
        wl.acquire(60_000L);
        try {
            AlarmStateManager.fixAlarmInstances(this);
            rescheduleMightyBackgroundWork();
            LogUtils.i("DeskClockBackupAgent: post-restore setup complete");
        } catch (RuntimeException e) {
            LogUtils.e("DeskClockBackupAgent: post-restore setup failed", e);
        } finally {
            if (wl.isHeld()) {
                wl.release();
            }
        }
    }

    private void rescheduleMightyBackgroundWork() {
        try {
            if (JsonBackupManager.isScheduleEnabled(this)) {
                BackupWorker.schedulePeriodic(this, JsonBackupManager.getIntervalHours(this));
            }
            if (!ExchangeManager.getFolders(this).isEmpty()) {
                ExchangeScanWorker.schedulePeriodic(this);
            }
        } catch (RuntimeException e) {
            LogUtils.e("DeskClockBackupAgent: failed to reschedule Mighty background work", e);
        }
    }
}

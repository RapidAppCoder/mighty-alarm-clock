// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.exchange;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import androidx.annotation.Nullable;

import com.best.deskclock.data.SettingsDAO;
import com.best.deskclock.utils.LogUtils;

import static com.best.deskclock.DeskClockApplication.getDefaultSharedPreferences;

/**
 * Optionally asks Syncthing Fork to start before an exchange scan, via its remote-control
 * broadcast ({@code *.action.START}). Requires Syncthing's experimental "remote control by
 * broadcast intents" setting and usually unrestricted battery for the Syncthing app.
 */
public final class SyncthingNudge {

    private static final String[] CANDIDATE_PACKAGES = {
        "com.github.catfriend1.syncthingfork",
        "com.github.catfriend1.syncthingandroid",
    };

    private static final long POST_NUDGE_DELAY_MS = 2000L;

    private SyncthingNudge() {
    }

    /**
     * If the preference is enabled and a known Syncthing package is installed, sends a START
     * broadcast and waits briefly so the sync service can come up before the scan continues.
     */
    public static void nudgeIfEnabled(Context context) {
        final Context appContext = context.getApplicationContext();
        final SharedPreferences prefs = getDefaultSharedPreferences(appContext);
        if (!SettingsDAO.isExchangeSyncthingNudgeEnabled(prefs)) {
            return;
        }

        final String pkg = findInstalledPackage(appContext);
        if (pkg == null) {
            LogUtils.i("SyncthingNudge: no known Syncthing package installed");
            return;
        }

        final Intent intent = new Intent(pkg + ".action.START");
        intent.setPackage(pkg);
        try {
            appContext.sendBroadcast(intent);
            LogUtils.i("SyncthingNudge: sent START to " + pkg);
        } catch (RuntimeException e) {
            LogUtils.e("SyncthingNudge: failed to send START to " + pkg, e);
            return;
        }

        try {
            Thread.sleep(POST_NUDGE_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Opens the Syncthing Fork launcher activity if a known package is installed.
     *
     * @return {@code true} if an activity was started
     */
    public static boolean openApp(Context context) {
        final Context appContext = context.getApplicationContext();
        final String pkg = findInstalledPackage(appContext);
        if (pkg == null) {
            LogUtils.i("SyncthingNudge: cannot open — no known Syncthing package installed");
            return false;
        }

        final Intent launch = appContext.getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) {
            LogUtils.i("SyncthingNudge: no launch intent for " + pkg);
            return false;
        }

        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            appContext.startActivity(launch);
            return true;
        } catch (RuntimeException e) {
            LogUtils.e("SyncthingNudge: failed to open " + pkg, e);
            return false;
        }
    }

    @Nullable
    public static String findInstalledPackage(Context context) {
        final PackageManager pm = context.getPackageManager();
        for (String pkg : CANDIDATE_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0);
                return pkg;
            } catch (PackageManager.NameNotFoundException ignored) {
                // try next
            }
        }
        return null;
    }
}

// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.wifi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;

import com.best.deskclock.base.AppExecutors;

/**
 * Listens for Wi-Fi/connectivity changes and re-evaluates the per-alarm Wi-Fi rules whenever
 * they occur. Registered dynamically (not via the manifest) from {@code DeskClockApplication}.
 */
public final class WifiRuleReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context appContext = context.getApplicationContext();
        AppExecutors.getDiskIO().execute(() -> WifiAlarmRuleManager.evaluateRules(appContext));
    }

    /**
     * @return an {@link IntentFilter} matching the broadcasts this receiver cares about.
     */
    @SuppressWarnings("deprecation")
    public static IntentFilter createIntentFilter() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        return filter;
    }
}

// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.wifi;

import android.content.ContentResolver;
import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import com.best.deskclock.alarms.AlarmStateManager;
import com.best.deskclock.provider.Alarm;
import com.best.deskclock.provider.AlarmInstance;
import com.best.deskclock.provider.EventLogStore;
import com.best.deskclock.utils.LogUtils;

import java.util.Calendar;
import java.util.List;

/**
 * Applies per-alarm Wi-Fi based rules: when the currently connected SSID matches (or does not
 * match, depending on the configured condition) the alarm's configured SSID, the configured
 * action (enable/disable) is applied to the alarm.
 * <p>
 * This should be invoked whenever Wi-Fi connectivity changes, e.g. from {@link WifiRuleReceiver}.
 */
public final class WifiAlarmRuleManager {

    private WifiAlarmRuleManager() {
    }

    /**
     * Evaluates every alarm with a Wi-Fi rule enabled against the currently connected SSID and
     * applies the configured action when the condition is met.
     */
    public static void evaluateRules(Context context) {
        try {
            final String ssid = getCurrentSsid(context);
            final ContentResolver cr = context.getContentResolver();
            final String selection = Alarm.WIFI_RULE_ENABLED + "=1";
            final List<Alarm> alarms = Alarm.getAlarms(cr, selection);

            LogUtils.i("WifiAlarmRuleManager: evaluating %d wifi-rule alarm(s), current ssid=%s", alarms.size(), ssid);

            for (Alarm alarm : alarms) {
                applyRule(context, cr, alarm, ssid);
            }
        } catch (Exception e) {
            LogUtils.e("WifiAlarmRuleManager: failed to evaluate rules", e);
        }
    }

    private static void applyRule(Context context, ContentResolver cr, Alarm alarm, String currentSsid) {
        if (alarm.wifiSsid == null || alarm.wifiSsid.isEmpty() || alarm.wifiCondition == null || alarm.wifiAction == null) {
            return;
        }

        final boolean networkPresent = currentSsid != null && currentSsid.equalsIgnoreCase(alarm.wifiSsid);
        final boolean conditionMet = Alarm.WIFI_CONDITION_PRESENT.equals(alarm.wifiCondition) == networkPresent;

        if (!conditionMet) {
            return;
        }

        final boolean desiredEnabled = Alarm.WIFI_ACTION_ENABLE.equals(alarm.wifiAction);
        if (alarm.enabled == desiredEnabled) {
            return;
        }

        alarm.enabled = desiredEnabled;
        alarm.updateAlarm(cr);

        if (desiredEnabled) {
            final AlarmInstance instance = alarm.createInstanceAfter(Calendar.getInstance());
            instance.addInstance(cr);
            AlarmStateManager.registerInstance(context, instance, true);
        } else {
            AlarmStateManager.deleteAllInstances(context, alarm.id);
        }

        LogUtils.i("WifiAlarmRuleManager: applied rule to alarm %d -> enabled=%b (ssid=%s, condition=%s, action=%s)",
            alarm.id, desiredEnabled, currentSsid, alarm.wifiCondition, alarm.wifiAction);

        EventLogStore.logEvent(cr, "WIFI_RULE_APPLIED", alarm.stableUuid, alarm.label,
            "ssid=" + currentSsid + ", condition=" + alarm.wifiCondition + ", action=" + alarm.wifiAction);
    }

    /**
     * @return the currently connected Wi-Fi SSID (without surrounding quotes), or {@code null}
     * if unavailable (not connected, permission missing, or unknown).
     */
    private static String getCurrentSsid(Context context) {
        try {
            final WifiManager wifiManager =
                (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) {
                return null;
            }

            final WifiInfo info = wifiManager.getConnectionInfo();
            if (info == null) {
                return null;
            }

            String ssid = info.getSSID();
            if (ssid == null || ssid.equals("<unknown ssid>")) {
                return null;
            }

            if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }

            return ssid;
        } catch (Exception e) {
            LogUtils.e("WifiAlarmRuleManager: failed to read current SSID (missing location permission?)", e);
            return null;
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty;

import static com.best.deskclock.settings.PreferencesKeys.KEY_SAMPLE_ALARMS_SEEDED;
import static java.util.Calendar.FRIDAY;
import static java.util.Calendar.MONDAY;
import static java.util.Calendar.THURSDAY;
import static java.util.Calendar.TUESDAY;
import static java.util.Calendar.WEDNESDAY;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import com.best.deskclock.R;
import com.best.deskclock.data.Weekdays;
import com.best.deskclock.provider.Alarm;
import com.best.deskclock.provider.Tag;
import com.best.deskclock.utils.LogUtils;

import java.util.Calendar;
import java.util.List;

/**
 * Seeds a small set of disabled demo alarms (and tags) on first launch so users can explore
 * Mighty features without configuring everything from scratch.
 */
public final class SampleAlarmsSeeder {

    private SampleAlarmsSeeder() {
    }

    /**
     * Inserts sample alarms and tags once, when the alarm list is still empty.
     */
    public static void seedIfNeeded(Context context, SharedPreferences prefs) {
        if (prefs.getBoolean(KEY_SAMPLE_ALARMS_SEEDED, false)) {
            return;
        }

        final ContentResolver cr = context.getContentResolver();
        final List<Alarm> existing = Alarm.getAlarms(cr, null);
        if (existing != null && !existing.isEmpty()) {
            prefs.edit().putBoolean(KEY_SAMPLE_ALARMS_SEEDED, true).apply();
            return;
        }

        try {
            final Tag workTag = new Tag(context.getString(R.string.mighty_sample_tag_work), Color.parseColor("#FF1976D2"));
            workTag.addTag(cr);
            final Tag homeTag = new Tag(context.getString(R.string.mighty_sample_tag_home), Color.parseColor("#FF388E3C"));
            homeTag.addTag(cr);

            final Calendar now = Calendar.getInstance();

            // Weekday wake-up.
            final Alarm weekdays = new Alarm(now.get(Calendar.YEAR), now.get(Calendar.MONTH),
                now.get(Calendar.DAY_OF_MONTH), 7, 0);
            weekdays.enabled = false;
            weekdays.label = context.getString(R.string.mighty_sample_alarm_weekdays);
            weekdays.daysOfWeek = Weekdays.NONE
                .setBit(MONDAY, true)
                .setBit(TUESDAY, true)
                .setBit(WEDNESDAY, true)
                .setBit(THURSDAY, true)
                .setBit(FRIDAY, true);
            weekdays.addAlarm(cr);

            // One-shot weekend-style alarm that deletes after use.
            final Alarm oneShot = new Alarm(now.get(Calendar.YEAR), now.get(Calendar.MONTH),
                now.get(Calendar.DAY_OF_MONTH), 9, 0);
            oneShot.enabled = false;
            oneShot.label = context.getString(R.string.mighty_sample_alarm_oneshot);
            oneShot.daysOfWeek = Weekdays.NONE;
            oneShot.deleteAfterUse = true;
            oneShot.addAlarm(cr);

            // Tagged work alarm.
            final Alarm work = new Alarm(now.get(Calendar.YEAR), now.get(Calendar.MONTH),
                now.get(Calendar.DAY_OF_MONTH), 8, 30);
            work.enabled = false;
            work.label = context.getString(R.string.mighty_sample_alarm_work_tag);
            work.daysOfWeek = Weekdays.NONE
                .setBit(MONDAY, true)
                .setBit(TUESDAY, true)
                .setBit(WEDNESDAY, true)
                .setBit(THURSDAY, true)
                .setBit(FRIDAY, true);
            work.addAlarm(cr);
            if (work.id != Alarm.INVALID_ID && workTag.id != Tag.INVALID_ID) {
                Tag.addTagToAlarm(cr, work.id, workTag.id);
            }

            // Wi-Fi rule example (alarm stays disabled).
            final Alarm wifi = new Alarm(now.get(Calendar.YEAR), now.get(Calendar.MONTH),
                now.get(Calendar.DAY_OF_MONTH), 18, 0);
            wifi.enabled = false;
            wifi.label = context.getString(R.string.mighty_sample_alarm_wifi);
            wifi.daysOfWeek = Weekdays.NONE;
            wifi.wifiRuleEnabled = true;
            wifi.wifiSsid = "HomeWiFi";
            wifi.wifiCondition = Alarm.WIFI_CONDITION_PRESENT;
            wifi.wifiAction = Alarm.WIFI_ACTION_ENABLE;
            wifi.addAlarm(cr);
            if (wifi.id != Alarm.INVALID_ID && homeTag.id != Tag.INVALID_ID) {
                Tag.addTagToAlarm(cr, wifi.id, homeTag.id);
            }

            // Snooze extension example.
            final Alarm snoozeExtend = new Alarm(now.get(Calendar.YEAR), now.get(Calendar.MONTH),
                now.get(Calendar.DAY_OF_MONTH), 6, 45);
            snoozeExtend.enabled = false;
            snoozeExtend.label = context.getString(R.string.mighty_sample_alarm_snooze_extend);
            snoozeExtend.daysOfWeek = Weekdays.NONE
                .setBit(MONDAY, true)
                .setBit(TUESDAY, true)
                .setBit(WEDNESDAY, true)
                .setBit(THURSDAY, true)
                .setBit(FRIDAY, true);
            snoozeExtend.snoozeExtendEnabled = true;
            snoozeExtend.snoozeExtendMinutes = 5;
            snoozeExtend.snoozeExtendMaxMinutes = 30;
            snoozeExtend.addAlarm(cr);

            // Interval repeat example: every 30 minutes, max 3 times.
            final Alarm interval = new Alarm(now.get(Calendar.YEAR), now.get(Calendar.MONTH),
                now.get(Calendar.DAY_OF_MONTH), 12, 0);
            interval.enabled = false;
            interval.label = context.getString(R.string.mighty_sample_alarm_interval);
            interval.daysOfWeek = Weekdays.NONE;
            interval.repeatIntervalMinutes = 30;
            interval.repeatMaxCount = 3;
            interval.intervalFireCount = 0;
            interval.addAlarm(cr);

            prefs.edit().putBoolean(KEY_SAMPLE_ALARMS_SEEDED, true).apply();
            LogUtils.i("Seeded sample Mighty alarms and tags");
        } catch (Exception e) {
            LogUtils.e("Failed to seed sample alarms", e);
        }
    }
}

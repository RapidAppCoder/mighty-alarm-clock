// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty;

import android.content.SharedPreferences;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.best.deskclock.provider.Alarm;

/**
 * Remembers behavioral settings from the most recently created (or last edited-via-sheet) alarm
 * so the next newly created alarm can start with the same vibrate/ringtone/snooze/etc. values.
 */
public final class LastCreatedAlarmDefaults {

    private static final String PREFS_PREFIX = "last_created_alarm_";
    private static final String KEY_HAS_DEFAULTS = PREFS_PREFIX + "has_defaults";
    private static final String KEY_VIBRATE = PREFS_PREFIX + "vibrate";
    private static final String KEY_VIBRATION_PATTERN = PREFS_PREFIX + "vibration_pattern";
    private static final String KEY_FLASH = PREFS_PREFIX + "flash";
    private static final String KEY_DELETE_AFTER_USE = PREFS_PREFIX + "delete_after_use";
    private static final String KEY_AUTO_SILENCE = PREFS_PREFIX + "auto_silence";
    private static final String KEY_SNOOZE = PREFS_PREFIX + "snooze";
    private static final String KEY_MISSED_REPEAT = PREFS_PREFIX + "missed_repeat";
    private static final String KEY_CRESCENDO = PREFS_PREFIX + "crescendo";
    private static final String KEY_VOLUME = PREFS_PREFIX + "volume";
    private static final String KEY_ALERT = PREFS_PREFIX + "alert";
    private static final String KEY_SNOOZE_EXTEND_ENABLED = PREFS_PREFIX + "snooze_extend_enabled";
    private static final String KEY_SNOOZE_EXTEND_MINUTES = PREFS_PREFIX + "snooze_extend_minutes";
    private static final String KEY_SNOOZE_EXTEND_MAX = PREFS_PREFIX + "snooze_extend_max";
    private static final String KEY_REPEAT_INTERVAL = PREFS_PREFIX + "repeat_interval";
    private static final String KEY_REPEAT_MAX = PREFS_PREFIX + "repeat_max";

    private LastCreatedAlarmDefaults() {
    }

    public static boolean hasDefaults(@NonNull SharedPreferences prefs) {
        return prefs.getBoolean(KEY_HAS_DEFAULTS, false);
    }

    /**
     * Persists the behavioral fields of {@code alarm} for use when creating the next new alarm.
     */
    public static void save(@NonNull SharedPreferences prefs, @NonNull Alarm alarm) {
        final SharedPreferences.Editor editor = prefs.edit()
            .putBoolean(KEY_HAS_DEFAULTS, true)
            .putBoolean(KEY_VIBRATE, alarm.vibrate)
            .putString(KEY_VIBRATION_PATTERN, alarm.vibrationPattern)
            .putBoolean(KEY_FLASH, alarm.flash)
            .putBoolean(KEY_DELETE_AFTER_USE, alarm.deleteAfterUse)
            .putInt(KEY_AUTO_SILENCE, alarm.autoSilenceDuration)
            .putInt(KEY_SNOOZE, alarm.snoozeDuration)
            .putInt(KEY_MISSED_REPEAT, alarm.missedAlarmRepeatLimit)
            .putInt(KEY_CRESCENDO, alarm.crescendoDuration)
            .putInt(KEY_VOLUME, alarm.alarmVolume)
            .putBoolean(KEY_SNOOZE_EXTEND_ENABLED, alarm.snoozeExtendEnabled)
            .putInt(KEY_SNOOZE_EXTEND_MINUTES, alarm.snoozeExtendMinutes)
            .putInt(KEY_SNOOZE_EXTEND_MAX, alarm.snoozeExtendMaxMinutes)
            .putInt(KEY_REPEAT_INTERVAL, alarm.repeatIntervalMinutes)
            .putInt(KEY_REPEAT_MAX, alarm.repeatMaxCount);

        if (alarm.alert == null) {
            editor.putString(KEY_ALERT, null);
        } else {
            editor.putString(KEY_ALERT, alarm.alert.toString());
        }

        editor.apply();
    }

    /**
     * Applies previously saved defaults onto {@code alarm}. No-op if nothing has been saved yet.
     */
    public static void apply(@NonNull SharedPreferences prefs, @NonNull Alarm alarm) {
        if (!hasDefaults(prefs)) {
            return;
        }

        alarm.vibrate = prefs.getBoolean(KEY_VIBRATE, alarm.vibrate);
        alarm.vibrationPattern = prefs.getString(KEY_VIBRATION_PATTERN, alarm.vibrationPattern);
        alarm.flash = prefs.getBoolean(KEY_FLASH, alarm.flash);
        alarm.deleteAfterUse = prefs.getBoolean(KEY_DELETE_AFTER_USE, alarm.deleteAfterUse);
        alarm.autoSilenceDuration = prefs.getInt(KEY_AUTO_SILENCE, alarm.autoSilenceDuration);
        alarm.snoozeDuration = prefs.getInt(KEY_SNOOZE, alarm.snoozeDuration);
        alarm.missedAlarmRepeatLimit = prefs.getInt(KEY_MISSED_REPEAT, alarm.missedAlarmRepeatLimit);
        alarm.crescendoDuration = prefs.getInt(KEY_CRESCENDO, alarm.crescendoDuration);
        alarm.alarmVolume = prefs.getInt(KEY_VOLUME, alarm.alarmVolume);
        alarm.snoozeExtendEnabled = prefs.getBoolean(KEY_SNOOZE_EXTEND_ENABLED, alarm.snoozeExtendEnabled);
        alarm.snoozeExtendMinutes = prefs.getInt(KEY_SNOOZE_EXTEND_MINUTES, alarm.snoozeExtendMinutes);
        alarm.snoozeExtendMaxMinutes = prefs.getInt(KEY_SNOOZE_EXTEND_MAX, alarm.snoozeExtendMaxMinutes);
        alarm.repeatIntervalMinutes = prefs.getInt(KEY_REPEAT_INTERVAL, alarm.repeatIntervalMinutes);
        alarm.repeatMaxCount = prefs.getInt(KEY_REPEAT_MAX, alarm.repeatMaxCount);

        final String alert = prefs.getString(KEY_ALERT, null);
        alarm.alert = parseAlert(alert);
    }

    @Nullable
    private static Uri parseAlert(@Nullable String alert) {
        if (alert == null || alert.isEmpty()) {
            return null;
        }
        return Uri.parse(alert);
    }
}

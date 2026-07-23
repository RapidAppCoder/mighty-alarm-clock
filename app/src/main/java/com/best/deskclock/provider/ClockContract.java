/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package com.best.deskclock.provider;

import android.net.Uri;
import android.provider.BaseColumns;

import com.best.deskclock.BuildConfig;

/**
 * <p>
 * The contract between the clock provider and desk clock. Contains
 * definitions for the supported URIs and data columns.
 * </p>
 * <h3>Overview</h3>
 * <p>
 * ClockContract defines the data model of clock related information.
 * This data is stored in a number of tables:
 * </p>
 * <ul>
 * <li>The {@link AlarmsColumns} table holds the user created alarms</li>
 * <li>The {@link InstancesColumns} table holds the current state of each
 * alarm in the AlarmsColumn table.
 * </li>
 * </ul>
 */
public final class ClockContract {

    /**
     * This authority is used for writing to or querying from the clock
     * provider.
     */
    public static final String AUTHORITY = BuildConfig.APPLICATION_ID;

    /**
     * This utility class cannot be instantiated
     */
    private ClockContract() {
    }

    /**
     * Constants for tables with AlarmSettings.
     */
    private interface AlarmSettingColumns extends BaseColumns {

        /**
         * True if alarm should vibrate
         * <p>Type: BOOLEAN</p>
         */
        String VIBRATE = "vibrate";

        /**
         * Alarm vibration pattern.
         *
         * <p>Type: STRING</p>
         */
        String VIBRATION_PATTERN = "vibrationPattern";

        /**
         * True if flash should turn on
         * <p>Type: BOOLEAN</p>
         */
        String FLASH = "flash";

        /**
         * Alarm label.
         *
         * <p>Type: STRING</p>
         */
        String LABEL = "label";

        /**
         * True if alarms sharing the same label should be enabled or disabled together.
         *
         * <p>Type: BOOLEAN</p>
         */
        String SYNC_BY_LABEL = "syncByLabel";

        /**
         * Audio alert to play when alarm triggers. Null entry
         * means use system default and entry that equal
         * Uri.EMPTY.toString() means no ringtone.
         *
         * <p>Type: STRING</p>
         */
        String RINGTONE = "ringtone";

        /**
         * Alarm auto silence duration.
         * <p>Type: INTEGER</p>
         */
        String AUTO_SILENCE_DURATION = "autoSilenceDuration";

        /**
         * Alarm snooze duration.
         * <p>Type: INTEGER</p>
         */
        String SNOOZE_DURATION = "snoozeDuration";

        /**
         * Missed alarm repeat limit
         * <p>Type: INTEGER</p>
         */
        String MISSED_ALARM_REPEAT_LIMIT = "missed_alarm_repeat_limit";

        /**
         * Alarm crescendo duration.
         * <p>Type: INTEGER</p>
         */
        String CRESCENDO_DURATION = "crescendoDuration";

        /**
         * Alarm crescendo duration.
         * <p>Type: INTEGER</p>
         */
        String ALARM_VOLUME = "alarmVolume";

        /**
         * Manuel sort order.
         * <p>Type: INTEGER</p>
         */
        String MANUAL_SORT_ORDER = "manualSortOrder";

        /**
         * Start of the pause.
         * <p>Type: LONG</p>
         */
        String PAUSE_START_DATE = "pauseStartDate";

        /**
         * End of the pause.
         * <p>Type: LONG</p>
         */
        String PAUSE_END_DATE = "pauseEndDate";
    }

    /**
     * Column names shared by tables that track creation/update timestamps.
     */
    private interface TimestampColumns {

        /**
         * Timestamp (ms since epoch) when the row was created.
         * <p>Type: LONG</p>
         */
        String CREATED_AT = "created_at";

        /**
         * Timestamp (ms since epoch) when the row was last updated.
         * <p>Type: LONG</p>
         */
        String UPDATED_AT = "updated_at";
    }

    /**
     * Constants for the Alarms table, which contains the user created alarms.
     */
    protected interface AlarmsColumns extends AlarmSettingColumns, TimestampColumns, BaseColumns {

        /**
         * The content:// style URL for this table.
         */
        Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/alarms");

        /**
         * The content:// style URL for the alarms with instance tables, which is used to get the
         * next firing instance and the current state of an alarm.
         */
        Uri ALARMS_WITH_INSTANCES_URI = Uri.parse("content://" + AUTHORITY + "/alarms_with_instances");

        /**
         * Alarm year.
         *
         * <p>Type: INTEGER</p>
         */
        String YEAR = "year";

        /**
         * Alarm month in year.
         *
         * <p>Type: INTEGER</p>
         */
        String MONTH = "month";

        /**
         * Alarm day in month.
         *
         * <p>Type: INTEGER</p>
         */
        String DAY = "day";

        /**
         * Hour in 24-hour localtime 0 - 23.
         * <p>Type: INTEGER</p>
         */
        String HOUR = "hour";

        /**
         * Minutes in localtime 0 - 59.
         * <p>Type: INTEGER</p>
         */
        String MINUTES = "minutes";

        /**
         * Days of the week encoded as a bit set.
         * <p>Type: INTEGER</p>
         * <p>
         * {@link com.best.deskclock.data.Weekdays}
         */
        String DAYS_OF_WEEK = "daysofweek";

        /**
         * True if alarm is active.
         * <p>Type: BOOLEAN</p>
         */
        String ENABLED = "enabled";

        /**
         * Determine if alarm is deleted after it has been used.
         * <p>Type: INTEGER</p>
         */
        String DELETE_AFTER_USE = "delete_after_use";

        /**
         * Stable unique identifier for the alarm that survives id reuse/renumbering.
         * <p>Type: TEXT</p>
         */
        String STABLE_UUID = "stable_uuid";

        /**
         * Number of times this alarm has rung.
         * <p>Type: INTEGER</p>
         */
        String RING_COUNT = "ring_count";

        /**
         * True if the snooze duration should be progressively extended on repeated snoozes.
         * <p>Type: BOOLEAN</p>
         */
        String SNOOZE_EXTEND_ENABLED = "snooze_extend_enabled";

        /**
         * Number of minutes to extend the snooze duration by on each successive snooze.
         * <p>Type: INTEGER</p>
         */
        String SNOOZE_EXTEND_MINUTES = "snooze_extend_minutes";

        /**
         * Maximum number of minutes the snooze duration is allowed to be extended to.
         * <p>Type: INTEGER</p>
         */
        String SNOOZE_EXTEND_MAX_MINUTES = "snooze_extend_max_minutes";

        /**
         * True if a Wi-Fi based rule should control this alarm.
         * <p>Type: BOOLEAN</p>
         */
        String WIFI_RULE_ENABLED = "wifi_rule_enabled";

        /**
         * SSID of the Wi-Fi network used by the Wi-Fi rule.
         * <p>Type: STRING</p>
         */
        String WIFI_SSID = "wifi_ssid";

        /**
         * Condition of the Wi-Fi rule. One of {@link #WIFI_CONDITION_PRESENT} or
         * {@link #WIFI_CONDITION_ABSENT}.
         * <p>Type: STRING</p>
         */
        String WIFI_CONDITION = "wifi_condition";

        /**
         * Action of the Wi-Fi rule. One of {@link #WIFI_ACTION_ENABLE} or
         * {@link #WIFI_ACTION_DISABLE}.
         * <p>Type: STRING</p>
         */
        String WIFI_ACTION = "wifi_action";

        /**
         * {@link #WIFI_CONDITION} value meaning the Wi-Fi network is in range/connected.
         */
        String WIFI_CONDITION_PRESENT = "PRESENT";

        /**
         * {@link #WIFI_CONDITION} value meaning the Wi-Fi network is out of range/disconnected.
         */
        String WIFI_CONDITION_ABSENT = "ABSENT";

        /**
         * {@link #WIFI_ACTION} value meaning the alarm should be enabled when the Wi-Fi
         * condition is met.
         */
        String WIFI_ACTION_ENABLE = "ENABLE";

        /**
         * {@link #WIFI_ACTION} value meaning the alarm should be disabled when the Wi-Fi
         * condition is met.
         */
        String WIFI_ACTION_DISABLE = "DISABLE";

        /**
         * Interval in minutes between successive firings when interval repeat is enabled.
         * 0 means interval repeat is off (weekday or one-shot mode applies instead).
         * <p>Type: INTEGER</p>
         */
        String REPEAT_INTERVAL_MINUTES = "repeat_interval_minutes";

        /**
         * Maximum number of interval firings before the alarm is disabled.
         * 0 means unlimited.
         * <p>Type: INTEGER</p>
         */
        String REPEAT_MAX_COUNT = "repeat_max_count";

        /**
         * Number of times this interval alarm has fired since the counter was last reset.
         * <p>Type: INTEGER</p>
         */
        String INTERVAL_FIRE_COUNT = "interval_fire_count";
    }

    /**
     * Constants for the Instance table, which contains the state of each alarm.
     */
    protected interface InstancesColumns extends AlarmSettingColumns, BaseColumns {

        /**
         * The content:// style URL for this table.
         */
        Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/instances");

        /**
         * Alarm state when to show no notification.
         * <p>
         * Can transitions to:
         * NOTIFICATION_STATE
         */
        int SILENT_STATE = 0;

        /**
         * Alarm state to show alarm notification.
         * <p>
         * Can transitions to:
         * DISMISSED_STATE
         * FIRED_STATE
         */
        int NOTIFICATION_STATE = 1;

        /**
         * Alarm state when alarm is in snooze.
         * <p>
         * Can transitions to:
         * DISMISSED_STATE
         * FIRED_STATE
         */
        int SNOOZE_STATE = 2;

        /**
         * Alarm state when alarm is being fired.
         * <p>
         * Can transitions to:
         * DISMISSED_STATE
         * SNOOZED_STATE
         * MISSED_STATE
         */
        int FIRED_STATE = 3;

        /**
         * Alarm state when alarm has been missed.
         * <p>
         * Can transitions to:
         * DISMISSED_STATE
         */
        int MISSED_STATE = 4;

        /**
         * Alarm state when alarm is done.
         */
        int DISMISSED_STATE = 5;

        /**
         * Alarm state when alarm has been dismissed before its intended firing time.
         */
        int PREDISMISSED_STATE = 6;

        /**
         * Alarm year.
         *
         * <p>Type: INTEGER</p>
         */
        String YEAR = "year";

        /**
         * Alarm month in year.
         *
         * <p>Type: INTEGER</p>
         */
        String MONTH = "month";

        /**
         * Alarm day in month.
         *
         * <p>Type: INTEGER</p>
         */
        String DAY = "day";

        /**
         * Alarm hour in 24-hour localtime 0 - 23.
         * <p>Type: INTEGER</p>
         */
        String HOUR = "hour";

        /**
         * Alarm minutes in localtime 0 - 59
         * <p>Type: INTEGER</p>
         */
        String MINUTES = "minutes";

        /**
         * Foreign key to Alarms table
         * <p>Type: INTEGER (long)</p>
         */
        String ALARM_ID = "alarm_id";

        /**
         * Alarm state
         * <p>Type: INTEGER</p>
         */
        String ALARM_STATE = "alarm_state";

        /**
         * Missed alarm repeat count
         * <p>Type: INTEGER</p>
         */
        String MISSED_ALARM_REPEAT_COUNT = "missed_alarm_repeat_count";

        /**
         * Number of times this alarm instance has been snoozed.
         * <p>Type: INTEGER</p>
         */
        String SNOOZE_COUNT = "snooze_count";
    }

    /**
     * Constants for the Tags table, which contains user-defined alarm tags.
     */
    protected interface TagsColumns extends TimestampColumns, BaseColumns {

        /**
         * The content:// style URL for this table.
         */
        Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/tags");

        /**
         * Tag name.
         * <p>Type: TEXT</p>
         */
        String NAME = "name";

        /**
         * Tag color.
         * <p>Type: INTEGER</p>
         */
        String COLOR = "color";

        /**
         * Optional ringtone associated with the tag.
         * <p>Type: TEXT</p>
         */
        String RINGTONE_URI = "ringtone_uri";
    }

    /**
     * Constants for the AlarmTags table, which maps alarms to tags (many-to-many).
     */
    protected interface AlarmTagsColumns {

        /**
         * The content:// style URL for this table.
         */
        Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/alarm_tags");

        /**
         * Foreign key to the Alarms table.
         * <p>Type: INTEGER (long)</p>
         */
        String ALARM_ID = "alarm_id";

        /**
         * Foreign key to the Tags table.
         * <p>Type: INTEGER (long)</p>
         */
        String TAG_ID = "tag_id";
    }

    /**
     * Constants for the EventLog table, which contains a history of alarm-related events.
     */
    protected interface EventLogColumns extends BaseColumns {

        /**
         * The content:// style URL for this table.
         */
        Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/event_log");

        /**
         * Timestamp (ms since epoch) when the event occurred.
         * <p>Type: LONG</p>
         */
        String TIMESTAMP = "timestamp";

        /**
         * Type of event, e.g. "ALARM_FIRED", "ALARM_DISMISSED", "ALARM_SNOOZED".
         * <p>Type: TEXT</p>
         */
        String EVENT_TYPE = "event_type";

        /**
         * Stable UUID of the alarm the event refers to, if any.
         * <p>Type: TEXT</p>
         */
        String ALARM_UUID = "alarm_uuid";

        /**
         * Label of the alarm at the time the event occurred, if any.
         * <p>Type: TEXT</p>
         */
        String ALARM_LABEL = "alarm_label";

        /**
         * Free-form details about the event.
         * <p>Type: TEXT</p>
         */
        String DETAILS = "details";
    }
}

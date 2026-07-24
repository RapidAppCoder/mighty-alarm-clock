/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package com.best.deskclock.provider;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;

import androidx.annotation.NonNull;

import java.util.LinkedList;
import java.util.List;

/**
 * Read/write access to the alarm-related event log, backed by the {@link ClockProvider}.
 */
public final class EventLogStore implements ClockContract.EventLogColumns {

    // Well-known event types. Callers may also use their own custom values.
    public static final String EVENT_ALARM_CREATED = "ALARM_CREATED";
    public static final String EVENT_ALARM_UPDATED = "ALARM_UPDATED";
    public static final String EVENT_ALARM_DELETED = "ALARM_DELETED";
    public static final String EVENT_ALARM_FIRED = "ALARM_FIRED";
    public static final String EVENT_ALARM_DISMISSED = "ALARM_DISMISSED";
    public static final String EVENT_ALARM_DISABLED = "ALARM_DISABLED";
    public static final String EVENT_ALARM_SNOOZED = "ALARM_SNOOZED";
    public static final String EVENT_ALARM_MISSED = "ALARM_MISSED";

    private static final String[] QUERY_COLUMNS = {
        _ID,
        TIMESTAMP,
        EVENT_TYPE,
        ALARM_UUID,
        ALARM_LABEL,
        DETAILS
    };

    /**
     * These save calls to cursor.getColumnIndexOrThrow()
     * THEY MUST BE KEPT IN SYNC WITH ABOVE QUERY COLUMNS
     */
    private static final int ID_INDEX = 0;
    private static final int TIMESTAMP_INDEX = 1;
    private static final int EVENT_TYPE_INDEX = 2;
    private static final int ALARM_UUID_INDEX = 3;
    private static final int ALARM_LABEL_INDEX = 4;
    private static final int DETAILS_INDEX = 5;

    private EventLogStore() {
    }

    /**
     * An immutable snapshot of a single row of the event log.
     */
    public static final class EventLogEntry {
        public final long id;
        public final long timestamp;
        public final String eventType;
        public final String alarmUuid;
        public final String alarmLabel;
        public final String details;

        EventLogEntry(Cursor c) {
            id = c.getLong(ID_INDEX);
            timestamp = c.getLong(TIMESTAMP_INDEX);
            eventType = c.getString(EVENT_TYPE_INDEX);
            alarmUuid = c.isNull(ALARM_UUID_INDEX) ? null : c.getString(ALARM_UUID_INDEX);
            alarmLabel = c.isNull(ALARM_LABEL_INDEX) ? null : c.getString(ALARM_LABEL_INDEX);
            details = c.isNull(DETAILS_INDEX) ? null : c.getString(DETAILS_INDEX);
        }

        @NonNull
        @Override
        public String toString() {
            return "EventLogEntry{" +
                "id=" + id +
                ", timestamp=" + timestamp +
                ", eventType='" + eventType + '\'' +
                ", alarmUuid='" + alarmUuid + '\'' +
                ", alarmLabel='" + alarmLabel + '\'' +
                ", details='" + details + '\'' +
                '}';
        }
    }

    /**
     * Records a new event in the event log.
     *
     * @param contentResolver provides access to the content model
     * @param eventType       one of the {@code EVENT_*} constants, or a custom value
     * @param alarmUuid       the stable UUID of the alarm the event refers to, or null
     * @param alarmLabel      the label of the alarm at the time of the event, or null
     * @param details         free-form details about the event, or null
     */
    public static void logEvent(ContentResolver contentResolver, String eventType, String alarmUuid,
                                String alarmLabel, String details) {
        ContentValues values = new ContentValues(5);
        values.put(TIMESTAMP, System.currentTimeMillis());
        values.put(EVENT_TYPE, eventType);

        if (alarmUuid == null) {
            values.putNull(ALARM_UUID);
        } else {
            values.put(ALARM_UUID, alarmUuid);
        }

        if (alarmLabel == null) {
            values.putNull(ALARM_LABEL);
        } else {
            values.put(ALARM_LABEL, alarmLabel);
        }

        if (details == null) {
            values.putNull(DETAILS);
        } else {
            values.put(DETAILS, details);
        }

        contentResolver.insert(CONTENT_URI, values);
    }

    /**
     * @return every recorded event, most recent first.
     */
    public static List<EventLogEntry> getEvents(ContentResolver contentResolver) {
        return getEvents(contentResolver, null, null);
    }

    /**
     * @return the events matching the given selection, most recent first.
     */
    public static List<EventLogEntry> getEvents(ContentResolver contentResolver, String selection,
                                                String[] selectionArgs) {
        final List<EventLogEntry> result = new LinkedList<>();
        final String sortOrder = TIMESTAMP + " DESC";
        try (Cursor cursor = contentResolver.query(CONTENT_URI, QUERY_COLUMNS, selection, selectionArgs, sortOrder)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    result.add(new EventLogEntry(cursor));
                }
            }
        }

        return result;
    }

    /**
     * @return the events recorded for the alarm with the given stable UUID, most recent first.
     */
    public static List<EventLogEntry> getEventsForAlarm(ContentResolver contentResolver, String alarmUuid) {
        return getEvents(contentResolver, ALARM_UUID + "=?", new String[]{alarmUuid});
    }

    /**
     * Deletes every recorded event.
     */
    public static void clear(ContentResolver contentResolver) {
        contentResolver.delete(CONTENT_URI, null, null);
    }

    /**
     * Deletes events recorded strictly before the given timestamp.
     */
    public static void clearOlderThan(ContentResolver contentResolver, long timestampMillis) {
        contentResolver.delete(CONTENT_URI, TIMESTAMP + "<?", new String[]{String.valueOf(timestampMillis)});
    }

    /**
     * Exports every recorded event as a CSV string. The first line is the header row.
     */
    public static String exportCsv(ContentResolver contentResolver) {
        StringBuilder sb = new StringBuilder();
        sb.append("id,timestamp,event_type,alarm_uuid,alarm_label,details\n");

        for (EventLogEntry entry : getEvents(contentResolver)) {
            sb.append(entry.id).append(',')
                .append(entry.timestamp).append(',')
                .append(csvEscape(entry.eventType)).append(',')
                .append(csvEscape(entry.alarmUuid)).append(',')
                .append(csvEscape(entry.alarmLabel)).append(',')
                .append(csvEscape(entry.details))
                .append('\n');
        }

        return sb.toString();
    }

    private static String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}

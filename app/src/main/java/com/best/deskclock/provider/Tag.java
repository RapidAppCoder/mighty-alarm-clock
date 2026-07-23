/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package com.best.deskclock.provider;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

import androidx.annotation.NonNull;

import java.util.LinkedList;
import java.util.List;

/**
 * Simple model representing a user-defined alarm tag, backed by the {@link ClockProvider}.
 */
public final class Tag implements ClockContract.TagsColumns {

    /**
     * Tags start with an invalid id when they haven't been saved to the database.
     */
    public static final long INVALID_ID = -1;

    private static final String[] QUERY_COLUMNS = {
        _ID,
        NAME,
        COLOR,
        RINGTONE_URI,
        CREATED_AT,
        UPDATED_AT
    };

    /**
     * These save calls to cursor.getColumnIndexOrThrow()
     * THEY MUST BE KEPT IN SYNC WITH ABOVE QUERY COLUMNS
     */
    private static final int ID_INDEX = 0;
    private static final int NAME_INDEX = 1;
    private static final int COLOR_INDEX = 2;
    private static final int RINGTONE_URI_INDEX = 3;
    private static final int CREATED_AT_INDEX = 4;
    private static final int UPDATED_AT_INDEX = 5;

    // Public fields
    public long id;
    public String name;
    public int color;
    public String ringtoneUri;
    public long createdAt;
    public long updatedAt;

    public Tag() {
        this.id = INVALID_ID;
        this.name = "";
        this.color = 0;
        this.ringtoneUri = null;
        final long now = System.currentTimeMillis();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Tag(String name, int color) {
        this();
        this.name = name;
        this.color = color;
    }

    public Tag(Cursor c) {
        id = c.getLong(ID_INDEX);
        name = c.getString(NAME_INDEX);
        color = c.getInt(COLOR_INDEX);
        ringtoneUri = c.isNull(RINGTONE_URI_INDEX) ? null : c.getString(RINGTONE_URI_INDEX);
        createdAt = c.getLong(CREATED_AT_INDEX);
        updatedAt = c.getLong(UPDATED_AT_INDEX);
    }

    public ContentValues createContentValues() {
        ContentValues values = new ContentValues(QUERY_COLUMNS.length);
        if (id != INVALID_ID) {
            values.put(_ID, id);
        }

        values.put(NAME, name);
        values.put(COLOR, color);
        values.put(CREATED_AT, createdAt);
        values.put(UPDATED_AT, updatedAt);

        if (ringtoneUri == null) {
            values.putNull(RINGTONE_URI);
        } else {
            values.put(RINGTONE_URI, ringtoneUri);
        }

        return values;
    }

    public static Uri getContentUri(long tagId) {
        return ContentUris.withAppendedId(CONTENT_URI, tagId);
    }

    public static long getId(Uri contentUri) {
        return ContentUris.parseId(contentUri);
    }

    /**
     * Get tag by id.
     *
     * @param cr    provides access to the content model
     * @param tagId for the desired tag.
     * @return tag if found, null otherwise
     */
    public static Tag getTag(ContentResolver cr, long tagId) {
        try (Cursor cursor = cr.query(getContentUri(tagId), QUERY_COLUMNS, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return new Tag(cursor);
            }
        }

        return null;
    }

    /**
     * @return every tag defined by the user, sorted by name.
     */
    public static List<Tag> getTags(ContentResolver cr) {
        final List<Tag> result = new LinkedList<>();
        try (Cursor cursor = cr.query(CONTENT_URI, QUERY_COLUMNS, null, null, NAME + " ASC")) {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    result.add(new Tag(cursor));
                } while (cursor.moveToNext());
            }
        }

        return result;
    }

    public Tag addTag(ContentResolver contentResolver) {
        ContentValues values = createContentValues();
        Uri uri = contentResolver.insert(CONTENT_URI, values);
        id = getId(uri);
        return this;
    }

    public void updateTag(ContentResolver contentResolver) {
        if (id == INVALID_ID) {
            return;
        }
        updatedAt = System.currentTimeMillis();
        ContentValues values = createContentValues();
        contentResolver.update(getContentUri(id), values, null, null);
    }

    public static boolean deleteTag(ContentResolver contentResolver, long tagId) {
        if (tagId == INVALID_ID) {
            return false;
        }
        int deletedRows = contentResolver.delete(getContentUri(tagId), null, null);
        return deletedRows == 1;
    }

    /**
     * Associates the given alarm with the given tag. Associating the same pair twice is a no-op.
     */
    public static void addTagToAlarm(ContentResolver contentResolver, long alarmId, long tagId) {
        ContentValues values = new ContentValues(2);
        values.put(ClockContract.AlarmTagsColumns.ALARM_ID, alarmId);
        values.put(ClockContract.AlarmTagsColumns.TAG_ID, tagId);
        contentResolver.insert(ClockContract.AlarmTagsColumns.CONTENT_URI, values);
    }

    /**
     * Removes the association between the given alarm and tag, if it exists.
     */
    public static void removeTagFromAlarm(ContentResolver contentResolver, long alarmId, long tagId) {
        final String where = ClockContract.AlarmTagsColumns.ALARM_ID + "=? AND "
            + ClockContract.AlarmTagsColumns.TAG_ID + "=?";
        final String[] args = {String.valueOf(alarmId), String.valueOf(tagId)};
        contentResolver.delete(ClockContract.AlarmTagsColumns.CONTENT_URI, where, args);
    }

    /**
     * Removes every tag association for the given alarm.
     */
    public static void clearTagsForAlarm(ContentResolver contentResolver, long alarmId) {
        final String where = ClockContract.AlarmTagsColumns.ALARM_ID + "=?";
        final String[] args = {String.valueOf(alarmId)};
        contentResolver.delete(ClockContract.AlarmTagsColumns.CONTENT_URI, where, args);
    }

    /**
     * @return the ids of every tag associated with the given alarm.
     */
    public static List<Long> getTagIdsForAlarm(ContentResolver contentResolver, long alarmId) {
        final List<Long> tagIds = new LinkedList<>();
        final String[] projection = {ClockContract.AlarmTagsColumns.TAG_ID};
        final String selection = ClockContract.AlarmTagsColumns.ALARM_ID + "=?";
        final String[] selectionArgs = {String.valueOf(alarmId)};
        try (Cursor cursor = contentResolver.query(ClockContract.AlarmTagsColumns.CONTENT_URI,
                projection, selection, selectionArgs, null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    tagIds.add(cursor.getLong(0));
                }
            }
        }

        return tagIds;
    }

    /**
     * @return the ids of every alarm associated with the given tag.
     */
    public static List<Long> getAlarmIdsForTag(ContentResolver contentResolver, long tagId) {
        final List<Long> alarmIds = new LinkedList<>();
        final String[] projection = {ClockContract.AlarmTagsColumns.ALARM_ID};
        final String selection = ClockContract.AlarmTagsColumns.TAG_ID + "=?";
        final String[] selectionArgs = {String.valueOf(tagId)};
        try (Cursor cursor = contentResolver.query(ClockContract.AlarmTagsColumns.CONTENT_URI,
                projection, selection, selectionArgs, null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    alarmIds.add(cursor.getLong(0));
                }
            }
        }

        return alarmIds;
    }

    /**
     * @return the distinct ids of every alarm that has at least one tag assigned.
     */
    public static List<Long> getAlarmIdsWithAnyTag(ContentResolver contentResolver) {
        final List<Long> alarmIds = new LinkedList<>();
        final String[] projection = {ClockContract.AlarmTagsColumns.ALARM_ID};
        try (Cursor cursor = contentResolver.query(ClockContract.AlarmTagsColumns.CONTENT_URI,
                projection, null, null, null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    final long alarmId = cursor.getLong(0);
                    if (!alarmIds.contains(alarmId)) {
                        alarmIds.add(alarmId);
                    }
                }
            }
        }

        return alarmIds;
    }

    /**
     * @return every tag associated with the given alarm.
     */
    public static List<Tag> getTagsForAlarm(ContentResolver contentResolver, long alarmId) {
        final List<Tag> tags = new LinkedList<>();
        for (long tagId : getTagIdsForAlarm(contentResolver, alarmId)) {
            Tag tag = getTag(contentResolver, tagId);
            if (tag != null) {
                tags.add(tag);
            }
        }

        return tags;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof final Tag other)) return false;
        return id == other.id;
    }

    @Override
    public int hashCode() {
        return Long.valueOf(id).hashCode();
    }

    @NonNull
    @Override
    public String toString() {
        return "Tag{" +
            "id=" + id +
            ", name='" + name + '\'' +
            ", color=" + color +
            ", ringtoneUri='" + ringtoneUri + '\'' +
            ", createdAt=" + createdAt +
            ", updatedAt=" + updatedAt +
            '}';
    }
}

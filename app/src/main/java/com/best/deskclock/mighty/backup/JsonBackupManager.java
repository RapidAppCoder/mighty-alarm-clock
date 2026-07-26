// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.backup;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import com.best.deskclock.mighty.exchange.ExchangeManager;
import com.best.deskclock.provider.Alarm;
import com.best.deskclock.provider.Tag;
import com.best.deskclock.utils.LogUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Exports alarms, tags and alarm/tag associations as pretty-printed JSON files into a
 * user-selected folder (via {@code ACTION_OPEN_DOCUMENT_TREE}). No ZIP is used; plain
 * {@code .json} files are written directly into the folder.
 * <p>
 * Two kinds of files are written on every export:
 * <ul>
 *     <li>A dated file, e.g. {@code alarms-Pixel-2026-07-22_16-30-00.json}, including the
 *     configured exchange device name, rotated so that only the most recent
 *     {@value #MAX_DATED_BACKUPS} dated files are kept.</li>
 *     <li>{@code alarms-latest.json}, always overwritten with the most recent export.</li>
 * </ul>
 */
public final class JsonBackupManager {

    private static final String PREFS_NAME = "mighty_backup_prefs";
    public static final String KEY_TREE_URI = "backup_tree_uri";
    public static final String KEY_SCHEDULE_ENABLED = "backup_schedule_enabled";
    public static final String KEY_INTERVAL_HOURS = "backup_interval_hours";

    private static final int MAX_DATED_BACKUPS = 14;
    private static final String LATEST_FILE_NAME = "alarms-latest.json";
    private static final String MIME_JSON = "application/json";

    private JsonBackupManager() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static void setTreeUri(Context context, Uri treeUri) {
        prefs(context).edit().putString(KEY_TREE_URI, treeUri == null ? null : treeUri.toString()).apply();
    }

    public static Uri getTreeUri(Context context) {
        final String value = prefs(context).getString(KEY_TREE_URI, null);
        return value == null ? null : Uri.parse(value);
    }

    public static void setScheduleEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SCHEDULE_ENABLED, enabled).apply();
    }

    public static boolean isScheduleEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SCHEDULE_ENABLED, false);
    }

    public static void setIntervalHours(Context context, int hours) {
        prefs(context).edit().putInt(KEY_INTERVAL_HOURS, hours).apply();
    }

    public static int getIntervalHours(Context context) {
        return prefs(context).getInt(KEY_INTERVAL_HOURS, 24);
    }

    /**
     * Performs an export right now. Safe to call from a background thread.
     *
     * @return {@code true} if the export succeeded.
     */
    public static boolean exportNow(Context context) {
        final Uri treeUri = getTreeUri(context);
        if (treeUri == null) {
            LogUtils.i("JsonBackupManager: no backup folder configured, skipping export");
            return false;
        }

        try {
            final DocumentFile dir = DocumentFile.fromTreeUri(context, treeUri);
            if (dir == null || !dir.canWrite()) {
                LogUtils.e("JsonBackupManager: backup folder is not writable");
                return false;
            }

            final JSONObject json = buildJson(context);

            final String dateStamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
            final String devicePart = ExchangeManager.sanitizeFileNamePart(ExchangeManager.getDeviceName(context));
            writeJsonFile(context, dir, "alarms-" + devicePart + "-" + dateStamp + ".json", json);
            writeJsonFile(context, dir, LATEST_FILE_NAME, json);

            rotateOldBackups(dir);

            LogUtils.i("JsonBackupManager: export completed successfully");
            return true;
        } catch (Exception e) {
            LogUtils.e("JsonBackupManager: export failed", e);
            return false;
        }
    }

    private static void writeJsonFile(Context context, DocumentFile dir, String name, JSONObject json)
        throws IOException, JSONException {
        final DocumentFile existing = dir.findFile(name);
        if (existing != null) {
            existing.delete();
        }

        final DocumentFile file = dir.createFile(MIME_JSON, name);
        if (file == null) {
            throw new IOException("Could not create backup file: " + name);
        }

        final ContentResolver cr = context.getContentResolver();
        try (OutputStream out = cr.openOutputStream(file.getUri())) {
            if (out == null) {
                throw new IOException("Could not open output stream for: " + name);
            }
            out.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void rotateOldBackups(DocumentFile dir) {
        final List<DocumentFile> dated = new ArrayList<>();
        for (DocumentFile file : dir.listFiles()) {
            final String name = file.getName();
            if (name != null && name.startsWith("alarms-") && name.endsWith(".json") && !name.equals(LATEST_FILE_NAME)) {
                dated.add(file);
            }
        }

        dated.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));

        while (dated.size() > MAX_DATED_BACKUPS) {
            final DocumentFile oldest = dated.remove(0);
            oldest.delete();
        }
    }

    private static JSONObject buildJson(Context context) throws JSONException {
        final ContentResolver cr = context.getContentResolver();

        final JSONObject root = new JSONObject();

        final JSONObject metadata = new JSONObject();
        metadata.put("exportedAt", System.currentTimeMillis());
        metadata.put("appId", context.getPackageName());
        metadata.put("formatVersion", 1);
        root.put("metadata", metadata);

        final List<Alarm> alarms = Alarm.getAlarms(cr, null);
        final JSONArray alarmsArray = new JSONArray();
        for (Alarm alarm : alarms) {
            final JSONObject a = new JSONObject();
            a.put("id", alarm.id);
            a.put("stableUuid", alarm.stableUuid);
            a.put("hour", alarm.hour);
            a.put("minutes", alarm.minutes);
            a.put("daysOfWeekBits", alarm.daysOfWeek.getBits());
            a.put("enabled", alarm.enabled);
            a.put("label", alarm.label);
            a.put("ringtone", alarm.alert == null ? JSONObject.NULL : alarm.alert.toString());
            a.put("vibrate", alarm.vibrate);
            a.put("snoozeDuration", alarm.snoozeDuration);
            a.put("ringCount", alarm.ringCount);
            a.put("repeatIntervalMinutes", alarm.repeatIntervalMinutes);
            a.put("repeatMaxCount", alarm.repeatMaxCount);
            a.put("intervalFireCount", alarm.intervalFireCount);
            a.put("createdAt", alarm.createdAt);
            a.put("updatedAt", alarm.updatedAt);
            alarmsArray.put(a);
        }
        root.put("alarms", alarmsArray);

        final List<Tag> tags = Tag.getTags(cr);
        final JSONArray tagsArray = new JSONArray();
        for (Tag tag : tags) {
            final JSONObject t = new JSONObject();
            t.put("id", tag.id);
            t.put("name", tag.name);
            t.put("color", tag.color);
            t.put("ringtoneUri", tag.ringtoneUri == null ? JSONObject.NULL : tag.ringtoneUri);
            tagsArray.put(t);
        }
        root.put("tags", tagsArray);

        final JSONArray alarmTagsArray = new JSONArray();
        for (Alarm alarm : alarms) {
            for (Long tagId : Tag.getTagIdsForAlarm(cr, alarm.id)) {
                final JSONObject at = new JSONObject();
                at.put("alarmId", alarm.id);
                at.put("tagId", tagId);
                alarmTagsArray.put(at);
            }
        }
        root.put("alarm_tags", alarmTagsArray);

        return root;
    }
}

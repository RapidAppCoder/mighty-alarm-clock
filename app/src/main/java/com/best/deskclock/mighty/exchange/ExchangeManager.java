// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.exchange;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import com.best.deskclock.alarms.AlarmStateManager;
import com.best.deskclock.provider.Alarm;
import com.best.deskclock.provider.EventLogStore;
import com.best.deskclock.utils.LogUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Manages "exchange" folders: shared folders (typically on a cloud-synced storage provider) that
 * other devices/installs also watch, used to move or share alarms between devices.
 * <p>
 * A device can "move" an alarm to a folder by writing a handoff JSON file describing the alarm.
 * Other devices that watch the same folder (via {@link ExchangeScanWorker}) will detect the new
 * handoff file and either automatically import it (policy {@link ImportPolicy#AUTO}) or place it
 * in an inbox for the user to accept/reject (policy {@link ImportPolicy#ASK}).
 */
public final class ExchangeManager {

    public enum ImportPolicy { ASK, AUTO }

    private static final String PREFS_NAME = "mighty_exchange_prefs";
    private static final String KEY_FOLDERS = "exchange_folders";
    private static final String KEY_INBOX = "exchange_inbox";
    private static final String KEY_PROCESSED_PREFIX = "exchange_processed_";

    private static final String HANDOFFS_DIR = "handoffs";
    private static final String EXCHANGE_FILE = "alarms-exchange.json";
    private static final String MIME_JSON = "application/json";

    private ExchangeManager() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------------
    // Folder configuration
    // ---------------------------------------------------------------------

    public static final class ExchangeFolder {
        public final String name;
        public final String treeUri;
        public final ImportPolicy importPolicy;

        public ExchangeFolder(String name, String treeUri, ImportPolicy importPolicy) {
            this.name = name;
            this.treeUri = treeUri;
            this.importPolicy = importPolicy;
        }
    }

    public static List<ExchangeFolder> getFolders(Context context) {
        final List<ExchangeFolder> folders = new ArrayList<>();
        try {
            final String raw = prefs(context).getString(KEY_FOLDERS, "[]");
            final JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                final JSONObject o = array.getJSONObject(i);
                folders.add(new ExchangeFolder(
                    o.optString("name"),
                    o.optString("treeUri"),
                    "AUTO".equals(o.optString("importPolicy")) ? ImportPolicy.AUTO : ImportPolicy.ASK));
            }
        } catch (JSONException e) {
            LogUtils.e("ExchangeManager: failed to read folders", e);
        }
        return folders;
    }

    private static void saveFolders(Context context, List<ExchangeFolder> folders) {
        try {
            final JSONArray array = new JSONArray();
            for (ExchangeFolder folder : folders) {
                final JSONObject o = new JSONObject();
                o.put("name", folder.name);
                o.put("treeUri", folder.treeUri);
                o.put("importPolicy", folder.importPolicy.name());
                array.put(o);
            }
            prefs(context).edit().putString(KEY_FOLDERS, array.toString()).apply();
        } catch (JSONException e) {
            LogUtils.e("ExchangeManager: failed to save folders", e);
        }
    }

    public static void addFolder(Context context, String name, Uri treeUri, ImportPolicy policy) {
        final List<ExchangeFolder> folders = getFolders(context);
        folders.add(new ExchangeFolder(name, treeUri.toString(), policy));
        saveFolders(context, folders);
    }

    public static void removeFolder(Context context, int index) {
        final List<ExchangeFolder> folders = getFolders(context);
        if (index >= 0 && index < folders.size()) {
            folders.remove(index);
            saveFolders(context, folders);
        }
    }

    // ---------------------------------------------------------------------
    // Moving an alarm out to a folder
    // ---------------------------------------------------------------------

    /**
     * Writes a "move" handoff JSON file for the given alarm into the given folder, then disables
     * the local alarm (it is now considered owned by whoever claims the handoff).
     *
     * @return {@code true} on success.
     */
    public static boolean moveAlarmTo(Context context, Alarm alarm, ExchangeFolder folder) {
        try {
            final DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(folder.treeUri));
            if (root == null || !root.canWrite()) {
                LogUtils.e("ExchangeManager: target folder is not writable");
                return false;
            }

            final DocumentFile handoffsDir = getOrCreateSubDir(root, HANDOFFS_DIR);
            if (handoffsDir == null) {
                LogUtils.e("ExchangeManager: could not create handoffs directory");
                return false;
            }

            final JSONObject handoff = new JSONObject();
            handoff.put("transferMode", "move");
            handoff.put("claimPolicy", "receiver_takes_ownership");
            handoff.put("handoffId", UUID.randomUUID().toString());
            handoff.put("createdAt", System.currentTimeMillis());
            handoff.put("alarm", alarmToJson(alarm));

            final String fileName = "handoff-" + handoff.getString("handoffId") + ".json";
            writeJson(context, handoffsDir, fileName, handoff);

            alarm.enabled = false;
            alarm.updateAlarm(context.getContentResolver());
            AlarmStateManager.deleteAllInstances(context, alarm.id);

            EventLogStore.logEvent(context.getContentResolver(), "ALARM_MOVED_TO_EXCHANGE", alarm.stableUuid, alarm.label,
                "folder=" + folder.name);

            return true;
        } catch (Exception e) {
            LogUtils.e("ExchangeManager: failed to move alarm to folder", e);
            return false;
        }
    }

    private static JSONObject alarmToJson(Alarm alarm) throws JSONException {
        final JSONObject a = new JSONObject();
        a.put("stableUuid", alarm.stableUuid);
        a.put("hour", alarm.hour);
        a.put("minutes", alarm.minutes);
        a.put("daysOfWeekBits", alarm.daysOfWeek.getBits());
        a.put("label", alarm.label);
        a.put("ringtone", alarm.alert == null ? JSONObject.NULL : alarm.alert.toString());
        a.put("vibrate", alarm.vibrate);
        a.put("snoozeDuration", alarm.snoozeDuration);
        return a;
    }

    // ---------------------------------------------------------------------
    // Scanning folders for incoming handoffs
    // ---------------------------------------------------------------------

    /**
     * Scans every configured folder for new handoff files and either imports them automatically
     * or adds them to the inbox, depending on each folder's {@link ImportPolicy}.
     */
    public static void scanAll(Context context) {
        for (ExchangeFolder folder : getFolders(context)) {
            scanFolder(context, folder);
        }
    }

    private static void scanFolder(Context context, ExchangeFolder folder) {
        try {
            final DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(folder.treeUri));
            if (root == null) {
                return;
            }

            final Set<String> processed = getProcessedFileNames(context, folder);
            final List<DocumentFile> candidates = new ArrayList<>();

            final DocumentFile handoffsDir = root.findFile(HANDOFFS_DIR);
            if (handoffsDir != null && handoffsDir.isDirectory()) {
                for (DocumentFile f : handoffsDir.listFiles()) {
                    if (f.getName() != null && f.getName().endsWith(".json")) {
                        candidates.add(f);
                    }
                }
            }

            final DocumentFile exchangeFile = root.findFile(EXCHANGE_FILE);
            if (exchangeFile != null) {
                candidates.add(exchangeFile);
            }

            for (DocumentFile file : candidates) {
                final String name = file.getName();
                if (name == null || processed.contains(name)) {
                    continue;
                }

                final JSONObject json = readJson(context, file);
                if (json == null) {
                    continue;
                }

                handleIncoming(context, folder, name, json);
                processed.add(name);
            }

            saveProcessedFileNames(context, folder, processed);
        } catch (Exception e) {
            LogUtils.e("ExchangeManager: failed to scan folder " + folder.name, e);
        }
    }

    private static void handleIncoming(Context context, ExchangeFolder folder, String fileName, JSONObject handoff) {
        final JSONObject alarmJson = handoff.optJSONObject("alarm");
        if (alarmJson == null) {
            return;
        }

        if (folder.importPolicy == ImportPolicy.AUTO) {
            createAlarmFromJson(context, alarmJson);
            LogUtils.i("ExchangeManager: auto-imported alarm from %s/%s", folder.name, fileName);
        } else {
            addToInbox(context, folder, fileName, alarmJson);
            LogUtils.i("ExchangeManager: queued alarm from %s/%s for user review", folder.name, fileName);
        }
    }

    // ---------------------------------------------------------------------
    // Inbox (for ASK policy folders)
    // ---------------------------------------------------------------------

    public static final class PendingImport {
        public final String id;
        public final String folderName;
        public final String sourceFileName;
        public final String alarmJson;
        public final long receivedAt;

        PendingImport(String id, String folderName, String sourceFileName, String alarmJson, long receivedAt) {
            this.id = id;
            this.folderName = folderName;
            this.sourceFileName = sourceFileName;
            this.alarmJson = alarmJson;
            this.receivedAt = receivedAt;
        }
    }

    private static void addToInbox(Context context, ExchangeFolder folder, String fileName, JSONObject alarmJson) {
        try {
            final JSONArray inbox = new JSONArray(prefs(context).getString(KEY_INBOX, "[]"));
            final JSONObject entry = new JSONObject();
            entry.put("id", UUID.randomUUID().toString());
            entry.put("folderName", folder.name);
            entry.put("sourceFileName", fileName);
            entry.put("alarm", alarmJson);
            entry.put("receivedAt", System.currentTimeMillis());
            inbox.put(entry);
            prefs(context).edit().putString(KEY_INBOX, inbox.toString()).apply();
        } catch (JSONException e) {
            LogUtils.e("ExchangeManager: failed to add to inbox", e);
        }
    }

    public static List<PendingImport> getInbox(Context context) {
        final List<PendingImport> result = new ArrayList<>();
        try {
            final JSONArray inbox = new JSONArray(prefs(context).getString(KEY_INBOX, "[]"));
            for (int i = 0; i < inbox.length(); i++) {
                final JSONObject entry = inbox.getJSONObject(i);
                result.add(new PendingImport(
                    entry.optString("id"),
                    entry.optString("folderName"),
                    entry.optString("sourceFileName"),
                    entry.optJSONObject("alarm") != null ? entry.optJSONObject("alarm").toString() : "{}",
                    entry.optLong("receivedAt")));
            }
        } catch (JSONException e) {
            LogUtils.e("ExchangeManager: failed to read inbox", e);
        }
        return result;
    }

    private static void removeFromInbox(Context context, String id) {
        try {
            final JSONArray inbox = new JSONArray(prefs(context).getString(KEY_INBOX, "[]"));
            final JSONArray updated = new JSONArray();
            for (int i = 0; i < inbox.length(); i++) {
                final JSONObject entry = inbox.getJSONObject(i);
                if (!id.equals(entry.optString("id"))) {
                    updated.put(entry);
                }
            }
            prefs(context).edit().putString(KEY_INBOX, updated.toString()).apply();
        } catch (JSONException e) {
            LogUtils.e("ExchangeManager: failed to update inbox", e);
        }
    }

    /**
     * Accepts a pending import: creates the local alarm and removes the entry from the inbox.
     */
    public static void acceptImport(Context context, PendingImport pending) {
        try {
            createAlarmFromJson(context, new JSONObject(pending.alarmJson));
        } catch (JSONException e) {
            LogUtils.e("ExchangeManager: failed to accept import " + pending.id, e);
        }
        removeFromInbox(context, pending.id);
    }

    /**
     * Rejects a pending import: simply removes the entry from the inbox without creating an alarm.
     */
    public static void rejectImport(Context context, PendingImport pending) {
        removeFromInbox(context, pending.id);
    }

    private static void createAlarmFromJson(Context context, JSONObject alarmJson) {
        try {
            final Alarm alarm = new Alarm();
            alarm.hour = alarmJson.optInt("hour", alarm.hour);
            alarm.minutes = alarmJson.optInt("minutes", alarm.minutes);
            alarm.label = alarmJson.optString("label", "");
            alarm.vibrate = alarmJson.optBoolean("vibrate", alarm.vibrate);
            alarm.snoozeDuration = alarmJson.optInt("snoozeDuration", alarm.snoozeDuration);
            alarm.enabled = true;

            final ContentResolver cr = context.getContentResolver();
            final Alarm saved = alarm.addAlarm(cr);
            final var instance = saved.createInstanceAfter(Calendar.getInstance());
            instance.addInstance(cr);
            AlarmStateManager.registerInstance(context, instance, true);

            EventLogStore.logEvent(cr, "ALARM_IMPORTED_FROM_EXCHANGE", saved.stableUuid, saved.label, null);
        } catch (Exception e) {
            LogUtils.e("ExchangeManager: failed to create alarm from imported JSON", e);
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static Set<String> getProcessedFileNames(Context context, ExchangeFolder folder) {
        final Set<String> result = new HashSet<>();
        try {
            final JSONArray array = new JSONArray(prefs(context).getString(KEY_PROCESSED_PREFIX + folder.name, "[]"));
            for (int i = 0; i < array.length(); i++) {
                result.add(array.getString(i));
            }
        } catch (JSONException ignored) {
            // No processed files yet.
        }
        return result;
    }

    private static void saveProcessedFileNames(Context context, ExchangeFolder folder, Set<String> processed) {
        final JSONArray array = new JSONArray();
        for (String name : processed) {
            array.put(name);
        }
        prefs(context).edit().putString(KEY_PROCESSED_PREFIX + folder.name, array.toString()).apply();
    }

    @Nullable
    private static DocumentFile getOrCreateSubDir(@NonNull DocumentFile parent, String name) {
        final DocumentFile existing = parent.findFile(name);
        if (existing != null && existing.isDirectory()) {
            return existing;
        }
        return parent.createDirectory(name);
    }

    private static void writeJson(Context context, DocumentFile dir, String fileName, JSONObject json)
        throws IOException, JSONException {
        final DocumentFile file = dir.createFile(MIME_JSON, fileName);
        if (file == null) {
            throw new IOException("Could not create file: " + fileName);
        }
        try (OutputStream out = context.getContentResolver().openOutputStream(file.getUri())) {
            if (out == null) {
                throw new IOException("Could not open output stream for: " + fileName);
            }
            out.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    @Nullable
    private static JSONObject readJson(Context context, DocumentFile file) {
        try (InputStream in = context.getContentResolver().openInputStream(file.getUri())) {
            if (in == null) {
                return null;
            }
            final StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            return new JSONObject(sb.toString());
        } catch (IOException | JSONException e) {
            LogUtils.e("ExchangeManager: failed to read " + file.getName(), e);
            return null;
        }
    }
}

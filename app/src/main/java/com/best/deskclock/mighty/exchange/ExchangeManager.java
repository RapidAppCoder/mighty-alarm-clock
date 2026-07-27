// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.exchange;

import static com.best.deskclock.settings.PreferencesDefaultValues.DEFAULT_ALARM_SNOOZE_DURATION;
import static com.best.deskclock.settings.PreferencesDefaultValues.DEFAULT_ALARM_VOLUME;
import static com.best.deskclock.settings.PreferencesDefaultValues.DEFAULT_AUTO_SILENCE_DURATION;
import static com.best.deskclock.settings.PreferencesDefaultValues.DEFAULT_ENABLE_DELETE_OCCASIONAL_ALARM_BY_DEFAULT;
import static com.best.deskclock.settings.PreferencesDefaultValues.DEFAULT_MISSED_ALARM_REPEAT_LIMIT;
import static com.best.deskclock.settings.PreferencesDefaultValues.DEFAULT_VIBRATION_PATTERN;
import static com.best.deskclock.settings.PreferencesDefaultValues.DEFAULT_VOLUME_CRESCENDO_DURATION;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import com.best.deskclock.alarms.AlarmStateManager;
import com.best.deskclock.data.Weekdays;
import com.best.deskclock.mighty.tags.TagColorUtils;
import com.best.deskclock.provider.Alarm;
import com.best.deskclock.provider.EventLogStore;
import com.best.deskclock.provider.Tag;
import com.best.deskclock.utils.LogUtils;
import com.best.deskclock.utils.SdkUtils;

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
import java.util.concurrent.TimeUnit;

/**
 * Manages "exchange" folders: shared folders (typically Syncthing-synced) that other devices also
 * watch, used to move alarms between installs.
 * <p>
 * Each watching install writes a {@code Device-<name>.json} presence file and periodically updates
 * its {@code lastSeenAt}. Moving an alarm writes an {@code AlarmTransfer-<uuid>.json} in the folder
 * root (with sender and optional recipient). {@link ExchangeScanWorker} discovers new transfers and
 * either auto-imports them or places them in an inbox.
 */
public final class ExchangeManager {

    public enum ImportPolicy { ASK, AUTO }

    private static final String PREFS_NAME = "mighty_exchange_prefs";
    private static final String KEY_FOLDERS = "exchange_folders";
    private static final String KEY_INBOX = "exchange_inbox";
    private static final String KEY_PROCESSED_PREFIX = "exchange_processed_";
    private static final String KEY_DEVICE_ID = "exchange_device_id";
    private static final String KEY_DEVICE_NAME = "exchange_device_name";
    private static final String KEY_LAST_PRESENCE_FILE_PREFIX = "exchange_presence_file_";

    private static final String DEVICE_FILE_PREFIX = "Device-";
    private static final String TRANSFER_FILE_PREFIX = "AlarmTransfer-";
    private static final String JSON_SUFFIX = ".json";
    private static final String MIME_JSON = "application/json";

    private static final long DEVICE_STALE_MS = TimeUnit.DAYS.toMillis(7);

    private ExchangeManager() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------------
    // Local device identity
    // ---------------------------------------------------------------------

    public static final class Device {
        public final String deviceId;
        public final String deviceName;
        public final long lastSeenAt;

        public Device(String deviceId, String deviceName, long lastSeenAt) {
            this.deviceId = deviceId;
            this.deviceName = deviceName;
            this.lastSeenAt = lastSeenAt;
        }

        JSONObject toJson() throws JSONException {
            final JSONObject o = new JSONObject();
            o.put("deviceId", deviceId);
            o.put("deviceName", deviceName);
            o.put("lastSeenAt", lastSeenAt);
            return o;
        }

        static Device fromJson(JSONObject o) {
            if (o == null) {
                return null;
            }
            final String id = o.optString("deviceId", "");
            final String name = o.optString("deviceName", "");
            if (TextUtils.isEmpty(id) && TextUtils.isEmpty(name)) {
                return null;
            }
            return new Device(id, name, o.optLong("lastSeenAt", 0L));
        }
    }

    @NonNull
    public static String getDeviceId(Context context) {
        final SharedPreferences p = prefs(context);
        String id = p.getString(KEY_DEVICE_ID, null);
        if (TextUtils.isEmpty(id)) {
            id = UUID.randomUUID().toString();
            p.edit().putString(KEY_DEVICE_ID, id).apply();
        }
        return id;
    }

    @NonNull
    public static String getDeviceName(Context context) {
        final SharedPreferences p = prefs(context);
        String name = p.getString(KEY_DEVICE_NAME, null);
        if (TextUtils.isEmpty(name)) {
            name = resolveDefaultDeviceName(context);
            p.edit().putString(KEY_DEVICE_NAME, name).apply();
        }
        return name;
    }

    public static void setDeviceName(Context context, String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            trimmed = resolveDefaultDeviceName(context);
        }
        prefs(context).edit().putString(KEY_DEVICE_NAME, trimmed).apply();
        refreshPresence(context);
    }

    @NonNull
    private static String resolveDefaultDeviceName(Context context) {
        if (SdkUtils.isAtLeastAndroid71()) {
            try {
                final String global = Settings.Global.getString(context.getContentResolver(),
                    Settings.Global.DEVICE_NAME);
                if (!TextUtils.isEmpty(global)) {
                    return global.trim();
                }
            } catch (Exception ignored) {
                // Fall through.
            }
        }
        try {
            final String bluetooth = Settings.Secure.getString(context.getContentResolver(), "bluetooth_name");
            if (!TextUtils.isEmpty(bluetooth)) {
                return bluetooth.trim();
            }
        } catch (Exception ignored) {
            // Fall through.
        }
        final String model = Build.MODEL;
        return TextUtils.isEmpty(model) ? "Device" : model;
    }

    @NonNull
    public static String sanitizeFileNamePart(String name) {
        if (TextUtils.isEmpty(name)) {
            return "Device";
        }
        final String sanitized = name.trim()
            .replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]", "_")
            .replaceAll("\\s+", " ")
            .trim();
        if (sanitized.isEmpty() || sanitized.equals(".") || sanitized.equals("..")) {
            return "Device";
        }
        // Keep filenames reasonably short for SAF providers.
        return sanitized.length() > 80 ? sanitized.substring(0, 80).trim() : sanitized;
    }

    @NonNull
    private static String deviceFileNameFor(String deviceName) {
        return DEVICE_FILE_PREFIX + sanitizeFileNamePart(deviceName) + JSON_SUFFIX;
    }

    @NonNull
    private static Device localDevice(Context context) {
        return new Device(getDeviceId(context), getDeviceName(context), System.currentTimeMillis());
    }

    private static boolean isSameDevice(@Nullable Device a, @NonNull Device b) {
        if (a == null) {
            return false;
        }
        if (!TextUtils.isEmpty(a.deviceId) && a.deviceId.equals(b.deviceId)) {
            return true;
        }
        return !TextUtils.isEmpty(a.deviceName) && a.deviceName.equals(b.deviceName);
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
        refreshPresence(context);
    }

    public static void removeFolder(Context context, int index) {
        final List<ExchangeFolder> folders = getFolders(context);
        if (index >= 0 && index < folders.size()) {
            final ExchangeFolder removed = folders.remove(index);
            saveFolders(context, folders);
            prefs(context).edit()
                .remove(KEY_LAST_PRESENCE_FILE_PREFIX + removed.name)
                .remove(KEY_PROCESSED_PREFIX + removed.name)
                .apply();
            if (folders.isEmpty()) {
                ExchangeScanWorker.cancel(context);
            }
        }
    }

    /**
     * Updates the import policy for the folder at {@code index}.
     */
    public static void setFolderImportPolicy(Context context, int index, ImportPolicy policy) {
        final List<ExchangeFolder> folders = getFolders(context);
        if (index < 0 || index >= folders.size() || policy == null) {
            return;
        }
        final ExchangeFolder existing = folders.get(index);
        folders.set(index, new ExchangeFolder(existing.name, existing.treeUri, policy));
        saveFolders(context, folders);
    }

    // ---------------------------------------------------------------------
    // Presence (Device-*.json)
    // ---------------------------------------------------------------------

    /**
     * Writes or refreshes this install's {@code Device-<name>.json} in every configured folder.
     */
    public static void refreshPresence(Context context) {
        final Device local = localDevice(context);
        for (ExchangeFolder folder : getFolders(context)) {
            refreshPresenceInFolder(context, folder, local);
        }
    }

    private static void refreshPresenceInFolder(Context context, ExchangeFolder folder, Device local) {
        try {
            final DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(folder.treeUri));
            if (root == null || !root.canWrite()) {
                LogUtils.e("ExchangeManager: cannot write presence to folder " + folder.name);
                return;
            }

            final String fileName = deviceFileNameFor(local.deviceName);
            final String lastFileName = prefs(context).getString(KEY_LAST_PRESENCE_FILE_PREFIX + folder.name, null);
            if (lastFileName != null && !lastFileName.equals(fileName)) {
                final DocumentFile old = root.findFile(lastFileName);
                if (old != null && old.isFile()) {
                    //noinspection ResultOfMethodCallIgnored
                    old.delete();
                }
            }

            writeOrReplaceJson(context, root, fileName, local.toJson());
            prefs(context).edit().putString(KEY_LAST_PRESENCE_FILE_PREFIX + folder.name, fileName).apply();
        } catch (Exception e) {
            LogUtils.e("ExchangeManager: failed to refresh presence in " + folder.name, e);
        }
    }

    /**
     * Lists other devices that recently wrote a presence file into the given folder.
     */
    @NonNull
    public static List<Device> listDevices(Context context, ExchangeFolder folder) {
        final List<Device> result = new ArrayList<>();
        try {
            final DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(folder.treeUri));
            if (root == null) {
                return result;
            }

            final Device local = localDevice(context);
            final long now = System.currentTimeMillis();

            for (DocumentFile file : root.listFiles()) {
                final String name = file.getName();
                if (name == null || !name.startsWith(DEVICE_FILE_PREFIX) || !name.endsWith(JSON_SUFFIX)) {
                    continue;
                }
                final JSONObject json = readJson(context, file);
                final Device device = Device.fromJson(json);
                if (device == null || isSameDevice(device, local)) {
                    continue;
                }
                if (device.lastSeenAt > 0 && now - device.lastSeenAt > DEVICE_STALE_MS) {
                    continue;
                }
                result.add(device);
            }
        } catch (Exception e) {
            LogUtils.e("ExchangeManager: failed to list devices in " + folder.name, e);
        }
        return result;
    }

    // ---------------------------------------------------------------------
    // Moving an alarm out to a folder
    // ---------------------------------------------------------------------

    /**
     * Writes an {@code AlarmTransfer-*.json} for the given alarm into the folder root, then disables
     * the local alarm.
     *
     * @param target optional recipient; {@code null} means any watching device may claim it
     * @return {@code true} on success
     */
    public static boolean moveAlarmTo(Context context, Alarm alarm, ExchangeFolder folder,
                                      @Nullable Device target) {
        try {
            final DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(folder.treeUri));
            if (root == null || !root.canWrite()) {
                LogUtils.e("ExchangeManager: target folder is not writable");
                return false;
            }

            refreshPresenceInFolder(context, folder, localDevice(context));

            final String transferId = UUID.randomUUID().toString();
            final Device from = localDevice(context);

            final JSONObject transfer = new JSONObject();
            transfer.put("transferMode", "move");
            transfer.put("claimPolicy", "receiver_takes_ownership");
            transfer.put("transferId", transferId);
            transfer.put("createdAt", System.currentTimeMillis());
            transfer.put("from", from.toJson());
            if (target != null) {
                transfer.put("to", new Device(target.deviceId, target.deviceName, 0L).toJson());
            }
            transfer.put("alarm", alarmToJson(alarm));

            final String fileName = TRANSFER_FILE_PREFIX + transferId + JSON_SUFFIX;
            writeOrReplaceJson(context, root, fileName, transfer);

            alarm.enabled = false;
            alarm.updateAlarm(context.getContentResolver());
            AlarmStateManager.deleteAllInstances(context, alarm.id);

            final String detail = target == null
                ? "folder=" + folder.name
                : "folder=" + folder.name + ",to=" + target.deviceName;
            EventLogStore.logEvent(context.getContentResolver(), "ALARM_MOVED_TO_EXCHANGE", alarm.stableUuid,
                alarm.label, detail);

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
    // Scanning folders for incoming transfers
    // ---------------------------------------------------------------------

    /**
     * Refreshes presence, then scans every configured folder for new {@code AlarmTransfer-*.json}
     * files.
     */
    public static void scanAll(Context context) {
        SyncthingNudge.nudgeIfEnabled(context);
        refreshPresence(context);
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

            final Device local = localDevice(context);
            final Set<String> processed = getProcessedFileNames(context, folder);

            for (DocumentFile file : root.listFiles()) {
                final String name = file.getName();
                if (name == null || !name.startsWith(TRANSFER_FILE_PREFIX) || !name.endsWith(JSON_SUFFIX)) {
                    continue;
                }
                if (processed.contains(name)) {
                    continue;
                }

                final JSONObject json = readJson(context, file);
                if (json == null) {
                    continue;
                }

                final Device from = Device.fromJson(json.optJSONObject("from"));
                if (isSameDevice(from, local)) {
                    // Own outbound transfer — mark processed so we don't keep re-reading it.
                    processed.add(name);
                    continue;
                }

                final Device to = Device.fromJson(json.optJSONObject("to"));
                if (to != null && !isSameDevice(to, local)) {
                    // Addressed to someone else — leave unprocessed so a rename can still claim it.
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

    private static void handleIncoming(Context context, ExchangeFolder folder, String fileName,
                                       JSONObject transfer) {
        final JSONObject alarmJson = transfer.optJSONObject("alarm");
        if (alarmJson == null) {
            return;
        }

        final Device from = Device.fromJson(transfer.optJSONObject("from"));
        final Device to = Device.fromJson(transfer.optJSONObject("to"));

        if (folder.importPolicy == ImportPolicy.AUTO) {
            final Alarm imported = createAlarmFromJson(context, alarmJson);
            if (imported != null) {
                applyFolderTag(context, imported, folder.name);
            }
            deleteTransferFile(context, folder, fileName);
            LogUtils.i("ExchangeManager: auto-imported alarm from %s/%s", folder.name, fileName);
        } else {
            addToInbox(context, folder, fileName, alarmJson, from, to);
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
        @Nullable
        public final String fromDeviceName;
        @Nullable
        public final String toDeviceName;

        PendingImport(String id, String folderName, String sourceFileName, String alarmJson,
                      long receivedAt, @Nullable String fromDeviceName, @Nullable String toDeviceName) {
            this.id = id;
            this.folderName = folderName;
            this.sourceFileName = sourceFileName;
            this.alarmJson = alarmJson;
            this.receivedAt = receivedAt;
            this.fromDeviceName = fromDeviceName;
            this.toDeviceName = toDeviceName;
        }
    }

    private static void addToInbox(Context context, ExchangeFolder folder, String fileName,
                                   JSONObject alarmJson, @Nullable Device from, @Nullable Device to) {
        try {
            final JSONArray inbox = new JSONArray(prefs(context).getString(KEY_INBOX, "[]"));
            final JSONObject entry = new JSONObject();
            entry.put("id", UUID.randomUUID().toString());
            entry.put("folderName", folder.name);
            entry.put("sourceFileName", fileName);
            entry.put("alarm", alarmJson);
            entry.put("receivedAt", System.currentTimeMillis());
            if (from != null) {
                entry.put("fromDeviceName", from.deviceName);
            }
            if (to != null) {
                entry.put("toDeviceName", to.deviceName);
            }
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
                    entry.optLong("receivedAt"),
                    entry.has("fromDeviceName") ? entry.optString("fromDeviceName") : null,
                    entry.has("toDeviceName") ? entry.optString("toDeviceName") : null));
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
     * Accepts a pending import: creates the local alarm, deletes the transfer file, and removes the
     * inbox entry.
     */
    public static void acceptImport(Context context, PendingImport pending) {
        try {
            final Alarm imported = createAlarmFromJson(context, new JSONObject(pending.alarmJson));
            if (imported != null) {
                applyFolderTag(context, imported, pending.folderName);
            }
        } catch (JSONException e) {
            LogUtils.e("ExchangeManager: failed to accept import " + pending.id, e);
        }

        ExchangeFolder folder = findFolderByName(context, pending.folderName);
        if (folder != null) {
            deleteTransferFile(context, folder, pending.sourceFileName);
            final Set<String> processed = getProcessedFileNames(context, folder);
            processed.add(pending.sourceFileName);
            saveProcessedFileNames(context, folder, processed);
        }

        removeFromInbox(context, pending.id);
    }

    /**
     * Rejects a pending import: removes the inbox entry without creating an alarm or deleting the
     * transfer file (so another device may still claim an unaddressed transfer).
     */
    public static void rejectImport(Context context, PendingImport pending) {
        removeFromInbox(context, pending.id);
    }

    @Nullable
    private static ExchangeFolder findFolderByName(Context context, String name) {
        for (ExchangeFolder folder : getFolders(context)) {
            if (folder.name.equals(name)) {
                return folder;
            }
        }
        return null;
    }

    private static void deleteTransferFile(Context context, ExchangeFolder folder, String fileName) {
        try {
            final DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(folder.treeUri));
            if (root == null) {
                return;
            }
            final DocumentFile file = root.findFile(fileName);
            if (file != null && file.isFile()) {
                if (!file.delete()) {
                    LogUtils.w("ExchangeManager: failed to delete transfer file " + fileName);
                }
            }
        } catch (Exception e) {
            LogUtils.e("ExchangeManager: failed to delete transfer file " + fileName, e);
        }
    }

    @Nullable
    private static Alarm createAlarmFromJson(Context context, JSONObject alarmJson) {
        try {
            // Avoid Alarm()'s default constructor: it reads ringtone settings via DataModel, which
            // may only be called on the main thread. Import/scan often run on a background executor.
            final Calendar now = Calendar.getInstance();
            final int hour = alarmJson.optInt("hour", 0);
            final int minutes = alarmJson.optInt("minutes", 0);
            final String label = alarmJson.optString("label", "");
            final boolean vibrate = alarmJson.optBoolean("vibrate", true);
            final int snoozeDuration = alarmJson.optInt("snoozeDuration", DEFAULT_ALARM_SNOOZE_DURATION);
            final Weekdays daysOfWeek = Weekdays.fromBits(alarmJson.optInt("daysOfWeekBits", 0));

            String alert = alarmJson.isNull("ringtone") ? null : alarmJson.optString("ringtone", null);
            if (TextUtils.isEmpty(alert)) {
                final Uri defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                alert = defaultUri != null ? defaultUri.toString() : Uri.EMPTY.toString();
            }

            final Alarm alarm = new Alarm(
                Alarm.INVALID_ID,
                true,
                now.get(Calendar.YEAR),
                now.get(Calendar.MONTH),
                now.get(Calendar.DAY_OF_MONTH),
                hour,
                minutes,
                vibrate,
                DEFAULT_VIBRATION_PATTERN,
                true,
                daysOfWeek,
                label,
                false,
                alert,
                DEFAULT_ENABLE_DELETE_OCCASIONAL_ALARM_BY_DEFAULT,
                DEFAULT_AUTO_SILENCE_DURATION,
                snoozeDuration,
                Integer.parseInt(DEFAULT_MISSED_ALARM_REPEAT_LIMIT),
                DEFAULT_VOLUME_CRESCENDO_DURATION,
                DEFAULT_ALARM_VOLUME,
                0,
                0L,
                0L);

            final ContentResolver cr = context.getContentResolver();
            final Alarm saved = alarm.addAlarm(cr);
            final var instance = saved.createInstanceAfter(Calendar.getInstance());
            instance.addInstance(cr);
            AlarmStateManager.registerInstance(context, instance, true);

            EventLogStore.logEvent(cr, "ALARM_IMPORTED_FROM_EXCHANGE", saved.stableUuid, saved.label, null);
            return saved;
        } catch (Exception e) {
            LogUtils.e("ExchangeManager: failed to create alarm from imported JSON", e);
            return null;
        }
    }

    /**
     * Ensures a tag named like the exchange folder exists, then assigns it to the alarm.
     */
    private static void applyFolderTag(Context context, Alarm alarm, String folderName) {
        if (alarm == null || alarm.id == Alarm.INVALID_ID || TextUtils.isEmpty(folderName)) {
            return;
        }
        final Tag tag = ensureFolderTag(context, folderName.trim());
        if (tag == null || tag.id == Tag.INVALID_ID) {
            return;
        }
        Tag.addTagToAlarm(context.getContentResolver(), alarm.id, tag.id);
    }

    @Nullable
    private static Tag ensureFolderTag(Context context, String folderName) {
        final ContentResolver cr = context.getContentResolver();
        for (Tag existing : Tag.getTags(cr)) {
            if (folderName.equals(existing.name)) {
                return existing;
            }
        }
        final Tag tag = new Tag(folderName, TagColorUtils.nextAutoColor(cr));
        return tag.addTag(cr);
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

    private static void writeOrReplaceJson(Context context, DocumentFile dir, String fileName, JSONObject json)
        throws IOException, JSONException {
        final DocumentFile existing = dir.findFile(fileName);
        if (existing != null && existing.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            existing.delete();
        }
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

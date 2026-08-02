// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.settings;

import static com.best.deskclock.settings.PreferencesKeys.*;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.best.deskclock.R;
import com.best.deskclock.base.AppExecutors;
import com.best.deskclock.data.SettingsDAO;
import com.best.deskclock.mighty.backup.BackupWorker;
import com.best.deskclock.mighty.backup.JsonBackupManager;
import com.best.deskclock.mighty.exchange.ExchangeManager;
import com.best.deskclock.mighty.exchange.ExchangeScanWorker;
import com.best.deskclock.mighty.exchange.SyncthingNudge;
import com.best.deskclock.mighty.mute.GlobalMuteController;
import com.best.deskclock.mighty.stats.DeviceStats;
import com.best.deskclock.mighty.tags.TagColorUtils;
import com.best.deskclock.mighty.wifi.WifiAlarmRuleManager;
import com.best.deskclock.provider.EventLogStore;
import com.best.deskclock.provider.Tag;
import com.best.deskclock.uicomponents.toast.CustomToast;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.rarepebble.colorpicker.ColorPickerView;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Settings screen exposing the "Mighty" fork features that don't fit naturally into the existing
 * settings screens: device statistics, global mute, Wi-Fi rules, JSON backup and alarm exchange,
 * and the clock timezone globe toggle.
 */
public class MightyFeaturesFragment extends ScreenFragment
    implements Preference.OnPreferenceClickListener, Preference.OnPreferenceChangeListener {

    public static final String ARG_OPEN_EXCHANGE_INBOX = "arg_open_exchange_inbox";

    Preference mDeviceStatsPref;
    Preference mViewEventLogPref;
    Preference mExportEventLogPref;
    Preference mGlobalMutePref;
    Preference mWifiReevaluatePref;
    Preference mBackupFolderPref;
    SwitchPreferenceCompat mBackupScheduleEnabledPref;
    ListPreference mBackupIntervalPref;
    Preference mBackupNowPref;
    Preference mExchangeDeviceNamePref;
    Preference mExchangeAddFolderPref;
    Preference mExchangeFoldersPref;
    Preference mExchangeScanNowPref;
    Preference mExchangeInboxPref;
    Preference mManageTagsPref;
    SwitchPreferenceCompat mDisplayTimezoneGlobePref;

    private final ActivityResultLauncher<Intent> mBackupFolderPicker = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                return;
            }
            final Uri treeUri = result.getData().getData();
            if (treeUri == null) {
                return;
            }
            requireContext().getContentResolver().takePersistableUriPermission(treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            JsonBackupManager.setTreeUri(requireContext(), treeUri);
            refreshSummaries();
        });

    private final ActivityResultLauncher<Intent> mExchangeFolderPicker = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                return;
            }
            final Uri treeUri = result.getData().getData();
            if (treeUri == null) {
                return;
            }
            requireContext().getContentResolver().takePersistableUriPermission(treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            showAddExchangeFolderNameDialog(treeUri);
        });

    // Reading the currently connected Wi-Fi SSID (used by the per-alarm Wi-Fi rules feature)
    // requires a location permission on modern Android versions. This is requested lazily, the
    // first time the user interacts with a Wi-Fi related preference on this screen, rather than
    // unconditionally at app startup.
    private final ActivityResultLauncher<String[]> mWifiLocationPermissionLauncher = registerForActivityResult(
        new ActivityResultContracts.RequestMultiplePermissions(), result -> {
            // No explicit handling needed here: if permission is denied, WifiAlarmRuleManager
            // will simply be unable to read the current SSID and Wi-Fi rules won't trigger.
        });

    @Override
    protected String getFragmentTitle() {
        return getString(R.string.mighty_features_title);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.settings_mighty_features);

        mDeviceStatsPref = findPreference(KEY_MIGHTY_DEVICE_STATS);
        mViewEventLogPref = findPreference(KEY_MIGHTY_VIEW_EVENT_LOG);
        mExportEventLogPref = findPreference(KEY_MIGHTY_EXPORT_EVENT_LOG);
        mGlobalMutePref = findPreference(KEY_MIGHTY_GLOBAL_MUTE);
        mWifiReevaluatePref = findPreference(KEY_MIGHTY_WIFI_REEVALUATE);
        mBackupFolderPref = findPreference(KEY_MIGHTY_BACKUP_FOLDER);
        mBackupScheduleEnabledPref = findPreference(KEY_MIGHTY_BACKUP_SCHEDULE_ENABLED);
        mBackupIntervalPref = findPreference(KEY_MIGHTY_BACKUP_INTERVAL_HOURS);
        mBackupNowPref = findPreference(KEY_MIGHTY_BACKUP_NOW);
        mExchangeDeviceNamePref = findPreference(KEY_MIGHTY_EXCHANGE_DEVICE_NAME);
        mExchangeAddFolderPref = findPreference(KEY_MIGHTY_EXCHANGE_ADD_FOLDER);
        mExchangeFoldersPref = findPreference(KEY_MIGHTY_EXCHANGE_FOLDERS);
        mExchangeScanNowPref = findPreference(KEY_MIGHTY_EXCHANGE_SCAN_NOW);
        mExchangeInboxPref = findPreference(KEY_MIGHTY_EXCHANGE_INBOX);
        mManageTagsPref = findPreference(KEY_MIGHTY_MANAGE_TAGS);
        mDisplayTimezoneGlobePref = findPreference(KEY_DISPLAY_TIMEZONE_GLOBE);

        setupPreferences();
    }

    @Override
    public void onResume() {
        super.onResume();

        refreshSummaries();
        maybeOpenExchangeInboxFromArgs();
    }

    private void maybeOpenExchangeInboxFromArgs() {
        final Bundle args = getArguments();
        if (args == null || !args.getBoolean(ARG_OPEN_EXCHANGE_INBOX, false)) {
            return;
        }
        args.putBoolean(ARG_OPEN_EXCHANGE_INBOX, false);
        // Defer until the fragment view is ready and preference screen is shown.
        if (getView() != null) {
            getView().post(this::showExchangeInboxDialog);
        } else {
            showExchangeInboxDialog();
        }
    }

    @Override
    public void onDestroy() {
        nullifyPreferenceListeners(mDeviceStatsPref, mViewEventLogPref, mExportEventLogPref, mGlobalMutePref, mWifiReevaluatePref, mBackupFolderPref,
            mBackupScheduleEnabledPref, mBackupIntervalPref, mBackupNowPref, mExchangeDeviceNamePref, mExchangeAddFolderPref,
            mExchangeFoldersPref, mExchangeScanNowPref, mExchangeInboxPref, mManageTagsPref, mDisplayTimezoneGlobePref);

        nullifyAllPrefs();

        super.onDestroy();
    }

    private void setupPreferences() {
        mDeviceStatsPref.setOnPreferenceClickListener(this);
        mViewEventLogPref.setOnPreferenceClickListener(this);
        mExportEventLogPref.setOnPreferenceClickListener(this);
        mGlobalMutePref.setOnPreferenceClickListener(this);
        mWifiReevaluatePref.setOnPreferenceClickListener(this);
        mBackupFolderPref.setOnPreferenceClickListener(this);
        mBackupScheduleEnabledPref.setOnPreferenceChangeListener(this);
        mBackupIntervalPref.setOnPreferenceChangeListener(this);
        mBackupNowPref.setOnPreferenceClickListener(this);
        mExchangeDeviceNamePref.setOnPreferenceClickListener(this);
        mExchangeAddFolderPref.setOnPreferenceClickListener(this);
        mExchangeFoldersPref.setOnPreferenceClickListener(this);
        mExchangeScanNowPref.setOnPreferenceClickListener(this);
        mExchangeInboxPref.setOnPreferenceClickListener(this);
        mManageTagsPref.setOnPreferenceClickListener(this);
        mDisplayTimezoneGlobePref.setOnPreferenceChangeListener(this);

        refreshSummaries();
    }

    private void refreshSummaries() {
        if (!isAdded()) {
            return;
        }
        final Context context = requireContext();

        mDeviceStatsPref.setSummary(getString(R.string.mighty_device_stats_summary,
            DeviceStats.getTotalRingCount(context), DeviceStats.getTotalSnoozeCount(context)));

        updateGlobalMuteSummary();

        final Uri backupUri = JsonBackupManager.getTreeUri(context);
        mBackupFolderPref.setSummary(backupUri != null ? backupUri.toString() : getString(R.string.mighty_backup_folder_not_set));
        mBackupScheduleEnabledPref.setChecked(JsonBackupManager.isScheduleEnabled(context));
        mBackupIntervalPref.setValue(String.valueOf(JsonBackupManager.getIntervalHours(context)));
        mBackupIntervalPref.setSummary(mBackupIntervalPref.getEntry());

        mExchangeDeviceNamePref.setSummary(ExchangeManager.getDeviceName(context));

        final List<ExchangeManager.ExchangeFolder> folders = ExchangeManager.getFolders(context);
        mExchangeFoldersPref.setSummary(getString(R.string.mighty_exchange_folders_summary, folders.size()));

        final List<ExchangeManager.PendingImport> inbox = ExchangeManager.getInbox(context);
        mExchangeInboxPref.setSummary(getString(R.string.mighty_exchange_inbox_summary, inbox.size()));
    }

    private void updateGlobalMuteSummary() {
        final Context context = requireContext();
        if (GlobalMuteController.isActive(context)) {
            final CharSequence time = DateFormat.getTimeFormat(context).format(new Date(GlobalMuteController.getEndTime(context)));
            mGlobalMutePref.setSummary(getString(R.string.mighty_global_mute_active_summary, time));
        } else {
            mGlobalMutePref.setSummary(getString(R.string.mighty_global_mute_inactive_summary));
        }
    }

    @Override
    public boolean onPreferenceClick(@NonNull Preference pref) {
        final Context context = requireContext();

        switch (pref.getKey()) {
            case KEY_MIGHTY_DEVICE_STATS -> showDeviceStatsDialog();

            case KEY_MIGHTY_VIEW_EVENT_LOG -> showEventLogDialog();

            case KEY_MIGHTY_EXPORT_EVENT_LOG -> exportEventLog();

            case KEY_MIGHTY_GLOBAL_MUTE -> showGlobalMuteDialog();

            case KEY_MIGHTY_WIFI_REEVALUATE -> {
                requestWifiLocationPermissionIfNeeded();
                final Context appContext = context.getApplicationContext();
                AppExecutors.getDiskIO().execute(() -> WifiAlarmRuleManager.evaluateRules(appContext));
                CustomToast.show(context, R.string.mighty_wifi_reevaluate_toast);
            }

            case KEY_MIGHTY_BACKUP_FOLDER -> mBackupFolderPicker.launch(createOpenTreeIntent());

            case KEY_MIGHTY_BACKUP_NOW -> {
                final Context appContext = context.getApplicationContext();
                AppExecutors.getDiskIO().execute(() -> {
                    final boolean success = JsonBackupManager.exportNow(appContext);
                    AppExecutors.getMainThread().post(() ->
                        CustomToast.show(context, success ? R.string.mighty_backup_now_success : R.string.mighty_backup_now_failure));
                });
            }

            case KEY_MIGHTY_EXCHANGE_DEVICE_NAME -> showExchangeDeviceNameDialog();

            case KEY_MIGHTY_EXCHANGE_ADD_FOLDER -> mExchangeFolderPicker.launch(createOpenTreeIntent());

            case KEY_MIGHTY_EXCHANGE_FOLDERS -> showManageExchangeFoldersDialog();

            case KEY_MIGHTY_EXCHANGE_SCAN_NOW -> {
                final Context appContext = context.getApplicationContext();
                AppExecutors.getDiskIO().execute(() -> {
                    ExchangeManager.scanAll(appContext);
                    final int inboxCount = ExchangeManager.getInbox(appContext).size();
                    AppExecutors.getMainThread().post(() -> {
                        if (!isAdded()) {
                            return;
                        }
                        refreshSummaries();
                        if (inboxCount > 0) {
                            CustomToast.show(requireContext(),
                                getString(R.string.mighty_exchange_inbox_after_scan, inboxCount));
                        }
                    });
                });
            }

            case KEY_MIGHTY_EXCHANGE_INBOX -> showExchangeInboxDialog();

            case KEY_MIGHTY_MANAGE_TAGS -> showManageTagsDialog();
        }

        return true;
    }

    @Override
    public boolean onPreferenceChange(@NonNull Preference pref, Object newValue) {
        final Context context = requireContext();

        switch (pref.getKey()) {
            case KEY_MIGHTY_BACKUP_SCHEDULE_ENABLED -> {
                final boolean enabled = (boolean) newValue;
                JsonBackupManager.setScheduleEnabled(context, enabled);
                if (enabled) {
                    BackupWorker.schedulePeriodic(context, JsonBackupManager.getIntervalHours(context));
                } else {
                    BackupWorker.cancel(context);
                }
            }

            case KEY_MIGHTY_BACKUP_INTERVAL_HOURS -> {
                final int hours = Integer.parseInt((String) newValue);
                JsonBackupManager.setIntervalHours(context, hours);
                if (JsonBackupManager.isScheduleEnabled(context)) {
                    BackupWorker.schedulePeriodic(context, hours);
                }
                final int index = mBackupIntervalPref.findIndexOfValue((String) newValue);
                if (index >= 0) {
                    mBackupIntervalPref.setSummary(mBackupIntervalPref.getEntries()[index]);
                }
            }
        }

        return true;
    }

    /**
     * Requests {@link Manifest.permission#ACCESS_FINE_LOCATION} / {@link Manifest.permission#ACCESS_COARSE_LOCATION}
     * if neither is currently granted. Reading the connected Wi-Fi SSID (needed by the per-alarm
     * Wi-Fi rules feature) requires one of these permissions on modern Android versions.
     */
    private void requestWifiLocationPermissionIfNeeded() {
        final Context context = requireContext();

        final boolean hasFineLocation =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        final boolean hasCoarseLocation =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (!hasFineLocation && !hasCoarseLocation) {
            mWifiLocationPermissionLauncher.launch(new String[] {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            });
        }
    }

    private Intent createOpenTreeIntent() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    }

    private void showDeviceStatsDialog() {
        final Context context = requireContext();
        final String message = getString(R.string.mighty_device_stats_dialog_message,
            DeviceStats.getTotalRingCount(context), DeviceStats.getTotalSnoozeCount(context));

        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.mighty_device_stats_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(R.string.mighty_reset_stats, (dialog, which) -> {
                DeviceStats.reset(context);
                refreshSummaries();
            })
            .show();
    }

    private void showEventLogDialog() {
        final Context context = requireContext();
        final Context appContext = context.getApplicationContext();

        AppExecutors.getDiskIO().execute(() -> {
            final List<EventLogStore.EventLogEntry> events =
                EventLogStore.getEvents(appContext.getContentResolver());

            AppExecutors.getMainThread().post(() -> {
                if (!isAdded()) {
                    return;
                }

                if (events.isEmpty()) {
                    new MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.mighty_view_event_log)
                        .setMessage(R.string.mighty_event_log_empty)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                    return;
                }

                final java.text.DateFormat dateFormat =
                    android.text.format.DateFormat.getMediumDateFormat(context);
                final java.text.DateFormat timeFormat =
                    android.text.format.DateFormat.getTimeFormat(context);
                final CharSequence[] items = new CharSequence[events.size()];

                for (int i = 0; i < events.size(); i++) {
                    final EventLogStore.EventLogEntry entry = events.get(i);
                    final Date date = new Date(entry.timestamp);
                    final String when = dateFormat.format(date) + " " + timeFormat.format(date);
                    final String label = entry.alarmLabel == null || entry.alarmLabel.isEmpty()
                        ? getString(R.string.mighty_event_log_untitled_alarm)
                        : entry.alarmLabel;
                    final String type = formatEventType(entry.eventType);
                    final String details = entry.details;

                    if (details == null || details.isEmpty()) {
                        items[i] = getString(R.string.mighty_event_log_entry_no_details, when, type, label);
                    } else {
                        items[i] = getString(R.string.mighty_event_log_entry, when, type, label, details);
                    }
                }

                new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.mighty_view_event_log)
                    .setItems(items, null)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(R.string.mighty_export_event_log, (dialog, which) -> exportEventLog())
                    .show();
            });
        });
    }

    private String formatEventType(String eventType) {
        if (eventType == null) {
            return "";
        }
        if (EventLogStore.EVENT_STOPWATCH_STOPPED.equals(eventType)) {
            return getString(R.string.mighty_event_log_stopwatch_stopped);
        }
        return eventType;
    }

    private void exportEventLog() {
        final Context context = requireContext();
        final Context appContext = context.getApplicationContext();

        AppExecutors.getDiskIO().execute(() -> {
            final String csv = EventLogStore.exportCsv(appContext.getContentResolver());

            AppExecutors.getMainThread().post(() -> {
                if (!isAdded()) {
                    return;
                }
                final Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_SUBJECT, "AlarmClock Deluxe (Wecker, Uhr, Timer, Stoppuhr) event log");
                shareIntent.putExtra(Intent.EXTRA_TEXT, csv);
                startActivity(Intent.createChooser(shareIntent, getString(R.string.mighty_export_event_log)));
            });
        });
    }

    private void showGlobalMuteDialog() {
        final Context context = requireContext();

        final CharSequence[] options = {
            getString(R.string.mighty_mute_1h_silent),
            getString(R.string.mighty_mute_1h_vibrate),
            getString(R.string.mighty_mute_2h_silent),
            getString(R.string.mighty_mute_2h_vibrate),
            getString(R.string.mighty_mute_until_tomorrow_silent),
            getString(R.string.mighty_mute_until_tomorrow_vibrate),
            getString(R.string.mighty_mute_disable),
        };

        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.mighty_global_mute_title)
            .setItems(options, (dialog, which) -> {
                switch (which) {
                    case 0 -> GlobalMuteController.muteForOneHour(context, GlobalMuteController.Mode.SILENT);
                    case 1 -> GlobalMuteController.muteForOneHour(context, GlobalMuteController.Mode.VIBRATE);
                    case 2 -> GlobalMuteController.muteForTwoHours(context, GlobalMuteController.Mode.SILENT);
                    case 3 -> GlobalMuteController.muteForTwoHours(context, GlobalMuteController.Mode.VIBRATE);
                    case 4 -> GlobalMuteController.muteUntilTomorrowMorning(context, GlobalMuteController.Mode.SILENT);
                    case 5 -> GlobalMuteController.muteUntilTomorrowMorning(context, GlobalMuteController.Mode.VIBRATE);
                    case 6 -> GlobalMuteController.disable(context);
                    default -> {
                        // No-op.
                    }
                }
                updateGlobalMuteSummary();
            })
            .show();
    }

    private void showExchangeDeviceNameDialog() {
        final Context context = requireContext();
        final EditText input = new EditText(context);
        input.setHint(R.string.mighty_exchange_device_name_hint);
        input.setText(ExchangeManager.getDeviceName(context));
        input.setSelection(input.getText() != null ? input.getText().length() : 0);

        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.mighty_exchange_device_name_dialog_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                final String name = input.getText() != null ? input.getText().toString().trim() : "";
                final Context appContext = context.getApplicationContext();
                AppExecutors.getDiskIO().execute(() -> {
                    ExchangeManager.setDeviceName(appContext, name);
                    AppExecutors.getMainThread().post(this::refreshSummaries);
                });
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showAddExchangeFolderNameDialog(Uri treeUri) {
        final Context context = requireContext();
        final EditText input = new EditText(context);
        input.setHint(R.string.mighty_exchange_folder_name_hint);
        final String suggestedName = resolveTreeDisplayName(context, treeUri);
        if (suggestedName != null) {
            input.setText(suggestedName);
            input.setSelection(0, suggestedName.length());
        }

        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.mighty_exchange_add_folder_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                String name = input.getText() != null ? input.getText().toString().trim() : "";
                if (name.isEmpty()) {
                    name = suggestedName != null ? suggestedName : treeUri.toString();
                }
                showAddExchangeFolderPolicyDialog(treeUri, name);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    @Nullable
    private static String resolveTreeDisplayName(Context context, Uri treeUri) {
        try {
            final DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);
            if (root != null) {
                final String name = root.getName();
                if (!TextUtils.isEmpty(name)) {
                    final String trimmed = name.trim();
                    if (!trimmed.isEmpty()) {
                        return trimmed;
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall through to URI last segment.
        }
        final String last = treeUri.getLastPathSegment();
        if (TextUtils.isEmpty(last)) {
            return null;
        }
        // Tree URIs often look like "primary:FolderName" or ".../FolderName".
        final int colon = last.lastIndexOf(':');
        final String candidate = colon >= 0 && colon + 1 < last.length()
            ? last.substring(colon + 1)
            : last;
        final int slash = candidate.lastIndexOf('/');
        final String name = slash >= 0 && slash + 1 < candidate.length()
            ? candidate.substring(slash + 1)
            : candidate;
        final String trimmed = name.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void showAddExchangeFolderPolicyDialog(Uri treeUri, String folderName) {
        final Context context = requireContext();
        final CharSequence[] policies = {
            getString(R.string.mighty_exchange_import_policy_ask),
            getString(R.string.mighty_exchange_import_policy_auto),
        };

        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.mighty_exchange_folder_change_policy)
            .setItems(policies, (dialog, which) -> {
                final ExchangeManager.ImportPolicy policy = which == 1
                    ? ExchangeManager.ImportPolicy.AUTO
                    : ExchangeManager.ImportPolicy.ASK;
                final Context appContext = context.getApplicationContext();
                AppExecutors.getDiskIO().execute(() -> {
                    ExchangeManager.addFolder(appContext, folderName, treeUri, policy);
                    ExchangeScanWorker.schedulePeriodic(appContext);
                    AppExecutors.getMainThread().post(this::refreshSummaries);
                });
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showManageExchangeFoldersDialog() {
        final Context context = requireContext();
        final List<ExchangeManager.ExchangeFolder> folders = ExchangeManager.getFolders(context);
        final boolean nudgeEnabled = SettingsDAO.isExchangeSyncthingNudgeEnabled(mPrefs);
        final String nudgeLabel = getString(R.string.mighty_exchange_nudge_syncthing_toggle,
            getString(nudgeEnabled
                ? R.string.mighty_exchange_nudge_syncthing_on
                : R.string.mighty_exchange_nudge_syncthing_off));
        final String openSyncthingLabel = getString(R.string.mighty_exchange_open_syncthing);

        if (folders.isEmpty()) {
            new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.mighty_exchange_folders_title)
                .setMessage(R.string.mighty_exchange_folders_empty)
                .setItems(new CharSequence[]{nudgeLabel, openSyncthingLabel},
                    (dialog, which) -> handleExchangeFoldersSyncthingAction(which))
                .setPositiveButton(R.string.mighty_exchange_add_folder_title,
                    (dialog, which) -> mExchangeFolderPicker.launch(createOpenTreeIntent()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return;
        }

        final CharSequence[] items = new CharSequence[folders.size() + 2];
        items[0] = nudgeLabel;
        items[1] = openSyncthingLabel;
        for (int i = 0; i < folders.size(); i++) {
            final ExchangeManager.ExchangeFolder folder = folders.get(i);
            items[i + 2] = getString(R.string.mighty_exchange_folder_list_item,
                folder.name, importPolicyLabel(folder.importPolicy));
        }

        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.mighty_exchange_folders_title)
            .setItems(items, (dialog, which) -> {
                if (which < 2) {
                    handleExchangeFoldersSyncthingAction(which);
                } else {
                    showExchangeFolderActionsDialog(which - 2);
                }
            })
            .setPositiveButton(R.string.mighty_exchange_add_folder_title,
                (d, w) -> mExchangeFolderPicker.launch(createOpenTreeIntent()))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void handleExchangeFoldersSyncthingAction(int which) {
        if (which == 0) {
            toggleExchangeSyncthingNudge();
            return;
        }
        if (!SyncthingNudge.openApp(requireContext())) {
            CustomToast.show(requireContext(), R.string.mighty_exchange_syncthing_not_installed);
        }
    }

    private void toggleExchangeSyncthingNudge() {
        final boolean next = !SettingsDAO.isExchangeSyncthingNudgeEnabled(mPrefs);
        mPrefs.edit().putBoolean(KEY_MIGHTY_EXCHANGE_NUDGE_SYNCTHING, next).apply();
        CustomToast.show(requireContext(), next
            ? R.string.mighty_exchange_nudge_syncthing_enabled_toast
            : R.string.mighty_exchange_nudge_syncthing_disabled_toast);
        showManageExchangeFoldersDialog();
    }

    private void showExchangeFolderActionsDialog(int folderIndex) {
        final Context context = requireContext();
        final List<ExchangeManager.ExchangeFolder> folders = ExchangeManager.getFolders(context);
        if (folderIndex < 0 || folderIndex >= folders.size()) {
            return;
        }
        final ExchangeManager.ExchangeFolder folder = folders.get(folderIndex);

        final CharSequence[] actions = {
            getString(R.string.mighty_exchange_folder_change_policy),
            getString(R.string.delete),
        };

        new MaterialAlertDialogBuilder(context)
            .setTitle(folder.name)
            .setItems(actions, (dialog, which) -> {
                if (which == 0) {
                    showExchangeFolderPolicyDialog(folderIndex);
                } else if (which == 1) {
                    ExchangeManager.removeFolder(context, folderIndex);
                    CustomToast.show(context, R.string.mighty_exchange_folder_deleted);
                    refreshSummaries();
                    showManageExchangeFoldersDialog();
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showExchangeFolderPolicyDialog(int folderIndex) {
        final Context context = requireContext();
        final List<ExchangeManager.ExchangeFolder> folders = ExchangeManager.getFolders(context);
        if (folderIndex < 0 || folderIndex >= folders.size()) {
            return;
        }

        final CharSequence[] policies = {
            getString(R.string.mighty_exchange_import_policy_ask),
            getString(R.string.mighty_exchange_import_policy_auto),
        };
        final int checked = folders.get(folderIndex).importPolicy == ExchangeManager.ImportPolicy.AUTO ? 1 : 0;

        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.mighty_exchange_folder_change_policy)
            .setSingleChoiceItems(policies, checked, (dialog, which) -> {
                final ExchangeManager.ImportPolicy policy = which == 1
                    ? ExchangeManager.ImportPolicy.AUTO
                    : ExchangeManager.ImportPolicy.ASK;
                ExchangeManager.setFolderImportPolicy(context, folderIndex, policy);
                dialog.dismiss();
                refreshSummaries();
                showManageExchangeFoldersDialog();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private String importPolicyLabel(ExchangeManager.ImportPolicy policy) {
        if (policy == ExchangeManager.ImportPolicy.AUTO) {
            return getString(R.string.mighty_exchange_import_policy_auto);
        }
        return getString(R.string.mighty_exchange_import_policy_ask);
    }

    private void showExchangeInboxDialog() {
        final Context context = requireContext();
        final List<ExchangeManager.PendingImport> inbox = ExchangeManager.getInbox(context);
        if (inbox.isEmpty()) {
            CustomToast.show(context, R.string.mighty_exchange_inbox_empty);
            return;
        }

        final ExchangeManager.PendingImport first = inbox.get(0);
        final String sender = first.fromDeviceName != null
            ? first.fromDeviceName
            : getString(R.string.mighty_exchange_unknown_sender);
        final String alarmSummary = formatInboxAlarmSummary(first.alarmJson);
        final String message;
        if (first.toDeviceName != null && !first.toDeviceName.isEmpty()) {
            message = getString(R.string.mighty_exchange_inbox_item_message_to,
                first.folderName, sender, first.toDeviceName, alarmSummary);
        } else {
            message = getString(R.string.mighty_exchange_inbox_item_message,
                first.folderName, sender, alarmSummary);
        }

        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.mighty_exchange_inbox_title)
            .setMessage(message)
            .setPositiveButton(R.string.mighty_accept, (dialog, which) -> {
                final Context appContext = context.getApplicationContext();
                AppExecutors.getDiskIO().execute(() -> {
                    ExchangeManager.acceptImport(appContext, first);
                    AppExecutors.getMainThread().post(this::refreshSummaries);
                });
            })
            .setNegativeButton(R.string.mighty_reject, (dialog, which) -> {
                ExchangeManager.rejectImport(context, first);
                refreshSummaries();
            })
            .show();
    }

    private static String formatInboxAlarmSummary(String alarmJson) {
        try {
            final JSONObject alarm = new JSONObject(alarmJson);
            final int hour = alarm.optInt("hour", 0);
            final int minutes = alarm.optInt("minutes", 0);
            final String label = alarm.optString("label", "");
            final String time = String.format(Locale.getDefault(), "%02d:%02d", hour, minutes);
            if (label.isEmpty()) {
                return time;
            }
            return time + " — " + label;
        } catch (JSONException e) {
            return alarmJson;
        }
    }

    private void showManageTagsDialog() {
        final Context context = requireContext();
        TagColorUtils.ensureColorsAssigned(context.getContentResolver());
        final List<Tag> tags = Tag.getTags(context.getContentResolver());

        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.mighty_manage_tags_title)
            .setPositiveButton(R.string.mighty_tags_add, (dialog, which) -> showCreateTagDialog())
            .setNegativeButton(android.R.string.cancel, null);

        if (tags.isEmpty()) {
            builder.setMessage(R.string.mighty_tags_empty);
            builder.show();
            return;
        }

        final ArrayAdapter<Tag> adapter = new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, tags) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                final LinearLayout row;
                final TextView label;
                final View swatch;
                if (convertView instanceof LinearLayout) {
                    row = (LinearLayout) convertView;
                    swatch = row.getChildAt(0);
                    label = (TextView) row.getChildAt(1);
                } else {
                    row = new LinearLayout(context);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    final int pad = (int) (16 * context.getResources().getDisplayMetrics().density);
                    row.setPadding(pad, pad / 2, pad, pad / 2);

                    swatch = new View(context);
                    final int size = (int) (20 * context.getResources().getDisplayMetrics().density);
                    final LinearLayout.LayoutParams swatchLp = new LinearLayout.LayoutParams(size, size);
                    swatchLp.setMarginEnd(pad);
                    row.addView(swatch, swatchLp);

                    label = new TextView(context);
                    label.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
                    row.addView(label, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }

                final Tag tag = getItem(position);
                if (tag != null) {
                    label.setText(tag.name);
                    final GradientDrawable circle = new GradientDrawable();
                    circle.setShape(GradientDrawable.OVAL);
                    circle.setColor(TagColorUtils.displayColor(tag));
                    swatch.setBackground(circle);
                }
                return row;
            }
        };

        builder.setAdapter(adapter, (dialog, which) -> showTagActionsDialog(tags.get(which)))
            .show();
    }

    private void showTagActionsDialog(Tag tag) {
        final Context context = requireContext();
        new MaterialAlertDialogBuilder(context)
            .setTitle(tag.name)
            .setItems(new CharSequence[]{
                getString(R.string.mighty_tags_change_color),
                getString(R.string.mighty_tags_delete)
            }, (dialog, which) -> {
                if (which == 0) {
                    showTagColorPickerDialog(tag);
                } else {
                    Tag.deleteTag(context.getContentResolver(), tag.id);
                    CustomToast.show(context, R.string.mighty_tags_deleted);
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showTagColorPickerDialog(Tag tag) {
        final Context context = requireContext();
        final ColorPickerView colorPickerView = new ColorPickerView(context);
        colorPickerView.setColor(TagColorUtils.displayColor(tag));
        colorPickerView.showAlpha(false);
        colorPickerView.showHex(true);
        colorPickerView.showPreview(true);

        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.mighty_tags_change_color)
            .setView(colorPickerView)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                tag.color = colorPickerView.getColor() | 0xFF000000;
                tag.updatedAt = System.currentTimeMillis();
                tag.updateTag(context.getContentResolver());
                CustomToast.show(context, R.string.mighty_tags_color_updated);
                showManageTagsDialog();
            })
            .setNegativeButton(android.R.string.cancel, (dialog, which) -> showManageTagsDialog())
            .show();
    }

    private void showCreateTagDialog() {
        final Context context = requireContext();
        final EditText input = new EditText(context);
        input.setHint(R.string.mighty_tags_add_hint);

        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.mighty_tags_add_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                final String name = input.getText() != null ? input.getText().toString().trim() : "";
                if (name.isEmpty()) {
                    return;
                }
                final Tag tag = new Tag(name, TagColorUtils.nextAutoColor(context.getContentResolver()));
                tag.addTag(context.getContentResolver());
                CustomToast.show(context, R.string.mighty_tags_created);
                showManageTagsDialog();
            })
            .setNegativeButton(android.R.string.cancel, (dialog, which) -> showManageTagsDialog())
            .show();
    }

    private void nullifyAllPrefs() {
        mDeviceStatsPref = null;
        mViewEventLogPref = null;
        mExportEventLogPref = null;
        mGlobalMutePref = null;
        mWifiReevaluatePref = null;
        mBackupFolderPref = null;
        mBackupScheduleEnabledPref = null;
        mBackupIntervalPref = null;
        mBackupNowPref = null;
        mExchangeDeviceNamePref = null;
        mExchangeAddFolderPref = null;
        mExchangeFoldersPref = null;
        mExchangeScanNowPref = null;
        mExchangeInboxPref = null;
        mManageTagsPref = null;
        mDisplayTimezoneGlobePref = null;
    }
}

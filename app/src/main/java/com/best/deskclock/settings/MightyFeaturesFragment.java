// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.settings;

import static com.best.deskclock.settings.PreferencesKeys.*;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.widget.EditText;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.best.deskclock.R;
import com.best.deskclock.base.AppExecutors;
import com.best.deskclock.mighty.backup.BackupWorker;
import com.best.deskclock.mighty.backup.JsonBackupManager;
import com.best.deskclock.mighty.exchange.ExchangeManager;
import com.best.deskclock.mighty.exchange.ExchangeScanWorker;
import com.best.deskclock.mighty.mute.GlobalMuteController;
import com.best.deskclock.mighty.stats.DeviceStats;
import com.best.deskclock.mighty.wifi.WifiAlarmRuleManager;
import com.best.deskclock.provider.EventLogStore;
import com.best.deskclock.provider.Tag;
import com.best.deskclock.uicomponents.toast.CustomToast;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Date;
import java.util.List;

/**
 * Settings screen exposing the "Mighty" fork features that don't fit naturally into the existing
 * settings screens: device statistics, global mute, Wi-Fi rules, JSON backup and alarm exchange,
 * and the clock timezone globe toggle.
 */
public class MightyFeaturesFragment extends ScreenFragment
    implements Preference.OnPreferenceClickListener, Preference.OnPreferenceChangeListener {

    Preference mDeviceStatsPref;
    Preference mExportEventLogPref;
    Preference mGlobalMutePref;
    Preference mWifiReevaluatePref;
    Preference mBackupFolderPref;
    SwitchPreferenceCompat mBackupScheduleEnabledPref;
    ListPreference mBackupIntervalPref;
    Preference mBackupNowPref;
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
        mExportEventLogPref = findPreference(KEY_MIGHTY_EXPORT_EVENT_LOG);
        mGlobalMutePref = findPreference(KEY_MIGHTY_GLOBAL_MUTE);
        mWifiReevaluatePref = findPreference(KEY_MIGHTY_WIFI_REEVALUATE);
        mBackupFolderPref = findPreference(KEY_MIGHTY_BACKUP_FOLDER);
        mBackupScheduleEnabledPref = findPreference(KEY_MIGHTY_BACKUP_SCHEDULE_ENABLED);
        mBackupIntervalPref = findPreference(KEY_MIGHTY_BACKUP_INTERVAL_HOURS);
        mBackupNowPref = findPreference(KEY_MIGHTY_BACKUP_NOW);
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
    }

    @Override
    public void onDestroy() {
        nullifyPreferenceListeners(mDeviceStatsPref, mExportEventLogPref, mGlobalMutePref, mWifiReevaluatePref, mBackupFolderPref,
            mBackupScheduleEnabledPref, mBackupIntervalPref, mBackupNowPref, mExchangeAddFolderPref, mExchangeFoldersPref,
            mExchangeScanNowPref, mExchangeInboxPref, mManageTagsPref, mDisplayTimezoneGlobePref);

        nullifyAllPrefs();

        super.onDestroy();
    }

    private void setupPreferences() {
        mDeviceStatsPref.setOnPreferenceClickListener(this);
        mExportEventLogPref.setOnPreferenceClickListener(this);
        mGlobalMutePref.setOnPreferenceClickListener(this);
        mWifiReevaluatePref.setOnPreferenceClickListener(this);
        mBackupFolderPref.setOnPreferenceClickListener(this);
        mBackupScheduleEnabledPref.setOnPreferenceChangeListener(this);
        mBackupIntervalPref.setOnPreferenceChangeListener(this);
        mBackupNowPref.setOnPreferenceClickListener(this);
        mExchangeAddFolderPref.setOnPreferenceClickListener(this);
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

            case KEY_MIGHTY_EXCHANGE_ADD_FOLDER -> mExchangeFolderPicker.launch(createOpenTreeIntent());

            case KEY_MIGHTY_EXCHANGE_SCAN_NOW -> {
                final Context appContext = context.getApplicationContext();
                AppExecutors.getDiskIO().execute(() -> {
                    ExchangeManager.scanAll(appContext);
                    AppExecutors.getMainThread().post(this::refreshSummaries);
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
                shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Mighty Alarm Clock event log");
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

    private void showAddExchangeFolderNameDialog(Uri treeUri) {
        final Context context = requireContext();
        final EditText input = new EditText(context);
        input.setHint(R.string.mighty_exchange_folder_name_hint);

        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.mighty_exchange_add_folder_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                String name = input.getText() != null ? input.getText().toString().trim() : "";
                if (name.isEmpty()) {
                    name = treeUri.toString();
                }
                ExchangeManager.addFolder(context, name, treeUri, ExchangeManager.ImportPolicy.ASK);
                ExchangeScanWorker.schedulePeriodic(context);
                refreshSummaries();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showExchangeInboxDialog() {
        final Context context = requireContext();
        final List<ExchangeManager.PendingImport> inbox = ExchangeManager.getInbox(context);
        if (inbox.isEmpty()) {
            CustomToast.show(context, R.string.mighty_exchange_inbox_empty);
            return;
        }

        final ExchangeManager.PendingImport first = inbox.get(0);
        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.mighty_exchange_inbox_title)
            .setMessage(getString(R.string.mighty_exchange_inbox_item_message, first.folderName, first.alarmJson))
            .setPositiveButton(R.string.mighty_accept, (dialog, which) -> {
                ExchangeManager.acceptImport(context, first);
                refreshSummaries();
            })
            .setNegativeButton(R.string.mighty_reject, (dialog, which) -> {
                ExchangeManager.rejectImport(context, first);
                refreshSummaries();
            })
            .show();
    }

    private void showManageTagsDialog() {
        final Context context = requireContext();
        final List<Tag> tags = Tag.getTags(context.getContentResolver());
        final CharSequence[] items;
        if (tags.isEmpty()) {
            items = new CharSequence[]{getString(R.string.mighty_tags_empty)};
        } else {
            items = new CharSequence[tags.size()];
            for (int i = 0; i < tags.size(); i++) {
                items[i] = tags.get(i).name;
            }
        }

        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.mighty_manage_tags_title)
            .setItems(items, (dialog, which) -> {
                if (tags.isEmpty()) {
                    return;
                }
                final Tag selected = tags.get(which);
                new MaterialAlertDialogBuilder(context)
                    .setTitle(selected.name)
                    .setMessage(R.string.mighty_manage_tags_summary)
                    .setPositiveButton(R.string.mighty_tags_delete, (d, w) -> {
                        Tag.deleteTag(context.getContentResolver(), selected.id);
                        CustomToast.show(context, R.string.mighty_tags_deleted);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            })
            .setPositiveButton(R.string.mighty_tags_add, (dialog, which) -> showCreateTagDialog())
            .setNegativeButton(android.R.string.cancel, null)
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
                final Tag tag = new Tag(name, 0);
                tag.addTag(context.getContentResolver());
                CustomToast.show(context, R.string.mighty_tags_created);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void nullifyAllPrefs() {
        mDeviceStatsPref = null;
        mExportEventLogPref = null;
        mGlobalMutePref = null;
        mWifiReevaluatePref = null;
        mBackupFolderPref = null;
        mBackupScheduleEnabledPref = null;
        mBackupIntervalPref = null;
        mBackupNowPref = null;
        mExchangeAddFolderPref = null;
        mExchangeFoldersPref = null;
        mExchangeScanNowPref = null;
        mExchangeInboxPref = null;
        mManageTagsPref = null;
        mDisplayTimezoneGlobePref = null;
    }
}

/*
 * Copyright (C) 2015 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package com.best.deskclock.alarms;

import static android.media.AudioManager.STREAM_ALARM;
import static com.best.deskclock.DeskClockApplication.getDefaultSharedPreferences;
import static com.best.deskclock.settings.PreferencesDefaultValues.SPINNER_TIME_PICKER_STYLE;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.media.AudioManager;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TimePicker;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentManager;

import com.best.deskclock.R;
import com.best.deskclock.base.AppExecutors;
import com.best.deskclock.data.SettingsDAO;
import com.best.deskclock.data.Weekdays;
import com.best.deskclock.dialogfragment.AlarmDelayPickerDialogFragment;
import com.best.deskclock.dialogfragment.MaterialTimePickerDialogFragment;
import com.best.deskclock.dialogfragment.SpinnerTimePickerDialogFragment;
import com.best.deskclock.events.Events;
import com.best.deskclock.mighty.LastCreatedAlarmDefaults;
import com.best.deskclock.provider.Alarm;
import com.best.deskclock.provider.AlarmInstance;
import com.best.deskclock.provider.Tag;
import com.best.deskclock.uidata.UiDataModel;
import com.best.deskclock.utils.AlarmUtils;
import com.best.deskclock.utils.LogUtils;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.DateValidatorPointForward;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.color.MaterialColors;

import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/**
 * Click handler for an alarm time item.
 */
public final class AlarmTimeClickHandler {

    public static final String TAG = "AlarmTimeClickHandler";
    private static final LogUtils.Logger LOGGER = new LogUtils.Logger(TAG);

    private final AlarmFragment mAlarmFragment;
    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final AlarmUpdateHandler mAlarmUpdateHandler;
    private Alarm mSelectedAlarm;

    public AlarmTimeClickHandler(AlarmFragment alarmFragment, AlarmUpdateHandler alarmUpdateHandler) {
        mAlarmFragment = alarmFragment;
        mContext = mAlarmFragment.requireContext();
        mPrefs = getDefaultSharedPreferences(mContext);
        mAlarmUpdateHandler = alarmUpdateHandler;
    }

    public Alarm getSelectedAlarm() {
        return mSelectedAlarm;
    }

    public void setSelectedAlarm(Alarm selectedAlarm) {
        mSelectedAlarm = selectedAlarm;
    }

    public void displayBottomSheetDialog(Alarm alarm, boolean isNewAlarm) {
        AlarmEditBottomSheetFragment fragment =
            AlarmEditBottomSheetFragment.newInstance(alarm, alarm.id, mAlarmFragment.getTag(), isNewAlarm);

        AlarmEditBottomSheetFragment.show(mAlarmFragment.getParentFragmentManager(), fragment);
        LOGGER.v("Opening BottomSheet to edit alarm: " + alarm.id);
    }

    public void setAlarmEnabled(Alarm alarm, boolean newState) {
        if (newState != alarm.enabled) {
            alarm.enabled = newState;

            if (newState) {
                AlarmVisualCache.invalidate(alarm.id);
                if (alarm.isIntervalRepeating()) {
                    alarm.intervalFireCount = 0;
                }
            }

            // If the alarm is set for a specific date and that date is already in the past,
            // update it to the current date. An alarm cannot be scheduled in the past.
            fixAlarmDateIfPast(alarm);

            Events.sendAlarmEvent(newState ? R.string.action_enable : R.string.action_disable, R.string.label_deskclock);

            // When enabling a synchronized alarm, enable all alarms sharing the same label.
            if (alarm.syncByLabel && newState) {
                syncAlarmsWithSameLabel(alarm, true);
                mAlarmUpdateHandler.useSyncToastForLabel(alarm.label);
            }

            if (newState) {
                mAlarmFragment.setSmoothScrollStableId(alarm.id);
            }

            // Update the current alarm instance.
            mAlarmUpdateHandler.asyncUpdateAlarm(alarm, alarm.enabled, false);

            // When disabling a synchronized alarm, disable the entire group only if this alarm
            // is not currently firing or snoozed.
            if (alarm.syncByLabel && !newState) {
                AlarmInstance activeInstance = AlarmInstance.getFiredOrSnoozedInstanceForAlarm(mContext.getContentResolver(), alarm.id);

                // If the alarm is not active (neither firing nor snoozed),
                // propagate the disabled state to the whole group.
                if (activeInstance == null) {
                    syncAlarmsWithSameLabel(alarm, false);
                }
            }

            LOGGER.d("Updating alarm enabled state to " + newState);
        }
    }

    /**
     * Synchronizes the enabled state of all alarms sharing the same label and
     * synchronization setting as the given source alarm.
     *
     * @param sourceAlarm the alarm whose label and sync settings define the group
     * @param newState    the enabled state to apply to all matching alarms
     */
    private void syncAlarmsWithSameLabel(Alarm sourceAlarm, boolean newState) {
        if (sourceAlarm.label == null || sourceAlarm.label.trim().isEmpty()) {
            // No label: nothing to synchronize
            return;
        }

        AppExecutors.getDiskIO().execute(() -> {
            List<Alarm> alarms = Alarm.getAlarms(mContext.getContentResolver(), null);

            for (Alarm alarm : alarms) {
                if (alarm.id != sourceAlarm.id
                    && sourceAlarm.label.equals(alarm.label)
                    && sourceAlarm.syncByLabel == alarm.syncByLabel) {

                    if (alarm.enabled != newState) {
                        alarm.enabled = newState;

                        fixAlarmDateIfPast(alarm);

                        mAlarmUpdateHandler.asyncUpdateAlarm(alarm, false, false);
                        LOGGER.d("Sync alarm " + alarm.id + " with label " + alarm.label);
                    }
                }
            }
        });
    }

    /**
     * Ensures that the alarm's scheduled date is not in the past.
     *
     * <p>If the alarm is configured for a specific calendar date and that date has
     * already passed, this method updates the alarm's year, month, and day fields
     * to the current date. This prevents the creation of alarm instances that
     * would immediately be considered expired.</p>
     *
     * @param alarm the alarm whose date should be validated and corrected
     */
    private void fixAlarmDateIfPast(Alarm alarm) {
        if (alarm.isRecurring()) {
            return;
        }

        if (alarm.isDateInThePast()) {
            Calendar currentCalendar = Calendar.getInstance();
            alarm.year = currentCalendar.get(Calendar.YEAR);
            alarm.month = currentCalendar.get(Calendar.MONTH);
            alarm.day = currentCalendar.get(Calendar.DAY_OF_MONTH);
        }
    }

    public void dismissAlarmInstance(AlarmItemHolder itemHolder, AlarmInstance alarmInstance) {
        final Alarm alarm = itemHolder.item;

        // For occasional alarms, handle in the same way as the Delete button.
        if (alarm.isDeleteAfterUse()) {
            mAlarmFragment.removeItem(itemHolder);

            Events.sendAlarmEvent(R.string.action_delete, R.string.label_deskclock);
            mAlarmUpdateHandler.asyncDeleteAlarm(alarm);
            LOGGER.d("Deleting alarm.");
            return;
        }

        // Otherwise, standard behavior: disable the alarm.
        final Intent dismissIntent = AlarmStateManager.createStateChangeIntent(
            mContext, AlarmStateManager.ALARM_DISMISS_TAG, alarmInstance, AlarmInstance.PREDISMISSED_STATE);
        mContext.startService(dismissIntent);
    }

    public void onClockClicked(Alarm alarm) {
        mSelectedAlarm = alarm;

        if (SettingsDAO.getMaterialTimePickerStyle(mPrefs).equals(SPINNER_TIME_PICKER_STYLE)) {
            showSpinnerTimePickerDialog(alarm.hour, alarm.minutes);
        } else {
            showMaterialTimePicker(alarm.hour, alarm.minutes);
        }
    }

    public void onClockLongClicked(Alarm alarm) {
        mSelectedAlarm = alarm;
        showAlarmDelayPickerDialog();
    }

    public void showAlarmDelayPickerDialog() {
        Events.sendAlarmEvent(R.string.action_set_delay, R.string.label_deskclock);

        final AlarmDelayPickerDialogFragment fragment = AlarmDelayPickerDialogFragment.newInstance(0, 0);
        AlarmDelayPickerDialogFragment.show(mAlarmFragment.getParentFragmentManager(), fragment);
    }

    public void showSpinnerTimePickerDialog(int hours, int minutes) {
        Events.sendAlarmEvent(R.string.action_set_time, R.string.label_deskclock);

        final SpinnerTimePickerDialogFragment fragment = SpinnerTimePickerDialogFragment.newInstance(hours, minutes);
        SpinnerTimePickerDialogFragment.show(mAlarmFragment.getParentFragmentManager(), fragment);
    }

    public void showMaterialTimePicker(int hours, int minutes) {
        FragmentManager fragmentManager = ((AppCompatActivity) mContext).getSupportFragmentManager();

        // Prevents opening the same dialog twice
        if (fragmentManager.findFragmentByTag(TAG) != null) {
            return;
        }

        Events.sendAlarmEvent(R.string.action_set_time, R.string.label_deskclock);

        MaterialTimePickerDialogFragment.show(mContext, fragmentManager, TAG, hours, minutes, mPrefs);
    }

    public void setAlarm(int hour, int minute) {
        if (mSelectedAlarm == null) {
            // Legacy path (e.g. external intents that still open a time picker first):
            // continue with name → category after the time is known.
            final Alarm newAlarm = buildNewAlarm(hour, minute);
            promptForNewAlarmName(label -> {
                newAlarm.label = label;
                promptForNewAlarmCategory(newAlarm);
            });
        } else {
            updateExistingAlarm(hour, minute, false);
        }
    }

    public void setAlarmWithDelay(int hour, int minute) {
        Calendar alarmTime = Calendar.getInstance();
        alarmTime.add(Calendar.HOUR_OF_DAY, hour);
        alarmTime.add(Calendar.MINUTE, minute);

        int h = alarmTime.get(Calendar.HOUR_OF_DAY);
        int m = alarmTime.get(Calendar.MINUTE);

        if (mSelectedAlarm == null) {
            final Alarm newAlarm = buildNewAlarm(h, m);
            promptForNewAlarmName(label -> {
                newAlarm.label = label;
                promptForNewAlarmCategory(newAlarm);
            });
        } else {
            updateExistingAlarm(h, m, true);
        }
    }

    /**
     * Starts the new-alarm wizard: name → time (+ optional date) → category.
     */
    public void beginCreateAlarmWizard() {
        promptForNewAlarmName(this::promptForNewAlarmTimeAndDate);
    }

    private interface NameChosenListener {
        void onNameChosen(String label);
    }

    private void promptForNewAlarmName(NameChosenListener listener) {
        final EditText input = new EditText(mContext);
        input.setHint(R.string.add_label);
        input.setSingleLine(true);
        final int paddingPx = (int) (16 * mContext.getResources().getDisplayMetrics().density);
        input.setPadding(paddingPx, paddingPx / 2, paddingPx, paddingPx / 2);

        new MaterialAlertDialogBuilder(mContext)
            .setTitle(R.string.mighty_new_alarm_name_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                final CharSequence text = input.getText();
                final String label = text != null ? text.toString().trim() : "";
                listener.onNameChosen(label);
            })
            .setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.dismiss())
            .setCancelable(true)
            .show();
    }

    private void promptForNewAlarmTimeAndDate(String label) {
        final View content = LayoutInflater.from(mContext).inflate(R.layout.dialog_new_alarm_time_date, null, false);
        final TimePicker timePicker = content.findViewById(R.id.new_alarm_time_picker);
        final MaterialButtonToggleGroup repeatGroup = content.findViewById(R.id.new_alarm_repeat_days_group);
        final MaterialSwitch dateSwitch = content.findViewById(R.id.new_alarm_date_switch);
        final MaterialButton dateButton = content.findViewById(R.id.new_alarm_date_button);

        final Calendar now = Calendar.getInstance();
        timePicker.setIs24HourView(DateFormat.is24HourFormat(mContext));
        timePicker.setHour(now.get(Calendar.HOUR_OF_DAY));
        timePicker.setMinute(now.get(Calendar.MINUTE));

        // Holds the optional date selection (local calendar fields). Null = no specific date.
        final Calendar[] selectedDate = {null};
        final Weekdays[] daysOfWeek = {Weekdays.NONE};
        final boolean[] suppressMutualExclusion = {false};

        final Runnable clearDateSelection = () -> {
            suppressMutualExclusion[0] = true;
            dateSwitch.setChecked(false);
            suppressMutualExclusion[0] = false;
            selectedDate[0] = null;
            dateButton.setEnabled(false);
            dateButton.setText(R.string.mighty_new_alarm_pick_date);
        };

        final Runnable clearRepeatSelection = () -> {
            suppressMutualExclusion[0] = true;
            daysOfWeek[0] = Weekdays.NONE;
            repeatGroup.clearChecked();
            for (int i = 0; i < repeatGroup.getChildCount(); i++) {
                final View child = repeatGroup.getChildAt(i);
                if (child instanceof MaterialButton button) {
                    updateNewAlarmDayButtonVisuals(button, false);
                }
            }
            suppressMutualExclusion[0] = false;
        };

        bindNewAlarmRepeatDays(repeatGroup, daysOfWeek, () -> {
            if (suppressMutualExclusion[0]) {
                return;
            }
            if (daysOfWeek[0].isRepeating()) {
                clearDateSelection.run();
            }
        });

        dateSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressMutualExclusion[0]) {
                return;
            }
            dateButton.setEnabled(isChecked);
            if (!isChecked) {
                selectedDate[0] = null;
                dateButton.setText(R.string.mighty_new_alarm_pick_date);
            } else {
                clearRepeatSelection.run();
                if (selectedDate[0] == null) {
                    // Default to tomorrow if today's alarm time has already passed, else today.
                    final Calendar suggestion = Calendar.getInstance();
                    suggestion.set(Calendar.HOUR_OF_DAY, timePicker.getHour());
                    suggestion.set(Calendar.MINUTE, timePicker.getMinute());
                    suggestion.set(Calendar.SECOND, 0);
                    suggestion.set(Calendar.MILLISECOND, 0);
                    if (suggestion.getTimeInMillis() <= System.currentTimeMillis()) {
                        suggestion.add(Calendar.DAY_OF_YEAR, 1);
                    }
                    selectedDate[0] = suggestion;
                    updateNewAlarmDateButton(dateButton, suggestion);
                }
            }
        });

        dateButton.setOnClickListener(v -> showNewAlarmDatePicker(selectedDate, dateButton, timePicker));

        new MaterialAlertDialogBuilder(mContext)
            .setTitle(R.string.mighty_new_alarm_time_date_title)
            .setView(content)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                final Alarm newAlarm = buildNewAlarm(timePicker.getHour(), timePicker.getMinute());
                newAlarm.label = label;
                newAlarm.daysOfWeek = daysOfWeek[0];
                if (dateSwitch.isChecked() && selectedDate[0] != null) {
                    newAlarm.daysOfWeek = Weekdays.NONE;
                    newAlarm.year = selectedDate[0].get(Calendar.YEAR);
                    newAlarm.month = selectedDate[0].get(Calendar.MONTH);
                    newAlarm.day = selectedDate[0].get(Calendar.DAY_OF_MONTH);
                }
                promptForNewAlarmCategory(newAlarm);
            })
            .setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.dismiss())
            .setCancelable(true)
            .show();
    }

    private void bindNewAlarmRepeatDays(MaterialButtonToggleGroup repeatGroup, Weekdays[] daysOfWeek,
                                        Runnable onDaysChanged) {
        final LayoutInflater inflater = LayoutInflater.from(mContext);
        final List<Integer> weekdays = SettingsDAO.getWeekdayOrder(mPrefs).getCalendarDays();
        final MaterialButton[] dayButtons = new MaterialButton[7];

        repeatGroup.removeAllViews();
        for (int i = 0; i < 7; i++) {
            final MaterialButton dayButton =
                (MaterialButton) inflater.inflate(R.layout.day_button, repeatGroup, false);
            final int weekday = weekdays.get(i);
            dayButton.setId(View.generateViewId());
            dayButton.setText(UiDataModel.getUiDataModel().getShortWeekday(weekday));
            dayButton.setContentDescription(UiDataModel.getUiDataModel().getLongWeekday(weekday));
            repeatGroup.addView(dayButton);
            dayButtons[i] = dayButton;
            updateNewAlarmDayButtonVisuals(dayButton, false);
        }

        repeatGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            for (int i = 0; i < dayButtons.length; i++) {
                if (dayButtons[i].getId() == checkedId) {
                    final int weekday = weekdays.get(i);
                    daysOfWeek[0] = daysOfWeek[0].setBit(weekday, isChecked);
                    updateNewAlarmDayButtonVisuals(dayButtons[i], isChecked);
                    onDaysChanged.run();
                    break;
                }
            }
        });
    }

    private void updateNewAlarmDayButtonVisuals(MaterialButton dayButton, boolean isSelected) {
        final int backgroundColor = isSelected
            ? MaterialColors.getColor(mContext, com.google.android.material.R.attr.colorTertiary, Color.BLACK)
            : Color.TRANSPARENT;

        final ColorStateList strokeColor = ColorStateList.valueOf(
            MaterialColors.getColor(mContext, isSelected
                ? com.google.android.material.R.attr.colorTertiary
                : com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        );

        final int textColor = MaterialColors.getColor(mContext, isSelected
            ? android.R.attr.colorBackground
            : android.R.attr.textColorPrimary, Color.BLACK);

        dayButton.setBackgroundTintList(ColorStateList.valueOf(backgroundColor));
        dayButton.setStrokeColor(strokeColor);
        dayButton.setTextColor(textColor);
    }

    private void showNewAlarmDatePicker(Calendar[] selectedDateHolder, MaterialButton dateButton, TimePicker timePicker) {
        final FragmentManager fragmentManager = mAlarmFragment.getParentFragmentManager();
        if (fragmentManager.findFragmentByTag("new_alarm_date_picker") != null) {
            return;
        }

        final Calendar initial = selectedDateHolder[0] != null
            ? (Calendar) selectedDateHolder[0].clone()
            : Calendar.getInstance();

        // MaterialDatePicker uses UTC midnight for selections.
        final Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH));

        final CalendarConstraints constraints = new CalendarConstraints.Builder()
            .setValidator(DateValidatorPointForward.now())
            .build();

        final MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.date_picker_dialog_title)
            .setSelection(utc.getTimeInMillis())
            .setCalendarConstraints(constraints)
            .build();

        picker.addOnPositiveButtonClickListener(selection -> {
            if (selection == null) {
                return;
            }
            final Calendar utcSelected = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            utcSelected.setTimeInMillis(selection);
            final Calendar local = Calendar.getInstance();
            local.set(Calendar.YEAR, utcSelected.get(Calendar.YEAR));
            local.set(Calendar.MONTH, utcSelected.get(Calendar.MONTH));
            local.set(Calendar.DAY_OF_MONTH, utcSelected.get(Calendar.DAY_OF_MONTH));
            local.set(Calendar.HOUR_OF_DAY, timePicker.getHour());
            local.set(Calendar.MINUTE, timePicker.getMinute());
            local.set(Calendar.SECOND, 0);
            local.set(Calendar.MILLISECOND, 0);
            selectedDateHolder[0] = local;
            updateNewAlarmDateButton(dateButton, local);
        });

        picker.show(fragmentManager, "new_alarm_date_picker");
    }

    private void updateNewAlarmDateButton(MaterialButton dateButton, Calendar date) {
        final Alarm temp = new Alarm();
        temp.year = date.get(Calendar.YEAR);
        temp.month = date.get(Calendar.MONTH);
        temp.day = date.get(Calendar.DAY_OF_MONTH);
        dateButton.setText(AlarmUtils.formatAlarmDate(temp));
    }

    private void promptForNewAlarmCategory(Alarm newAlarm) {
        final ContentResolver cr = mContext.getContentResolver();
        final List<Tag> tags = Tag.getTags(cr);

        if (tags.isEmpty()) {
            new MaterialAlertDialogBuilder(mContext)
                .setTitle(R.string.mighty_new_alarm_category_title)
                .setMessage(R.string.mighty_new_alarm_category_empty)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> createNewAlarm(newAlarm, null))
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.dismiss())
                .show();
            return;
        }

        final CharSequence[] names = new CharSequence[tags.size()];
        final boolean[] checked = new boolean[tags.size()];
        for (int i = 0; i < tags.size(); i++) {
            names[i] = tags.get(i).name;
            checked[i] = false;
        }

        new MaterialAlertDialogBuilder(mContext)
            .setTitle(R.string.mighty_new_alarm_category_title)
            .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                final Set<Long> selected = new HashSet<>();
                for (int i = 0; i < tags.size(); i++) {
                    if (checked[i]) {
                        selected.add(tags.get(i).id);
                    }
                }
                createNewAlarm(newAlarm, selected.isEmpty() ? null : selected);
            })
            .setNeutralButton(R.string.mighty_new_alarm_category_none, (dialog, which) -> createNewAlarm(newAlarm, null))
            .setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.dismiss())
            .show();
    }

    private void createNewAlarm(Alarm newAlarm, Set<Long> tagIds) {
        AlarmVisualCache.invalidate(newAlarm.id);
        LastCreatedAlarmDefaults.save(mPrefs, newAlarm);

        mAlarmUpdateHandler.asyncAddAlarm(
            newAlarm,
            false,
            addedAlarm -> {
                if (tagIds != null && !tagIds.isEmpty()) {
                    final ContentResolver cr = mContext.getContentResolver();
                    for (Long tagId : tagIds) {
                        Tag.addTagToAlarm(cr, addedAlarm.id, tagId);
                    }
                }
            },
            addedAlarm -> {
                if (mAlarmFragment.isAdded()) {
                    mAlarmFragment.setPendingAlarmToEdit(addedAlarm);
                }
            });
    }

    private Alarm buildNewAlarm(int hour, int minute) {
        final Alarm alarm = new Alarm();
        final AudioManager audioManager = mContext.getApplicationContext().getSystemService(AudioManager.class);

        alarm.hour = hour;
        alarm.minutes = minute;
        alarm.syncByLabel = false;
        alarm.enabled = true;

        if (LastCreatedAlarmDefaults.hasDefaults(mPrefs)) {
            LastCreatedAlarmDefaults.apply(mPrefs, alarm);
        } else {
            alarm.vibrate = SettingsDAO.areAlarmVibrationsEnabledByDefault(mPrefs);
            alarm.vibrationPattern = SettingsDAO.getVibrationPattern(mPrefs);
            alarm.flash = SettingsDAO.shouldTurnOnBackFlashForTriggeredAlarm(mPrefs);
            alarm.deleteAfterUse = SettingsDAO.isOccasionalAlarmDeletedByDefault(mPrefs);
            alarm.autoSilenceDuration = SettingsDAO.getAlarmTimeout(mPrefs);
            alarm.snoozeDuration = SettingsDAO.getSnoozeLength(mPrefs);
            alarm.missedAlarmRepeatLimit = SettingsDAO.getMissedAlarmRepeatLimit(mPrefs);
            alarm.crescendoDuration = SettingsDAO.getAlarmVolumeCrescendoDuration(mPrefs);
            alarm.alarmVolume = audioManager.getStreamVolume(STREAM_ALARM);
        }

        return alarm;
    }

    private void updateExistingAlarm(int hour, int minute, boolean isFromDelay) {
        mSelectedAlarm.hour = hour;
        mSelectedAlarm.minutes = minute;

        if (isFromDelay) {
            mSelectedAlarm.daysOfWeek = Weekdays.fromBits(0);
        }

        Calendar currentCalendar = Calendar.getInstance();

        // Necessary when an existing alarm has been created in the past, and it is not enabled.
        // Even if the date is not specified, it is saved in AlarmInstance; we need to make
        // sure that the date is not in the past when changing time, in which case we reset
        // to the current date (an alarm cannot be scheduled in the past).
        // This is due to the change in the code made with commit : 6ac23cf.
        // Fix https://github.com/BlackyHawky/Clock/issues/299
        boolean mustResetDate = mSelectedAlarm.isDateInThePast() || (isFromDelay && mSelectedAlarm.isSpecifiedDate());

        if (mustResetDate) {
            mSelectedAlarm.year = currentCalendar.get(Calendar.YEAR);
            mSelectedAlarm.month = currentCalendar.get(Calendar.MONTH);
            mSelectedAlarm.day = currentCalendar.get(Calendar.DAY_OF_MONTH);
        }

        mSelectedAlarm.enabled = true;

        AlarmVisualCache.invalidate(mSelectedAlarm.id);

        mAlarmUpdateHandler.asyncUpdateAlarm(mSelectedAlarm, true, false);
        mSelectedAlarm = null;
    }

}

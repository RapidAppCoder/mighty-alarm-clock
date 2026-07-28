// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.alarms;

import static android.app.Activity.RESULT_OK;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static androidx.core.util.TypedValueCompat.dpToPx;
import static com.best.deskclock.DeskClockApplication.getDefaultSharedPreferences;
import static com.best.deskclock.settings.PreferencesDefaultValues.*;

import android.app.Dialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.TooltipCompat;
import androidx.core.graphics.Insets;
import androidx.core.util.Pair;
import androidx.core.view.HapticFeedbackConstantsCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.best.deskclock.DeskClock;
import com.best.deskclock.R;
import com.best.deskclock.base.AppExecutors;
import com.best.deskclock.data.DataModel;
import com.best.deskclock.data.SettingsDAO;
import com.best.deskclock.data.Weekdays;
import com.best.deskclock.data.WidgetDAO;
import com.best.deskclock.databinding.AlarmEditBottomSheetBinding;
import com.best.deskclock.databinding.DeskClockBinding;
import com.best.deskclock.dialogfragment.AlarmDelayPickerDialogFragment;
import com.best.deskclock.dialogfragment.AlarmMissedRepeatLimitDialogFragment;
import com.best.deskclock.dialogfragment.AlarmSnoozeDurationDialogFragment;
import com.best.deskclock.dialogfragment.AlarmVolumeDialogFragment;
import com.best.deskclock.dialogfragment.AutoSilenceDurationDialogFragment;
import com.best.deskclock.dialogfragment.DatePickerDialogFragment;
import com.best.deskclock.dialogfragment.LabelDialogFragment;
import com.best.deskclock.dialogfragment.MaterialTimePickerDialogFragment;
import com.best.deskclock.dialogfragment.SpinnerDatePickerDialogFragment;
import com.best.deskclock.dialogfragment.SpinnerTimePickerDialogFragment;
import com.best.deskclock.dialogfragment.VibrationPatternDialogFragment;
import com.best.deskclock.dialogfragment.VolumeCrescendoDurationDialogFragment;
import com.best.deskclock.events.Events;
import com.best.deskclock.mighty.LastCreatedAlarmDefaults;
import com.best.deskclock.mighty.exchange.ExchangeManager;
import com.best.deskclock.mighty.tags.TagColorUtils;
import com.best.deskclock.provider.Alarm;
import com.best.deskclock.provider.AlarmInstance;
import com.best.deskclock.provider.Tag;
import com.best.deskclock.ringtone.RingtonePickerActivity;
import com.best.deskclock.uicomponents.CustomTooltip;
import com.best.deskclock.uicomponents.toast.CustomToast;
import com.best.deskclock.uidata.UiDataModel;
import com.best.deskclock.utils.AlarmUtils;
import com.best.deskclock.utils.DeviceUtils;
import com.best.deskclock.utils.InsetsUtils;
import com.best.deskclock.utils.RingtoneUtils;
import com.best.deskclock.utils.SdkUtils;
import com.best.deskclock.utils.ThemeUtils;
import com.best.deskclock.utils.Utils;
import com.best.deskclock.utils.WidgetUtils;
import com.best.deskclock.widgets.DigitalAppWidgetProvider;
import com.best.deskclock.widgets.NextAlarmAppWidgetProvider;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.timepicker.MaterialTimePicker;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;

public class AlarmEditBottomSheetFragment extends BottomSheetDialogFragment {

    public static final String TAG = "alarm_edit_bottom_sheet";
    private static final String ARG_ALARM = "arg_alarm";
    private static final String ARG_ALARM_ID = "arg_alarm_id";
    private static final String ARG_IS_NEW_ALARM = "arg_is_new_alarm";
    private static final String ARG_TAG = "arg_tag";
    public static final String SCROLL_TO_ALARM_ID = "scroll_to_alarm_id";
    public static final String REQUEST_KEY = "alarm_saved";

    private AlarmEditBottomSheetBinding mBinding;
    private SharedPreferences mPrefs;
    private Typeface mGeneralTypeface;
    private Typeface mAlarmBoldTypeface;
    private DisplayMetrics mDisplayMetrics;
    private Alarm mAlarm;
    private Alarm mOriginalAlarm;
    private AlarmUpdateHandler mAlarmUpdateHandler;
    private String mTag;
    private boolean mIsNewAlarm;
    private boolean mIsDeleted;
    private int mScreenHeight;
    private int mVisualPadding;

    public static AlarmEditBottomSheetFragment newInstance(Alarm alarm, long alarmId, String tag, boolean isNewAlarm) {

        final Bundle args = new Bundle();

        args.putParcelable(ARG_ALARM, alarm);
        args.putLong(ARG_ALARM_ID, alarmId);
        args.putString(ARG_TAG, tag);
        args.putBoolean(ARG_IS_NEW_ALARM, isNewAlarm);

        final AlarmEditBottomSheetFragment fragment = new AlarmEditBottomSheetFragment();
        fragment.setArguments(args);
        return fragment;
    }

    public static void show(FragmentManager manager, AlarmEditBottomSheetFragment fragment) {
        Utils.showDialogFragment(manager, fragment, TAG);
    }

    private final ActivityResultLauncher<Intent> mRingtonePickerLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Uri uri = SdkUtils.isAtLeastAndroid13()
                    ? result.getData().getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri.class)
                    : result.getData().getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);

                mAlarm.alert = (uri != null) ? uri : RingtoneUtils.RINGTONE_SILENT;

                bindRingtone();
            }
        }
    );

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mTag = requireArguments().getString(ARG_TAG);
        mIsNewAlarm = requireArguments().getBoolean(ARG_IS_NEW_ALARM, false);

        mPrefs = getDefaultSharedPreferences(requireContext());
        mGeneralTypeface = ThemeUtils.loadFont(SettingsDAO.getGeneralFont(mPrefs));
        mAlarmBoldTypeface = ThemeUtils.boldTypeface(SettingsDAO.getAlarmFont(mPrefs));

        mDisplayMetrics = getResources().getDisplayMetrics();
        mScreenHeight = Resources.getSystem().getDisplayMetrics().heightPixels;
        mVisualPadding = (int) dpToPx(8, mDisplayMetrics);

        setupFragmentResultListeners();
    }

    @Override
    public void onStart() {
        super.onStart();

        DeskClock activity = (DeskClock) requireActivity();
        DeskClockBinding activityBinding = activity.getDeskClockBinding();

        mAlarmUpdateHandler = new AlarmUpdateHandler(requireContext(), null, activityBinding.contentView);
    }

    @Override
    public void onDestroyView() {
        if (mBinding != null) {
            nullifyClickListeners(mBinding.digitalClock, mBinding.scheduleAlarmLayout, mBinding.pauseAlarmLayout, mBinding.editLabel,
                mBinding.chooseRingtone, mBinding.vibrationPatternLayout, mBinding.autoSilenceDurationLayout, mBinding.snoozeDurationLayout,
                mBinding.missedAlarmRepeatLimitLayout, mBinding.crescendoDurationLayout, mBinding.alarmVolumeLayout, mBinding.deleteButton,
                mBinding.duplicateButton);
        }

        // DialogFragment.onDestroyView() invokes onDismiss(), which may call saveAlarmSettings().
        // Keep the update handler until after that so dismiss-time persistence does not NPE.
        super.onDestroyView();

        mAlarmUpdateHandler = null;
        mBinding = null;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        // As long as this dialog exists, save its state.
        if (mAlarm != null) {
            outState.putParcelable(ARG_ALARM, mAlarm);
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);

        Window window = dialog.getWindow();
        if (window != null) {
            // Display within the cutout area
            ThemeUtils.allowDisplayCutout(window);

            // To prevent flickering when a 'MaterialAlertDialog' opens on top of this BottomSheet, remove the background dimming
            // caused by the BottomSheet. The 'MaterialAlertDialog' will handle this dimming.
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

            // Prevent the BottomSheet from moving when the keyboard opens (for example, when editing the alarm label).
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        }

        final Bundle bundleToUse = (savedInstanceState != null) ? savedInstanceState : requireArguments();
        Alarm alarmFromArguments = SdkUtils.isAtLeastAndroid13()
            ? bundleToUse.getParcelable(ARG_ALARM, Alarm.class)
            : bundleToUse.getParcelable(ARG_ALARM);

        if (alarmFromArguments == null) {
            dismiss();
            return dialog;
        }

        mOriginalAlarm = new Alarm(alarmFromArguments);
        mAlarm = new Alarm(alarmFromArguments);

        mBinding = AlarmEditBottomSheetBinding.inflate(getLayoutInflater());

        dialog.setContentView(mBinding.getRoot());

        BottomSheetBehavior<?> behavior = dialog.getBehavior();
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        behavior.setSkipCollapsed(true);

        InsetsUtils.doOnApplyWindowInsets(mBinding.getRoot(), (v, insets) -> {
            Insets statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            int statusBarHeight = statusBars.top;

            behavior.setMaxHeight(mScreenHeight - statusBarHeight - mVisualPadding);
        });

        bindCustomDragHandleTooltip();
        bindClock();
        bindDaysOfWeekButtons();
        bindSelectedDate();
        bindPauseAlarm();
        bindLabel();
        bindRingtone();
        bindVibrator();
        bindVibrationPattern();
        bindFlash();
        bindDeleteOccasionalAlarmAfterUse();
        bindAutoSilenceValue();
        bindSnoozeDurationValue();
        bindMissedAlarmRepeatLimit();
        bindCrescendoDuration();
        bindAlarmVolume();
        bindMightyAlarmFeatures();
        bindDeleteButton();
        bindDuplicateButton();

        updateAllGroupBackgrounds();

        dialog.setOnShowListener(dialogInterface -> {
            BottomSheetDialog d = (BottomSheetDialog) dialogInterface;
            View bottomSheetInternal = d.findViewById(com.google.android.material.R.id.design_bottom_sheet);

            if (bottomSheetInternal != null) {
                bottomSheetInternal.setElevation(dpToPx(12, mDisplayMetrics));
            }
        });

        return dialog;
    }

    @Override
    public void onResume() {
        super.onResume();

        restoreMaterialTimePickerListener();
        restoreMaterialDatePickerListener();
        restoreMaterialDateRangePickerListener();
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        if (getActivity() != null && !getActivity().isChangingConfigurations()) {
            saveAlarmSettings();
        }

        // When the per-alarm volume feature is enabled, AlarmFragment temporarily "freezes"
        // its volume warning banner.
        // This prevents the banner from glitching or disappearing when the user tests
        // the alarm volume in the sub-dialog (AlarmVolumeDialogFragment).
        // Therefore, when this BottomSheet is fully dismissed, we must force the parent
        // AlarmFragment to re-evaluate the actual system volume.
        // This catches any system volume changes the user might have made
        // (e.g., using hardware buttons) while the UI was frozen, ensuring the banner state
        // remains perfectly synchronized.
        if (SettingsDAO.isPerAlarmVolumeEnabled(mPrefs)) {
            Fragment parentFragment = getParentFragmentManager().findFragmentByTag(mTag);
            if (parentFragment instanceof AlarmFragment alarmFragment) {
                alarmFragment.updateWarningBannerVisibility();
            }
        }

        super.onDismiss(dialog);
    }

    private void bindCustomDragHandleTooltip() {
        CharSequence nativeText = mBinding.dragHandle.getContentDescription();
        String tooltipText = nativeText != null ? nativeText.toString() : "";

        TooltipCompat.setTooltipText(mBinding.dragHandle, null);

        mBinding.dragHandle.setOnLongClickListener(v -> {
            if (!tooltipText.isEmpty()) {
                CustomTooltip.showBelow(v, tooltipText);
            }
            return true;
        });
    }

    private void bindClock() {
        mBinding.digitalClock.setBackground(ThemeUtils.pillRippleDrawable(requireContext(), Color.TRANSPARENT));
        mBinding.digitalClock.setTime(mAlarm.hour, mAlarm.minutes);
        mBinding.digitalClock.setTypeface(mAlarmBoldTypeface);

        mBinding.digitalClock.setOnClickListener(v -> {
            Events.sendAlarmEvent(R.string.action_set_time, R.string.label_deskclock);

            if (SettingsDAO.getMaterialTimePickerStyle(mPrefs).equals(SPINNER_TIME_PICKER_STYLE)) {
                final SpinnerTimePickerDialogFragment fragment = SpinnerTimePickerDialogFragment.newInstance(mAlarm.hour, mAlarm.minutes);
                SpinnerTimePickerDialogFragment.show(getChildFragmentManager(), fragment);
            } else {
                MaterialTimePickerDialogFragment.show(
                    requireContext(), getChildFragmentManager(), TAG, mAlarm.hour, mAlarm.minutes, mPrefs);
            }
        });

        mBinding.digitalClock.setOnLongClickListener(v -> {
            Events.sendAlarmEvent(R.string.action_set_delay, R.string.label_deskclock);

            final AlarmDelayPickerDialogFragment fragment = AlarmDelayPickerDialogFragment.newInstance(0, 0);
            AlarmDelayPickerDialogFragment.show(getChildFragmentManager(), fragment);

            return true;
        });
    }

    private void bindDaysOfWeekButtons() {
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        List<Integer> weekdays = SettingsDAO.getWeekdayOrder(mPrefs).getCalendarDays();

        mBinding.repeatDaysGroup.removeAllViews();

        final MaterialButton[] dayButtons = new MaterialButton[7];

        for (int i = 0; i < 7; i++) {
            MaterialButton dayButton = (MaterialButton) inflater.inflate(R.layout.day_button, mBinding.repeatDaysGroup, false);
            int weekday = weekdays.get(i);

            dayButton.setId(View.generateViewId());
            dayButton.setTypeface(mGeneralTypeface);
            dayButton.setText(UiDataModel.getUiDataModel().getShortWeekday(weekday));
            dayButton.setContentDescription(UiDataModel.getUiDataModel().getLongWeekday(weekday));

            mBinding.repeatDaysGroup.addView(dayButton);
            dayButtons[i] = dayButton;

            boolean isChecked = mAlarm.daysOfWeek.isBitOn(weekday);

            if (isChecked) {
                mBinding.repeatDaysGroup.check(dayButton.getId());
            }

            updateDaysOfWeekButtonVisuals(dayButtons[i], isChecked);
        }

        mBinding.repeatDaysGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            for (int i = 0; i < dayButtons.length; i++) {
                if (dayButtons[i].getId() == checkedId) {
                    int weekday = weekdays.get(i);
                    mAlarm.daysOfWeek = mAlarm.daysOfWeek.setBit(weekday, isChecked);
                    updateDaysOfWeekButtonVisuals(dayButtons[i], isChecked);

                    if (isChecked && mAlarm.isIntervalRepeating()) {
                        mAlarm.repeatIntervalMinutes = 0;
                        mAlarm.repeatMaxCount = 0;
                        mAlarm.intervalFireCount = 0;
                        bindMightyAlarmFeatures();
                    }

                    if (!mAlarm.daysOfWeek.isRepeating()) {
                        mAlarm.pauseStartDate = 0;
                        mAlarm.pauseEndDate = 0;
                    }

                    if (mAlarm.daysOfWeek.getBits() == mOriginalAlarm.daysOfWeek.getBits()) {
                        // If the user has set the days exactly as they were originally, restore the original date to undo the change
                        // when saving the alarm.
                        mAlarm.year = mOriginalAlarm.year;
                        mAlarm.month = mOriginalAlarm.month;
                        mAlarm.day = mOriginalAlarm.day;
                    } else {
                        // Otherwise, set the date to today.
                        final Calendar now = Calendar.getInstance();
                        mAlarm.year = now.get(Calendar.YEAR);
                        mAlarm.month = now.get(Calendar.MONTH);
                        mAlarm.day = now.get(Calendar.DAY_OF_MONTH);
                    }

                    bindSelectedDate();
                    bindPauseAlarm();
                    bindDeleteOccasionalAlarmAfterUse();
                    Utils.performHapticFeedback(dayButtons[i], HapticFeedbackConstantsCompat.VIRTUAL_KEY);
                    break;
                }
            }
        });
    }

    private void bindSelectedDate() {
        int openCalendarText = R.string.schedule_alarm_title;

        mBinding.scheduleAlarm.setTypeface(mGeneralTypeface);

        mBinding.scheduleAlarmLayout.setOnClickListener(v -> DatePickerDialogFragment.show(
            getChildFragmentManager(),
            mPrefs,
            mAlarm,
            this::applyDate)
        );

        if (mAlarm.daysOfWeek.isRepeating()) {
            clearSelectedDate(openCalendarText);
        } else if (mAlarm.isSpecifiedDate()) {
            if (mAlarm.isDateInThePast()) {
                clearSelectedDate(openCalendarText);
            } else {
                mBinding.scheduleAlarm.setText(AlarmUtils.formatAlarmDate(mAlarm));

                mBinding.cancelScheduledAlarm.setTypeface(mGeneralTypeface);
                mBinding.cancelScheduledAlarm.setOnClickListener(v -> {
                    Calendar now = Calendar.getInstance();
                    mAlarm.year = now.get(Calendar.YEAR);
                    mAlarm.month = now.get(Calendar.MONTH);
                    mAlarm.day = now.get(Calendar.DAY_OF_MONTH);

                    bindSelectedDate();
                });
                mBinding.cancelScheduledAlarm.setVisibility(VISIBLE);
            }
        } else {
            clearSelectedDate(openCalendarText);
        }
    }

    private void bindPauseAlarm() {
        boolean isRepeating = mAlarm.isRecurring();

        mBinding.pauseAlarmLayout.setEnabled(isRepeating);
        mBinding.pauseAlarm.setEnabled(isRepeating);
        mBinding.pauseAlarm.setTypeface(mGeneralTypeface);

        mAlarm.clearPauseIfExpired();

        if (isRepeating && mAlarm.isPauseSet()) {
            String dateRangeStr = AlarmUtils.formatPauseDateRange(requireContext(), mAlarm.pauseStartDate, mAlarm.pauseEndDate);

            mBinding.pauseAlarm.setText(getString(R.string.pause_alarm_range, dateRangeStr));

            mBinding.cancelPauseAlarm.setTypeface(mGeneralTypeface);
            mBinding.cancelPauseAlarm.setVisibility(View.VISIBLE);
        } else {
            mBinding.pauseAlarm.setText(R.string.pause_alarm_title);

            mBinding.cancelPauseAlarm.setVisibility(View.GONE);
        }

        if (isRepeating) {
            mBinding.pauseAlarmLayout.setOnClickListener(v -> DatePickerDialogFragment.showMaterialDateRangePicker(
                getChildFragmentManager(),
                mPrefs,
                mAlarm,
                (start, end) -> {
                    mAlarm.pauseStartDate = start;
                    mAlarm.pauseEndDate = end;
                    bindPauseAlarm();
                }
            ));

            mBinding.cancelPauseAlarm.setOnClickListener(v -> {
                mAlarm.pauseStartDate = 0;
                mAlarm.pauseEndDate = 0;
                bindPauseAlarm();
            });
        } else {
            mBinding.pauseAlarmLayout.setOnClickListener(null);
        }
    }

    private void bindLabel() {
        final boolean alarmLabelIsEmpty = mAlarm.label == null || mAlarm.label.isEmpty();

        mBinding.editLabel.setText(alarmLabelIsEmpty ? getString(R.string.add_label) : mAlarm.label);
        mBinding.editLabel.setTypeface(mGeneralTypeface);
        mBinding.editLabel.setContentDescription(alarmLabelIsEmpty
            ? getString(R.string.no_label_specified)
            : getString(R.string.label_description) + " " + mAlarm.label);

        mBinding.editLabel.setOnClickListener(v -> {
            Events.sendAlarmEvent(R.string.action_set_label, R.string.label_deskclock);

            final LabelDialogFragment fragment = LabelDialogFragment.newInstance(mAlarm.label, mAlarm.syncByLabel);
            LabelDialogFragment.show(getChildFragmentManager(), fragment);
        });
    }

    private void bindRingtone() {
        final String title = DataModel.getDataModel().getRingtoneTitle(mAlarm.alert);
        mBinding.chooseRingtone.setText(title);
        mBinding.chooseRingtone.setTypeface(mGeneralTypeface);

        final String description = getString(R.string.ringtone_description);
        mBinding.chooseRingtone.setContentDescription(description + " " + title);

        final Drawable iconRingtone;
        if (RingtoneUtils.RINGTONE_SILENT.equals(mAlarm.alert)) {
            iconRingtone = AppCompatResources.getDrawable(requireContext(), R.drawable.ic_ringtone_silent);
        } else if (RingtoneUtils.isRandomRingtone(mAlarm.alert) || RingtoneUtils.isRandomCustomRingtone(mAlarm.alert)) {
            iconRingtone = AppCompatResources.getDrawable(requireContext(), R.drawable.ic_random);
        } else {
            iconRingtone = AppCompatResources.getDrawable(requireContext(), R.drawable.ic_ringtone);
        }

        mBinding.chooseRingtone.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRingtone, null, null, null);

        mBinding.chooseRingtone.setOnClickListener(v -> {
            Events.sendAlarmEvent(R.string.action_set_ringtone, R.string.label_deskclock);
            final Intent intent = RingtonePickerActivity.createAlarmRingtonePickerIntent(requireContext(), mAlarm);
            mRingtonePickerLauncher.launch(intent);
        });
    }

    private void bindVibrator() {
        if (!DeviceUtils.hasVibrator(requireContext())) {
            mBinding.vibrateOnOff.setVisibility(GONE);
            mBinding.vibrationPatternLayout.setVisibility(GONE);
            return;
        }

        mBinding.vibrateOnOff.setTypeface(mGeneralTypeface);
        mBinding.vibrateOnOff.setVisibility(VISIBLE);

        mBinding.vibrateOnOff.setOnCheckedChangeListener(null);
        mBinding.vibrateOnOff.setChecked(mAlarm.vibrate);

        mBinding.vibrateOnOff.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Events.sendAlarmEvent(R.string.action_toggle_vibrate, R.string.label_deskclock);
            mAlarm.vibrate = isChecked;
            bindVibrationPattern();
            updateSecondGroup();
            if (isChecked) {
                Utils.setVibrationTime(requireContext(), 300);
            }
        });
    }

    private void bindVibrationPattern() {
        if (!mAlarm.vibrate || !SettingsDAO.isPerAlarmVibrationPatternEnabled(mPrefs)) {
            mBinding.vibrationPatternLayout.setVisibility(GONE);
            return;
        }

        mBinding.vibrationPatternTitle.setTypeface(mGeneralTypeface);
        mBinding.vibrationPatternValue.setTypeface(mGeneralTypeface);
        mBinding.vibrationPatternLayout.setVisibility(VISIBLE);

        String vibrationPatternText = mAlarm.vibrationPattern;
        switch (vibrationPatternText) {
            case VIBRATION_PATTERN_SOFT -> mBinding.vibrationPatternValue.setText(getString(R.string.vibration_pattern_soft));
            case VIBRATION_PATTERN_STRONG -> mBinding.vibrationPatternValue.setText(getString(R.string.vibration_pattern_strong));
            case VIBRATION_PATTERN_HEARTBEAT -> mBinding.vibrationPatternValue.setText(getString(R.string.vibration_pattern_heartbeat));
            case VIBRATION_PATTERN_ESCALATING -> mBinding.vibrationPatternValue.setText(getString(R.string.vibration_pattern_escalating));
            case VIBRATION_PATTERN_TICK_TOCK -> mBinding.vibrationPatternValue.setText(getString(R.string.vibration_pattern_tick_tock));
            default -> mBinding.vibrationPatternValue.setText(getString(R.string.label_default));
        }

        View.OnClickListener openVibrationPatternFragment = v -> {
            Events.sendAlarmEvent(R.string.action_set_vibration_pattern, R.string.label_deskclock);

            final VibrationPatternDialogFragment fragment = VibrationPatternDialogFragment.newInstance(mAlarm.vibrationPattern);
            VibrationPatternDialogFragment.show(getChildFragmentManager(), fragment);
        };

        mBinding.vibrationPatternLayout.setOnClickListener(openVibrationPatternFragment);
    }

    private void bindFlash() {
        if (!DeviceUtils.hasBackFlash(requireContext())) {
            mBinding.flashOnOff.setVisibility(GONE);
            return;
        }

        mBinding.flashOnOff.setTypeface(mGeneralTypeface);
        mBinding.flashOnOff.setVisibility(VISIBLE);
        mBinding.flashOnOff.setOnCheckedChangeListener(null);
        mBinding.flashOnOff.setChecked(mAlarm.flash);
        mBinding.flashOnOff.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Events.sendAlarmEvent(R.string.action_toggle_flash, R.string.label_deskclock);
            mAlarm.flash = isChecked;
            Utils.performHapticFeedback(mBinding.flashOnOff, HapticFeedbackConstantsCompat.VIRTUAL_KEY);
        });
    }

    private void bindDeleteOccasionalAlarmAfterUse() {
        final boolean isRepeating = mAlarm.isRecurring();

        mBinding.deleteOccasionalAlarmAfterUse.setTypeface(mGeneralTypeface);
        mBinding.deleteOccasionalAlarmAfterUse.setEnabled(!isRepeating);
        mBinding.deleteOccasionalAlarmAfterUse.setOnCheckedChangeListener(null);
        mBinding.deleteOccasionalAlarmAfterUse.setChecked(!isRepeating && mAlarm.deleteAfterUse);

        mBinding.deleteOccasionalAlarmAfterUse.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mAlarm.deleteAfterUse = isChecked;
            Utils.performHapticFeedback(mBinding.deleteOccasionalAlarmAfterUse, HapticFeedbackConstantsCompat.VIRTUAL_KEY);
        });
    }

    private void bindAutoSilenceValue() {
        if (SettingsDAO.isPerAlarmAutoSilenceDisabled(mPrefs)) {
            mBinding.autoSilenceDurationLayout.setVisibility(GONE);
            return;
        }

        mBinding.autoSilenceDurationTitle.setTypeface(mGeneralTypeface);
        mBinding.autoSilenceDurationValue.setTypeface(mGeneralTypeface);

        int autoSilenceDuration = mAlarm.autoSilenceDuration;

        if (autoSilenceDuration == TIMEOUT_NEVER) {
            mBinding.autoSilenceDurationValue.setText(getString(R.string.label_never));
        } else if (autoSilenceDuration == TIMEOUT_END_OF_RINGTONE) {
            mBinding.autoSilenceDurationValue.setText(getString(R.string.auto_silence_end_of_ringtone));
        } else {
            int m = autoSilenceDuration / 60;
            int s = autoSilenceDuration % 60;

            if (m > 0 && s > 0) {
                String minutesString = getResources().getQuantityString(R.plurals.minutes_short, m, m);
                String secondsString = s + " " + getString(R.string.seconds_label);
                mBinding.autoSilenceDurationValue.setText(String.format("%s %s", minutesString, secondsString));
            } else if (m > 0) {
                mBinding.autoSilenceDurationValue.setText(getResources().getQuantityString(R.plurals.minutes_short, m, m));
            } else {
                String secondsString = s + " " + getString(R.string.seconds_label);
                mBinding.autoSilenceDurationValue.setText(secondsString);
            }
        }

        mBinding.autoSilenceDurationLayout.setVisibility(VISIBLE);

        View.OnClickListener openAutoSilenceDurationFragment = v -> {
            Events.sendAlarmEvent(R.string.action_set_auto_silence_duration, R.string.label_deskclock);

            final AutoSilenceDurationDialogFragment fragment = AutoSilenceDurationDialogFragment.newInstance(mAlarm.autoSilenceDuration);
            AutoSilenceDurationDialogFragment.show(getChildFragmentManager(), fragment);
        };

        mBinding.autoSilenceDurationLayout.setOnClickListener(openAutoSilenceDurationFragment);
    }

    private void bindSnoozeDurationValue() {
        if (SettingsDAO.isPerAlarmSnoozeDurationDisabled(mPrefs)) {
            mBinding.snoozeDurationLayout.setVisibility(GONE);
            return;
        }

        mBinding.snoozeDurationTitle.setTypeface(mGeneralTypeface);
        mBinding.snoozeDurationValue.setTypeface(mGeneralTypeface);

        int snoozeDuration = mAlarm.snoozeDuration;

        if (snoozeDuration == ALARM_SNOOZE_DURATION_DISABLED) {
            mBinding.snoozeDurationValue.setText(getString(R.string.snooze_duration_none));
        } else {
            int h = snoozeDuration / 60;
            int m = snoozeDuration % 60;

            if (h > 0 && m > 0) {
                String hoursString = getResources().getQuantityString(R.plurals.hours_short, h, h);
                String minutesString = getResources().getQuantityString(R.plurals.minutes_short, m, m);
                mBinding.snoozeDurationValue.setText(String.format("%s %s", hoursString, minutesString));
            } else if (h > 0) {
                mBinding.snoozeDurationValue.setText(getResources().getQuantityString(R.plurals.hours_short, h, h));
            } else {
                mBinding.snoozeDurationValue.setText(getResources().getQuantityString(R.plurals.minutes_short, m, m));
            }
        }

        mBinding.snoozeDurationLayout.setVisibility(VISIBLE);

        View.OnClickListener openAlarmSnoozeDurationFragment = v -> {
            Events.sendAlarmEvent(R.string.action_set_snooze_duration, R.string.label_deskclock);

            final AlarmSnoozeDurationDialogFragment fragment = AlarmSnoozeDurationDialogFragment.newInstance(mAlarm.snoozeDuration);
            AlarmSnoozeDurationDialogFragment.show(getChildFragmentManager(), fragment);
        };

        mBinding.snoozeDurationLayout.setOnClickListener(openAlarmSnoozeDurationFragment);
    }

    private void bindMissedAlarmRepeatLimit() {
        if (SettingsDAO.isPerAlarmMissedRepeatLimitDisabled(mPrefs)
            || mAlarm.autoSilenceDuration == TIMEOUT_NEVER
            || mAlarm.snoozeDuration == ALARM_SNOOZE_DURATION_DISABLED) {
            mBinding.missedAlarmRepeatLimitLayout.setVisibility(GONE);
            return;
        }

        mBinding.missedAlarmRepeatLimitTitle.setTypeface(mGeneralTypeface);
        mBinding.missedAlarmRepeatLimitValue.setTypeface(mGeneralTypeface);

        int missedAlarmRepeatLimit = mAlarm.missedAlarmRepeatLimit;

        switch (missedAlarmRepeatLimit) {
            case 0 -> mBinding.missedAlarmRepeatLimitValue.setText(getString(R.string.label_never));
            case 1 -> mBinding.missedAlarmRepeatLimitValue.setText(getString(R.string.missed_alarm_repeat_limit_1_time));
            case 3 -> mBinding.missedAlarmRepeatLimitValue.setText(getString(R.string.missed_alarm_repeat_limit_3_times));
            case 5 -> mBinding.missedAlarmRepeatLimitValue.setText(getString(R.string.missed_alarm_repeat_limit_5_times));
            case 10 -> mBinding.missedAlarmRepeatLimitValue.setText(getString(R.string.missed_alarm_repeat_limit_10_times));
            default -> mBinding.missedAlarmRepeatLimitValue.setText(getString(R.string.label_indefinitely));
        }

        mBinding.missedAlarmRepeatLimitLayout.setVisibility(VISIBLE);

        View.OnClickListener openAlarmMissedRepeatLimitFragment = v -> {
            Events.sendAlarmEvent(R.string.action_set_missed_alarm_repeat_limit, R.string.label_deskclock);

            final AlarmMissedRepeatLimitDialogFragment fragment =
                AlarmMissedRepeatLimitDialogFragment.newInstance(mAlarm.missedAlarmRepeatLimit);

            AlarmMissedRepeatLimitDialogFragment.show(getChildFragmentManager(), fragment);
        };

        mBinding.missedAlarmRepeatLimitLayout.setOnClickListener(openAlarmMissedRepeatLimitFragment);
    }

    private void bindCrescendoDuration() {
        if (SettingsDAO.isPerAlarmCrescendoDurationDisabled(mPrefs)) {
            mBinding.crescendoDurationLayout.setVisibility(GONE);
            return;
        }

        mBinding.crescendoDurationTitle.setTypeface(mGeneralTypeface);
        mBinding.crescendoDurationValue.setTypeface(mGeneralTypeface);

        int crescendoDuration = mAlarm.crescendoDuration;

        if (crescendoDuration == DEFAULT_VOLUME_CRESCENDO_DURATION) {
            mBinding.crescendoDurationValue.setText(getString(R.string.label_off));
        } else {
            int m = crescendoDuration / 60;
            int s = crescendoDuration % 60;

            if (m > 0 && s > 0) {
                String minutesString = getResources().getQuantityString(R.plurals.minutes_short, m, m);
                String secondsString = s + " " + getString(R.string.seconds_label);
                mBinding.crescendoDurationValue.setText(String.format("%s %s", minutesString, secondsString));
            } else if (m > 0) {
                mBinding.crescendoDurationValue.setText(getResources().getQuantityString(R.plurals.minutes_short, m, m));
            } else {
                String secondsString = s + " " + getString(R.string.seconds_label);
                mBinding.crescendoDurationValue.setText(secondsString);
            }
        }

        mBinding.crescendoDurationLayout.setVisibility(VISIBLE);

        View.OnClickListener openVolumeCrescendoFragment = v -> {
            Events.sendAlarmEvent(R.string.action_set_crescendo_duration, R.string.label_deskclock);

            final VolumeCrescendoDurationDialogFragment fragment =
                VolumeCrescendoDurationDialogFragment.newInstance(mAlarm.crescendoDuration);

            VolumeCrescendoDurationDialogFragment.show(getChildFragmentManager(), fragment);
        };

        mBinding.crescendoDurationLayout.setOnClickListener(openVolumeCrescendoFragment);
    }

    private void bindAlarmVolume() {
        if (!SettingsDAO.isPerAlarmVolumeEnabled(mPrefs)) {
            mBinding.alarmVolumeLayout.setVisibility(GONE);
            return;
        }

        mBinding.alarmVolumeTitle.setTypeface(mGeneralTypeface);
        mBinding.alarmVolumeValue.setTypeface(mGeneralTypeface);

        final AudioManager audioManager = requireContext().getApplicationContext().getSystemService(AudioManager.class);
        final int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM);
        final int currentVolume = Math.min(mAlarm.alarmVolume, maxVolume);

        int volumePercent = (int) (((float) currentVolume / maxVolume) * 100);
        String formatted = String.format(Locale.getDefault(), "%d%%", volumePercent);
        mBinding.alarmVolumeValue.setText(formatted);

        Drawable icon = AppCompatResources.getDrawable(requireContext(), volumePercent < 50
            ? R.drawable.ic_volume_down
            : R.drawable.ic_volume_up);

        if (icon != null) {
            mBinding.alarmVolumeTitle.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null);
        }

        mBinding.alarmVolumeLayout.setVisibility(VISIBLE);

        View.OnClickListener openVolumeFragment = v -> {
            Events.sendAlarmEvent(R.string.action_set_alarm_volume, R.string.label_deskclock);

            final AlarmVolumeDialogFragment fragment = AlarmVolumeDialogFragment.newInstance(mAlarm.alarmVolume, mAlarm.alert);
            AlarmVolumeDialogFragment.show(getChildFragmentManager(), fragment);
        };

        mBinding.alarmVolumeLayout.setOnClickListener(openVolumeFragment);
    }

    // ****************************
    // ** "MIGHTY" ALARM FEATURES **
    // ****************************

    /**
     * Populates {@code mighty_features_container} (a plain vertical LinearLayout placed at the
     * bottom of the alarm edit bottom sheet's scroll content) with the controls for the "Mighty"
     * per-alarm features: progressive snooze extension, the Wi-Fi enable/disable rule, and the
     * "move to exchange folder" action.
     *
     * <p>These controls are built programmatically (rather than declared in the XML layout and
     * exposed via ViewBinding) because their content is dynamic and/or numerous small pieces
     * that don't warrant permanently bloating the generated binding class.</p>
     */
    private void bindMightyAlarmFeatures() {
        mBinding.mightyFeaturesContainer.removeAllViews();

        bindTagsSection();
        bindIntervalRepeatSection();
        bindSnoozeExtendSection();
        bindWifiRuleSection();
        bindExchangeSection();
    }

    private void bindIntervalRepeatSection() {
        mBinding.mightyFeaturesContainer.addView(createSectionHeader(getString(R.string.mighty_interval_repeat_title)));

        final LinearLayout controlsContainer = new LinearLayout(requireContext());
        controlsContainer.setOrientation(LinearLayout.VERTICAL);
        controlsContainer.setVisibility(mAlarm.isIntervalRepeating() ? VISIBLE : GONE);

        final int initialMinutes = mAlarm.repeatIntervalMinutes > 0 ? mAlarm.repeatIntervalMinutes : 30;
        // Prefer hours when the stored interval is a whole number of hours (e.g. 25h).
        final boolean[] useHours = {initialMinutes >= 60 && initialMinutes % 60 == 0};
        final int[] pendingMinutes = {initialMinutes};
        final LinearLayout valueStepperHost = new LinearLayout(requireContext());
        valueStepperHost.setOrientation(LinearLayout.VERTICAL);

        final Runnable rebuildValueStepper = () -> {
            valueStepperHost.removeAllViews();
            if (useHours[0]) {
                final int hours = Math.max(1, Math.min(168, Math.round(pendingMinutes[0] / 60f)));
                pendingMinutes[0] = hours * 60;
                if (mAlarm.isIntervalRepeating()) {
                    mAlarm.repeatIntervalMinutes = pendingMinutes[0];
                }
                valueStepperHost.addView(createStepperRow(
                    getString(R.string.mighty_interval_value_label),
                    hours, 1, 168, 1,
                    value -> getString(R.string.mighty_interval_summary_hours, value),
                    value -> {
                        pendingMinutes[0] = value * 60;
                        mAlarm.repeatIntervalMinutes = pendingMinutes[0];
                        mAlarm.intervalFireCount = 0;
                    }));
            } else {
                final int displayMinutes = Math.max(1, Math.min(1440, pendingMinutes[0]));
                pendingMinutes[0] = displayMinutes;
                if (mAlarm.isIntervalRepeating()) {
                    mAlarm.repeatIntervalMinutes = pendingMinutes[0];
                }
                valueStepperHost.addView(createStepperRow(
                    getString(R.string.mighty_interval_value_label),
                    displayMinutes, 1, 1440, 1,
                    value -> getString(R.string.mighty_interval_summary_minutes, value),
                    value -> {
                        pendingMinutes[0] = value;
                        mAlarm.repeatIntervalMinutes = pendingMinutes[0];
                        mAlarm.intervalFireCount = 0;
                    }));
            }
        };

        final String[] unitLabels = {
            getString(R.string.mighty_interval_unit_minutes),
            getString(R.string.mighty_interval_unit_hours)
        };
        controlsContainer.addView(createSpinnerRow(
            getString(R.string.mighty_interval_unit_label),
            unitLabels,
            useHours[0] ? 1 : 0,
            position -> {
                useHours[0] = position == 1;
                rebuildValueStepper.run();
                mAlarm.intervalFireCount = 0;
            }));

        rebuildValueStepper.run();
        controlsContainer.addView(valueStepperHost);

        controlsContainer.addView(createStepperRow(
            getString(R.string.mighty_interval_max_count_label),
            mAlarm.repeatMaxCount, 0, 100, 1,
            value -> value == 0
                ? getString(R.string.mighty_interval_max_unlimited)
                : String.valueOf(value),
            value -> {
                mAlarm.repeatMaxCount = value;
                mAlarm.intervalFireCount = 0;
            }));

        mBinding.mightyFeaturesContainer.addView(createSwitchRow(
            getString(R.string.mighty_interval_repeat_enabled),
            mAlarm.isIntervalRepeating(),
            (button, isChecked) -> {
                Utils.performHapticFeedback(button, HapticFeedbackConstantsCompat.VIRTUAL_KEY);
                if (isChecked) {
                    mAlarm.daysOfWeek = Weekdays.NONE;
                    mAlarm.pauseStartDate = 0;
                    mAlarm.pauseEndDate = 0;
                    mAlarm.repeatIntervalMinutes = pendingMinutes[0];
                    mAlarm.intervalFireCount = 0;
                    bindDaysOfWeekButtons();
                    bindSelectedDate();
                    bindPauseAlarm();
                    bindDeleteOccasionalAlarmAfterUse();
                } else {
                    mAlarm.repeatIntervalMinutes = 0;
                    mAlarm.repeatMaxCount = 0;
                    mAlarm.intervalFireCount = 0;
                }
                controlsContainer.setVisibility(isChecked ? VISIBLE : GONE);
            }));

        mBinding.mightyFeaturesContainer.addView(controlsContainer);
    }

    private void bindTagsSection() {
        mBinding.mightyFeaturesContainer.addView(createSectionHeader(getString(R.string.mighty_tags_section_title)));

        final TextView summary = new TextView(requireContext());
        summary.setTypeface(mGeneralTypeface);
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        final int paddingH = (int) dpToPx(10, mDisplayMetrics);
        final LinearLayout.LayoutParams summaryParams =
            new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        summaryParams.setMargins(paddingH, 0, paddingH, (int) dpToPx(4, mDisplayMetrics));
        summary.setLayoutParams(summaryParams);
        refreshTagSummary(summary);
        mBinding.mightyFeaturesContainer.addView(summary);

        final MaterialButton editTagsButton =
            new MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        editTagsButton.setText(getString(R.string.mighty_tags_edit_button));
        editTagsButton.setTypeface(mGeneralTypeface);
        final LinearLayout.LayoutParams buttonParams =
            new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonParams.setMargins(paddingH, 0, paddingH, (int) dpToPx(8, mDisplayMetrics));
        editTagsButton.setLayoutParams(buttonParams);
        editTagsButton.setOnClickListener(v -> {
            Utils.performHapticFeedback(v, HapticFeedbackConstantsCompat.VIRTUAL_KEY);
            showAssignTagsDialog(summary);
        });
        mBinding.mightyFeaturesContainer.addView(editTagsButton);
    }

    private void refreshTagSummary(TextView summary) {
        if (mAlarm == null || mAlarm.id == Alarm.INVALID_ID) {
            summary.setText(getString(R.string.mighty_tags_none));
            return;
        }
        final List<Tag> tags = Tag.getTagsForAlarm(requireContext().getContentResolver(), mAlarm.id);
        if (tags.isEmpty()) {
            summary.setText(getString(R.string.mighty_tags_none));
            return;
        }
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(tags.get(i).name);
        }
        summary.setText(builder.toString());
    }

    private void showAssignTagsDialog(TextView summary) {
        showAssignTagsDialog(summary, null);
    }

    /**
     * @param pendingCheckedIds if non-null, restores checkbox state (e.g. after creating a tag);
     *                          otherwise loads current associations from the database
     */
    private void showAssignTagsDialog(TextView summary, Set<Long> pendingCheckedIds) {
        if (mAlarm == null || mAlarm.id == Alarm.INVALID_ID) {
            return;
        }
        final ContentResolver cr = requireContext().getContentResolver();
        final List<Tag> allTags = Tag.getTags(cr);
        if (allTags.isEmpty()) {
            showCreateTagFromAssignDialog(summary, new HashSet<>());
            return;
        }

        final Set<Long> assigned = pendingCheckedIds != null
            ? pendingCheckedIds
            : new HashSet<>(Tag.getTagIdsForAlarm(cr, mAlarm.id));
        final CharSequence[] names = new CharSequence[allTags.size()];
        final boolean[] checked = new boolean[allTags.size()];
        for (int i = 0; i < allTags.size(); i++) {
            final Tag tag = allTags.get(i);
            final int usageCount = Tag.getAlarmCountForTag(cr, tag.id);
            names[i] = getString(R.string.mighty_tags_with_count, tag.name, usageCount);
            checked[i] = assigned.contains(tag.id);
        }

        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mighty_tags_assign_title)
            .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                Tag.clearTagsForAlarm(cr, mAlarm.id);
                for (int i = 0; i < allTags.size(); i++) {
                    if (checked[i]) {
                        Tag.addTagToAlarm(cr, mAlarm.id, allTags.get(i).id);
                    }
                }
                refreshTagSummary(summary);
            })
            .setNeutralButton(R.string.mighty_tags_add, (dialog, which) -> {
                final Set<Long> pending = new HashSet<>();
                for (int i = 0; i < allTags.size(); i++) {
                    if (checked[i]) {
                        pending.add(allTags.get(i).id);
                    }
                }
                showCreateTagFromAssignDialog(summary, pending);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showCreateTagFromAssignDialog(TextView summary, Set<Long> pendingCheckedIds) {
        final Context context = requireContext();
        final EditText input = new EditText(context);
        input.setHint(R.string.mighty_tags_add_hint);
        final int padding = (int) dpToPx(20, mDisplayMetrics);
        input.setPadding(padding, padding, padding, padding);

        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.mighty_tags_add_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                final String name = input.getText() != null ? input.getText().toString().trim() : "";
                if (name.isEmpty()) {
                    showAssignTagsDialog(summary, pendingCheckedIds);
                    return;
                }
                final Tag tag = new Tag(name, TagColorUtils.nextAutoColor(context.getContentResolver()));
                tag.addTag(context.getContentResolver());
                pendingCheckedIds.add(tag.id);
                CustomToast.show(context, R.string.mighty_tags_created);
                showAssignTagsDialog(summary, pendingCheckedIds);
            })
            .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                // Only reopen assign if tags exist (avoids looping when the list is still empty).
                if (!Tag.getTags(context.getContentResolver()).isEmpty()) {
                    showAssignTagsDialog(summary,
                        pendingCheckedIds.isEmpty() ? null : pendingCheckedIds);
                }
            })
            .show();
    }

    private void bindSnoozeExtendSection() {
        mBinding.mightyFeaturesContainer.addView(createSectionHeader(getString(R.string.mighty_snooze_extend_title)));

        final LinearLayout controlsContainer = new LinearLayout(requireContext());
        controlsContainer.setOrientation(LinearLayout.VERTICAL);
        controlsContainer.setVisibility(mAlarm.snoozeExtendEnabled ? VISIBLE : GONE);

        final LinearLayout minutesRow = createStepperRow(
            getString(R.string.mighty_snooze_extend_minutes_label),
            mAlarm.snoozeExtendMinutes, 1, 60, 1,
            value -> getResources().getQuantityString(R.plurals.minutes_short, value, value),
            value -> mAlarm.snoozeExtendMinutes = value);

        final LinearLayout maxMinutesRow = createStepperRow(
            getString(R.string.mighty_snooze_extend_max_minutes_label),
            mAlarm.snoozeExtendMaxMinutes, 0, 180, 5,
            value -> value == 0
                ? getString(R.string.mighty_snooze_extend_no_max)
                : getResources().getQuantityString(R.plurals.minutes_short, value, value),
            value -> mAlarm.snoozeExtendMaxMinutes = value);

        controlsContainer.addView(minutesRow);
        controlsContainer.addView(maxMinutesRow);

        final MaterialSwitch enabledSwitch = createSwitchRow(
            getString(R.string.mighty_snooze_extend_enabled_title),
            mAlarm.snoozeExtendEnabled,
            (buttonView, isChecked) -> {
                mAlarm.snoozeExtendEnabled = isChecked;
                controlsContainer.setVisibility(isChecked ? VISIBLE : GONE);
                Utils.performHapticFeedback(buttonView, HapticFeedbackConstantsCompat.VIRTUAL_KEY);
            });

        mBinding.mightyFeaturesContainer.addView(enabledSwitch);
        mBinding.mightyFeaturesContainer.addView(controlsContainer);
    }

    private void bindWifiRuleSection() {
        mBinding.mightyFeaturesContainer.addView(createSectionHeader(getString(R.string.mighty_wifi_rule_title)));

        final LinearLayout controlsContainer = new LinearLayout(requireContext());
        controlsContainer.setOrientation(LinearLayout.VERTICAL);
        controlsContainer.setVisibility(mAlarm.wifiRuleEnabled ? VISIBLE : GONE);

        final EditText ssidField = createEditTextRow(getString(R.string.mighty_wifi_ssid_hint), mAlarm.wifiSsid);
        ssidField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                mAlarm.wifiSsid = s.toString();
            }
        });

        final String[] conditionLabels = {
            getString(R.string.mighty_wifi_condition_present),
            getString(R.string.mighty_wifi_condition_absent)
        };
        final int conditionSelection = Alarm.WIFI_CONDITION_ABSENT.equals(mAlarm.wifiCondition) ? 1 : 0;
        final LinearLayout conditionRow = createSpinnerRow(
            getString(R.string.mighty_wifi_condition_label), conditionLabels, conditionSelection,
            position -> mAlarm.wifiCondition = position == 1 ? Alarm.WIFI_CONDITION_ABSENT : Alarm.WIFI_CONDITION_PRESENT);

        final String[] actionLabels = {
            getString(R.string.mighty_wifi_action_enable),
            getString(R.string.mighty_wifi_action_disable)
        };
        final int actionSelection = Alarm.WIFI_ACTION_DISABLE.equals(mAlarm.wifiAction) ? 1 : 0;
        final LinearLayout actionRow = createSpinnerRow(
            getString(R.string.mighty_wifi_action_label), actionLabels, actionSelection,
            position -> mAlarm.wifiAction = position == 1 ? Alarm.WIFI_ACTION_DISABLE : Alarm.WIFI_ACTION_ENABLE);

        controlsContainer.addView(ssidField);
        controlsContainer.addView(conditionRow);
        controlsContainer.addView(actionRow);

        final MaterialSwitch enabledSwitch = createSwitchRow(
            getString(R.string.mighty_wifi_rule_enabled_title),
            mAlarm.wifiRuleEnabled,
            (buttonView, isChecked) -> {
                mAlarm.wifiRuleEnabled = isChecked;
                controlsContainer.setVisibility(isChecked ? VISIBLE : GONE);
                Utils.performHapticFeedback(buttonView, HapticFeedbackConstantsCompat.VIRTUAL_KEY);
            });

        mBinding.mightyFeaturesContainer.addView(enabledSwitch);
        mBinding.mightyFeaturesContainer.addView(controlsContainer);
    }

    private void bindExchangeSection() {
        mBinding.mightyFeaturesContainer.addView(createSectionHeader(getString(R.string.mighty_category_exchange)));

        final MaterialButton moveButton =
            new MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        moveButton.setText(getString(R.string.mighty_move_to_exchange_button));
        moveButton.setTypeface(mGeneralTypeface);
        moveButton.setIconResource(R.drawable.ic_share);

        final int paddingH = (int) dpToPx(10, mDisplayMetrics);
        final LinearLayout.LayoutParams params =
            new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(paddingH, (int) dpToPx(4, mDisplayMetrics), paddingH, (int) dpToPx(12, mDisplayMetrics));
        moveButton.setLayoutParams(params);

        moveButton.setOnClickListener(v -> {
            Utils.performHapticFeedback(v, HapticFeedbackConstantsCompat.VIRTUAL_KEY);
            showMoveToExchangeDialog();
        });

        mBinding.mightyFeaturesContainer.addView(moveButton);
    }

    private void showMoveToExchangeDialog() {
        final Context context = requireContext();
        final List<ExchangeManager.ExchangeFolder> folders = ExchangeManager.getFolders(context);

        if (folders.isEmpty()) {
            CustomToast.show(context, R.string.mighty_exchange_no_folders_configured);
            return;
        }

        final CharSequence[] names = new CharSequence[folders.size()];
        for (int i = 0; i < folders.size(); i++) {
            names[i] = folders.get(i).name;
        }

        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.mighty_move_to_exchange_title)
            .setItems(names, (dialog, which) -> showMoveToExchangeRecipientDialog(folders.get(which)))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showMoveToExchangeRecipientDialog(ExchangeManager.ExchangeFolder folder) {
        final Context context = requireContext();
        final Context appContext = context.getApplicationContext();

        AppExecutors.getDiskIO().execute(() -> {
            ExchangeManager.refreshPresence(appContext);
            final List<ExchangeManager.Device> devices = ExchangeManager.listDevices(appContext, folder);

            AppExecutors.getMainThread().post(() -> {
                if (!isAdded()) {
                    return;
                }

                final CharSequence[] items = new CharSequence[devices.size() + 1];
                items[0] = getString(R.string.mighty_move_to_exchange_any_device);
                for (int i = 0; i < devices.size(); i++) {
                    items[i + 1] = devices.get(i).deviceName;
                }

                new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.mighty_move_to_exchange_recipient_title)
                    .setItems(items, (dialog, which) -> {
                        final ExchangeManager.Device target = which == 0 ? null : devices.get(which - 1);
                        moveAlarmToExchangeFolder(folder, target);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            });
        });
    }

    private void moveAlarmToExchangeFolder(ExchangeManager.ExchangeFolder folder,
                                           @Nullable ExchangeManager.Device target) {
        final Context appContext = requireContext().getApplicationContext();
        final Alarm alarmToMove = mAlarm;

        AppExecutors.getDiskIO().execute(() -> {
            final boolean success = ExchangeManager.moveAlarmTo(appContext, alarmToMove, folder, target);

            AppExecutors.getMainThread().post(() -> {
                if (!isAdded()) {
                    return;
                }
                if (success) {
                    // The alarm ownership was just handed off to the exchange folder; skip
                    // saveAlarmSettings() on dismiss so it isn't re-persisted/re-enabled.
                    mIsDeleted = true;
                    CustomToast.show(appContext, R.string.mighty_move_to_exchange_success);
                    dismiss();
                } else {
                    CustomToast.show(appContext, R.string.mighty_move_to_exchange_failure);
                }
            });
        });
    }

    /**
     * Creates a small bold section header label used to visually separate the "Mighty" feature
     * groups within {@code mighty_features_container}.
     */
    private TextView createSectionHeader(String text) {
        final Context context = requireContext();

        final TextView header = new TextView(context);
        header.setText(text);
        header.setTypeface(Typeface.create(mGeneralTypeface, Typeface.BOLD));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        header.setTextColor(MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, Color.BLACK));

        final int paddingH = (int) dpToPx(10, mDisplayMetrics);
        final LinearLayout.LayoutParams params =
            new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(paddingH, (int) dpToPx(16, mDisplayMetrics), paddingH, (int) dpToPx(4, mDisplayMetrics));
        header.setLayoutParams(params);

        return header;
    }

    /**
     * Creates a full-width {@link MaterialSwitch} row used for the boolean on/off toggles of the
     * "Mighty" feature sections (snooze extension enabled, Wi-Fi rule enabled).
     */
    private MaterialSwitch createSwitchRow(String label, boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        final Context context = requireContext();

        final MaterialSwitch switchView = new MaterialSwitch(context);
        switchView.setText(label);
        switchView.setTypeface(mGeneralTypeface);
        switchView.setChecked(checked);

        final int paddingH = (int) dpToPx(10, mDisplayMetrics);
        final LinearLayout.LayoutParams params =
            new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(paddingH, (int) dpToPx(4, mDisplayMetrics), paddingH, 0);
        switchView.setLayoutParams(params);
        switchView.setPadding(paddingH, (int) dpToPx(6, mDisplayMetrics), paddingH, (int) dpToPx(6, mDisplayMetrics));

        // Set the listener last so setChecked() above doesn't spuriously trigger it.
        switchView.setOnCheckedChangeListener(listener);

        return switchView;
    }

    /**
     * Creates a single-line text field row (e.g. for the Wi-Fi SSID) with a floating hint.
     */
    private EditText createEditTextRow(String hint, String initialValue) {
        final Context context = requireContext();

        final EditText editText = new EditText(context);
        editText.setHint(hint);
        editText.setTypeface(mGeneralTypeface);
        editText.setText(initialValue);
        editText.setInputType(InputType.TYPE_CLASS_TEXT);
        editText.setSingleLine(true);
        editText.setMaxLines(1);

        final int paddingH = (int) dpToPx(10, mDisplayMetrics);
        final LinearLayout.LayoutParams params =
            new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(paddingH, (int) dpToPx(4, mDisplayMetrics), paddingH, 0);
        editText.setLayoutParams(params);

        return editText;
    }

    /**
     * Creates a row combining a label and a {@link Spinner} used to pick between a small fixed
     * set of options (e.g. the Wi-Fi rule condition/action).
     */
    private LinearLayout createSpinnerRow(String label, String[] options, int initialSelection,
                                           java.util.function.IntConsumer onSelected) {
        final Context context = requireContext();
        final int paddingH = (int) dpToPx(10, mDisplayMetrics);

        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final LinearLayout.LayoutParams rowParams =
            new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(paddingH, (int) dpToPx(4, mDisplayMetrics), paddingH, 0);
        row.setLayoutParams(rowParams);

        final TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTypeface(mGeneralTypeface);
        labelView.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final Spinner spinner = new Spinner(context);
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(initialSelection, false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                onSelected.accept(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        row.addView(labelView);
        row.addView(spinner);

        return row;
    }

    /**
     * Creates a "label / minus / value / plus" row used for small bounded integer settings
     * (snooze extension minutes and maximum minutes).
     *
     * @param min            the minimum value (inclusive)
     * @param max            the maximum value (inclusive)
     * @param step           the amount by which each tap of minus/plus changes the value
     * @param valueFormatter formats the current integer value into the text displayed between
     *                       the minus/plus buttons
     * @param onChange       invoked with the new value whenever it changes
     */
    private LinearLayout createStepperRow(String label, int initialValue, int min, int max, int step,
                                           java.util.function.IntFunction<String> valueFormatter,
                                           java.util.function.IntConsumer onChange) {
        final Context context = requireContext();
        final int paddingH = (int) dpToPx(10, mDisplayMetrics);
        final int buttonSize = (int) dpToPx(36, mDisplayMetrics);

        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final LinearLayout.LayoutParams rowParams =
            new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(paddingH, (int) dpToPx(4, mDisplayMetrics), paddingH, 0);
        row.setLayoutParams(rowParams);

        final TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTypeface(mGeneralTypeface);
        labelView.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView valueView = new TextView(context);
        valueView.setTypeface(mGeneralTypeface);
        valueView.setGravity(Gravity.CENTER);
        valueView.setMinWidth((int) dpToPx(64, mDisplayMetrics));
        valueView.setText(valueFormatter.apply(initialValue));

        final int[] currentValue = {initialValue};

        final ImageButton minusButton = createStepperButton(context, R.drawable.ic_minus, buttonSize);
        minusButton.setOnClickListener(v -> {
            currentValue[0] = Math.max(min, currentValue[0] - step);
            valueView.setText(valueFormatter.apply(currentValue[0]));
            onChange.accept(currentValue[0]);
            Utils.performHapticFeedback(v, HapticFeedbackConstantsCompat.VIRTUAL_KEY);
        });

        final ImageButton plusButton = createStepperButton(context, R.drawable.ic_add, buttonSize);
        plusButton.setOnClickListener(v -> {
            currentValue[0] = Math.min(max, currentValue[0] + step);
            valueView.setText(valueFormatter.apply(currentValue[0]));
            onChange.accept(currentValue[0]);
            Utils.performHapticFeedback(v, HapticFeedbackConstantsCompat.VIRTUAL_KEY);
        });

        row.addView(labelView);
        row.addView(minusButton);
        row.addView(valueView);
        row.addView(plusButton);

        return row;
    }

    private ImageButton createStepperButton(Context context, int iconRes, int size) {
        final ImageButton button = new ImageButton(context);
        button.setImageResource(iconRes);
        button.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        button.setPadding(0, 0, 0, 0);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

        final TypedValue outValue = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true);
        button.setBackgroundResource(outValue.resourceId);

        return button;
    }

    private void bindDeleteButton() {
        mBinding.deleteButton.setTypeface(mGeneralTypeface);

        mBinding.deleteButton.setOnClickListener(v -> {
            mIsDeleted = true;
            Events.sendAlarmEvent(R.string.action_delete, R.string.label_deskclock);
            mAlarmUpdateHandler.asyncDeleteAlarm(mAlarm);
            Utils.performHapticFeedback(mBinding.deleteButton, HapticFeedbackConstantsCompat.VIRTUAL_KEY);
            dismiss();
        });
    }

    private void bindDuplicateButton() {
        mBinding.duplicateButton.setTypeface(mGeneralTypeface);

        mBinding.duplicateButton.setOnClickListener(v -> {
            Events.sendAlarmEvent(R.string.action_duplicate, R.string.label_deskclock);

            final ContentResolver cr = requireContext().getApplicationContext().getContentResolver();
            final long originalAlarmId = mAlarm.id;

            Alarm duplicatedAlarm = new Alarm(mAlarm);
            duplicatedAlarm.id = Alarm.INVALID_ID;
            duplicatedAlarm.instanceState = AlarmInstance.SILENT_STATE;
            // Pause windows are alarm-specific; a fresh copy should not inherit them.
            duplicatedAlarm.pauseStartDate = 0;
            duplicatedAlarm.pauseEndDate = 0;
            if (duplicatedAlarm.label != null && !duplicatedAlarm.label.isEmpty()) {
                duplicatedAlarm.label = duplicatedAlarm.label + " " + getString(R.string.mighty_copy_suffix);
            }

            // Copy tags on the disk thread right after insert (before scheduling / list reload)
            // so an active tag filter immediately includes the duplicate. Use the application
            // ContentResolver — never requireContext() after dismiss().
            mAlarmUpdateHandler.asyncAddAlarm(duplicatedAlarm, true, savedAlarm -> {
                for (Long tagId : Tag.getTagIdsForAlarm(cr, originalAlarmId)) {
                    Tag.addTagToAlarm(cr, savedAlarm.id, tagId);
                }
            }, null);

            Utils.performHapticFeedback(mBinding.duplicateButton, HapticFeedbackConstantsCompat.VIRTUAL_KEY);

            dismiss();
        });
    }

    // ********************
    // ** HELPER METHODS **
    // ********************

    private void setupFragmentResultListeners() {
        FragmentManager childFragmentManager = getChildFragmentManager();

        childFragmentManager.setFragmentResultListener(MaterialTimePickerDialogFragment.REQUEST_KEY, this,
            (requestKey, bundle) -> {
                int h = bundle.getInt(MaterialTimePickerDialogFragment.BUNDLE_KEY_HOURS);
                int m = bundle.getInt(MaterialTimePickerDialogFragment.BUNDLE_KEY_MINUTES);
                applyTime(h, m, false);
            });

        childFragmentManager.setFragmentResultListener(SpinnerTimePickerDialogFragment.REQUEST_KEY, this,
            (requestKey, bundle) -> {
                int h = bundle.getInt(SpinnerTimePickerDialogFragment.BUNDLE_KEY_HOURS);
                int m = bundle.getInt(SpinnerTimePickerDialogFragment.BUNDLE_KEY_MINUTES);
                applyTime(h, m, false);
            });

        childFragmentManager.setFragmentResultListener(AlarmDelayPickerDialogFragment.REQUEST_KEY, this,
            (requestKey, bundle) -> {
                int h = bundle.getInt(AlarmDelayPickerDialogFragment.BUNDLE_KEY_HOURS);
                int m = bundle.getInt(AlarmDelayPickerDialogFragment.BUNDLE_KEY_MINUTES);
                applyDelay(h, m);
            });

        childFragmentManager.setFragmentResultListener(SpinnerDatePickerDialogFragment.REQUEST_KEY, this,
            (requestKey, bundle) -> {
                int year = bundle.getInt(SpinnerDatePickerDialogFragment.BUNDLE_KEY_YEAR);
                int month = bundle.getInt(SpinnerDatePickerDialogFragment.BUNDLE_KEY_MONTH);
                int day = bundle.getInt(SpinnerDatePickerDialogFragment.BUNDLE_KEY_DAY);

                applyDate(year, month, day);
            });

        childFragmentManager.setFragmentResultListener(LabelDialogFragment.REQUEST_KEY, this,
            (requestKey, bundle) -> {
                mAlarm.label = bundle.getString(LabelDialogFragment.RESULT_LABEL);
                mAlarm.syncByLabel = bundle.getBoolean(LabelDialogFragment.RESULT_SYNC, false);
                bindLabel();
            });

        childFragmentManager.setFragmentResultListener(VibrationPatternDialogFragment.REQUEST_KEY, this,
            (requestKey, bundle) -> {
                String selectedPattern = bundle.getString(VibrationPatternDialogFragment.RESULT_PATTERN_KEY);
                if (selectedPattern != null) {
                    mAlarm.vibrationPattern = selectedPattern;
                    bindVibrationPattern();
                }
            });

        childFragmentManager.setFragmentResultListener(AutoSilenceDurationDialogFragment.REQUEST_KEY, this,
            (requestKey, bundle) -> {
                mAlarm.autoSilenceDuration = bundle.getInt(AutoSilenceDurationDialogFragment.AUTO_SILENCE_DURATION_VALUE);
                bindAutoSilenceValue();
                bindMissedAlarmRepeatLimit();
                updateThirdGroup();
            });

        childFragmentManager.setFragmentResultListener(AlarmSnoozeDurationDialogFragment.REQUEST_KEY, this,
            (requestKey, bundle) -> {
                mAlarm.snoozeDuration = bundle.getInt(AlarmSnoozeDurationDialogFragment.ALARM_SNOOZE_DURATION_VALUE);
                bindSnoozeDurationValue();
                bindMissedAlarmRepeatLimit();
                updateThirdGroup();
            });

        childFragmentManager.setFragmentResultListener(AlarmMissedRepeatLimitDialogFragment.REQUEST_KEY, this,
            (requestKey, bundle) -> {
                mAlarm.missedAlarmRepeatLimit = bundle.getInt(AlarmMissedRepeatLimitDialogFragment.RESULT_MISSED_REPEAT_LIMIT);
                bindMissedAlarmRepeatLimit();
            });

        childFragmentManager.setFragmentResultListener(VolumeCrescendoDurationDialogFragment.REQUEST_KEY, this,
            (requestKey, bundle) -> {
                mAlarm.crescendoDuration = bundle.getInt(VolumeCrescendoDurationDialogFragment.VOLUME_CRESCENDO_DURATION_VALUE);
                bindCrescendoDuration();
            });

        childFragmentManager.setFragmentResultListener(AlarmVolumeDialogFragment.REQUEST_KEY, this,
            (requestKey, bundle) -> {
                mAlarm.alarmVolume = bundle.getInt(AlarmVolumeDialogFragment.RESULT_VOLUME_VALUE);
                bindAlarmVolume();
            });
    }

    /**
     * Restores the positive button click listener for the Material time picker.
     *
     * <p>This ensures that the time selection callback is not lost and remains
     * functional after a configuration change, such as a screen rotation.</p>
     */
    private void restoreMaterialTimePickerListener() {
        Fragment fragment = getChildFragmentManager().findFragmentByTag(TAG);

        if (fragment instanceof MaterialTimePicker materialTimePicker) {
            materialTimePicker.clearOnPositiveButtonClickListeners();

            materialTimePicker.addOnPositiveButtonClickListener(dialog -> {
                Bundle result = new Bundle();
                result.putInt(MaterialTimePickerDialogFragment.BUNDLE_KEY_HOURS, materialTimePicker.getHour());
                result.putInt(MaterialTimePickerDialogFragment.BUNDLE_KEY_MINUTES, materialTimePicker.getMinute());

                getChildFragmentManager().setFragmentResult(MaterialTimePickerDialogFragment.REQUEST_KEY, result);
            });
        }
    }

    /**
     * Restores the positive button click listener for the single date Material picker.
     *
     * <p>This prevents the dialog's confirmation button from becoming unresponsive
     * if the device is rotated while the picker is open.</p>
     */
    private void restoreMaterialDatePickerListener() {
        Fragment fragment = getChildFragmentManager().findFragmentByTag(DatePickerDialogFragment.TAG_DATE_PICKER);

        if (fragment instanceof MaterialDatePicker) {
            @SuppressWarnings("unchecked")
            MaterialDatePicker<Long> materialDatePicker = (MaterialDatePicker<Long>) fragment;

            materialDatePicker.clearOnPositiveButtonClickListeners();

            materialDatePicker.addOnPositiveButtonClickListener(selection -> {
                Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
                calendar.setTimeInMillis(selection);

                applyDate(
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.MONTH),
                    calendar.get(Calendar.DAY_OF_MONTH)
                );
            });
        }
    }

    /**
     * Restores the positive button click listener for the Material date range picker.
     *
     * <p>This guarantees that the selected start and end dates are properly captured
     * and processed, even if a configuration change occurs while the dialog is visible.</p>
     */
    private void restoreMaterialDateRangePickerListener() {
        Fragment fragment = getChildFragmentManager().findFragmentByTag(DatePickerDialogFragment.TAG_DATE_RANGE_PICKER);

        if (fragment instanceof MaterialDatePicker) {
            @SuppressWarnings("unchecked")
            MaterialDatePicker<Pair<Long, Long>> materialDatePicker = (MaterialDatePicker<Pair<Long, Long>>) fragment;

            materialDatePicker.clearOnPositiveButtonClickListeners();

            materialDatePicker.addOnPositiveButtonClickListener(selection -> {
                if (selection.first != null && selection.second != null) {
                    mAlarm.pauseStartDate = selection.first;
                    mAlarm.pauseEndDate = selection.second;
                    bindPauseAlarm();
                }
            });
        }
    }

    private void applyDelay(int hoursToAdd, int minutesToAdd) {
        Calendar alarmTime = Calendar.getInstance();
        alarmTime.add(Calendar.HOUR_OF_DAY, hoursToAdd);
        alarmTime.add(Calendar.MINUTE, minutesToAdd);

        applyTime(alarmTime.get(Calendar.HOUR_OF_DAY), alarmTime.get(Calendar.MINUTE), true);
    }

    private void applyTime(int hour, int minute, boolean isFromDelay) {
        mAlarm.hour = hour;
        mAlarm.minutes = minute;

        if (isFromDelay) {
            mAlarm.daysOfWeek = Weekdays.fromBits(0);
        }

        Calendar currentCalendar = Calendar.getInstance();

        // Necessary when an existing alarm has been created in the past, and it is not enabled.
        // Even if the date is not specified, it is saved in AlarmInstance; we need to make
        // sure that the date is not in the past when changing time, in which case we reset
        // to the current date (an alarm cannot be scheduled in the past).
        // This is due to the change in the code made with commit : 6ac23cf.
        // Fix https://github.com/BlackyHawky/Clock/issues/299
        boolean mustResetDate = mAlarm.isDateInThePast() || (isFromDelay && mAlarm.isSpecifiedDate());

        if (mustResetDate) {
            mAlarm.year = currentCalendar.get(Calendar.YEAR);
            mAlarm.month = currentCalendar.get(Calendar.MONTH);
            mAlarm.day = currentCalendar.get(Calendar.DAY_OF_MONTH);

            bindSelectedDate();
        }

        if (isFromDelay) {
            bindDaysOfWeekButtons();
            bindDeleteOccasionalAlarmAfterUse();
        }

        bindClock();
    }

    private void applyDate(int year, int month, int day) {
        if (mAlarm.daysOfWeek.isRepeating()) {
            mAlarm.daysOfWeek = Weekdays.NONE;
        }

        if (mAlarm.isPauseSet()) {
            mAlarm.pauseStartDate = 0;
            mAlarm.pauseEndDate = 0;
        }

        mAlarm.year = year;
        mAlarm.month = month;
        mAlarm.day = day;
        mAlarm.intervalFireCount = 0;

        bindSelectedDate();
        bindDaysOfWeekButtons();
        bindPauseAlarm();
        bindDeleteOccasionalAlarmAfterUse();
    }

    private void updateDaysOfWeekButtonVisuals(MaterialButton dayButton, boolean isSelected) {
        final int backgroundColor = isSelected
            ? MaterialColors.getColor(requireContext(), com.google.android.material.R.attr.colorTertiary, Color.BLACK)
            : Color.TRANSPARENT;

        final ColorStateList strokeColor = ColorStateList.valueOf(
            MaterialColors.getColor(requireContext(), isSelected
                ? com.google.android.material.R.attr.colorTertiary
                : com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        );

        final int textColor = MaterialColors.getColor(requireContext(), isSelected
            ? android.R.attr.colorBackground
            : android.R.attr.textColorPrimary, Color.BLACK);

        dayButton.setBackgroundTintList(ColorStateList.valueOf(backgroundColor));
        dayButton.setStrokeColor(strokeColor);
        dayButton.setTextColor(textColor);
    }

    private void clearSelectedDate(@StringRes int text) {
        mBinding.cancelScheduledAlarm.setVisibility(GONE);
        mBinding.scheduleAlarm.setText(getString(text));
    }

    private void saveAlarmSettings() {
        if (mIsDeleted || mAlarm == null || mOriginalAlarm == null || mAlarmUpdateHandler == null) {
            return;
        }

        boolean timeChanged = mAlarm.hasTimeChanged(mOriginalAlarm);
        boolean minorFieldsChanged = mAlarm.hasMinorFieldsChanged(mOriginalAlarm);
        boolean isNewAlarmCreated = mIsNewAlarm && mAlarm.enabled;

        if (!timeChanged && !minorFieldsChanged) {
            if (isNewAlarmCreated) {
                mAlarmUpdateHandler.asyncUpdateAlarm(mAlarm, true, false);
                LastCreatedAlarmDefaults.save(mPrefs, mAlarm);
            }
            return;
        }

        boolean updateWidgets = !Objects.equals(mAlarm.label, mOriginalAlarm.label);
        boolean minorUpdate = !timeChanged;
        boolean popToast = timeChanged || isNewAlarmCreated;

        if (timeChanged) {
            mAlarm.enabled = true;
        }

        AlarmVisualCache.invalidate(mAlarm.id);

        mAlarmUpdateHandler.asyncUpdateAlarm(mAlarm, popToast, minorUpdate);

        // Remember behavioral settings so the next newly created alarm can reuse them.
        LastCreatedAlarmDefaults.save(mPrefs, mAlarm);

        if (isAdded()) {
            Bundle result = new Bundle();
            result.putLong(SCROLL_TO_ALARM_ID, mAlarm.id);
            getParentFragmentManager().setFragmentResult(REQUEST_KEY, result);
        }

        if (updateWidgets) {
            Context appContext = requireContext().getApplicationContext();

            if (WidgetDAO.isNextAlarmDisplayedOnDigitalWidget(mPrefs) && WidgetDAO.isNextAlarmTitleDisplayedOnDigitalWidget(mPrefs)) {
                WidgetUtils.updateWidget(appContext, DigitalAppWidgetProvider.class);
            }

            WidgetUtils.updateWidget(appContext, NextAlarmAppWidgetProvider.class);
        }
    }

    private void applyExpressiveBackgroundsToGroup(View... views) {
        List<View> visibleViews = new ArrayList<>();
        for (View view : views) {
            if (view.getVisibility() == View.VISIBLE) {
                visibleViews.add(view);
            }
        }

        int totalCount = visibleViews.size();
        if (totalCount == 0) {
            return;
        }

        Integer backgroundColor = null;
        if (!SettingsDAO.isCardBackgroundDisplayed(mPrefs)) {
            backgroundColor = MaterialColors.getColor(
                requireContext(), com.google.android.material.R.attr.colorSurfaceContainerLowest, Color.BLACK);
        }

        for (int i = 0; i < totalCount; i++) {
            View view = visibleViews.get(i);

            Drawable cardBackground = ThemeUtils.expressiveCardBackgroundWithColor(requireContext(), i, totalCount, backgroundColor);

            view.setBackground(ThemeUtils.rippleDrawable(requireContext(), cardBackground));
        }
    }

    private void updateSecondGroup() {
        applyExpressiveBackgroundsToGroup(
            mBinding.vibrateOnOff,
            mBinding.vibrationPatternLayout,
            mBinding.flashOnOff,
            mBinding.deleteOccasionalAlarmAfterUse
        );
    }

    private void updateThirdGroup() {
        applyExpressiveBackgroundsToGroup(
            mBinding.autoSilenceDurationLayout,
            mBinding.snoozeDurationLayout,
            mBinding.missedAlarmRepeatLimitLayout,
            mBinding.crescendoDurationLayout,
            mBinding.alarmVolumeLayout
        );
    }

    private void updateAllGroupBackgrounds() {
        applyExpressiveBackgroundsToGroup(mBinding.scheduleAlarmLayout, mBinding.pauseAlarmLayout);

        applyExpressiveBackgroundsToGroup(mBinding.editLabel, mBinding.chooseRingtone);

        applyExpressiveBackgroundsToGroup(
            mBinding.vibrateOnOff,
            mBinding.vibrationPatternLayout,
            mBinding.flashOnOff,
            mBinding.deleteOccasionalAlarmAfterUse
        );

        applyExpressiveBackgroundsToGroup(
            mBinding.autoSilenceDurationLayout,
            mBinding.snoozeDurationLayout,
            mBinding.missedAlarmRepeatLimitLayout,
            mBinding.crescendoDurationLayout,
            mBinding.alarmVolumeLayout
        );
    }

    private void nullifyClickListeners(View... views) {
        mBinding.digitalClock.setOnLongClickListener(null);

        for (View view : views) {
            if (view != null) {
                view.setOnClickListener(null);
            }
        }
    }

}

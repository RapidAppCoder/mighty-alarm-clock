/*
 * Copyright (C) 2015 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package com.best.deskclock.alarms;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.ViewGroup;

import androidx.core.view.HapticFeedbackConstantsCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.best.deskclock.R;
import com.best.deskclock.data.SettingsDAO;
import com.best.deskclock.data.Weekdays;
import com.best.deskclock.databinding.AlarmItemBinding;
import com.best.deskclock.mighty.tags.TagColorUtils;
import com.best.deskclock.provider.Alarm;
import com.best.deskclock.provider.AlarmInstance;
import com.best.deskclock.provider.Tag;
import com.best.deskclock.utils.AlarmUtils;
import com.best.deskclock.utils.FormattedTextUtils;
import com.best.deskclock.utils.RingtoneUtils;
import com.best.deskclock.utils.ThemeUtils;
import com.best.deskclock.utils.Utils;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * ViewHolder for alarm items.
 */
public class AlarmItemViewHolder extends RecyclerView.ViewHolder {

    public static final float CLOCK_ENABLED_ALPHA = 1f;
    public static final float CLOCK_DISABLED_ALPHA = 0.6f;
    public static final int ALPHA_ANIMATION_DURATION = 300;
    public static final String SKELETON = "EEE MMM d";
    public static final String SKELETON_WITH_YEAR = "EEE MMM d yyyy";

    public final AlarmItemBinding mBinding;

    private final Calendar mLocalCalendar = Calendar.getInstance();
    public final SharedPreferences mPrefs;
    private final AlarmAdapter mAdapter;
    private AlarmItemHolder mItemHolder;
    private final Typeface mGeneralTypeface;
    private final Typeface mGeneralBoldTypeface;
    private final Locale mLocale;
    private final String mDatePattern;
    private final String mDatePatternWithYear;
    public int mItemPosition = 0;
    public int mTotalCount = 0;

    public AlarmItemViewHolder(AlarmItemBinding binding, AlarmAdapter alarmAdapter, SharedPreferences prefs, Typeface generalTypeface,
                               Typeface generalBoldTypeface, Locale locale, String datePattern, String datePatternWithYear) {

        super(binding.getRoot());

        final Context context = itemView.getContext();

        mBinding = binding;
        mAdapter = alarmAdapter;
        mPrefs = prefs;
        mGeneralTypeface = generalTypeface;
        mGeneralBoldTypeface = generalBoldTypeface;
        mLocale = locale;
        mDatePattern = datePattern;
        mDatePatternWithYear = datePatternWithYear;

        itemView.setOnClickListener(v ->
            mItemHolder.getAlarmTimeClickHandler().displayBottomSheetDialog(mItemHolder.item, false)
        );

        // Clock handler
        mBinding.digitalClock.setOnClickListener(v -> mItemHolder.getAlarmTimeClickHandler().onClockClicked(mItemHolder.item));
        mBinding.digitalClock.setOnLongClickListener(v -> {
            mItemHolder.getAlarmTimeClickHandler().onClockLongClicked(mItemHolder.item);
            return true;
        });

        // Upcoming date font
        mBinding.upcomingDate.setTypeface(mGeneralTypeface);

        // Preemptive dismiss button handler
        mBinding.preemptiveDismissButton.setBackground(ThemeUtils.pillRippleDrawable(context, Color.TRANSPARENT));
        mBinding.preemptiveDismissButton.setTypeface(mGeneralBoldTypeface);
        mBinding.preemptiveDismissButton.setOnClickListener(v -> {
            final AlarmInstance alarmInstance = mItemHolder.getAlarmInstance();
            if (alarmInstance != null) {
                Utils.performHapticFeedback(v, HapticFeedbackConstantsCompat.VIRTUAL_KEY);
                mItemHolder.getAlarmTimeClickHandler().dismissAlarmInstance(mItemHolder, alarmInstance);
            }
        });
    }

    public AlarmItemHolder getItemHolder() {
        return mItemHolder;
    }

    public void updateAlarmFont(Typeface alarmTypeface) {
        mBinding.digitalClock.setTypeface(alarmTypeface);
    }

    public void bind(final AlarmItemHolder itemHolder) {
        this.mItemHolder = itemHolder;
        final Alarm alarm = itemHolder.item;
        final AlarmInstance alarmInstance = itemHolder.getAlarmInstance();
        final Context context = itemView.getContext();

        bindExpressiveCardBackground();
        bindAlarmLabel(context, alarm);
        bindClock(alarm);
        bindTags(context, alarm);
        bindOnOffSwitch(alarm);
        bindRepeatText(context, alarm, alarmInstance);
        bindUpcomingDate(alarm, alarmInstance);
        bindPreemptiveDismissButton(context, alarm, alarmInstance);
        bindAlphaAnimation(alarm);

        itemView.setContentDescription(mBinding.digitalClock.getText() + " " + alarm.getLabelOrDefault(context));
    }

    private void bindTags(Context context, Alarm alarm) {
        final ChipGroup chipGroup = mBinding.alarmTags;
        chipGroup.removeAllViews();

        if (alarm.id == Alarm.INVALID_ID) {
            chipGroup.setVisibility(GONE);
            return;
        }

        final List<Tag> tags = Tag.getTagsForAlarm(context.getContentResolver(), alarm.id);
        if (tags.isEmpty()) {
            chipGroup.setVisibility(GONE);
            return;
        }

        final float density = context.getResources().getDisplayMetrics().density;
        for (Tag tag : tags) {
            final int bg = TagColorUtils.displayColor(tag);
            final int fg = TagColorUtils.contrastingTextColor(bg);
            final Chip chip = new Chip(context);
            chip.setText(tag.name);
            chip.setClickable(false);
            chip.setCheckable(false);
            chip.setFocusable(false);
            chip.setEnsureMinTouchTargetSize(false);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            chip.setChipMinHeight(22f * density);
            chip.setChipBackgroundColor(ColorStateList.valueOf(bg));
            chip.setTextColor(fg);
            chip.setChipStrokeWidth(0f);
            chipGroup.addView(chip, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        chipGroup.setVisibility(VISIBLE);
    }

    private void bindExpressiveCardBackground() {
        if (mAdapter == null) {
            return;
        }

        int position = getBindingAdapterPosition();
        if (position == RecyclerView.NO_POSITION) {
            return;
        }

        Drawable.ConstantState bgState;

        if (mAdapter.isUseExpressiveBackground()) {
            // Phone in portrait mode
            int totalCount = mAdapter.getItemCount();
            this.mItemPosition = position;
            this.mTotalCount = totalCount;

            if (totalCount <= 1) {
                bgState = mAdapter.getBgSingle();
            } else if (position == 0) {
                bgState = mAdapter.getBgTop();
            } else if (position == totalCount - 1) {
                bgState = mAdapter.getBgBottom();
            } else {
                bgState = mAdapter.getBgMiddle();
            }
        } else {
            // Tablet / Landscape
            bgState = mAdapter.getBgStandard();
        }

        if (bgState != null) {
            itemView.setBackground(bgState.newDrawable());
        }
    }

    private void bindAlarmLabel(Context context, Alarm alarm) {
        if (alarm.label == null || alarm.label.isEmpty()) {
            mBinding.alarmLabel.setVisibility(GONE);
            return;
        }

        Typeface typeface = alarm.enabled ? mGeneralBoldTypeface : mGeneralTypeface;

        mBinding.alarmLabel.setTypeface(typeface);
        mBinding.alarmLabel.setText(alarm.label);
        mBinding.alarmLabel.setVisibility(VISIBLE);
        mBinding.alarmLabel.setContentDescription(context.getString(R.string.label_description) + " " + alarm.label);
    }

    private void bindOnOffSwitch(Alarm alarm) {
        if (RingtoneUtils.RINGTONE_SILENT.equals(alarm.alert)) {
            mBinding.onOffButton.setThumbIconResource(R.drawable.ic_ringtone_silent_filled);
        } else {
            mBinding.onOffButton.setThumbIconResource(R.drawable.alarm_switch_thumb_icon);
        }

        mBinding.onOffButton.setOnCheckedChangeListener(null);
        mBinding.onOffButton.setChecked(alarm.enabled);
        mBinding.onOffButton.setOnCheckedChangeListener((compoundButton, checked) -> {
            mItemHolder.getAlarmTimeClickHandler().setAlarmEnabled(mItemHolder.item, checked);
            if (checked) {
                Utils.performHapticFeedback(compoundButton, HapticFeedbackConstantsCompat.VIRTUAL_KEY);

                compoundButton.postDelayed(() ->
                    Utils.performHapticFeedback(compoundButton, HapticFeedbackConstantsCompat.VIRTUAL_KEY), 50);
            } else {
                Utils.performHapticFeedback(compoundButton, HapticFeedbackConstantsCompat.VIRTUAL_KEY);
            }
        });
    }

    private void bindClock(Alarm alarm) {
        mBinding.digitalClock.refreshFormat();
        mBinding.digitalClock.setTime(alarm.hour, alarm.minutes);
    }

    private void bindRepeatText(Context context, Alarm alarm, AlarmInstance alarmInstance) {
        if (alarmInstance != null
            && alarm.canPreemptivelyDismiss(context)
            && alarm.instanceState == AlarmInstance.SNOOZE_STATE) {
            mBinding.daysOfWeek.setTypeface(mGeneralBoldTypeface);
            mBinding.daysOfWeek.setText(context.getString(R.string.alarm_alert_snooze_until,
                AlarmUtils.getAlarmText(context, alarmInstance, false)));
        } else if (alarm.isIntervalRepeating()) {
            mBinding.daysOfWeek.setTypeface(mGeneralTypeface);
            mBinding.daysOfWeek.setText(formatIntervalSummary(context, alarm));
        } else if (alarmInstance != null && alarm.daysOfWeek.isRepeating()) {
            setRepeatingDaysDescription(context, alarm, alarmInstance);
        } else if (alarm.isSpecifiedDate()) {
            setSpecifiedDateDescription(context, alarm);
        } else {
            setNonRepeatingDefaultDescription(context, alarm);
        }
    }

    private static String formatIntervalSummary(Context context, Alarm alarm) {
        final String base;
        if (alarm.repeatIntervalMinutes >= 60 && alarm.repeatIntervalMinutes % 60 == 0) {
            base = context.getString(R.string.mighty_interval_summary_hours, alarm.repeatIntervalMinutes / 60);
        } else {
            base = context.getString(R.string.mighty_interval_summary_minutes, alarm.repeatIntervalMinutes);
        }
        final String withMax = alarm.repeatMaxCount > 0
            ? context.getString(R.string.mighty_interval_summary_with_max, base, alarm.repeatMaxCount)
            : base;
        if (alarm.isSpecifiedDate() && !alarm.isDateInThePast()) {
            return context.getString(R.string.mighty_interval_summary_with_date,
                AlarmUtils.formatAlarmDate(alarm), withMax);
        }
        return withMax;
    }

    private void bindUpcomingDate(Alarm alarm, AlarmInstance alarmInstance) {
        if (alarmInstance == null || !alarm.enabled || !alarm.daysOfWeek.isRepeating()) {
            mBinding.upcomingDate.setVisibility(GONE);
            mBinding.digitalClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48);
            return;
        }

        Calendar nextAlarmTime = alarm.getNextAlarmTimeCalendar(alarmInstance);

        long diffInMillis = nextAlarmTime.getTimeInMillis() - System.currentTimeMillis();
        long diffInDays = TimeUnit.MILLISECONDS.toDays(diffInMillis);

        if (diffInDays < 6) {
            mBinding.upcomingDate.setVisibility(GONE);
            mBinding.digitalClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48);
            return;
        }

        mLocalCalendar.setTimeInMillis(System.currentTimeMillis());
        boolean isDifferentYear = mLocalCalendar.get(Calendar.YEAR) != nextAlarmTime.get(Calendar.YEAR);
        String formattedDate = DateFormat.format(isDifferentYear ? mDatePatternWithYear : mDatePattern, nextAlarmTime).toString();
        mBinding.upcomingDate.setText(FormattedTextUtils.capitalizeFirstLetter(formattedDate, mLocale));
        mBinding.upcomingDate.setVisibility(VISIBLE);

        boolean hasLabel = alarm.label != null && !alarm.label.isEmpty();
        mBinding.digitalClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, hasLabel ? 32 : 48);
    }

    private void bindPreemptiveDismissButton(Context context, Alarm alarm, AlarmInstance alarmInstance) {
        if (AlarmVisualCache.isDismissed(alarm.id)) {
            mBinding.preemptiveDismissButton.setVisibility(GONE);
            return;
        }

        final boolean canBind = alarm.canPreemptivelyDismiss(context) && alarmInstance != null;

        if (!canBind) {
            mBinding.preemptiveDismissButton.setVisibility(GONE);
            return;
        }

        final String dismissText = formatPreemptiveDismissLabel(context, alarm, alarmInstance);
        mBinding.preemptiveDismissButton.setText(dismissText);
        if (alarm.isRecurring() && !alarm.isDeleteAfterUse()) {
            mBinding.preemptiveDismissButton.setContentDescription(
                context.getString(R.string.mighty_skip_next_occurrence_content_description, dismissText));
        } else {
            mBinding.preemptiveDismissButton.setContentDescription(dismissText);
        }
        mBinding.preemptiveDismissButton.setVisibility(VISIBLE);
    }

    /**
     * Label for the preemptive dismiss / skip-to-next control.
     * Recurring alarms show how far ahead the occurrence after the skipped one is (e.g. "+2d").
     */
    private static String formatPreemptiveDismissLabel(Context context, Alarm alarm,
                                                       AlarmInstance alarmInstance) {
        if (alarm.isDeleteAfterUse()) {
            return context.getString(R.string.alarm_alert_dismiss_and_delete_text_button);
        }
        if (!alarm.isRecurring()) {
            return context.getString(R.string.alarm_alert_dismiss_text);
        }

        final Calendar currentOccurrence = alarmInstance.getAlarmTime();
        final Calendar nextOccurrence = alarm.getNextAlarmTime(currentOccurrence);
        final long diffMs = nextOccurrence.getTimeInMillis() - currentOccurrence.getTimeInMillis();
        if (diffMs <= 0) {
            return context.getString(R.string.alarm_alert_dismiss_text);
        }

        final int days = calendarDaysBetween(currentOccurrence, nextOccurrence);
        if (days >= 1) {
            return context.getString(R.string.mighty_skip_next_occurrence_days, days);
        }

        final long hours = TimeUnit.MILLISECONDS.toHours(diffMs);
        if (hours >= 1) {
            return context.getString(R.string.mighty_skip_next_occurrence_hours, hours);
        }

        final long minutes = Math.max(1, TimeUnit.MILLISECONDS.toMinutes(diffMs));
        return context.getString(R.string.mighty_skip_next_occurrence_minutes, minutes);
    }

    private static int calendarDaysBetween(Calendar from, Calendar to) {
        final Calendar start = (Calendar) from.clone();
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);

        final Calendar end = (Calendar) to.clone();
        end.set(Calendar.HOUR_OF_DAY, 0);
        end.set(Calendar.MINUTE, 0);
        end.set(Calendar.SECOND, 0);
        end.set(Calendar.MILLISECOND, 0);

        return (int) TimeUnit.MILLISECONDS.toDays(end.getTimeInMillis() - start.getTimeInMillis());
    }

    private void bindAlphaAnimation(Alarm alarm) {
        float targetAlpha = alarm.enabled ? CLOCK_ENABLED_ALPHA : CLOCK_DISABLED_ALPHA;

        mBinding.alarmLabel.animate().cancel();
        mBinding.digitalClock.animate().cancel();
        mBinding.daysOfWeek.animate().cancel();

        if (mBinding.digitalClock.getAlpha() == targetAlpha) {
            return;
        }

        if (!itemView.isAttachedToWindow()) {
            mBinding.alarmLabel.setAlpha(targetAlpha);
            mBinding.digitalClock.setAlpha(targetAlpha);
            mBinding.daysOfWeek.setAlpha(targetAlpha);
            return;
        }

        mBinding.alarmLabel.animate().alpha(targetAlpha).setDuration(ALPHA_ANIMATION_DURATION).start();
        mBinding.digitalClock.animate().alpha(targetAlpha).setDuration(ALPHA_ANIMATION_DURATION).start();
        mBinding.daysOfWeek.animate().alpha(targetAlpha).setDuration(ALPHA_ANIMATION_DURATION).start();
    }

    // ********************
    // ** HELPER METHODS **
    // ********************

    public void updateBackground() {
        bindExpressiveCardBackground();
    }

    private void setRepeatingDaysDescription(Context context, Alarm alarm, AlarmInstance alarmInstance) {
        Weekdays.Order weekdayOrder = SettingsDAO.getWeekdayOrder(mPrefs);
        String contentDesc = alarm.daysOfWeek.toAccessibilityString(context, weekdayOrder);
        CharSequence styledDaysText;

        if (isPauseEffectivelyActive(alarm, alarmInstance)) {
            String dateRangeStr = AlarmUtils.formatPauseDateRange(context, alarm.pauseStartDate, alarm.pauseEndDate);
            String pauseText = context.getString(R.string.pause_alarm_range, dateRangeStr);

            styledDaysText = pauseText;
            contentDesc = pauseText;
        } else if (alarm.enabled) {
            int nextAlarmDay = alarm.getNextAlarmDayOfWeek(alarmInstance);

            if (alarm.daysOfWeek.isAllDaysSelected()) {
                if (alarm.isRepeatDayStyleEnabled(mPrefs)) {
                    styledDaysText = alarm.daysOfWeek.toStyledString(context, weekdayOrder, false, nextAlarmDay);
                } else {
                    styledDaysText = alarm.daysOfWeek.toString(context, weekdayOrder);
                }
            } else {
                styledDaysText = alarm.daysOfWeek.toStyledString(context, weekdayOrder, false, nextAlarmDay);
            }
        } else {
            styledDaysText = alarm.daysOfWeek.toString(context, weekdayOrder);
        }

        setDaysOfWeekText(styledDaysText);
        mBinding.daysOfWeek.setContentDescription(contentDesc);
    }

    private boolean isPauseEffectivelyActive(Alarm alarm, AlarmInstance nextInstance) {
        if (!alarm.enabled || !alarm.isPauseSet() || nextInstance == null) {
            return false;
        }

        // Check if the pause is not already in the past
        if (AlarmUtils.isPauseExpired(alarm.pauseEndDate)) {
            return false;
        }

        // Check if the instance date is scheduled after the end of the pause
        return nextInstance.getAlarmTime().getTimeInMillis() > alarm.pauseEndDate;
    }

    private void setNonRepeatingDefaultDescription(Context context, Alarm alarm) {
        mLocalCalendar.setTimeInMillis(System.currentTimeMillis());

        if (alarm.isTomorrow(mLocalCalendar)) {
            setDaysOfWeekText(context.getString(R.string.alarm_tomorrow));
        } else {
            setDaysOfWeekText(context.getString(R.string.alarm_today));
        }
    }

    private void setSpecifiedDateDescription(Context context, Alarm alarm) {
        mLocalCalendar.setTimeInMillis(System.currentTimeMillis());

        if (Alarm.isSpecifiedDateTomorrow(alarm.year, alarm.month, alarm.day)) {
            setDaysOfWeekText(context.getString(R.string.alarm_tomorrow));
        } else if (alarm.isDateInThePast()) {
            setDaysOfWeekText(getTodayOrTomorrowBasedOnTime(context, alarm, mLocalCalendar));
        } else {
            setDaysOfWeekText(context.getString(R.string.alarm_scheduled_for, AlarmUtils.formatAlarmDate(alarm)));
        }
    }

    private void setDaysOfWeekText(CharSequence text) {
        mBinding.daysOfWeek.setTypeface(mGeneralTypeface);
        mBinding.daysOfWeek.setText(text);
    }

    private String getTodayOrTomorrowBasedOnTime(Context context, Alarm alarm, Calendar now) {
        // Used when the date has passed, the new alarm will be scheduled either the same day
        // or the next day depending on the time.
        // The text is therefore updated accordingly.
        return context.getString(alarm.isTimeBeforeOrEqual(now) ? R.string.alarm_tomorrow : R.string.alarm_today);
    }

}

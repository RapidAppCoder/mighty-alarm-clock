/*
 * Copyright (C) 2026 The Mighty Alarm Clock Project
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package com.best.deskclock.stopwatch;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.best.deskclock.R;
import com.best.deskclock.data.DataModel;
import com.best.deskclock.data.Stopwatch;
import com.google.android.material.chip.Chip;

import java.util.ArrayList;
import java.util.List;

/**
 * Horizontal list of stopwatch chips used to select which stopwatch is shown in the detail UI.
 */
final class StopwatchSelectorAdapter extends RecyclerView.Adapter<StopwatchSelectorAdapter.ViewHolder> {

    interface Listener {
        void onStopwatchSelected(Stopwatch stopwatch);

        void onStopwatchLongPressed(Stopwatch stopwatch);
    }

    private final LayoutInflater mInflater;
    private final Listener mListener;
    private final List<Stopwatch> mStopwatches = new ArrayList<>();
    private int mSelectedId = -1;

    StopwatchSelectorAdapter(Context context, Listener listener) {
        mInflater = LayoutInflater.from(context);
        mListener = listener;
        setHasStableIds(true);
        refresh();
    }

    void refresh() {
        mStopwatches.clear();
        mStopwatches.addAll(DataModel.getDataModel().getStopwatches());
        mSelectedId = DataModel.getDataModel().getSelectedStopwatchId();
        notifyDataSetChanged();
    }

    @Override
    public long getItemId(int position) {
        return mStopwatches.get(position).getId();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        final Chip chip = (Chip) mInflater.inflate(R.layout.stopwatch_chip_item, parent, false);
        return new ViewHolder(chip);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final Stopwatch stopwatch = mStopwatches.get(position);
        holder.bind(stopwatch, stopwatch.getId() == mSelectedId);
    }

    @Override
    public int getItemCount() {
        return mStopwatches.size();
    }

    final class ViewHolder extends RecyclerView.ViewHolder {
        private final Chip mChip;

        ViewHolder(Chip chip) {
            super(chip);
            mChip = chip;
        }

        void bind(Stopwatch stopwatch, boolean selected) {
            final Context context = mChip.getContext();
            final String label = stopwatch.getLabel();
            if (TextUtils.isEmpty(label)) {
                // Display order is 1-based for users.
                final int displayIndex = indexOf(stopwatch.getId()) + 1;
                mChip.setText(context.getString(R.string.sw_unnamed, displayIndex));
            } else {
                mChip.setText(label);
            }

            // Indicate running stopwatches with a trailing marker in the chip text.
            if (stopwatch.isRunning()) {
                mChip.setText(mChip.getText() + " ▶");
            } else if (stopwatch.isPaused()) {
                mChip.setText(mChip.getText() + " ❚❚");
            }

            mChip.setChecked(selected);
            mChip.setOnClickListener(v -> {
                if (stopwatch.getId() != mSelectedId) {
                    mListener.onStopwatchSelected(stopwatch);
                }
            });
            mChip.setOnLongClickListener(v -> {
                mListener.onStopwatchLongPressed(stopwatch);
                return true;
            });
        }

        private int indexOf(int stopwatchId) {
            for (int i = 0; i < mStopwatches.size(); i++) {
                if (mStopwatches.get(i).getId() == stopwatchId) {
                    return i;
                }
            }
            return 0;
        }
    }
}

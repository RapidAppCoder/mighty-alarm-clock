// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.alarms;

import android.content.Context;
import android.content.SharedPreferences;

import com.best.deskclock.utils.LogUtils;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A small FIFO queue of alarm instance ids that fired while another alarm was already being
 * presented by {@link AlarmService}. Instead of marking a newly fired instance as missed while
 * another instance is ringing, the new instance id is enqueued here and started once the
 * currently ringing alarm is dismissed or snoozed.
 * <p>
 * The queue is kept in-memory ({@link ConcurrentLinkedQueue}) for fast access while the process is
 * alive, and mirrored into {@link SharedPreferences} so it survives process death.
 */
public final class AlarmFireQueue {

    private static final String PREFS_NAME = "alarm_fire_queue";
    private static final String KEY_QUEUE = "queued_instance_ids";
    private static final String SEPARATOR = ",";

    private static final ConcurrentLinkedQueue<Long> sQueue = new ConcurrentLinkedQueue<>();
    private static volatile boolean sRestored = false;

    private AlarmFireQueue() {
    }

    /**
     * Restores the in-memory queue from {@link SharedPreferences} the first time it is needed
     * after process start.
     */
    public static synchronized void restore(Context context) {
        if (sRestored) {
            return;
        }
        sRestored = true;

        final SharedPreferences prefs = prefs(context);
        final String serialized = prefs.getString(KEY_QUEUE, "");
        if (serialized != null && !serialized.isEmpty()) {
            for (String part : serialized.split(SEPARATOR)) {
                try {
                    if (!part.isEmpty()) {
                        sQueue.add(Long.parseLong(part));
                    }
                } catch (NumberFormatException e) {
                    LogUtils.e("AlarmFireQueue: failed to parse persisted entry: " + part, e);
                }
            }
        }
    }

    /**
     * Adds the given instance id to the end of the queue.
     */
    public static void enqueue(Context context, long instanceId) {
        restore(context);
        sQueue.add(instanceId);
        persist(context);
        LogUtils.i("AlarmFireQueue: enqueued instance %d (queue size=%d)", instanceId, sQueue.size());
    }

    /**
     * Removes and returns the head of the queue, or {@code null} if the queue is empty.
     */
    public static Long dequeue(Context context) {
        restore(context);
        final Long id = sQueue.poll();
        if (id != null) {
            persist(context);
        }
        return id;
    }

    /**
     * Returns (without removing) the head of the queue, or {@code null} if the queue is empty.
     */
    public static Long peek(Context context) {
        restore(context);
        return sQueue.peek();
    }

    /**
     * @return the number of instance ids currently queued.
     */
    public static int size(Context context) {
        restore(context);
        return sQueue.size();
    }

    /**
     * Clears every queued instance id.
     */
    public static void clear(Context context) {
        restore(context);
        sQueue.clear();
        persist(context);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static void persist(Context context) {
        final StringBuilder sb = new StringBuilder();
        for (Long id : sQueue) {
            if (sb.length() > 0) {
                sb.append(SEPARATOR);
            }
            sb.append(id);
        }
        prefs(context).edit().putString(KEY_QUEUE, sb.toString()).apply();
    }
}

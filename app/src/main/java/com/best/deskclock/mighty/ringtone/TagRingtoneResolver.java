// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.ringtone;

import android.content.ContentResolver;
import android.media.RingtoneManager;
import android.net.Uri;

import com.best.deskclock.provider.Tag;
import com.best.deskclock.utils.LogUtils;
import com.best.deskclock.utils.RingtoneUtils;

import java.util.List;

/**
 * Resolves the ringtone that should be used for a firing alarm based on the tags associated with
 * it, when the alarm itself does not have an explicit non-default ringtone configured.
 */
public final class TagRingtoneResolver {

    private TagRingtoneResolver() {
    }

    /**
     * @param cr           provides access to the content model
     * @param alarmId      the alarm whose tags should be inspected
     * @param currentAlert the ringtone currently assigned (to the alarm or instance)
     * @return the ringtone {@link Uri} configured on the first tag (of the alarm) that defines
     * one, or {@code null} if {@code currentAlert} is not a "default" ringtone, or no tag defines
     * a ringtone.
     */
    public static Uri resolve(ContentResolver cr, long alarmId, Uri currentAlert) {
        try {
            final boolean isDefaultRingtone = currentAlert == null
                || RingtoneUtils.RINGTONE_SILENT.equals(currentAlert)
                || currentAlert.equals(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));

            if (!isDefaultRingtone) {
                return null;
            }

            final List<Tag> tags = Tag.getTagsForAlarm(cr, alarmId);
            for (Tag tag : tags) {
                if (tag.ringtoneUri != null && !tag.ringtoneUri.isEmpty()) {
                    return Uri.parse(tag.ringtoneUri);
                }
            }
        } catch (Exception e) {
            LogUtils.e("TagRingtoneResolver: failed to resolve tag ringtone for alarm " + alarmId, e);
        }

        return null;
    }
}

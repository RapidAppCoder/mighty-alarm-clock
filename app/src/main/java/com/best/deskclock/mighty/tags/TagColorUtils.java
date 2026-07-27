// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.mighty.tags;

import android.content.ContentResolver;
import android.graphics.Color;

import androidx.annotation.ColorInt;

import com.best.deskclock.provider.Tag;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Palette and helpers for tag colors. New tags get an automatic color; existing tags with
 * {@code color == 0} are migrated the first time colors are ensured.
 */
public final class TagColorUtils {

    /**
     * Distinct opaque Material-style colors used for automatic assignment.
     */
    private static final int[] PALETTE = {
        0xFF1976D2, // blue
        0xFF388E3C, // green
        0xFFF57C00, // orange
        0xFF7B1FA2, // purple
        0xFFC62828, // red
        0xFF00838F, // cyan
        0xFF5D4037, // brown
        0xFF455A64, // blue grey
        0xFFAD1457, // pink
        0xFFAFB42B, // lime
        0xFF283593, // indigo
        0xFF00695C, // teal
    };

    private TagColorUtils() {
    }

    /**
     * Picks the least-used palette color among existing tags (preferring unused slots).
     */
    @ColorInt
    public static int nextAutoColor(ContentResolver cr) {
        final List<Tag> tags = Tag.getTags(cr);
        final Map<Integer, Integer> usage = new HashMap<>();
        for (int color : PALETTE) {
            usage.put(color, 0);
        }
        for (Tag tag : tags) {
            if (tag.color != 0) {
                final int opaque = tag.color | 0xFF000000;
                if (usage.containsKey(opaque)) {
                    usage.put(opaque, usage.get(opaque) + 1);
                }
            }
        }

        int bestColor = PALETTE[0];
        int bestCount = Integer.MAX_VALUE;
        for (int color : PALETTE) {
            final int count = usage.get(color);
            if (count < bestCount) {
                bestCount = count;
                bestColor = color;
            }
        }
        return bestColor;
    }

    /**
     * Assigns palette colors to any tags that still have {@code color == 0}.
     *
     * @return number of tags updated
     */
    public static int ensureColorsAssigned(ContentResolver cr) {
        final List<Tag> tags = Tag.getTags(cr);
        int updated = 0;
        final Set<Integer> used = new HashSet<>();
        for (Tag tag : tags) {
            if (tag.color != 0) {
                used.add(tag.color | 0xFF000000);
            }
        }
        for (Tag tag : tags) {
            if (tag.color != 0) {
                continue;
            }
            tag.color = pickLeastUsed(used);
            used.add(tag.color);
            tag.updatedAt = System.currentTimeMillis();
            tag.updateTag(cr);
            updated++;
        }
        return updated;
    }

    @ColorInt
    private static int pickLeastUsed(Set<Integer> used) {
        for (int color : PALETTE) {
            if (!used.contains(color)) {
                return color;
            }
        }
        final Map<Integer, Integer> usage = new HashMap<>();
        for (int color : PALETTE) {
            usage.put(color, 0);
        }
        for (int color : used) {
            if (usage.containsKey(color)) {
                usage.put(color, usage.get(color) + 1);
            }
        }
        int best = PALETTE[0];
        int bestCount = Integer.MAX_VALUE;
        for (int color : PALETTE) {
            final int count = usage.get(color);
            if (count < bestCount) {
                bestCount = count;
                best = color;
            }
        }
        return best;
    }

    /**
     * Readable text/icon color on top of a tag background.
     */
    @ColorInt
    public static int contrastingTextColor(@ColorInt int background) {
        final int r = Color.red(background);
        final int g = Color.green(background);
        final int b = Color.blue(background);
        final double luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        return luminance > 0.6 ? Color.BLACK : Color.WHITE;
    }

    /**
     * Opaque color for display; falls back to the first palette entry when unset.
     */
    @ColorInt
    public static int displayColor(Tag tag) {
        if (tag == null || tag.color == 0) {
            return PALETTE[0];
        }
        return tag.color | 0xFF000000;
    }
}

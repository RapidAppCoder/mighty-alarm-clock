// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock;

/**
 * Public project URLs used in About, first launch, etc.
 */
public final class ProjectUrls {

    /** Base repository URL (no trailing slash). */
    public static final String GITHUB_REPO = "https://github.com/RapidAppCoder/mighty-alarm-clock";

    public static final String GITHUB_FEATURES = GITHUB_REPO + "?tab=readme-ov-file#-features";

    public static final String GITHUB_LICENSE = GITHUB_REPO + "/blob/main/LICENSE";

    /**
     * Translation platform for this fork.
     * No dedicated Weblate project yet — link to the issue tracker for now.
     */
    public static final String TRANSLATE = GITHUB_REPO + "/issues";

    private ProjectUrls() {
    }

    /** GitHub release page for a version tag (e.g. {@code 2026.08.001}). */
    public static String githubReleaseTag(String versionTag) {
        return GITHUB_REPO + "/releases/tag/" + versionTag;
    }
}

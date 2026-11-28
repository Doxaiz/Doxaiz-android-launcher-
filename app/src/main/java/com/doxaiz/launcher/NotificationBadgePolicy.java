package com.doxaiz.launcher;

/** Pure visibility rules for notification badges on desktop tiles. */
public final class NotificationBadgePolicy {

    private NotificationBadgePolicy() {
    }

    /** Whether badges are enabled at all by the user's toggle and selected mode. */
    public static boolean isEnabled(boolean userEnabled, int mode) {
        return userEnabled && mode != Prefs.NOTIF_BADGE_NONE;
    }
}
